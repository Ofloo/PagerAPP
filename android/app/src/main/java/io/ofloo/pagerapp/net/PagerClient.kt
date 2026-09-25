package io.ofloo.pagerapp.net

import io.ofloo.pagerapp.shared.Page
import io.ofloo.pagerapp.shared.PageParser
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** Thin PagerMCP HTTP client. The server is a dumb relay; this only speaks the RFC endpoints. */
object PagerClient {

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
                runCatching { PageParser.parse(arr.getJSONObject(i).toMap()) }.getOrNull()
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
