package io.ofloo.pagerapp.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.ofloo.pagerapp.R
import io.ofloo.pagerapp.shared.Channel as PagerChannel
import io.ofloo.pagerapp.shared.Decision
import io.ofloo.pagerapp.shared.NotifyAction
import io.ofloo.pagerapp.shared.Page
import io.ofloo.pagerapp.shared.VibrationPattern

object Notifications {

    const val CHANNEL_PAGES = "pages"
    const val CHANNEL_SERVICE = "service"

    /** Person-to-person chats: a separate, quieter channel so the phone can treat them apart. */
    const val CHANNEL_MESSAGES = "messages"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val pages = NotificationChannel(
            CHANNEL_PAGES,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.notif_channel_desc) }
        val messages = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.msg_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.msg_channel_desc) }
        val service = NotificationChannel(
            CHANNEL_SERVICE,
            context.getString(R.string.poll_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        )
        nm.createNotificationChannel(pages)
        nm.createNotificationChannel(messages)
        nm.createNotificationChannel(service)
    }

    fun vibrationMillis(pattern: VibrationPattern): LongArray = when (pattern) {
        VibrationPattern.SHORT -> longArrayOf(0, 200)
        VibrationPattern.LONG -> longArrayOf(0, 500, 200, 500)
        VibrationPattern.REPEATED -> longArrayOf(0, 300, 150, 300, 150, 300)
    }

    /** True when [decision] should make a sound/vibration (silent pages stay quiet). */
    fun audible(decision: Decision): Boolean = decision.action != NotifyAction.SILENT

    fun phoneChannels(decision: Decision): Boolean = PagerChannel.PHONE in decision.channels

    fun watchChannels(decision: Decision): Boolean = PagerChannel.WATCH in decision.channels

    fun title(page: Page): String =
        page.jobId ?: page.project ?: "PagerAPP page"

    fun text(page: Page): String =
        page.message ?: "status ${page.status}"
}
