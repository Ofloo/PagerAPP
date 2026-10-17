package io.ofloo.pagerapp.shared

/** The numeric status sent in an ACK page. 6 = acknowledged (see AGENTS.md). */
const val STATUS_ACKNOWLEDGED = 6

/**
 * An acknowledgement ready to send: [targetUuid] is the mailbox to `POST /notify` to (it is
 * also the Bearer token), [payload] is the body.
 */
data class AckRequest(val targetUuid: String, val payload: Map<String, Any?>)

/**
 * Builds the ACK for [page], sent by the device whose own mailbox is [ownUuid]. Returns null
 * when the page carries no `ack_uuid`, so callers cannot ACK a page that did not opt in.
 *
 * [note] is an optional free-text message from the acknowledger; when blank the message is the
 * plain "Acknowledged".
 *
 * This is pure construction; the actual `POST /notify` is the platform layer's job.
 */
fun buildAck(page: Page, ownUuid: String, note: String? = null): AckRequest? {
    val target = page.ackUuid?.takeIf { it.isNotBlank() } ?: return null
    val payload = linkedMapOf<String, Any?>(
        "PROJECT" to "PagerAPP",
        "status" to STATUS_ACKNOWLEDGED,
        "message" to (note?.trim()?.takeIf { it.isNotEmpty() } ?: "Acknowledged"),
        "acknowledged_by" to ownUuid,
    )
    if (page.jobId != null) payload["JOB_ID"] = page.jobId
    if (page.id.isNotBlank()) payload["in_reply_to"] = page.id
    return AckRequest(targetUuid = target, payload = payload)
}
