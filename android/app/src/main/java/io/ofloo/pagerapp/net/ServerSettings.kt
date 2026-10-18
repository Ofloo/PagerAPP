package io.ofloo.pagerapp.net

import org.json.JSONObject

/**
 * The server's effective, non-secret settings (`GET /settings`, PagerMCP 0.6.0+), so a client can
 * tune itself instead of hardcoding: which push channels exist, how long a subscription lease lasts
 * and when the warning arrives, whether SSE is available and how it behaves.
 *
 * Anything the server does not report falls back to the protocol defaults, so an older server keeps
 * working unchanged.
 */
data class ServerSettings(
    val version: String = "?",
    val build: String = "?",
    val subscriptionsEnabled: Boolean = true,
    val channels: List<String> = listOf("unifiedpush", "http"),
    val leaseSeconds: Long = 30L * 86_400L,
    val warnSeconds: Long = 3L * 86_400L,
    val maxPerMailbox: Int = 16,
    val sseEnabled: Boolean = true,
    val sseKeepaliveSeconds: Int = 15,
    val waitTimeoutSeconds: Int = 90,
) {
    /** How many days before expiry to renew: the server's warning window, plus a day of slack. */
    val renewLeadDays: Long get() = (warnSeconds / 86_400L) + 1L

    /** The best push channel both sides support, or null when push is off or there is none. */
    fun preferredChannel(): String? {
        if (!subscriptionsEnabled) return null
        return listOf("unifiedpush", "http").firstOrNull { it in channels }
    }

    companion object {
        fun parse(json: JSONObject): ServerSettings {
            val limits = json.optJSONObject("limits")
            val retention = json.optJSONObject("retention")
            val wait = json.optJSONObject("wait")
            val sse = json.optJSONObject("sse")
            val subs = json.optJSONObject("subscriptions")
            return ServerSettings(
                version = json.optString("version", "?"),
                build = json.optString("build", "?"),
                subscriptionsEnabled = subs?.optBoolean("enabled", true) ?: true,
                channels = subs?.optJSONArray("channels")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                }?.takeIf { it.isNotEmpty() } ?: listOf("unifiedpush", "http"),
                leaseSeconds = (subs?.opt("lease_seconds") as? Number)?.toLong() ?: 30L * 86_400L,
                warnSeconds = (subs?.opt("warn_seconds") as? Number)?.toLong() ?: 3L * 86_400L,
                maxPerMailbox = (subs?.opt("max_per_mailbox") as? Number)?.toInt() ?: 16,
                sseEnabled = sse?.optBoolean("enabled", true) ?: true,
                sseKeepaliveSeconds = (sse?.opt("keepalive_seconds") as? Number)?.toInt() ?: 15,
                waitTimeoutSeconds = (wait?.opt("timeout_seconds") as? Number)?.toInt() ?: 90,
            ).also {
                // Touch the two we do not use yet, so the parse stays obviously complete.
                limits?.optInt("max_message_bytes")
                retention?.optLong("message_ttl_seconds")
            }
        }
    }
}
