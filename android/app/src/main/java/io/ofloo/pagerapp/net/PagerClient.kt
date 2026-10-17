package io.ofloo.pagerapp.net

import android.util.Log
import io.ofloo.pagerapp.shared.Page
import io.ofloo.pagerapp.shared.PageParser
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** What `GET /version` reports: the running server's version, build and hostname. */
data class ServerInfo(val version: String, val build: String, val hostname: String)

/** Thin PagerMCP HTTP client. The server is a dumb relay; this only speaks the RFC endpoints. */
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
     */
    fun messages(serverUrl: String, uuid: String): List<Page> {
        val conn = open("$serverUrl/mailboxes/$uuid/messages", "GET", uuid)
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
        val conn = open("$serverUrl/notify", "POST", bearer)
        try {
            conn.outputStream.use { it.write(JSONObject(payload).toString().toByteArray()) }
            require(conn.responseCode in 200..299) { "notify HTTP ${conn.responseCode}" }
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

    /**
     * Streams pages over Server-Sent Events (`GET /mailboxes/{uuid}/events`).
     *
     * Blocks until the stream ends or throws, so callers reconnect. The server replays the
     * unacknowledged backlog on connect, so a dropped connection loses nothing; keepalives every
     * ~15s keep an idle connection alive, and the read timeout is set above that so a dead
     * connection surfaces as an exception instead of hanging forever.
     */
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
