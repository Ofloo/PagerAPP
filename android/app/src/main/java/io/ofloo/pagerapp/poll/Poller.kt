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
import io.ofloo.pagerapp.shared.decide
import io.ofloo.pagerapp.shared.decideForContact
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

    /** One polling pass over the private mailbox and every contact channel. */
    fun pollOnce(context: Context) {
        val store = Store(context)
        val own = store.uuid ?: return
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

            // On a shared channel, learn who is on the other end from their first message.
            if (fromPeer && contact!!.awaitingReply) {
                store.upsertContact(
                    contact.copy(senderId = page.from!!, name = page.fromName ?: contact.name),
                )
            }

            val isChat = contact != null && contact.kind == ContactKind.PERSON
            val decision: Decision = if (isChat) {
                // The chat's own level decides; "always" ignores this app's active hours.
                decideForContact(contact!!, config, now)
            } else {
                decide(page, config, now)
            }
            val deliver = decision.notify && !isMine   // never notify about our own sent message
            if (deliver) {
                val audible = Notifications.audible(decision) && Notifications.phoneChannels(decision)
                Notifier.post(
                    context,
                    page,
                    audible = audible,
                    channel = if (isChat) Notifications.CHANNEL_MESSAGES else Notifications.CHANNEL_PAGES,
                )
            }
            history += HistoryEntry.from(page, delivered = deliver)
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
