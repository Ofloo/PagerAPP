package io.ofloo.pagerapp.shared

/**
 * A page payload is opaque JSON; this parser reads the fields PagerAPP cares about and
 * tolerates missing optionals. Takes an already-decoded map so it stays dependency-free and
 * testable on the plain JVM; the Android/iOS layer supplies the platform JSON decoder.
 *
 * Status is normally numeric (see AGENTS.md), but PagerMCP is a dumb relay and real senders —
 * including the bundled `pager.sh` wrapper and other projects — often send the textual form
 * ("success", "failed", ...). Accept both rather than dropping the page.
 */
object PageParser {
    private val LEVEL_PREFIX =
        Regex("""^\[(DEBUG|INFO|WARNING|ALARM|CRITICAL)\]\s*""", RegexOption.IGNORE_CASE)
    private val UUID_PATTERN =
        Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    private val ACK_TOKEN = Regex("""[A-Za-z0-9]{4,64}""")

    fun parse(json: Map<String, Any?>): Page {
        val id = json["id"] as? String ?: error("page missing id")
        val project = json["PROJECT"] as? String
        val message = json["message"] as? String
        val logs = json["logs"] as? String
        val rawPrio = (json["prio"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        // A recognised top-level prio wins; otherwise fall back to ProLOG's legacy [LEVEL] title.
        val prio = when {
            rawPrio != null && statusForUrgency(rawPrio) != null -> rawPrio
            else -> prologLevel(message, project) ?: rawPrio
        }
        val prologAck = parsePrologAck(logs)
        val visibleLogs = stripPrologAckFooter(logs)
        return Page(
            id = id,
            // ProLOG always sends status "success"; its real level is prio. Fold it onto status so
            // the existing per-status rules and UI labels apply to the actual urgency.
            status = statusForUrgency(prio) ?: parseStatus(json["status"]),
            project = project,
            jobId = json["JOB_ID"] as? String,
            message = message,
            logs = visibleLogs,
            prio = prio,
            ackUuid = json["ack_uuid"] as? String,
            replyUuid = (json["reply_uuid"] as? String)?.takeIf { it.isNotBlank() }
                ?: prologAck?.first,
            ackToken = (json["ack_token"] as? String)?.takeIf { it.isNotBlank() }
                ?: prologAck?.second,
            imageId = json["image_id"] as? String,
            imagePart = intOrNull(json["image_part"]),
            imageTotal = intOrNull(json["image_total"]),
            imageChunk = json["image_chunk"] as? String,
            imageSha256 = json["image_sha256"] as? String,
            from = json["from"] as? String,
            fromName = json["from_name"] as? String,
            pub = json["pub"] as? String,
            enc = json["enc"] as? String,
            mid = json["mid"] as? String,
            read = json["read"] as? String,
            createdAt = (json["created_at"] as? Number)?.toDouble() ?: 0.0,
        )
    }

    /**
     * The urgency level in a ProLOG page title (`[WARNING] ACTIEF · ...`). ProLOG does not send a
     * structured level, so this is the compatibility path; it applies only to `PROJECT=ProLog`.
     * A top-level `prio` (when present) takes precedence in [parse].
     */
    fun prologLevel(message: String?, project: String?): String? {
        if (project?.equals("prolog", ignoreCase = true) != true) return null
        val text = message?.trimStart() ?: return null
        return LEVEL_PREFIX.find(text)?.groupValues?.get(1)?.lowercase()
    }

    /**
     * ProLOG's sender-managed ACK footer, as appended to `logs`:
     *
     * ```
     * ------------------------
     * ACK verplicht. Antwoord met een bericht naar mailbox:
     * <reply-uuid>
     * met in de tekst: ack <token>
     * ```
     *
     * Returns `(replyUuid, token)` or null when the page does not ask for an acknowledgement.
     */
    fun parsePrologAck(logs: String?): Pair<String, String>? {
        val lines = logs?.lines()?.map { it.trim() } ?: return null
        val marker = lines.indexOfFirst { it.startsWith("ACK verplicht", ignoreCase = true) }
        if (marker < 0) return null
        val tail = lines.drop(marker + 1)
        val uuid = tail.firstOrNull { UUID_PATTERN.matches(it) } ?: return null
        val tokenLine = tail.firstOrNull { it.contains("ack ", ignoreCase = true) } ?: return null
        val at = tokenLine.indexOf("ack ", ignoreCase = true)
        if (at < 0) return null
        val token = tokenLine.substring(at + 4).trim()
            .split(Regex("\\s+")).firstOrNull()
            ?.trimEnd('.', ',', ';', ':')
            ?.takeIf { ACK_TOKEN.matches(it) }
            ?: return null
        return uuid to token
    }

    private fun intOrNull(raw: Any?): Int? = when (raw) {
        is Number -> raw.toInt()
        is String -> raw.trim().toIntOrNull()
        else -> null
    }

    /** The human-readable part of `logs`, without ProLOG's ACK instructions (which the app acts on). */
    fun stripPrologAckFooter(logs: String?): String? {
        val lines = logs?.lines() ?: return logs
        val marker = lines.indexOfFirst { it.trim().startsWith("ACK verplicht", ignoreCase = true) }
        if (marker < 0) return logs
        val kept = lines.take(marker)
            .dropLastWhile { line -> line.isBlank() || line.trim().all { it == '-' } }
        return kept.joinToString("\n").trimEnd().ifEmpty { null }
    }

    /**
     * Accepts a number, a numeric string, or a known textual status. Unknown text is mapped to
     * [STATUS_UNKNOWN_TEXT] (1, Warning) so the page still surfaces instead of being discarded.
     */
    fun parseStatus(raw: Any?): Int = when (raw) {
        is Number -> raw.toInt()
        is String -> raw.trim().toIntOrNull() ?: textStatus(raw.trim().lowercase())
        else -> STATUS_UNKNOWN_TEXT
    }

    /** Textual status words used by pager.sh and similar senders, mapped onto the numeric table. */
    private fun textStatus(s: String): Int = when (s) {
        "success", "ok", "succeeded", "done", "completed", "complete" -> 0
        "warning", "warn" -> 1
        "failed", "failure", "fail", "error" -> 2
        "critical", "fatal", "urgent" -> 3
        "in progress", "in_progress", "running", "started" -> 4
        "queued", "pending" -> 5
        "acknowledged", "ack" -> STATUS_ACKNOWLEDGED
        else -> STATUS_UNKNOWN_TEXT
    }
}

/** Status used when a sender sent something non-numeric that we do not recognise. */
const val STATUS_UNKNOWN_TEXT = 1
