package io.ofloo.pagerapp.net

import android.util.Log
import io.ofloo.pagerapp.shared.Page
import io.ofloo.pagerapp.shared.PageParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** What `GET /version` reports: the running server's version, build and hostname. */
data class ServerInfo(val version: String, val build: String, val hostname: String)

class SubscriptionNotFoundException : IllegalStateException("subscription not found")

/** Thin PagerMCP HTTP client. The server is a dumb relay; this only speaks the RFC endpoints. */
/** Result of a notify call; the outbox needs to distinguish retryable from permanent errors. */
enum class NotifyKind { OK, DUPLICATE, EXPIRED, CONFLICT, DISABLED, FULL, RETRY }

data class NotifyOutcome(
    val kind: NotifyKind,
    val messageId: String? = null,
    val error: String? = null,
) {
    val accepted: Boolean get() = kind == NotifyKind.OK || kind == NotifyKind.DUPLICATE
}

object PagerClient {

    private const val TAG = "PagerAPP"

    /** `GET /version` — the build number identifies the exact image behind a tag. */
    fun version(serverUrl: String): ServerInfo {
        val conn = open("$serverUrl/version", "GET", null)
        try {
            require(conn.responseCode in 200..299) { "version HTTP ${conn.responseCode}" }
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val o = JSONObject(body)
            return ServerInfo(
                version = o.optString("version", "?"),
                build = o.optString("build", "?"),
                hostname = o.optString("hostname", "?"),
            )
        } finally {
            conn.disconnect()
        }
    }

    fun createMailbox(serverUrl: String): String {
        val conn = open("$serverUrl/mailboxes", "POST", null)
        try {
            require(conn.responseCode in 200..299) { "createMailbox HTTP ${conn.responseCode}" }
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            return JSONObject(body).getString("uuid")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Lists queued messages for [uuid]. The server returns
     * `{ "messages": [ { id, created_at, age_seconds, <flattened payload> } ] }`.
     * PagerMCP 0.10.0 returns the queue newest-first with `order=newest`, so normal polling can
     * stop as soon as it reaches an already-seen id instead of walking all 128 queued messages.
     */
    fun messages(serverUrl: String, uuid: String): List<Page> {
        val conn = open("$serverUrl/mailboxes/$uuid/messages?order=newest", "GET", uuid)
        try {
            require(conn.responseCode in 200..299) { "messages HTTP ${conn.responseCode}" }
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val arr = JSONObject(body).optJSONArray("messages") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                runCatching { PageParser.parse(arr.getJSONObject(i).toMap()) }
                    .onFailure { Log.w(TAG, "dropped unparseable page: ${it.message}") }
                    .getOrNull()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Sends an ACK page. [bearer] is the target mailbox UUID (the ack_uuid). */
    fun notify(serverUrl: String, bearer: String, payload: Map<String, Any?>) {
        notifyJson(serverUrl, bearer, JSONObject(payload).toString())
    }

    /** Sends a previously serialised payload, used when flushing the offline outbox. */
    fun notifyJson(serverUrl: String, bearer: String, payloadJson: String) {
        val outcome = notifyJsonOutcome(serverUrl, bearer, payloadJson)
        require(outcome.accepted) {
            "notify HTTP ${outcome.kind.name.lowercase()}: ${outcome.error ?: "no response"}"
        }
    }

    /** Notify with an explicit outcome, used by the P2P outbox to drop expired/conflict entries. */
    fun notifyJsonOutcome(serverUrl: String, bearer: String, payloadJson: String): NotifyOutcome {
        val conn = open("$serverUrl/notify", "POST", bearer)
        return try {
            conn.outputStream.use { it.write(payloadJson.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            when (code) {
                200, 201, 202 -> {
                    val duplicate = runCatching { JSONObject(body).optBoolean("duplicate", false) }
                        .getOrDefault(false)
                    val id = runCatching { JSONObject(body).optString("id").takeIf(String::isNotBlank) }
                        .getOrNull()
                    NotifyOutcome(if (duplicate) NotifyKind.DUPLICATE else NotifyKind.OK, id)
                }
                410 -> NotifyOutcome(NotifyKind.EXPIRED, error = body.ifBlank { "p2p message expired" })
                409 -> NotifyOutcome(NotifyKind.CONFLICT, error = body.ifBlank { "p2p_id conflict" })
                403 -> NotifyOutcome(NotifyKind.DISABLED, error = body.ifBlank { "p2p dedupe is not enabled" })
                429 -> NotifyOutcome(NotifyKind.FULL, error = body.ifBlank { "mailbox is full" })
                else -> NotifyOutcome(NotifyKind.RETRY, error = "HTTP $code: $body")
            }
        } catch (error: Throwable) {
            NotifyOutcome(NotifyKind.RETRY, error = error.message ?: error.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Acknowledges a message, removing it from the mailbox.
     *
     * PagerMCP delivery is at-least-once: a message handed to a client stays queued until it is
     * acked, and is returned again on every subsequent fetch otherwise. Call this once a page has
     * been processed; a repeated `id` before the ack lands is a retry, not a new event.
     */
    fun ack(serverUrl: String, uuid: String, messageId: String) {
        val conn = open("$serverUrl/mailboxes/$uuid/ack", "POST", uuid)
        try {
            conn.outputStream.use { it.write(JSONObject().put("id", messageId).toString().toByteArray()) }
            require(conn.responseCode in 200..299) { "ack HTTP ${conn.responseCode}" }
        } finally {
            conn.disconnect()
        }
    }

    /** `GET /mailboxes/{uuid}/mode` — whether read-copy retention is active, and until when. */
    fun webclientMode(serverUrl: String, uuid: String): MailboxMode {
        val conn = open("$serverUrl/mailboxes/$uuid/mode", "GET", uuid)
        try {
            require(conn.responseCode in 200..299) { "mode HTTP ${conn.responseCode}" }
            val body = JSONObject(conn.inputStream.bufferedReader().use(BufferedReader::readText))
            return MailboxMode.parse(body)
        } finally {
            conn.disconnect()
        }
    }

    /** Enables or disables read-copy retention. The server returns the resulting lease. */
    fun setWebclientMode(serverUrl: String, uuid: String, enabled: Boolean): MailboxMode {
        val conn = open("$serverUrl/mailboxes/$uuid/mode", "POST", uuid)
        try {
            conn.outputStream.use {
                it.write(JSONObject().put("webclient", enabled).toString().toByteArray())
            }
            require(conn.responseCode in 200..299) { "mode HTTP ${conn.responseCode}" }
            val body = JSONObject(conn.inputStream.bufferedReader().use(BufferedReader::readText))
            return MailboxMode.parse(body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Streams pages over Server-Sent Events (`GET /mailboxes/{uuid}/events`).
     *
     * Blocks until the stream ends or throws, so callers reconnect. The server replays the
     * unacknowledged backlog on connect, so a dropped connection loses nothing; keepalives every
     * ~15s keep an idle connection alive, and the read timeout is set above that so a dead
     * connection surfaces as an exception instead of hanging forever.
     */
    /**
     * Hands an encrypted backup to a PagerAPP web session. The token is scoped to the one-time
     * transfer session; the Docker server only receives ciphertext.
     */
    /** Fetches the ephemeral transfer public key for a browser session. */
    fun webHandoffPublicKey(baseUrl: String, sessionId: String, token: String): String {
        val url = "${baseUrl.trimEnd('/')}/api/session/$sessionId/public"
        val conn = open(url, "GET", token)
        try {
            require(conn.responseCode in 200..299) { "web public key HTTP ${conn.responseCode}" }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body).getString("publicKey")
        } finally {
            conn.disconnect()
        }
    }

    fun uploadWebHandoff(baseUrl: String, sessionId: String, token: String, armored: ByteArray) {
        val url = "${baseUrl.trimEnd('/')}/api/session/$sessionId/upload"
        val conn = open(url, "POST", token)
        try {
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setFixedLengthStreamingMode(armored.size)
            conn.outputStream.use { it.write(armored) }
            require(conn.responseCode in 200..299) { "web handoff HTTP ${conn.responseCode}" }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun streamEvents(serverUrl: String, uuid: String, onPage: (Page) -> Unit) {
        val conn = URL("$serverUrl/mailboxes/$uuid/events").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Authorization", "Bearer $uuid")
        conn.setRequestProperty("Accept", "text/event-stream")
        try {
            require(conn.responseCode in 200..299) { "events HTTP ${conn.responseCode}" }
            conn.inputStream.bufferedReader().use { reader ->
                var event = ""
                val data = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            if (event == "page" && data.isNotEmpty()) {
                                runCatching { parsePage(data.toString()) }.getOrNull()?.let(onPage)
                            }
                            event = ""
                            data.setLength(0)
                        }
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                        else -> Unit   // id:, retry:, and ": keepalive" comments
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Registers a wake-up route for [uuid] so the server can tell this device a page arrived,
     * without content. Either a generic [endpoint] (a UnifiedPush distributor URL, channel
     * `unifiedpush`/`http`) or — when the server itself is configured with an ntfy server — just a
     * [topic] on that server (`{"channel":"ntfy","topic":"…"}`). The reply carries the subscription
     * `id` and its lease `expires`; the client must renew before that, or push turns itself off.
     */
    fun subscribe(
        serverUrl: String,
        uuid: String,
        channel: String,
        endpoint: String? = null,
        topic: String? = null,
        token: String? = null,
        route: String? = null,
        routes: List<String>? = null,
    ): JSONObject {
        val conn = open("$serverUrl/mailboxes/$uuid/subscriptions", "POST", uuid)
        try {
            val body = JSONObject().put("channel", channel)
            if (endpoint != null) body.put("endpoint", endpoint)
            if (topic != null) body.put("topic", topic)
            if (token != null) body.put("token", token)
            if (!route.isNullOrBlank()) body.put("route", route)
            if (!routes.isNullOrEmpty()) body.put("routes", org.json.JSONArray(routes))
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            require(conn.responseCode in 200..299) { "subscribe HTTP ${conn.responseCode}" }
            return JSONObject(conn.inputStream.bufferedReader().use(BufferedReader::readText))
        } finally {
            conn.disconnect()
        }
    }

    /** Renews a subscription before its lease ends. */
    fun renewSubscription(serverUrl: String, uuid: String, id: String): JSONObject {
        val conn = open("$serverUrl/mailboxes/$uuid/subscriptions", "POST", uuid)
        try {
            conn.outputStream.use { it.write(JSONObject().put("id", id).toString().toByteArray()) }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw SubscriptionNotFoundException()
            require(code in 200..299) { "renew HTTP $code" }
            return JSONObject(conn.inputStream.bufferedReader().use(BufferedReader::readText))
        } finally {
            conn.disconnect()
        }
    }

    fun renewFcmSubscription(
        serverUrl: String,
        uuid: String,
        id: String,
        token: String,
        route: String? = null,
        routes: List<String>? = null,
    ): JSONObject {
        val conn = open("$serverUrl/mailboxes/$uuid/subscriptions", "POST", uuid)
        try {
            conn.outputStream.use {
                val body = JSONObject().put("id", id).put("token", token)
                if (!route.isNullOrBlank()) body.put("route", route)
                if (!routes.isNullOrEmpty()) body.put("routes", org.json.JSONArray(routes))
                it.write(body.toString().toByteArray())
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw SubscriptionNotFoundException()
            require(code in 200..299) { "renew HTTP $code" }
            return JSONObject(conn.inputStream.bufferedReader().use(BufferedReader::readText))
        } finally {
            conn.disconnect()
        }
    }

    /** Lists the mailbox's active subscriptions. */
    fun subscriptions(serverUrl: String, uuid: String): JSONArray {
        val conn = open("$serverUrl/mailboxes/$uuid/subscriptions", "GET", uuid)
        try {
            require(conn.responseCode in 200..299) { "subscriptions HTTP ${conn.responseCode}" }
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            return JSONObject(body).optJSONArray("subscriptions") ?: JSONArray()
        } finally {
            conn.disconnect()
        }
    }

    /** Removes one subscription, or every one of [channel] when [id] is null. */
    fun unsubscribe(serverUrl: String, uuid: String, id: String? = null) {
        val path = if (id == null) "$serverUrl/mailboxes/$uuid/subscriptions"
        else "$serverUrl/mailboxes/$uuid/subscriptions/$id"
        val conn = open(path, "DELETE", uuid)
        try {
            require(conn.responseCode in 200..299) { "unsubscribe HTTP ${conn.responseCode}" }
        } finally {
            conn.disconnect()
        }
    }

    /** `GET /mailboxes/{uuid}/status` — queued count, push state and retention, without secrets. */
    fun mailboxStatus(serverUrl: String, uuid: String): MailboxStatus {
        val conn = open("$serverUrl/mailboxes/$uuid/status", "GET", uuid)
        try {
            require(conn.responseCode in 200..299) { "status HTTP ${conn.responseCode}" }
            val body = JSONObject(conn.inputStream.bufferedReader().use(BufferedReader::readText))
            val push = body.optJSONObject("push")
            val retention = body.optJSONObject("retention")
            val webclient = body.optJSONObject("webclient")
            val channels = push?.optJSONArray("channels")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            } ?: emptyList()
            return MailboxStatus(
                messages = body.optInt("messages"),
                unacked = body.optInt("unacked", body.optInt("messages")),
                pushEnabled = push?.optBoolean("enabled") ?: false,
                subscriptions = push?.optInt("subscriptions") ?: 0,
                channels = channels,
                nextExpiry = (push?.opt("next_expiry") as? Number)?.toLong(),
                messageTtlSeconds = (retention?.opt("message_ttl_seconds") as? Number)?.toLong() ?: 0L,
                mailboxTtlSeconds = (retention?.opt("mailbox_ttl_seconds") as? Number)?.toLong() ?: 0L,
                webclientEnabled = webclient?.optBoolean("enabled") ?: false,
                webclientExpires = (webclient?.opt("expires") as? Number)?.toLong(),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** `GET /settings` — the server's effective, non-secret settings (PagerMCP 0.6.0+). */
    fun settings(serverUrl: String): ServerSettings {
        val conn = open("$serverUrl/settings", "GET", null)
        try {
            require(conn.responseCode in 200..299) { "settings HTTP ${conn.responseCode}" }
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            return ServerSettings.parse(JSONObject(body))
        } finally {
            conn.disconnect()
        }
    }

    private fun parsePage(json: String): Page =
        PageParser.parse(JSONObject(json).toMap())

    private fun open(url: String, method: String, bearer: String?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/json")
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
        if (method == "POST") {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
        }
        return conn
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        keys().forEach { k -> map[k] = if (isNull(k)) null else get(k) }
        return map
    }
}
