package io.ofloo.pagerapp.shared

/**
 * Default rule for a numeric status when the user has not configured one.
 * Matches the table in AGENTS.md; unconfigured custom statuses (6-99) stay quiet, except the
 * reserved chat status (see [STATUS_MESSAGE]) which behaves like a message.
 */
fun defaultRuleFor(status: Int): StatusRule = when (status) {
    0 -> StatusRule(NotifyAction.SILENT, TimeBound.NEVER)                 // Success
    1 -> StatusRule(NotifyAction.SOUND_VIBRATE, TimeBound.ACTIVE_HOURS, VibrationPattern.LONG) // Warning
    2 -> StatusRule(NotifyAction.SOUND_VIBRATE, TimeBound.ACTIVE_HOURS, VibrationPattern.LONG) // Failed
    3 -> StatusRule(NotifyAction.URGENT, TimeBound.ALWAYS, VibrationPattern.REPEATED)          // Critical
    4 -> StatusRule(NotifyAction.SILENT, TimeBound.NEVER)                 // In Progress
    5 -> StatusRule(NotifyAction.SILENT, TimeBound.NEVER)                 // Queued
    STATUS_MESSAGE -> StatusRule(NotifyAction.SOUND_VIBRATE, TimeBound.ACTIVE_HOURS, VibrationPattern.SHORT) // chat
    else -> StatusRule(NotifyAction.SILENT, TimeBound.NEVER)              // custom, undefined
}

/** Reserved custom status for ProLOG's `debug` level: logger-only, silent by default. */
const val STATUS_DEBUG = 7

/** The five named urgency levels, lowest to highest in ProLOG's sender-side threshold ladder. */
val LEVEL_NAMES = listOf("debug", "info", "warning", "alarm", "critical")

/** Display order used until the user drags the level cards into their own order. */
fun defaultLevelOrder(): List<String> = listOf("info", "warning", "alarm", "critical", "debug")

/** Keeps only known level names, removes duplicates, and appends any missing level. */
fun sanitizeLevelOrder(order: List<String>?): List<String> {
    val valid = order.orEmpty()
        .map { it.trim().lowercase() }
        .filter { it in LEVEL_NAMES }
        .distinct()
    if (valid.isEmpty()) return defaultLevelOrder()
    return valid + defaultLevelOrder().filter { it !in valid }
}

/**
 * Maps the five-level urgency ladder used by ProLOG onto the numeric status rules.
 *
 * The rank is `debug` (lowest) < `info` < `warning` < `alarm` < `critical` (highest). `debug` is
 * kept as its own custom status so a user can configure the logger channel separately; `info`
 * shares Success because both are non-alerting. Unknown values return null: the caller keeps the
 * sender's original `status`.
 */
fun statusForUrgency(prio: String?): Int? = when (prio?.trim()?.lowercase()) {
    "debug" -> STATUS_DEBUG
    "info" -> 0
    "warning" -> 1
    "alarm" -> 2
    "critical" -> 3
    else -> null
}

/** Level name used by a chat whose owner must be able to reach you at any time. */
const val LEVEL_IMPORTANT = "Important"

/** Level name for an ordinary chat: sound + vibrate inside active hours. */
const val LEVEL_NORMAL = "Normal"

/** Level name for a quiet chat: list only, no notification. */
const val LEVEL_SILENT = "Silent"

/**
 * The chat levels every install starts with. They live in the user's configuration, so editing
 * one changes it for every contact that picked it; a contact stores only the name.
 */
fun defaultChatLevels(): Map<String, StatusRule> = linkedMapOf(
    LEVEL_IMPORTANT to StatusRule(NotifyAction.URGENT, TimeBound.ALWAYS, VibrationPattern.REPEATED),
    LEVEL_NORMAL to StatusRule(NotifyAction.SOUND_VIBRATE, TimeBound.ACTIVE_HOURS, VibrationPattern.SHORT),
    LEVEL_SILENT to StatusRule(NotifyAction.SILENT, TimeBound.NEVER, VibrationPattern.SHORT),
)
