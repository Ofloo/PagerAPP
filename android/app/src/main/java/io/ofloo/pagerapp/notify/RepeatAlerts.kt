package io.ofloo.pagerapp.notify

import io.ofloo.pagerapp.shared.Page
import io.ofloo.pagerapp.shared.VibrationPattern
import java.util.concurrent.ConcurrentHashMap

/** One page whose level asked for an alert to continue until the page is acknowledged. */
data class RepeatAlert(
    val page: Page,
    val audible: Boolean,
    val channel: String,
    val mailbox: String?,
    val serverUrl: String?,
    val imagePath: String?,
    val sound: String?,
    val vibration: VibrationPattern?,
    @Volatile var notificationId: Int = page.id.hashCode(),
)

/**
 * In-memory set of alerts that must repeat. The foreground poll service owns the repeat timer and
 * re-posts these notifications with a fresh id (Android does not replay a channel sound for an
 * update of the same notification). An ACK removes the page here, which stops the repetition.
 */
object RepeatAlerts {
    private val alerts = ConcurrentHashMap<String, RepeatAlert>()

    fun add(alert: RepeatAlert) {
        alerts[alert.page.id] = alert
    }

    fun remove(pageId: String): Int? = alerts.remove(pageId)?.notificationId

    fun contains(pageId: String): Boolean = alerts.containsKey(pageId)

    fun snapshot(): List<RepeatAlert> = alerts.values.toList()

    fun updateId(pageId: String, notificationId: Int) {
        alerts[pageId]?.notificationId = notificationId
    }
}
