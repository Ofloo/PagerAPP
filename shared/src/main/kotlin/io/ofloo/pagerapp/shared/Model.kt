package io.ofloo.pagerapp.shared

import java.time.LocalTime

/** What a page should do once it is delivered. */
enum class NotifyAction { SILENT, SOUND_VIBRATE, URGENT }

/** When a page is allowed to be delivered. */
enum class TimeBound { ALWAYS, ACTIVE_HOURS, NEVER }

/** Watch behaviour for delivered pages. */
enum class WatchMode { OFF, WATCH_ONLY, PHONE_AND_WATCH }

/** Where a delivered notification is shown. */
enum class Channel { PHONE, WATCH }

/** Smartwatch vibration pattern. */
enum class VibrationPattern { SHORT, LONG, REPEATED }

/**
 * Configured behaviour for one numeric [Page.status].
 *
 * @param action sound/vibration behaviour when the page is delivered.
 * @param timeBound time window in which the page may be delivered.
 * @param vibration watch vibration pattern (ignored when [WatchMode.OFF]).
 */
data class StatusRule(
    val action: NotifyAction,
    val timeBound: TimeBound,
    val vibration: VibrationPattern = VibrationPattern.SHORT,
)

/**
 * An active-hours window in device local time. A window whose [end] is not after
 * [start] wraps midnight (e.g. 22:00-06:00).
 */
data class ActiveWindow(val start: LocalTime, val end: LocalTime) {
    fun contains(time: LocalTime): Boolean =
        if (start <= end) time >= start && time < end
        else time >= start || time < end
}

/** User configuration. Defaults match AGENTS.md. */
data class PagerConfig(
    val statusRules: Map<Int, StatusRule> = emptyMap(),
    val activeWindows: List<ActiveWindow> = listOf(
        ActiveWindow(LocalTime.of(7, 0), LocalTime.of(23, 0)),
    ),
    val bypassMode: Boolean = false,
    val silenceMode: Boolean = false,
    val watchMode: WatchMode = WatchMode.OFF,
)

/**
 * A PagerMCP page. The payload is opaque JSON; these are the fields PagerAPP reads.
 * [logs] and [prio] are not used by the decision logic but are kept for the UI.
 * [ackUuid], when present, is the mailbox an ACK may be sent back to.
 */
data class Page(
    val id: String,
    val status: Int,
    val project: String? = null,
    val jobId: String? = null,
    val message: String? = null,
    val logs: String? = null,
    val prio: String? = null,
    val ackUuid: String? = null,
) {
    /** ACK is opt-in per page: available only when the page carries an [ackUuid]. */
    val ackAvailable: Boolean get() = !ackUuid.isNullOrBlank()
}

/** The outcome of applying [decide]. */
data class Decision(
    val notify: Boolean,
    val action: NotifyAction,
    val channels: Set<Channel>,
    val vibration: VibrationPattern,
    val reason: String,
)
