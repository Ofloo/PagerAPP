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
    fun parse(json: Map<String, Any?>): Page {
        val id = json["id"] as? String ?: error("page missing id")
        return Page(
            id = id,
            status = parseStatus(json["status"]),
            project = json["PROJECT"] as? String,
            jobId = json["JOB_ID"] as? String,
            message = json["message"] as? String,
            logs = json["logs"] as? String,
            prio = json["prio"] as? String,
            ackUuid = json["ack_uuid"] as? String,
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
