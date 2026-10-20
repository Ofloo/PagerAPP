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
import io.ofloo.pagerapp.shared.VibrationPattern
import io.ofloo.pagerapp.shared.buildAck
import io.ofloo.pagerapp.ui.ChatPhotos
import io.ofloo.pagerapp.shared.buildPrologAck
import io.ofloo.pagerapp.poll.PollService

/** Builds and posts local notifications, including the opt-in ACK action. */
object Notifier {

    const val EXTRA_PAGE_ID = "pager_page_id"
    const val EXTRA_PAGE_FROM = "pager_page_from"

    private const val TAG = "PagerAPP"

    fun post(
        context: Context,
        page: Page,
        audible: Boolean,
        channel: String = Notifications.CHANNEL_PAGES,
        mailbox: String? = null,
        serverUrl: String? = null,
        imagePath: String? = null,
        sound: String? = null,
        vibration: VibrationPattern? = null,
        repeat: Boolean = false,
        notificationId: Int = page.id.hashCode(),
    ) {
        Notifications.ensureChannels(context)

        val tapIntent = PendingIntent.getActivity(
            context,
            page.id.hashCode(),
            Intent(context, io.ofloo.pagerapp.ui.MainActivity::class.java)
                .putExtra(EXTRA_PAGE_ID, page.id)
                .putExtra(EXTRA_PAGE_FROM, page.from)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val channelId = Notifications.channelId(context, channel, page.status, sound, vibration)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(Notifications.title(page))
            .setContentText(Notifications.text(page))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(
                if (audible) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW,
            )

        val picture = imagePath?.let { ChatPhotos.decodeSampled(it, 1080) }
        if (picture != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(picture)
                    .bigLargeIcon(null as android.graphics.Bitmap?),
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(page.textWithLogs()))
        }

        if (audible) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        } else {
            builder.setSilent(true)
        }

        // A page always offers ACK: that is how it leaves the queue. (A page that also named an
        // ack_uuid reports the acknowledgement back to its sender; one that did not is simply
        // confirmed and dropped.) Chat messages carry the action only when they opted in.
        if (page.ackAvailable || channel == Notifications.CHANNEL_PAGES) {
            val ackIntent = Intent(context, AckReceiver::class.java)
                .putExtra(AckReceiver.EXTRA_PAGE_ID, page.id)
                .putExtra(AckReceiver.EXTRA_JOB_ID, page.jobId)
                .putExtra(AckReceiver.EXTRA_ACK_UUID, page.ackUuid)
                .putExtra(AckReceiver.EXTRA_REPLY_UUID, page.replyUuid)
                .putExtra(AckReceiver.EXTRA_ACK_TOKEN, page.ackToken)
                .putExtra(AckReceiver.EXTRA_MAILBOX, mailbox ?: Store(context).uuid)
                .putExtra(AckReceiver.EXTRA_SERVER, serverUrl ?: Store(context).serverUrl)
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

        if (repeat) {
            RepeatAlerts.add(
                RepeatAlert(
                    page = page,
                    audible = audible,
                    channel = channel,
                    mailbox = mailbox,
                    serverUrl = serverUrl,
                    imagePath = imagePath,
                    sound = sound,
                    vibration = vibration,
                ),
            )
            // The foreground service owns the repeat timer. If it is already running this is a
            // no-op; if it cannot start from the background, the first notification still alerts.
            runCatching { PollService.start(context) }
                .onFailure { Log.w(TAG, "repeat service start failed: ${it.message}") }
        } else if (notificationId == page.id.hashCode()) {
            RepeatAlerts.remove(page.id)
        }

        if (canPost(context)) {
            try {
                NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            } catch (error: SecurityException) {
                Log.w(TAG, "notification permission unavailable: ${error.message}")
            }
        }
    }

    /**
     * Posts the ACK for a page: always confirms the mailbox queue entry, then reports through the
     * profile the page uses — native `ack_uuid` (status 6) or ProLOG's reply mailbox (`ack <token>`).
     * Returns true when the page was acknowledged: the required report succeeded, or (for a page
     * without a report target) the queue confirmation did.
     */
    fun sendAck(
        context: Context,
        pageId: String,
        jobId: String?,
        ackUuid: String?,
        replyUuid: String? = null,
        ackToken: String? = null,
        note: String? = null,
        mailbox: String? = null,
        serverUrl: String? = null,
    ): Boolean {
        val store = Store(context)
        val own = store.uuid
        val server = serverUrl ?: store.serverUrl
        // Stop any repeating alert for this page before removing its notification.
        val repeatId = RepeatAlerts.remove(pageId)
        // Confirming removes the page from its mailbox — that is what "acknowledged" means here.
        val box = mailbox ?: own
        var confirmed = false
        if (!box.isNullOrBlank()) {
            runCatching { io.ofloo.pagerapp.net.PagerClient.ack(server, box, pageId) }
                .onSuccess {
                    confirmed = true
                    Log.i(TAG, "page $pageId confirmed, removed from ${box.take(8)}…")
                }
                .onFailure { Log.w(TAG, "clearing $pageId failed: ${it.message}") }
        }

        val hasNative = !ackUuid.isNullOrBlank()
        val hasProlog = !replyUuid.isNullOrBlank() && !ackToken.isNullOrBlank()
        var reported = !hasNative && !hasProlog

        if (hasNative && own.isNullOrBlank()) {
            Log.w(TAG, "ACK report skipped: no own mailbox uuid")
        } else if (hasNative) {
            val page = Page(id = pageId, status = 0, jobId = jobId, ackUuid = ackUuid)
            val ack = buildAck(page, own.orEmpty(), note)
            if (ack == null) {
                Log.w(TAG, "ACK report skipped: buildAck returned null")
            } else {
                runCatching {
                    io.ofloo.pagerapp.net.PagerClient.notify(server, ack.targetUuid, ack.payload)
                }.onSuccess {
                    reported = true
                    Log.i(TAG, "ACK sent to ${ack.targetUuid} for page $pageId")
                }.onFailure {
                    Log.e(TAG, "ACK failed for page $pageId", it)
                }
            }
        } else if (hasProlog) {
            val page = Page(
                id = pageId,
                status = 0,
                jobId = jobId,
                replyUuid = replyUuid,
                ackToken = ackToken,
            )
            val ack = buildPrologAck(page)
            if (ack == null) {
                Log.w(TAG, "ProLOG ACK skipped: no reply mailbox or token")
            } else {
                runCatching {
                    io.ofloo.pagerapp.net.PagerClient.notify(server, ack.targetUuid, ack.payload)
                }.onSuccess {
                    reported = true
                    Log.i(TAG, "ProLOG ACK sent to ${ack.targetUuid} for page $pageId")
                }.onFailure {
                    Log.e(TAG, "ProLOG ACK failed for page $pageId", it)
                }
            }
        }

        val success = if (hasNative || hasProlog) reported else confirmed
        if (success) runCatching { store.markAcked(pageId) }
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(pageId.hashCode())
        repeatId?.let { nm.cancel(it) }
        return success
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
