package io.ofloo.pagerapp.shared

/**
 * A page payload is opaque JSON; this parser reads the fields PagerAPP cares about and
 * tolerates missing optionals. Takes an already-decoded map so it stays dependency-free and
 * testable on the plain JVM; the Android/iOS layer supplies the platform JSON decoder.
 */
object PageParser {
    fun parse(json: Map<String, Any?>): Page {
        val id = json["id"] as? String ?: error("page missing id")
        val status = when (val raw = json["status"]) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: error("status not numeric: $raw")
            else -> error("page missing numeric status")
        }
        return Page(
            id = id,
            status = status,
            project = json["PROJECT"] as? String,
            jobId = json["JOB_ID"] as? String,
            message = json["message"] as? String,
            logs = json["logs"] as? String,
            prio = json["prio"] as? String,
            ackUuid = json["ack_uuid"] as? String,
        )
    }
}
