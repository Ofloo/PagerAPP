package io.ofloo.pagerapp.shared

import java.time.LocalTime

/**
 * Pure decision logic: given a page, the user config, the current device local time,
 * return what should happen. No Android/iOS imports, so it is fully unit-testable on the JVM.
 *
 * Precedence (per AGENTS.md):
 * 1. Silence mode wins over everything: nothing is shown.
 * 2. Bypass mode overrides the per-status time bound: every page is shown.
 * 3. Otherwise the status rule's time bound decides (NEVER / ALWAYS / ACTIVE_HOURS).
 */
fun decide(page: Page, config: PagerConfig, now: LocalTime): Decision {
    val rule = config.statusRules[page.status] ?: defaultRuleFor(page.status)

    if (config.silenceMode) {
        return Decision(
            notify = false,
            action = NotifyAction.SILENT,
            channels = emptySet(),
            vibration = rule.vibration,
            reason = "silence mode",
        )
    }

    val withinTimeBound = when (rule.timeBound) {
        TimeBound.NEVER -> false
        TimeBound.ALWAYS -> true
        TimeBound.ACTIVE_HOURS -> config.activeWindows.any { it.contains(now) }
    }
    val show = config.bypassMode || withinTimeBound

    val reason = when {
        config.bypassMode -> "bypass mode"
        withinTimeBound -> "within time bound"
        else -> "outside time bound (${rule.timeBound})"
    }

    if (!show) {
        return Decision(
            notify = false,
            action = NotifyAction.SILENT,
            channels = emptySet(),
            vibration = rule.vibration,
            reason = reason,
        )
    }

    val channels = buildSet {
        if (config.watchMode != WatchMode.WATCH_ONLY) add(Channel.PHONE)
        if (config.watchMode != WatchMode.OFF) add(Channel.WATCH)
    }

    return Decision(
        notify = true,
        action = rule.action,
        channels = channels,
        vibration = rule.vibration,
        reason = reason,
    )
}
