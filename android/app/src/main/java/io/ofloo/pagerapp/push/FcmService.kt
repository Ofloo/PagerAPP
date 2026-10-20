package io.ofloo.pagerapp.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.ofloo.pagerapp.data.Store

/**
 * Wake-ups through Google's push service.
 *
 * Same contract as the UnifiedPush route: the message carries no content, only "something arrived",
 * so the page is fetched through the normal, encrypted path and every decision — notification level,
 * active hours, on-call, silence — stays in PagerAPP. Off unless the user turns it on in Settings.
 *
 * FCM callbacks process quickly on the service thread without relying on alarm polling.
 */
class FcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.i(TAG, "fcm token received")
        if (Store(this).fcmEnabled) FcmTokenWorker.enqueue(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val store = Store(this)
        if (!store.fcmEnabled) {
            Log.i(TAG, "ignoring fcm wake while disabled")
            return
        }
        if (store.alarmPolling) Log.w(TAG, "fcm wake received while alarm polling is enabled")
        val mailbox = message.data["mailbox"]
        Log.i(
            TAG,
            "fcm wake received: priority=${message.priority}, original=${message.originalPriority}, " +
                "dataKeys=${message.data.keys}",
        )
        FcmWakeWorker.enqueue(this, mailbox)
    }

    private companion object {
        const val TAG = "PagerAPP"
    }
}
