package io.ofloo.pagerapp.notify

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.ofloo.pagerapp.R
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.shared.Page
import io.ofloo.pagerapp.shared.buildAck

/** Builds and posts local notifications, including the opt-in ACK action. */
object Notifier {

    private const val TAG = "PagerAPP"

    fun post(
        context: Context,
        page: Page,
        audible: Boolean,
        channel: String = Notifications.CHANNEL_PAGES,
    ) {
        Notifications.ensureChannels(context)

        val tapIntent = PendingIntent.getActivity(
            context,
            page.id.hashCode(),
            Intent(context, io.ofloo.pagerapp.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(Notifications.title(page))
            .setContentText(Notifications.text(page))
            .setStyle(NotificationCompat.BigTextStyle().bigText(page.textWithLogs()))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(
                if (audible) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW,
            )

        if (audible) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        } else {
            builder.setSilent(true)
        }

        // ACK action: only when the page opted in with an ack_uuid.
        if (page.ackAvailable) {
            val ackIntent = Intent(context, AckReceiver::class.java)
                .putExtra(AckReceiver.EXTRA_PAGE_ID, page.id)
                .putExtra(AckReceiver.EXTRA_JOB_ID, page.jobId)
                .putExtra(AckReceiver.EXTRA_ACK_UUID, page.ackUuid)
            val ackPi = PendingIntent.getBroadcast(
                context,
                "ack:${page.id}".hashCode(),
                ackIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                R.drawable.ic_launcher,
                context.getString(R.string.action_ack),
                ackPi,
            )
        }

        if (canPost(context)) {
            NotificationManagerCompat.from(context).notify(page.id.hashCode(), builder.build())
        }
    }

    /** Posts the ACK, resolving the target from the intent (falls back to stored page). */
    fun sendAck(context: Context, pageId: String, jobId: String?, ackUuid: String?) {
        val store = Store(context)
        val own = store.uuid
        if (ackUuid.isNullOrBlank() || own.isNullOrBlank()) {
            Log.w(TAG, "ACK skipped: ackUuid=${ackUuid != null} ownUuid=${own != null}")
            return
        }
        val page = Page(id = pageId, status = 0, jobId = jobId, ackUuid = ackUuid)
        val ack = buildAck(page, own) ?: run {
            Log.w(TAG, "ACK skipped: buildAck returned null")
            return
        }
        runCatching {
            io.ofloo.pagerapp.net.PagerClient.notify(store.serverUrl, ack.targetUuid, ack.payload)
        }.onSuccess {
            Log.i(TAG, "ACK sent to ${ack.targetUuid} for page $pageId")
            runCatching { store.markAcked(pageId) }
        }.onFailure {
            Log.e(TAG, "ACK failed for page $pageId", it)
        }
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(pageId.hashCode())
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun Page.textWithLogs(): String =
        if (logs.isNullOrBlank()) Notifications.text(this)
        else "${Notifications.text(this)}\n\n${logs}"
}
