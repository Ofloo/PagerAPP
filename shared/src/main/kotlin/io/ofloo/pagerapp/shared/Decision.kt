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
fun decide(page: Page, config: PagerConfig, now: LocalTime, rule: StatusRule? = null): Decision {
    // A recognised urgency level overrides the outcome status for notification purposes.
    val status = statusForUrgency(page.prio) ?: page.status
    val effective = rule ?: config.statusRules[status] ?: defaultRuleFor(status)

    if (config.silenceMode) {
        return Decision(
            notify = false,
            action = NotifyAction.SILENT,
            channels = emptySet(),
            vibration = effective.vibration,
            reason = "silence mode",
            sound = effective.soundUri,
            repeat = effective.repeat,
        )
    }

    val withinTimeBound = when (effective.timeBound) {
        TimeBound.NEVER -> false
        TimeBound.ALWAYS -> true
        TimeBound.ACTIVE_HOURS -> effective.effectiveWindows(config).any { it.contains(now) }
    }
    val show = config.bypassMode || withinTimeBound

    val reason = when {
        config.bypassMode -> "bypass mode"
        withinTimeBound -> "within time bound"
        else -> "outside time bound (${effective.timeBound})"
    }

    if (!show) {
        return Decision(
            notify = false,
            action = NotifyAction.SILENT,
            channels = emptySet(),
            vibration = effective.vibration,
            reason = reason,
            sound = effective.soundUri,
            repeat = effective.repeat,
        )
    }

    val channels = buildSet {
        if (config.watchMode != WatchMode.WATCH_ONLY) add(Channel.PHONE)
        if (config.watchMode != WatchMode.OFF) add(Channel.WATCH)
    }

    return Decision(
        notify = true,
        action = effective.action,
        channels = channels,
        vibration = effective.vibration,
        reason = reason,
        sound = effective.soundUri,
        repeat = effective.repeat,
    )
}

/**
 * The decision for a message arriving on a chat channel, using the contact's chosen level from
 * [PagerConfig.chatLevels] instead of a status rule. Silence and bypass still apply, and "always"
 * means "ignores this app's active hours" — the phone's own silent/Do-Not-Disturb still rules.
 */
fun decideForContact(contact: Contact, config: PagerConfig, now: LocalTime): Decision =
    decide(
        page = Page(id = "", status = STATUS_MESSAGE),
        config = config,
        now = now,
        rule = config.chatLevels[contact.level] ?: defaultChatLevels()[LEVEL_NORMAL],
    )
