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
 * @param vibration vibration pattern (watch; the phone uses the action's default pattern).
 * @param followsGeneralSchedule when [timeBound] is [TimeBound.ACTIVE_HOURS], use the config's
 *   general [PagerConfig.activeWindows] instead of [customWindows].
 * @param customWindows the level's own windows when [followsGeneralSchedule] is false. Empty
 *   means "no custom schedule yet" and falls back to the general windows rather than muting it.
 */
data class StatusRule(
    val action: NotifyAction,
    val timeBound: TimeBound,
    val vibration: VibrationPattern = VibrationPattern.SHORT,
    val followsGeneralSchedule: Boolean = true,
    val customWindows: List<ActiveWindow> = emptyList(),
    /** `null` = system default, `"none"` = no sound, otherwise a ringtone content URI. */
    val soundUri: String? = null,
    /** Repeat the alert (sound/vibration) until the page is acknowledged. */
    val repeat: Boolean = false,
) {
    /** The windows actually used when [timeBound] is [TimeBound.ACTIVE_HOURS]. */
    fun effectiveWindows(config: PagerConfig): List<ActiveWindow> =
        if (followsGeneralSchedule) config.activeWindows
        else customWindows.ifEmpty { config.activeWindows }
}

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
    /**
     * The order of the five level cards in the UI, top first. The user can move a level up or
     * down; it is a ranking/display preference, not a second set of rules. Each level's actual
     * behaviour is in [statusRules] (via its mapped status).
     */
    val levelOrder: List<String> = defaultLevelOrder(),
    /**
     * Named notification levels offered per chat, e.g. "Important" = always reachable,
     * "Normal" = active hours, "Silent" = list only. A [Contact] stores the name it uses, so
     * retuning a level changes it for everyone on that level.
     */
    val chatLevels: Map<String, StatusRule> = defaultChatLevels(),
)

/**
 * A PagerMCP page. The payload is opaque JSON; these are the fields PagerAPP reads.
 * [prio] is the urgency level (`debug` < `info` < `warning` < `alarm` < `critical`); the parser
 * folds it onto [status] so the existing per-status rules apply. It is kept for display too.
 * [ackUuid] is the native ACK mailbox; [replyUuid]/[ackToken] are ProLOG's sender-managed ACK.
 * [from]/[fromName] identify the sender when the page came from a known [Contact].
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
    /** ProLOG ACK: the reply mailbox the `ack <token>` message must be sent to. */
    val replyUuid: String? = null,
    /** ProLOG ACK: the token that identifies the pending page. */
    val ackToken: String? = null,
    /** Chunked chat photo: shared id, 1-based part and total number of parts. */
    val imageId: String? = null,
    val imagePart: Int? = null,
    val imageTotal: Int? = null,
    /** One slice of the armored PGP image; the slices are concatenated in part order. */
    val imageChunk: String? = null,
    /** SHA-256 of the complete armored image, so a reassembly is verified before decryption. */
    val imageSha256: String? = null,
    val from: String? = null,
    val fromName: String? = null,
    /** The sender's armored OpenPGP public key, attached to every outgoing message. */
    val pub: String? = null,
    /** The message body, encrypted to the recipient's public key (see [Pgp]). */
    val enc: String? = null,
    /** Client-side id of the message, so the peer can send a read receipt for it. */
    val mid: String? = null,
    /** Set on a receipt-only page: the [mid] the sender has just read. */
    val read: String? = null,
    /** Set on a receipt-only page: the [mid] that reached the recipient's phone. */
    val deliveredMid: String? = null,
    /** Server `created_at` in Unix seconds; 0 when absent. Used only for ordering. */
    val createdAt: Double = 0.0,
    /**
     * The sender's client-side send time in Unix seconds, when the payload carried it. Unlike
     * [createdAt] this survives the order in which the gateway or BLE happened to deliver a copy.
     */
    val sentAt: Double = 0.0,
    /** PagerMCP read-copy state from a webclient-mode mailbox. */
    val acked: Boolean? = null,
    /** A copy of a message this user sent from another client; store it as own history, no alert. */
    val selfCopy: Boolean = false,
    /** Detached OpenPGP signature over the readable plaintext, when the sender provided one. */
    val sig: String? = null,
    /** Quote/reply metadata: target message id, excerpt and author. */
    val replyTo: String? = null,
    val replyPreview: String? = null,
    val replyFrom: String? = null,
    val replyFromName: String? = null,
    /** True when the payload reached this mailbox through a P2P relay (gateway fallback). */
    val p2p: Boolean = false,
    /** The relayed envelope id, when [p2p] is true. */
    val p2pId: String? = null,
) {
    /** Native ACK: available only when the page carries an [ackUuid]. */
    val ackAvailable: Boolean get() = !ackUuid.isNullOrBlank()

    /** ProLOG ACK: available when the page named a reply mailbox and a token. */
    val prologAckAvailable: Boolean get() = !replyUuid.isNullOrBlank() && !ackToken.isNullOrBlank()
}

/** The outcome of applying [decide]. */
data class Decision(
    val notify: Boolean,
    val action: NotifyAction,
    val channels: Set<Channel>,
    val vibration: VibrationPattern,
    val reason: String,
    /** Chosen notification sound: null = default, "none" = silent, else a URI. */
    val sound: String? = null,
    /** Repeat the alert until the page is acknowledged. */
    val repeat: Boolean = false,
)
