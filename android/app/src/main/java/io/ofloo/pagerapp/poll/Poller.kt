package io.ofloo.pagerapp.poll

import android.content.Context
import android.util.Log
import io.ofloo.pagerapp.data.HistoryEntry
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.net.NotifyKind
import io.ofloo.pagerapp.net.PagerClient
import io.ofloo.pagerapp.net.WebclientMode
import io.ofloo.pagerapp.notify.Notifications
import io.ofloo.pagerapp.notify.Notifier
import io.ofloo.pagerapp.push.PushManager
import io.ofloo.pagerapp.ui.ChatPhotos
import io.ofloo.pagerapp.ui.PhotoShare
import io.ofloo.pagerapp.shared.Contact
import io.ofloo.pagerapp.shared.ContactKind
import io.ofloo.pagerapp.shared.Decision
import io.ofloo.pagerapp.shared.Page
import io.ofloo.pagerapp.shared.FileLink
import io.ofloo.pagerapp.shared.PhotoLink
import io.ofloo.pagerapp.shared.STATUS_MESSAGE
import io.ofloo.pagerapp.shared.SignaturePolicy
import io.ofloo.pagerapp.shared.buildDeliveryReceipt
import io.ofloo.pagerapp.shared.decide
import io.ofloo.pagerapp.shared.decideForContact
import io.ofloo.pagerapp.shared.publicKeyFrom
import io.ofloo.pagerapp.shared.readableText
import io.ofloo.pagerapp.shared.shouldAck
import io.ofloo.pagerapp.shared.Pgp
import java.time.LocalTime

/**
 * The shared "what to do with a page" step, used by both transports (SSE push and polling) and
 * by both schedulers (foreground service and background worker).
 *
 * PagerMCP delivery is at-least-once, so nothing is removed until it has been processed and
 * acked; on a shared channel only the receiver may ack.
 */
object Poller {

    private const val TAG = "PagerAPP"

    /** Extend an existing key this many days before it lapses. */
    private const val KEY_RENEW_LEAD_DAYS = 30L

    /** UnifiedPush instance name for this app; also used by the receiver. */
    const val PUSH_INSTANCE = "io.ofloo.pagerapp"

    /** One polling pass over the private mailbox and every contact channel. */
    fun pollOnce(context: Context) {
        val store = Store(context)
        val own = store.uuid ?: return
        // Clean up rows left by older builds where a message could arrive over P2P and the
        // gateway without being deduplicated.
        store.collapseDuplicateMids()

        // Server policy is server-owned: P2P support, the push identity and the server clock.
        // P2P users get a fresh copy every poll; everyone else every 30 minutes.
        val nowMs = System.currentTimeMillis()
        val settingsDue = store.p2pModeEnabled ||
            nowMs - store.settingsCheckedAt > 30 * 60_000L
        if (settingsDue) {
            runCatching { PagerClient.settings(store.serverUrl) }
                .onSuccess { settings ->
                    store.cacheP2pSettings(settings)
                    store.cachePushIdentity(settings)
                    store.settingsCheckedAt = nowMs
                    Log.i(
                        TAG,
                        "server settings: identity=${settings.pushIdentityMode} " +
                            "period=${settings.pushIdentityPeriodSeconds}s " +
                            "time=${settings.serverTimeSeconds ?: "?"}",
                    )
                    store.uuid?.let { id ->
                        store.pushRoutes(id)?.let { routes ->
                            Log.i(TAG, "push routes: ${routes.map { it.take(8) }}")
                        }
                    }
                }
                .onFailure {
                    if (store.p2pModeEnabled) Log.w(TAG, "settings refresh failed: ${it.message}")
                }
        }

        // Keep push registered: the connector is idempotent, so this self-heals after a
        // reinstall, a distributor change or a cleared subscription. A distributor must be chosen
        // first, or the connector has nobody to ask for an endpoint.
        if (store.pushEnabled && store.uuid != null) {
            runCatching {
                PushManager.ensureDistributor(context)
                org.unifiedpush.android.connector.UnifiedPush.register(context, PUSH_INSTANCE)
            }.onFailure { Log.w(TAG, "push register failed: ${it.message}") }
        }

        // Keep our own key current without the user having to think about it: an existing key is
        // extended (same fingerprint) well before it lapses, so nothing has to be re-announced.
        if (store.keyDaysLeft() < KEY_RENEW_LEAD_DAYS) {
            runCatching { store.extendOwnKeys() }
                .onFailure { Log.w(TAG, "key extension failed: ${it.message}") }
        }

        if (store.fcmEnabled && System.currentTimeMillis() - store.fcmCheckedAt > 10 * 60_000L) {
            runCatching {
                PushManager.reconcileFcm(context)
                store.contacts().map { it.serverUrl }.distinct()
                    .filter { it != store.serverUrl }
                    .forEach { PushManager.reconcileFcm(context, serverUrl = it) }
            }.onFailure { Log.w(TAG, "fcm self-heal failed: ${it.message}") }
        }

        pollMailbox(context, mailbox = own, serverUrl = store.serverUrl, contact = null)
        // Extra page addresses, one per logging source, are ordinary system mailboxes too.
        store.pageMailboxes().forEach { address ->
            pollMailbox(context, mailbox = address.uuid, serverUrl = address.serverUrl, contact = null)
        }
        // Each contact has their own receiving mailbox; our private inbox is never one of these.
        store.contacts().forEach { contact ->
            pollMailbox(context, mailbox = contact.inbox, serverUrl = contact.serverUrl, contact = contact)
        }
        flushOutbox(context)
        flushP2PQueue(context)
    }

    /**
     * Uploads P2P envelopes we composed ourselves once the gateway is reachable again. Relayed
     * envelopes from other peers stay in custody; only our own messages become normal pages.
     */
    @Synchronized
    fun flushP2PQueue(context: Context) {
        val store = Store(context)
        val items = store.p2pQueue()
        if (items.isEmpty()) return
        val mySenderIds = store.contacts().map { it.mySenderId }.filter { it.isNotBlank() }.toSet()
        if (mySenderIds.isEmpty()) return
        val deliveredMids = store.history().filter { it.remoteDelivered }.mapNotNull { it.mid }.toSet()
        items.forEach { envelope ->
            // Already confirmed on the peer's phone over P2P: no gateway copy needed.
            if (envelope.id in deliveredMids) {
                store.removeP2P(envelope.id)
                return@forEach
            }
            val payload = runCatching { org.json.JSONObject(envelope.payload) }.getOrNull()
                ?: return@forEach
            if (payload.optString("from") !in mySenderIds) return@forEach
            val destination = payload.optString("destination").takeIf { it.isNotBlank() }
                ?: return@forEach
            val outcome = PagerClient.notifyJsonOutcome(store.serverUrl, destination, envelope.payload)
            when (outcome.kind) {
                NotifyKind.OK,
                NotifyKind.DUPLICATE -> {
                    store.markP2PDelivered(envelope.id)
                    Log.i(TAG, "P2P envelope delivered via gateway: ${envelope.id}")
                }
                NotifyKind.EXPIRED,
                NotifyKind.CONFLICT -> {
                    store.removeP2P(envelope.id)
                    Log.w(TAG, "P2P envelope dropped (${outcome.kind}): ${envelope.id}")
                }
                // FULL/RETRY/DISABLED: keep it and try again on the next poll.
                else -> {}
            }
        }
    }

    /** Retries messages that were queued while the gateway was unreachable. */
    @Synchronized
    fun flushOutbox(context: Context) {
        val store = Store(context)
        store.queuedSends().forEach { queued ->
            val outcome = PagerClient.notifyJsonOutcome(
                queued.serverUrl,
                queued.mailbox,
                queued.payloadJson,
            )
            when (outcome.kind) {
                NotifyKind.OK,
                NotifyKind.DUPLICATE -> {
                    store.completeQueuedSend(queued.id)
                    Log.i(TAG, "queued message sent: ${queued.id}")
                }
                // These cannot succeed on retry: drop them so a broken entry cannot wedge the
                // whole outbox. Network/full errors stay queued for the next poll.
                NotifyKind.EXPIRED,
                NotifyKind.CONFLICT,
                NotifyKind.DISABLED -> {
                    store.failQueuedSendPermanent(queued.id, outcome.error)
                    Log.w(TAG, "queued message dropped (${outcome.kind}): ${queued.id}")
                }
                NotifyKind.FULL,
                NotifyKind.RETRY -> {
                    store.failQueuedSend(queued.id, outcome.error)
                }
            }
        }
    }

    /** Fetches one mailbox by id, resolving the contact it belongs to (used by push triggers). */
    fun pollMailboxNow(context: Context, mailbox: String, serverUrl: String? = null): Boolean {
        val store = Store(context)
        val contacts = store.contacts()
        val targets = if (serverUrl != null) listOf(serverUrl to mailbox) else buildList {
            store.uuid?.takeIf { it == mailbox }?.let { add(store.serverUrl to it) }
            store.pageMailboxes().filter { it.uuid == mailbox }.forEach { add(it.serverUrl to it.uuid) }
            contacts.filter { it.inbox == mailbox }.forEach { add(it.serverUrl to it.inbox) }
        }.distinct().ifEmpty { listOf(store.serverUrl to mailbox) }
        return targets.map { (server, target) ->
            val contact = contacts.firstOrNull { it.serverUrl == server && it.inbox == target }
            pollMailbox(context, target, server, contact)
        }.all { it }
    }

    fun pollAllMailboxesNow(context: Context): Boolean {
        val store = Store(context)
        val targets = buildList {
            store.uuid?.let { add(Triple(store.serverUrl, it, null as Contact?)) }
            store.pageMailboxes().forEach { address ->
                add(Triple(address.serverUrl, address.uuid, null as Contact?))
            }
            store.contacts().forEach { contact -> add(Triple(contact.serverUrl, contact.inbox, contact)) }
        }.distinctBy { it.first to it.second }
        if (targets.isEmpty()) return true
        return targets.map { (server, mailbox, contact) ->
            pollMailbox(context, mailbox, server, contact)
        }.all { it }
    }

    fun runFcmWake(context: Context, mailbox: String?): Boolean {
        val store = Store(context)
        if (mailbox.isNullOrBlank()) {
            return pollAllMailboxesNow(context)
        }
        val matches = buildList {
            store.uuid?.takeIf { it == mailbox }?.let { add(store.serverUrl to it) }
            store.pageMailboxes().filter { it.uuid == mailbox }
                .forEach { add(it.serverUrl to it.uuid) }
            store.contacts().filter { it.inbox == mailbox }
                .forEach { add(it.serverUrl to it.inbox) }
        }.distinct()
        return if (matches.size == 1) pollMailboxNow(context, mailbox, matches.single().first)
        else pollAllMailboxesNow(context)
    }

    /** Fetches one mailbox's queued pages and processes them. */
    fun pollMailbox(context: Context, mailbox: String, serverUrl: String, contact: Contact?): Boolean {
        val store = Store(context)
        if (!WebclientMode.ensureTarget(store, serverUrl, mailbox)) {
            Log.w(TAG, "webclient lease not active for $mailbox; leaving it for the browser")
            return false
        }
        val pages = runCatching { PagerClient.messages(serverUrl, mailbox) }
            .onFailure { Log.w(TAG, "fetch failed for $mailbox: ${it.message}") }
            .getOrNull()
            ?: return false
        handlePages(context, mailbox, serverUrl, contact, pages)
        return true
    }

    /**
     * Processes pages from one mailbox: learn the peer, decide, notify, record history, ack.
     * Safe to call with the same page twice — the dedupe set filters retries.
     */
    fun handlePages(
        context: Context,
        mailbox: String,
        serverUrl: String,
        contact: Contact?,
        pages: List<Page>,
    ) {
        if (pages.isEmpty()) return

        val store = Store(context)
        if (!WebclientMode.ensureTarget(store, serverUrl, mailbox)) {
            Log.w(TAG, "webclient lease not active for $mailbox; not processing it")
            return
        }
        val seen = store.dedupeSnapshot()
        // `/messages?order=newest` is newest-first. Once the first already-seen id is reached,
        // everything after it is older than a message this client has handled; stop there.
        val fresh = pages.takeWhile { it.id !in seen }
        if (fresh.isEmpty()) return

        val now = LocalTime.now()
        val config = store.effectiveConfig()
        val history = mutableListOf<HistoryEntry>()
        val ownMids = store.history()
            .mapNotNull { entry -> entry.mid?.takeIf { it.isNotBlank() }?.let { it to entry.from } }
            .toSet()
        // Pages we told the user about: they stay in the mailbox until a human confirms them.
        val awaitingConfirmation = mutableSetOf<String>()

        // A chat photo arrives as numbered chunks. Store each slice, confirm it, and only turn
        // the image into a history/notification entry once every part has been assembled.
        val imagePages = fresh.filter { it.imageId != null }
        val normalFresh = fresh.filter { it.imageId == null }
        imagePages.forEach { page ->
            val mine = contact != null && page.from != null && page.from == contact.mySenderId
            if (!mine && contact != null) {
                // Learn the peer's identity/key from the first chunk too, so a photo that is the
                // first thing they send still leaves a usable contact behind.
                if (contact.awaitingReply && page.from != null) {
                    store.upsertContact(
                        contact.copy(senderId = page.from!!, name = page.fromName ?: contact.name),
                    )
                }
                io.ofloo.pagerapp.shared.publicKeyFrom(page)?.let { learned ->
                    if (learned != contact.publicKey) store.upsertContact(contact.copy(publicKey = learned))
                }
                if (ChatPhotos.storeChunk(context, page)) {
                    runCatching { PagerClient.ack(serverUrl, mailbox, page.id) }
                        .onFailure { Log.w(TAG, "image chunk ack failed for ${page.id}: ${it.message}") }
                } else {
                    Log.w(TAG, "image chunk rejected for ${page.id}")
                }
            }
        }
        if (contact != null) {
            val completed = ChatPhotos.completed(context, store.ownSecretKey.ifBlank { null })
            completed.forEach { photo ->
                if (store.dedupeSnapshot().contains(photo.imageKey)) return@forEach
                if (photo.from != null && contact.senderId.isNotBlank() && photo.from != contact.senderId) {
                    return@forEach
                }
                val shown = Page(
                    id = photo.imageKey,
                    status = STATUS_MESSAGE,
                    message = "Photo",
                    from = photo.from,
                    fromName = photo.fromName,
                    createdAt = photo.createdAt,
                )
                val decision = decideForContact(contact, config, now)
                val deliver = decision.notify
                if (deliver) {
                    Notifier.post(
                        context,
                        shown,
                        audible = Notifications.audible(decision) && Notifications.phoneChannels(decision),
                        channel = Notifications.CHANNEL_MESSAGES,
                        mailbox = mailbox,
                        serverUrl = serverUrl,
                        imagePath = photo.path,
                        sound = decision.sound,
                        vibration = decision.vibration,
                        repeat = decision.repeat,
                    )
                }
                store.rememberSeen(listOf(photo.imageKey))
                history += HistoryEntry(
                    id = photo.imageKey,
                    status = STATUS_MESSAGE,
                    jobId = null,
                    project = null,
                    message = "Photo",
                    createdAt = if (photo.createdAt > 0.0) photo.createdAt else System.currentTimeMillis() / 1000.0,
                    ackUuid = null,
                    mailbox = mailbox,
                    serverUrl = serverUrl,
                    imagePath = photo.path,
                    receivedAt = System.currentTimeMillis() / 1000.0,
                    keyBits = Pgp.bitsOf(store.ownPublicKey),
                    delivered = deliver,
                    from = photo.from,
                    fromName = photo.fromName,
                    encrypted = true,
                )
                Log.i(TAG, "chat photo ${photo.imageKey} assembled")
            }
        }

        normalFresh.forEach { page ->
            val fromPeer = contact != null && page.from != null && page.from != contact.mySenderId
            val isMine = contact != null && page.from != null && page.from == contact.mySenderId

            // Our own message seen in a shared mailbox would otherwise echo back as an incoming one.
            // We already keep our side of the thread locally when sending, so skip it entirely —
            // and deliberately do not ack it, or the peer would never receive it.
            if (isMine) return@forEach

            // Receipt-only pages: delivery means "on the peer's phone", read means opened.
            val deliveredMid = page.deliveredMid
            if (!deliveredMid.isNullOrBlank()) {
                store.markDeliveredByMid(deliveredMid)
                return@forEach
            }
            val readMid = page.read
            if (!readMid.isNullOrBlank()) {
                store.markReadByMid(readMid)
                return@forEach
            }

            // Tell the sender the message reached this phone. This is separate from the read
            // receipt, which is sent only when the user displays the conversation.
            val messageMid = page.mid
            if (contact != null && !messageMid.isNullOrBlank()) {
                runCatching {
                    PagerClient.notify(
                        contact.serverUrl,
                        contact.outbox,
                        buildDeliveryReceipt(contact, messageMid, store.displayName),
                    )
                }.onFailure { Log.w(TAG, "delivery receipt failed for ${page.id}: ${it.message}") }
            }

            // On a shared channel, learn who is on the other end from their first message.
            if (fromPeer && contact!!.awaitingReply) {
                store.upsertContact(
                    contact.copy(senderId = page.from!!, name = page.fromName ?: contact.name),
                )
            }

            // Learn the peer's key only on first contact. A later key is not silently trusted:
            // replacing it would let a holder of the mailbox bearer sign as the peer.
            val learnedKey = publicKeyFrom(page)
            val pinnedKey = contact?.publicKey.orEmpty()
            if (contact != null && !learnedKey.isNullOrBlank() && learnedKey != pinnedKey) {
                if (pinnedKey.isBlank()) {
                    store.upsertContact(contact.copy(publicKey = learnedKey))
                    store.addHistory(
                        listOf(
                            HistoryEntry(
                                id = "notice-${page.id}-key",
                                status = STATUS_MESSAGE,
                                jobId = null,
                                project = null,
                                message = "🔑 learned their key — messages are now encrypted and signed",
                                createdAt = System.currentTimeMillis() / 1000.0,
                                ackUuid = null,
                                receivedAt = System.currentTimeMillis() / 1000.0,
                                delivered = false,
                                from = contact.senderId.ifBlank { learnedKey },
                                fromName = page.fromName ?: contact.name,
                                acked = true,
                                notice = true,
                            ),
                        ),
                    )
                } else {
                    store.addHistory(
                        listOf(
                            HistoryEntry(
                                id = "notice-${page.id}-key-change",
                                status = STATUS_MESSAGE,
                                jobId = null,
                                project = null,
                                message = "⚠ their key changed — not trusted; re-add the contact to accept",
                                createdAt = System.currentTimeMillis() / 1000.0,
                                ackUuid = null,
                                receivedAt = System.currentTimeMillis() / 1000.0,
                                delivered = false,
                                from = contact.senderId.ifBlank { learnedKey },
                                fromName = page.fromName ?: contact.name,
                                acked = true,
                                notice = true,
                            ),
                        ),
                    )
                }
            }

            // A copy of this user's own message from another client is history, not an incoming
            // page. Only trust the marker when the sender id belongs to one of our contacts.
            val knownSelf = page.selfCopy && contact == null && page.from != null &&
                store.contacts().any { it.mySenderId == page.from }
            val selfCopy = page.selfCopy && (isMine || knownSelf)

            // Decrypt the body when it is encrypted; never show ciphertext as if it were a message.
            val clear = readableText(page, store.ownSecretKey.ifBlank { null })
            val verificationKey = when {
                // The web client signs self-copies with the phone's own key. There is no peer
                // contact for the private mailbox, so verify against our own key instead of
                // flagging every web message as a bad signature.
                selfCopy && store.ownPublicKey.isNotBlank() -> store.ownPublicKey
                contact == null -> null
                else -> pinnedKey.ifBlank { learnedKey ?: "" }.takeIf { it.isNotBlank() }
            }
            val signatureRequired = SignaturePolicy.isRequired(page.createdAt)
            val signature = page.sig
            val signatureValid = when {
                !signatureRequired -> null
                signature == null -> false
                clear == null || verificationKey == null -> false
                else -> runCatching { Pgp.verify(clear, signature, verificationKey) }
                    .getOrDefault(false)
            }
            var shown = when {
                page.enc.isNullOrBlank() -> page
                clear != null -> page.copy(message = clear)
                else -> page.copy(message = "🔒 encrypted — no usable key")
            }
            // A share link in a chat is fetched and decrypted on receipt, so the photo is already
            // on the device by the time the user opens the conversation.
            var imagePath: String? = null
            var filePath: String? = null
            var fileName: String? = null
            var fileMime: String? = null
            if (!isMine && contact != null && contact.kind == ContactKind.PERSON) {
                val photoLink = PhotoLink.parse(clear)
                if (photoLink != null) {
                    imagePath = downloadLink(context, store, page, photoLink)
                    if (imagePath != null) shown = shown.copy(message = "Photo")
                } else {
                    FileLink.parse(clear)?.let { link ->
                        downloadFileLink(context, store, page, link)?.let { file ->
                            filePath = file.path
                            fileName = file.name
                            fileMime = file.mime
                        }
                    }
                }
            }

            val isChat = contact != null && contact.kind == ContactKind.PERSON
            val decision: Decision = if (isChat) {
                // The chat's own level decides; "always" ignores this app's active hours.
                decideForContact(contact!!, config, now)
            } else {
                decide(shown, config, now)
            }
            // A web-sent attachment only carries the share link in its self copy. Do not show
            // the raw pagerapp:// URL as chat text; keep a readable placeholder on this device.
            if (selfCopy) {
                PhotoLink.parse(clear)?.let { shown = shown.copy(message = "Photo") }
                FileLink.parse(clear)?.let { shown = shown.copy(message = "Attachment: ${it.name}") }
            }
            // A native send already created this history entry locally; the back-channel copy is
            // only for the *other* client, so do not add a second bubble for it.
            if (selfCopy && page.mid != null && (page.mid to page.from) in ownMids) {
                return@forEach
            }
            // The same chat message can arrive over P2P first and later through the gateway (a
            // queued message that was uploaded once connectivity returned). Dedupe by mid/from,
            // not by the server's message id, so the thread never shows it twice.
            if (!selfCopy && page.mid != null && (page.mid to page.from) in ownMids) {
                Log.i(TAG, "duplicate chat message via gateway, already delivered: ${page.mid}")
                store.collapseDuplicateMids()
                return@forEach
            }
            val deliver = decision.notify && !isMine && !selfCopy && page.acked != true
            if (deliver) {
                val audible = Notifications.audible(decision) && Notifications.phoneChannels(decision)
                Notifier.post(
                    context,
                    shown,
                    audible = audible,
                    channel = if (isChat) Notifications.CHANNEL_MESSAGES else Notifications.CHANNEL_PAGES,
                    mailbox = mailbox,
                    serverUrl = serverUrl,
                    imagePath = imagePath,
                    sound = decision.sound,
                    vibration = decision.vibration,
                    repeat = decision.repeat,
                )
            }
            if (deliver && contact == null) awaitingConfirmation += page.id
            history += HistoryEntry.from(
                shown,
                delivered = deliver,
                mailbox = mailbox,
                serverUrl = serverUrl,
                imagePath = imagePath,
                filePath = filePath,
                fileName = fileName,
                fileMime = fileMime,
                receivedAt = System.currentTimeMillis() / 1000.0,
                keyBits = if (!shown.enc.isNullOrBlank()) Pgp.bitsOf(store.ownPublicKey) else null,
                signatureValid = signatureValid,
            )
        }

        store.rememberSeen(fresh.map { it.id })
        store.addHistory(history)
        store.collapseDuplicateMids()

        normalFresh.forEach { page ->
            // A chat is acknowledged by reading it. A **page** is not: it is an instruction, and it
            // stays in the queue — and shows as open to the sender — until the user confirms it. The
            // dedupe set keeps a waiting page from notifying twice while it sits there. Pages that
            // asked for nothing (silent by the rules) are cleared right away: they are history only.
            if (page.id in awaitingConfirmation) return@forEach
            if (contact != null && !shouldAck(contact, page)) return@forEach
            runCatching { PagerClient.ack(serverUrl, mailbox, page.id) }
                .onFailure { Log.w(TAG, "ack failed for ${page.id}: ${it.message}") }
        }
    }

    private fun downloadLink(
        context: Context,
        store: Store,
        page: Page,
        link: PhotoLink,
    ): String? {
        val secret = store.ownSecretKey.ifBlank { null } ?: return null
        val armor = PhotoShare.download(store.photoShareUrl, link.url, link.sha256) ?: run {
            Log.w(TAG, "share photo download failed for ${link.url}")
            return null
        }
        val jpeg = runCatching { Pgp.decryptBytes(armor, secret) }.getOrNull() ?: return null
        if (jpeg.size > 9_000_000) return null
        val path = ChatPhotos.saveImage(context, "image-${page.id}", jpeg) ?: return null
        if (!store.webSessionActive()) PhotoShare.delete(store.photoShareUrl, link.deleteUrl)
        return path
    }

    private data class DownloadedAttachment(val path: String, val name: String, val mime: String)

    private fun downloadFileLink(
        context: Context,
        store: Store,
        page: Page,
        link: FileLink,
    ): DownloadedAttachment? {
        val secret = store.ownSecretKey.ifBlank { null } ?: return null
        val armor = PhotoShare.download(store.photoShareUrl, link.url, link.sha256) ?: run {
            Log.w(TAG, "share file download failed for ${link.url}")
            return null
        }
        val bytes = runCatching { Pgp.decryptBytes(armor, secret) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > ChatPhotos.MAX_ATTACHMENT_BYTES) return null
        val path = ChatPhotos.saveAttachment(
            context,
            "file-${page.id}",
            link.name,
            bytes,
        ) ?: return null
        if (!store.webSessionActive()) PhotoShare.delete(store.photoShareUrl, link.deleteUrl)
        return DownloadedAttachment(path, link.name, link.mime)
    }
}
