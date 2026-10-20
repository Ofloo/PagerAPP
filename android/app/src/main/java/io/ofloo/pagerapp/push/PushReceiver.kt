package io.ofloo.pagerapp.push

import android.content.Context
import android.util.Log
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.poll.Poller
import org.json.JSONObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Receives UnifiedPush events, so the device can be woken without the app running and without a
 * foreground service or a notification.
 *
 * The server never sends content, only a trigger:
 * - `{"type":"page","mailbox":"…","id":"…"}` → fetch that mailbox through the normal path
 *   (dedupe, ack, notification, padlock) exactly as a poll would;
 * - `{"type":"warning","reason":"subscription-expiring"}` → renew the lease at once, so push
 *   never lapses unnoticed.
 */
class PushReceiver : MessagingReceiver() {

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        Log.i(TAG, "new distributor endpoint, registering it for the mailbox")
        background { PushManager.subscribe(context, endpoint.url) }
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        val text = String(message.content, Charsets.UTF_8)
        val trigger = runCatching { JSONObject(text) }.getOrNull()
        if (trigger == null || trigger.optString("type").isBlank()) {
            // The server's own `ntfy` channel does not send a JSON trigger but a plain-text wake-up
            // ("New page in mailbox <uuid>"). Any wake-up means the same thing: fetch now. A trigger
            // we cannot read lands here too — better a fetch than a silently dropped page.
            Log.i(TAG, "push wake-up: ${text.take(80)}")
            val mailbox = Store(context).uuid ?: return
            background { Poller.pollMailboxNow(context, mailbox) }
            return
        }
        when (trigger.optString("type")) {
            "page" -> {
                val mailbox = trigger.optString("mailbox").takeIf { it.isNotBlank() }
                    ?: Store(context).uuid ?: return
                background { Poller.pollMailboxNow(context, mailbox) }
            }
            "warning" -> {
                Log.i(TAG, "server says the subscription is expiring; renewing")
                background {
                    PushManager.renewIfNeeded(context, force = true)
                    if (Store(context).fcmEnabled) PushManager.reconcileFcm(context, force = true)
                }
            }
            else -> Log.i(TAG, "ignoring trigger type ${trigger.optString("type")}")
        }
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        Log.w(TAG, "distributor registration failed: $reason")
    }

    override fun onUnregistered(context: Context, instance: String) {
        Log.i(TAG, "distributor unregistered this app")
        val store = Store(context)
        if (store.pushChannel != PushManager.FCM_CHANNEL) {
            store.pushId = ""
            store.pushExpires = 0L
            store.pushChannel = ""
        }
    }

    /**
     * The connector calls us on the **main** thread, where any network call would die with
     * NetworkOnMainThreadException. Everything that touches the server therefore hops off it.
     */
    private fun background(block: () -> Unit) {
        Thread(block, "pagerapp-push").start()
    }

    private companion object {
        const val TAG = "PagerAPP"
    }
}
