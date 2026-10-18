package io.ofloo.pagerapp.push

import android.content.Context
import android.util.Log
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.poll.Poller
import org.json.JSONObject
import org.unifiedpush.android.connector.MessagingReceiver

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

    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        Log.i(TAG, "new distributor endpoint, registering it for the mailbox")
        PushManager.subscribe(context, endpoint)
    }

    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        val text = String(message, Charsets.UTF_8)
        val trigger = runCatching { JSONObject(text) }.getOrNull()
        if (trigger == null) {
            Log.w(TAG, "unparseable push trigger: ${text.take(120)}")
            return
        }
        when (trigger.optString("type")) {
            "page" -> {
                val mailbox = trigger.optString("mailbox").takeIf { it.isNotBlank() }
                    ?: Store(context).uuid ?: return
                Poller.pollMailboxNow(context, mailbox)
            }
            "warning" -> {
                Log.i(TAG, "server says the subscription is expiring; renewing")
                PushManager.renewIfNeeded(context, force = true)
            }
            else -> Log.i(TAG, "ignoring trigger type ${trigger.optString("type")}")
        }
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        Log.w(TAG, "distributor registration failed")
    }

    override fun onUnregistered(context: Context, instance: String) {
        Log.i(TAG, "distributor unregistered this app")
        val store = Store(context)
        store.pushId = ""
        store.pushExpires = 0L
    }

    private companion object {
        const val TAG = "PagerAPP"
    }
}
