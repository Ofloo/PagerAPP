package io.ofloo.pagerapp.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide, persisted cache for PagerMCP's `/settings`. Protocol policy is server-owned:
 * the key fingerprint, mesh hop budget, SSE keepalive, push leases and P2P limits all come from
 * here. Each server has its own public key and fingerprint; entries are keyed by server URL.
 *
 * The raw response is written to SharedPreferences so an offline sender still knows the
 * destination server's public key and fingerprint. A stale entry is returned when the network is
 * unavailable instead of retrying and blocking the send path.
 */
object ServerSettingsCache {

    private const val DEFAULT_TTL_MS = 5 * 60_000L
    private const val PREFS = "pagerapp"
    private const val KEY = "server_settings_cache_v1"

    private data class Entry(val settings: ServerSettings, val fetchedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Any>()

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        loadFromDisk()
    }

    private fun key(serverUrl: String): String = serverUrl.trim().trimEnd('/')

    fun cached(serverUrl: String): ServerSettings? = entries[key(serverUrl)]?.settings

    fun get(serverUrl: String, ttlMs: Long = DEFAULT_TTL_MS): ServerSettings? {
        val url = key(serverUrl)
        if (url.isBlank()) return null
        val now = System.currentTimeMillis()
        entries[url]?.let { if (now - it.fetchedAt <= ttlMs) return it.settings }
        // Offline: the cached key/settings are still authoritative enough to encrypt an envelope.
        if (!online()) return entries[url]?.settings
        val lock = locks.computeIfAbsent(url) { Any() }
        return synchronized(lock) {
            val freshNow = System.currentTimeMillis()
            entries[url]?.let { if (freshNow - it.fetchedAt <= ttlMs) return@let it.settings }
            val fetched = runCatching { PagerClient.settingsRaw(url) }.getOrNull()
            if (fetched != null) {
                entries[url] = Entry(fetched.first, freshNow)
                persist(url, fetched.second, freshNow)
                fetched.first
            } else {
                // Keep the last known key/hop budget instead of losing it on a transient failure.
                entries[url]?.settings
            }
        }
    }

    fun refresh(serverUrl: String): ServerSettings? = get(serverUrl, ttlMs = 0L)

    fun invalidate(serverUrl: String? = null) {
        if (serverUrl == null) entries.clear() else entries.remove(key(serverUrl))
    }

    private fun online(): Boolean {
        val context = appContext ?: return true
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadFromDisk() {
        val context = appContext ?: return
        val raw = prefs(context).getString(KEY, null) ?: return
        val all = runCatching { JSONObject(raw) }.getOrNull() ?: return
        all.keys().forEach { url ->
            val entry = all.optJSONObject(url) ?: return@forEach
            val settingsJson = entry.optString("settings")
            val at = entry.optLong("at", 0L)
            val settings = runCatching { ServerSettings.parse(JSONObject(settingsJson)) }
                .getOrNull() ?: return@forEach
            entries[url] = Entry(settings, at)
        }
    }

    private fun persist(url: String, raw: String, fetchedAt: Long) {
        val context = appContext ?: return
        val prefs = prefs(context)
        val all = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        all.put(url, JSONObject().put("at", fetchedAt).put("settings", raw))
        prefs.edit().putString(KEY, all.toString()).apply()
    }
}
