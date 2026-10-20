package io.ofloo.pagerapp.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import io.ofloo.pagerapp.R
import io.ofloo.pagerapp.shared.Channel as PagerChannel
import io.ofloo.pagerapp.shared.Decision
import io.ofloo.pagerapp.shared.NotifyAction
import io.ofloo.pagerapp.shared.FileLink
import io.ofloo.pagerapp.shared.Page
import io.ofloo.pagerapp.shared.PhotoLink
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
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500)
        }
        val messages = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.msg_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.msg_channel_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200)
        }
        val service = NotificationChannel(
            CHANNEL_SERVICE,
            context.getString(R.string.poll_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        )
        nm.createNotificationChannel(pages)
        nm.createNotificationChannel(messages)
        nm.createNotificationChannel(service)
    }

    /**
     * Picks (and lazily creates) a channel for the level's chosen sound. `null` means the system
     * default and keeps the base channel; `"none"` is a silent channel; anything else is a ringtone
     * URI with its own channel (Android fixes a channel's sound at creation).
     */
    fun channelId(
        context: Context,
        base: String,
        status: Int,
        soundUri: String?,
        vibration: VibrationPattern? = null,
    ): String {
        val suffix = when {
            soundUri.isNullOrBlank() -> "default"
            soundUri == "none" -> "silent"
            else -> soundUri.hashCode().toString()
        }
        val id = "${base}_${status}_$suffix"
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            val importance = if (base == CHANNEL_PAGES) {
                NotificationManager.IMPORTANCE_HIGH
            } else {
                NotificationManager.IMPORTANCE_DEFAULT
            }
            val label = if (base == CHANNEL_PAGES) "Pages · level $status"
            else "Messages · level $status"
            val channel = NotificationChannel(id, label, importance)
            when {
                soundUri == "none" -> channel.setSound(null, null)
                else -> channel.setSound(
                    (soundUri?.let(Uri::parse)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build(),
                )
            }
            channel.enableVibration(true)
            channel.vibrationPattern = vibrationPattern(base, vibration)
            nm.createNotificationChannel(channel)
        }
        return id
    }

    private fun vibrationPattern(base: String, vibration: VibrationPattern?): LongArray =
        if (vibration != null) vibrationMillis(vibration)
        else if (base == CHANNEL_PAGES) longArrayOf(0, 500, 200, 500)
        else longArrayOf(0, 200)

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

    fun text(page: Page): String {
        if (PhotoLink.parse(page.message) != null) return "Photo link"
        if (FileLink.parse(page.message) != null) return "Attachment"
        return page.message ?: "status ${page.status}"
    }
}
