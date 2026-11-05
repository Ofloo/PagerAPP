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
    /** PagerMCP 0.9.0 webclient read-copy lease; 0 means the server has no webclient mode. */
    val webclientTtlSeconds: Long = 0L,
    /** Message retention and queue cap, used for the webclient read-copy view. */
    val messageTtlSeconds: Long = 7L * 86_400L,
    val maxMessagesPerMailbox: Int = 128,
    /** The ntfy server the server itself publishes wake-ups to; null when that channel is off. */
    val ntfyUrl: String? = null,
    /** PagerMCP 0.11.0 opt-in P2P idempotency policy; all zero/false on older servers. */
    val p2pEnabled: Boolean = false,
    val p2pDedupeTtlSeconds: Long = 0L,
    val p2pMaxMessageAgeSeconds: Long = 0L,
    val p2pMaxEntries: Int = 0,
    val p2pMaxPerMailbox: Int = 0,
    /**
     * Which identity a push subscription uses. PagerMCP may switch from the mailbox UUID to an
     * anonymised route hash (optionally rotating). Reported as `subscriptions.identity`.
     */
    val pushIdentityMode: String = "uuid",
    val pushIdentityPeriodSeconds: Long = 0L,
    /**
     * The server's clock at the moment it answered (`server_time`, Unix seconds). The client uses
     * it to align a rotating push identity with the server's period boundaries instead of the
     * phone clock, which may be minutes off.
     */
    val serverTimeSeconds: Long? = null,
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
        /** Accepts Unix seconds/milliseconds or an ISO-8601 instant. */
        private fun parseEpochSeconds(value: Any?): Long? = when (value) {
            is Number -> value.toLong().let { if (it > 100_000_000_000L) it / 1000L else it }
            is String -> value.takeIf { it.isNotBlank() }?.let { text ->
                text.toLongOrNull()?.let { if (it > 100_000_000_000L) it / 1000L else it }
                    ?: runCatching { java.time.Instant.parse(text).epochSecond }.getOrNull()
            }
            else -> null
        }

        fun parse(json: JSONObject): ServerSettings {
            val limits = json.optJSONObject("limits")
            val retention = json.optJSONObject("retention")
            val wait = json.optJSONObject("wait")
            val sse = json.optJSONObject("sse")
            val subs = json.optJSONObject("subscriptions")
            val webclient = json.optJSONObject("webclient")
            val p2p = json.optJSONObject("p2p")
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
                webclientTtlSeconds = (webclient?.opt("ttl_seconds") as? Number)?.toLong() ?: 0L,
                messageTtlSeconds = (retention?.opt("message_ttl_seconds") as? Number)?.toLong()
                    ?: 7L * 86_400L,
                maxMessagesPerMailbox = (limits?.opt("max_messages_per_mailbox") as? Number)?.toInt()
                    ?: 128,
                ntfyUrl = subs?.optString("ntfy_url")?.takeIf { it.isNotBlank() && it != "null" },
                p2pEnabled = p2p?.optBoolean("enabled", false) ?: false,
                p2pDedupeTtlSeconds = (p2p?.opt("dedupe_ttl_seconds") as? Number)?.toLong() ?: 0L,
                p2pMaxMessageAgeSeconds = (p2p?.opt("max_message_age_seconds") as? Number)?.toLong() ?: 0L,
                p2pMaxEntries = (p2p?.opt("max_entries") as? Number)?.toInt() ?: 0,
                p2pMaxPerMailbox = (p2p?.opt("max_per_mailbox") as? Number)?.toInt() ?: 0,
                pushIdentityMode = listOf("identity", "route_identity", "fcm_identity")
                    .firstNotNullOfOrNull { key ->
                        subs?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }
                    } ?: "uuid",
                pushIdentityPeriodSeconds = listOf(
                    "identity_period_seconds",
                    "route_period_seconds",
                    "fcm_identity_period_seconds",
                ).firstNotNullOfOrNull { key -> (subs?.opt(key) as? Number)?.toLong() } ?: 0L,
                serverTimeSeconds = listOf("server_time", "time", "now", "server_now")
                    .firstNotNullOfOrNull { key -> parseEpochSeconds(json.opt(key)) }
                    ?: listOf("server_time", "time", "now", "server_now")
                        .firstNotNullOfOrNull { key -> parseEpochSeconds(subs?.opt(key)) },
            ).also {
                // Touch the two we do not use yet, so the parse stays obviously complete.
                limits?.optInt("max_message_bytes")
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
    val unacked: Int = 0,
    val pushEnabled: Boolean = false,
    val subscriptions: Int = 0,
    val channels: List<String> = emptyList(),
    val nextExpiry: Long? = null,
    val messageTtlSeconds: Long = 0L,
    val mailboxTtlSeconds: Long = 0L,
    val webclientEnabled: Boolean = false,
    val webclientExpires: Long? = null,
) {
    /** Whole days until the nearest subscription lapses; null when nothing is registered. */
    fun daysUntilExpiry(): Long? {
        val expiry = nextExpiry ?: return null
        val left = expiry - System.currentTimeMillis() / 1000L
        return if (left <= 0L) 0L else (left + 86_399L) / 86_400L
    }
}

/** `GET/POST /mailboxes/{uuid}/mode` for PagerMCP 0.9.0 webclient read-copy retention. */
data class MailboxMode(
    val webclient: Boolean = false,
    val expires: Long? = null,
) {
    companion object {
        fun parse(json: JSONObject) = MailboxMode(
            webclient = json.optBoolean("webclient", false),
            expires = (json.opt("expires") as? Number)?.toLong(),
        )
    }
}
