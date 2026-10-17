package io.ofloo.pagerapp.poll

import android.content.Context
import android.util.Log
import io.ofloo.pagerapp.data.HistoryEntry
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.net.PagerClient
import io.ofloo.pagerapp.notify.Notifications
import io.ofloo.pagerapp.notify.Notifier
import io.ofloo.pagerapp.shared.Contact
import io.ofloo.pagerapp.shared.ContactKind
import io.ofloo.pagerapp.shared.Decision
import io.ofloo.pagerapp.shared.Page
import io.ofloo.pagerapp.shared.STATUS_MESSAGE
import io.ofloo.pagerapp.shared.decide
import io.ofloo.pagerapp.shared.decideForContact
import io.ofloo.pagerapp.shared.publicKeyFrom
import io.ofloo.pagerapp.shared.readableText
import io.ofloo.pagerapp.shared.shouldAck
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

    /** One polling pass over the private mailbox and every contact channel. */
    fun pollOnce(context: Context) {
        val store = Store(context)
        val own = store.uuid ?: return

        // Keep our own key current without the user having to think about it: an existing key is
        // extended (same fingerprint) well before it lapses, so nothing has to be re-announced.
        if (store.keyDaysLeft() < KEY_RENEW_LEAD_DAYS) {
            runCatching { store.extendOwnKeys() }
                .onFailure { Log.w(TAG, "key extension failed: ${it.message}") }
        }

        pollMailbox(context, mailbox = own, serverUrl = store.serverUrl, contact = null)
        // Each contact has their own receiving mailbox; our private inbox is never one of these.
        store.contacts().forEach { contact ->
            pollMailbox(context, mailbox = contact.inbox, serverUrl = contact.serverUrl, contact = contact)
        }
    }

    /** Fetches one mailbox's queued pages and processes them. */
    fun pollMailbox(context: Context, mailbox: String, serverUrl: String, contact: Contact?) {
        val pages = runCatching { PagerClient.messages(serverUrl, mailbox) }
            .onFailure { Log.w(TAG, "fetch failed for $mailbox: ${it.message}") }
            .getOrNull()
            ?: return
        handlePages(context, mailbox, serverUrl, contact, pages)
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
        val seen = store.dedupeSnapshot()
        val fresh = pages.filter { it.id !in seen }
        if (fresh.isEmpty()) return

        val now = LocalTime.now()
        val config = store.effectiveConfig()
        val history = mutableListOf<HistoryEntry>()

        fresh.forEach { page ->
            val fromPeer = contact != null && page.from != null && page.from != contact.mySenderId
            val isMine = contact != null && page.from != null && page.from == contact.mySenderId

            // Our own message seen in a shared mailbox would otherwise echo back as an incoming one.
            // We already keep our side of the thread locally when sending, so skip it entirely —
            // and deliberately do not ack it, or the peer would never receive it.
            if (isMine) return@forEach

            // On a shared channel, learn who is on the other end from their first message.
            if (fromPeer && contact!!.awaitingReply) {
                store.upsertContact(
                    contact.copy(senderId = page.from!!, name = page.fromName ?: contact.name),
                )
            }

            // Learn the peer's key from any message that carries one, so replies go out encrypted.
            if (contact != null) {
                publicKeyFrom(page)?.let { learned ->
                    if (learned != contact.publicKey) {
                        store.upsertContact(contact.copy(publicKey = learned))
                        // Say so in the conversation: otherwise the handshake is invisible.
                        store.addHistory(
                            listOf(
                                HistoryEntry(
                                    id = "notice-${page.id}-key",
                                    status = STATUS_MESSAGE,
                                    jobId = null,
                                    project = null,
                                    message = if (contact.publicKey.isBlank()) {
                                        "🔑 learned their key — messages are now encrypted"
                                    } else {
                                        "🔑 their key changed"
                                    },
                                    createdAt = System.currentTimeMillis() / 1000.0,
                                    ackUuid = null,
                                    delivered = false,
                                    from = contact.senderId.ifBlank { learned },
                                    fromName = page.fromName ?: contact.name,
                                    acked = true,
                                    notice = true,
                                ),
                            ),
                        )
                    }
                }
            }

            // Decrypt the body when it is encrypted; never show ciphertext as if it were a message.
            val clear = readableText(page, store.ownSecretKey.ifBlank { null })
            val shown = when {
                page.enc.isNullOrBlank() -> page
                clear != null -> page.copy(message = clear)
                else -> page.copy(message = "🔒 encrypted — no usable key")
            }

            val isChat = contact != null && contact.kind == ContactKind.PERSON
            val decision: Decision = if (isChat) {
                // The chat's own level decides; "always" ignores this app's active hours.
                decideForContact(contact!!, config, now)
            } else {
                decide(shown, config, now)
            }
            val deliver = decision.notify && !isMine   // never notify about our own sent message
            if (deliver) {
                val audible = Notifications.audible(decision) && Notifications.phoneChannels(decision)
                Notifier.post(
                    context,
                    shown,
                    audible = audible,
                    channel = if (isChat) Notifications.CHANNEL_MESSAGES else Notifications.CHANNEL_PAGES,
                )
            }
            history += HistoryEntry.from(shown, delivered = deliver)
        }

        store.rememberSeen(fresh.map { it.id })
        store.addHistory(history)

        fresh.forEach { page ->
            if (contact != null && !shouldAck(contact, page)) return@forEach
            runCatching { PagerClient.ack(serverUrl, mailbox, page.id) }
                .onFailure { Log.w(TAG, "ack failed for ${page.id}: ${it.message}") }
        }
    }
}
