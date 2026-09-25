package io.ofloo.pagerapp.shared

/**
 * Default rule for a numeric status when the user has not configured one.
 * Matches the table in AGENTS.md; unconfigured custom statuses (6-99) stay quiet.
 */
fun defaultRuleFor(status: Int): StatusRule = when (status) {
    0 -> StatusRule(NotifyAction.SILENT, TimeBound.NEVER)                 // Success
    1 -> StatusRule(NotifyAction.SOUND_VIBRATE, TimeBound.ACTIVE_HOURS, VibrationPattern.LONG) // Warning
    2 -> StatusRule(NotifyAction.SOUND_VIBRATE, TimeBound.ACTIVE_HOURS, VibrationPattern.LONG) // Failed
    3 -> StatusRule(NotifyAction.URGENT, TimeBound.ALWAYS, VibrationPattern.REPEATED)          // Critical
    4 -> StatusRule(NotifyAction.SILENT, TimeBound.NEVER)                 // In Progress
    5 -> StatusRule(NotifyAction.SILENT, TimeBound.NEVER)                 // Queued
    else -> StatusRule(NotifyAction.SILENT, TimeBound.NEVER)              // custom, undefined
}
