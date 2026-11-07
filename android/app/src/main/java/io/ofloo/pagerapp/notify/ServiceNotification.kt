package io.ofloo.pagerapp.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.ofloo.pagerapp.R
import io.ofloo.pagerapp.p2p.BleP2P
import io.ofloo.pagerapp.p2p.P2PDirection
import io.ofloo.pagerapp.ui.MainActivity

/**
 * The single ongoing notification that keeps polling and nearby sync alive. Instead of the
 * generic "PagerAPP service" text it shows what the BLE scan actually sees: how many PagerAPP
 * peers are nearby and how strong the best advertisement is, so the notification is useful while
 * it is unavoidable.
 */
object ServiceNotification {

    fun text(context: Context, p2pEnabled: Boolean): String {
        if (!p2pEnabled) return context.getString(R.string.poll_no_p2p)
        val activity = BleP2P.activitySnapshot()
        val inbound = activity.inbound
        val outbound = activity.outbound
        val last = activity.lastCompleted
        val state = when {
            inbound != null -> context.getString(R.string.p2p_receiving, inbound.done, inbound.total)
            outbound != null -> context.getString(R.string.p2p_sending, outbound.done, outbound.total)
            last != null -> if (activity.lastDirection == P2PDirection.RECEIVING) {
                context.getString(R.string.p2p_received, last.done, last.total)
            } else {
                context.getString(R.string.p2p_sent, last.done, last.total)
            }
            activity.syncing -> context.getString(R.string.p2p_syncing)
            activity.connecting -> context.getString(R.string.p2p_connecting)
            activity.connected -> context.getString(R.string.p2p_idle)
            else -> context.getString(R.string.p2p_scanning)
        }
        val parts = mutableListOf(state)
        if (activity.queued > 0) {
            parts += context.resources.getQuantityString(
                R.plurals.p2p_queued,
                activity.queued,
                activity.queued,
            )
        }
        if (activity.peers > 0) {
            parts += context.resources.getQuantityString(
                R.plurals.p2p_peers_nearby,
                activity.peers,
                activity.peers,
            )
            activity.bestRssi?.let { best ->
                parts += if (activity.peers == 1) {
                    context.getString(R.string.p2p_signal_one, best)
                } else {
                    context.getString(R.string.p2p_signal_best, best)
                }
            }
        } else {
            parts += context.getString(R.string.p2p_no_peers_short)
        }
        if (activity.connected && activity.inbound == null && activity.outbound == null) {
            parts += context.getString(R.string.p2p_connected)
        }
        return parts.joinToString(" · ")
    }

    fun build(context: Context, text: String, p2pEnabled: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val channel = if (p2pEnabled) Notifications.CHANNEL_P2P else Notifications.CHANNEL_SERVICE
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
