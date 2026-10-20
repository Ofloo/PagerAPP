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
    val hostname: String = "?",
    val subscriptionsEnabled: Boolean = true,
    val channels: List<String> = listOf("unifiedpush", "http"),
    val leaseSeconds: Long = 30L * 86_400L,
    val warnSeconds: Long = 3L * 86_400L,
    val maxPerMailbox: Int = 16,
    val sseEnabled: Boolean = true,
    val sseKeepaliveSeconds: Int = 15,
    val waitTimeoutSeconds: Int = 90,
    /** The ntfy server the server itself publishes wake-ups to; null when that channel is off. */
    val ntfyUrl: String? = null,
) {
    /** How many days before expiry to renew: the server's warning window, plus a day of slack. */
    val renewLeadDays: Long get() = (warnSeconds / 86_400L) + 1L

    /** True when the server offers its own ntfy channel, i.e. it knows where to publish. */
    val supportsNtfy: Boolean get() = ntfyUrl != null && "ntfy" in channels

    /** The best push channel both sides support, or null when push is off or there is none. */
    fun preferredChannel(): String? {
        if (!subscriptionsEnabled) return null
        return listOf("unifiedpush", "http", "ntfy").firstOrNull { it in channels }
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
                hostname = json.optString("hostname", "?"),
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
                ntfyUrl = subs?.optString("ntfy_url")?.takeIf { it.isNotBlank() && it != "null" },
            ).also {
                // Touch the two we do not use yet, so the parse stays obviously complete.
                limits?.optInt("max_message_bytes")
                retention?.optLong("message_ttl_seconds")
            }
        }
    }
}

/**
 * A mailbox's compact state (`GET /mailboxes/{uuid}/status`, PagerMCP 0.7.0+): what is waiting, and
 * whether — and until when — the server has a way to wake this device. Carries no secrets, so it is
 * safe to show in the UI.
 */
data class MailboxStatus(
    val messages: Int = 0,
    val pushEnabled: Boolean = false,
    val subscriptions: Int = 0,
    val channels: List<String> = emptyList(),
    val nextExpiry: Long? = null,
    val messageTtlSeconds: Long = 0L,
    val mailboxTtlSeconds: Long = 0L,
) {
    /** Whole days until the nearest subscription lapses; null when nothing is registered. */
    fun daysUntilExpiry(): Long? {
        val expiry = nextExpiry ?: return null
        val left = expiry - System.currentTimeMillis() / 1000L
        return if (left <= 0L) 0L else (left + 86_399L) / 86_400L
    }
}
