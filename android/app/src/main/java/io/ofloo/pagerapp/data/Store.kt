package io.ofloo.pagerapp.data

import android.content.Context
import android.content.SharedPreferences
import io.ofloo.pagerapp.shared.Page
import io.ofloo.pagerapp.shared.PagerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Local persistence: mailbox UUID, user config, polling interval, dedupe set, and a bounded
 * page history. Uses SharedPreferences only (no DB) to keep the first version small.
 */
class Store(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("pagerapp", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, DEFAULT_SERVER)!!
        set(v) = prefs.edit().putString(KEY_SERVER, v).apply()

    var uuid: String?
        get() = prefs.getString(KEY_UUID, null)
        set(v) = prefs.edit().putString(KEY_UUID, v).apply()

    var pollSeconds: Int
        get() = prefs.getInt(KEY_POLL, DEFAULT_POLL_SECONDS)
        set(v) = prefs.edit().putInt(KEY_POLL, v.coerceIn(MIN_POLL, MAX_POLL)).apply()

    var bypassMode: Boolean
        get() = prefs.getBoolean(KEY_BYPASS, false)
        set(v) = prefs.edit().putBoolean(KEY_BYPASS, v).apply()

    var silenceMode: Boolean
        get() = prefs.getBoolean(KEY_SILENCE, false)
        set(v) = prefs.edit().putBoolean(KEY_SILENCE, v).apply()

    var config: PagerConfig
        get() = ConfigCodec.decode(prefs.getString(KEY_CONFIG, null))
        set(v) = prefs.edit().putString(KEY_CONFIG, ConfigCodec.encode(v)).apply()

    fun dedupeSnapshot(): Set<String> =
        prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()

    fun rememberSeen(ids: Collection<String>) {
        val merged = (dedupeSnapshot() + ids).toMutableSet()
        val trimmed = if (merged.size > MAX_SEEN) merged.toList().takeLast(MAX_SEEN).toSet() else merged
        prefs.edit().putStringSet(KEY_SEEN, trimmed).apply()
    }

    /** The effective config, with bypass/silence overlaid from the quick toggles. */
    fun effectiveConfig(): PagerConfig =
        config.copy(bypassMode = bypassMode, silenceMode = silenceMode)

    fun history(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> HistoryEntry.fromJson(arr.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addHistory(entries: List<HistoryEntry>) {
        val merged = (entries + history()).take(MAX_HISTORY)
        val arr = JSONArray()
        merged.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    companion object {
        const val DEFAULT_SERVER = "https://pager.ofloo.io"
        const val DEFAULT_POLL_SECONDS = 60
        const val MIN_POLL = 30
        const val MAX_POLL = 300
        private const val MAX_SEEN = 1000
        private const val MAX_HISTORY = 200

        private const val KEY_SERVER = "server_url"
        private const val KEY_UUID = "pager_uuid"
        private const val KEY_POLL = "poll_seconds"
        private const val KEY_CONFIG = "config"
        private const val KEY_BYPASS = "bypass"
        private const val KEY_SILENCE = "silence"
        private const val KEY_SEEN = "seen_ids"
        private const val KEY_HISTORY = "history"
    }
}

/** One row in the in-app history list. */
data class HistoryEntry(
    val id: String,
    val status: Int,
    val jobId: String?,
    val project: String?,
    val message: String?,
    val createdAt: Double,
    val ackUuid: String?,
    val delivered: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("status", status)
        .put("job", jobId)
        .put("project", project)
        .put("message", message)
        .put("created_at", createdAt)
        .put("ack_uuid", ackUuid)
        .put("delivered", delivered)

    companion object {
        fun fromJson(o: JSONObject) = HistoryEntry(
            id = o.optString("id"),
            status = o.optInt("status"),
            jobId = o.optString("job").takeIf { it.isNotEmpty() && it != "null" },
            project = o.optString("project").takeIf { it.isNotEmpty() && it != "null" },
            message = o.optString("message").takeIf { it.isNotEmpty() && it != "null" },
            createdAt = o.optDouble("created_at", 0.0),
            ackUuid = o.optString("ack_uuid").takeIf { it.isNotEmpty() && it != "null" },
            delivered = o.optBoolean("delivered", false),
        )

        fun from(page: Page, delivered: Boolean) = HistoryEntry(
            id = page.id,
            status = page.status,
            jobId = page.jobId,
            project = page.project,
            message = page.message,
            createdAt = Instant.now().epochSecond.toDouble(),
            ackUuid = page.ackUuid,
            delivered = delivered,
        )
    }
}
