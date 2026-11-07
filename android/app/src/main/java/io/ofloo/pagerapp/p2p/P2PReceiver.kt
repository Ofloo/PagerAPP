package io.ofloo.pagerapp.p2p

import android.content.Context
import io.ofloo.pagerapp.data.HistoryEntry
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.notify.Notifications
import io.ofloo.pagerapp.notify.Notifier
import io.ofloo.pagerapp.shared.Contact
import io.ofloo.pagerapp.shared.P2PEnvelope
import io.ofloo.pagerapp.shared.PageParser
import io.ofloo.pagerapp.shared.buildDeliveryReceipt
import io.ofloo.pagerapp.shared.p2pRouteHash
import io.ofloo.pagerapp.shared.Pgp
import io.ofloo.pagerapp.shared.SignaturePolicy
import io.ofloo.pagerapp.shared.decide
import io.ofloo.pagerapp.shared.decideForContact
import io.ofloo.pagerapp.shared.readableText
import org.json.JSONObject
import java.time.LocalTime

/** Turns a verified P2P envelope into the same local history/notification a PagerMCP page gets. */
object P2PReceiver {

    private const val TAG = "PagerAPP"

    fun deliver(context: Context, envelope: P2PEnvelope): Boolean {
        val store = Store(context)
        val json = runCatching { JSONObject(envelope.payload) }.getOrNull()
        if (json == null) {
            android.util.Log.w(TAG, "P2P deliver: payload is not JSON")
            return false
        }
        // A chat payload is built before any server assigns an id, so the envelope id is the id.
        val fields = json.toMap().toMutableMap().apply {
            if ((this["id"] as? String).isNullOrBlank()) this["id"] = envelope.id
        }
        val page = runCatching { PageParser.parse(fields) }.getOrElse {
            android.util.Log.w(TAG, "P2P deliver: unparsable payload (${it.message})")
            return false
        }.copy(id = envelope.id)
        if (page.status != io.ofloo.pagerapp.shared.STATUS_MESSAGE) {
            android.util.Log.w(TAG, "P2P deliver: status ${page.status} is not a chat message")
            return false
        }

        // Receipt-only pages never become bubbles; they only advance an outgoing message.
        page.deliveredMid?.takeIf { it.isNotBlank() }?.let {
            store.markDeliveredByMid(it)
            android.util.Log.i(TAG, "P2P deliver: delivery receipt for $it")
            return true
        }
        page.read?.takeIf { it.isNotBlank() }?.let {
            store.markReadByMid(it)
            android.util.Log.i(TAG, "P2P deliver: read receipt for $it")
            return true
        }

        // Decrypt with the key of the mailbox this envelope was addressed to; that is the key a
        // modern install holds per mailbox. The legacy single key is only a fallback.
        val targetMailbox = store.localMailboxIds().firstOrNull { p2pRouteHash(it) == envelope.to }
        val contacts = store.contacts()
        // A contact invited by us has no sender id until its first message arrives. Match that
        // first P2P message by the channel mailbox, then learn the sender id from the envelope.
        var contact = contacts.firstOrNull { c ->
            (page.from != null && page.from == c.senderId) ||
                (page.from != null && page.from == c.mySenderId)
        } ?: contacts.firstOrNull { c ->
            c.awaitingReply && targetMailbox != null && c.inbox == targetMailbox
        }
        val senderId = page.from
        if (contact != null && contact.awaitingReply && senderId != null &&
            senderId != contact.mySenderId
        ) {
            val learnedKey = page.pub?.takeIf { it.contains("BEGIN PGP PUBLIC KEY") }
            contact = contact.copy(
                senderId = senderId,
                name = page.fromName ?: contact.name,
                publicKey = learnedKey ?: contact.publicKey,
            )
            store.upsertContact(contact)
            android.util.Log.i(
                TAG,
                "P2P deliver: learned sender ${senderId.take(8)} on ${contact.inbox.take(8)}",
            )
        }
        val secret = targetMailbox?.let { store.secretKeyOf(it) }
            ?.takeIf { it.isNotBlank() }
            ?: store.ownSecretKey.ifBlank { null }
        val clear = readableText(page, secret)
        if (clear == null) {
            android.util.Log.w(
                TAG,
                "P2P deliver: no readable text (enc=${page.enc != null} " +
                    "mailbox=${targetMailbox?.take(8) ?: "-"} key=${if (secret == null) "none" else "present"})",
            )
            return false
        }
        val verificationKey = contact?.publicKey.orEmpty()
        val signatureRequired = SignaturePolicy.isRequired(page.createdAt)
        val signature = page.sig
        val signatureValid = when {
            !signatureRequired -> null
            signature == null -> false
            verificationKey.isBlank() -> false
            else -> runCatching { Pgp.verify(clear, signature, verificationKey) }
                .getOrDefault(false)
        }

        // A duplicate transfer may arrive from another peer; never add a second bubble. The peer
        // still needs a receipt on this transport, otherwise a BLE-only sender keeps its clock.
        if (store.history().any { it.id == envelope.id || (page.mid != null && it.mid == page.mid && it.from == page.from) }) {
            android.util.Log.i(TAG, "P2P deliver: ${envelope.id} already in history")
            val mid = page.mid
            if (contact != null && !mid.isNullOrBlank()) {
                enqueueDeliveryReceipt(context, contact, mid)
            }
            return true
        }

        val now = System.currentTimeMillis() / 1000.0
        val shown = page.copy(message = clear)
        val entry = HistoryEntry(
            id = envelope.id,
            status = shown.status,
            prio = shown.prio,
            jobId = shown.jobId,
            project = shown.project,
            message = clear,
            logs = shown.logs,
            replyTo = shown.replyTo,
            replyPreview = shown.replyPreview,
            replyFrom = shown.replyFrom,
            replyFromName = shown.replyFromName,
            sig = page.sig,
            signatureValid = signatureValid,
            createdAt = when {
                page.sentAt > 0.0 -> page.sentAt
                envelope.createdAt > 0L -> envelope.createdAt.toDouble()
                else -> now
            },
            ackUuid = null,
            mailbox = contact?.inbox,
            serverUrl = contact?.serverUrl,
            mid = page.mid,
            receivedAt = now,
            keyBits = if (page.enc != null) Pgp.bitsOf(store.ownPublicKey) else null,
            delivered = true,
            from = page.from,
            fromName = page.fromName,
            encrypted = page.enc != null,
        )
        store.addHistory(listOf(entry))
        store.collapseDuplicateMids()
        store.rememberSeen(listOf(envelope.id))

        if (contact != null) {
            val decision = decideForContact(contact, store.effectiveConfig(), LocalTime.now())
            if (decision.notify) {
                Notifier.post(
                    context = context,
                    page = shown,
                    audible = Notifications.audible(decision) && Notifications.phoneChannels(decision),
                    channel = Notifications.CHANNEL_MESSAGES,
                    mailbox = contact.inbox,
                    serverUrl = contact.serverUrl,
                    sound = decision.sound,
                    vibration = decision.vibration,
                    repeat = decision.repeat,
                )
            }
        } else {
            val decision = decide(shown, store.effectiveConfig(), LocalTime.now())
            if (decision.notify) {
                Notifier.post(
                    context = context,
                    page = shown,
                    audible = Notifications.audible(decision) && Notifications.phoneChannels(decision),
                    channel = Notifications.CHANNEL_MESSAGES,
                )
            }
        }
        android.util.Log.i(
            TAG,
            "P2P deliver ok ${envelope.id} from=${page.from} contact=${contact?.name ?: "-"}",
        )

        // Tell the sender the message reached this phone, over P2P when the gateway is away.
        page.mid?.takeIf { it.isNotBlank() }?.let { mid ->
            if (contact != null) enqueueDeliveryReceipt(context, contact, mid)
        }
        return true
    }

    /**
     * Queues a receipt on the P2P queue as well as the gateway path. A sender that is offline and
     * only has BLE can never fetch a server-side receipt, so the nearby transport must carry it.
     */
    fun enqueueDeliveryReceipt(context: Context, contact: Contact, mid: String) {
        val store = Store(context)
        if (!store.p2pModeEnabled || mid.isBlank()) return
        val now = System.currentTimeMillis() / 1000L
        val maxAge = store.p2pMaxAgeSeconds.takeIf { it > 0L } ?: 604800L
        val receipt = JSONObject(buildDeliveryReceipt(contact, mid, store.displayName))
        store.enqueueP2P(
            P2PEnvelope(
                id = java.util.UUID.randomUUID().toString(),
                from = store.ownRouteHash(),
                to = p2pRouteHash(contact.outbox),
                createdAt = now,
                expiresAt = now + maxAge,
                payload = receipt.toString(),
            ),
        )
        android.os.Handler(android.os.Looper.getMainLooper()).post { BleP2P.queueChanged() }
    }
}

private fun JSONObject.toMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    keys().forEach { key -> map[key] = if (isNull(key)) null else get(key) }
    return map
}
