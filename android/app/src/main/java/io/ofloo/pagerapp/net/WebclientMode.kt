package io.ofloo.pagerapp.net

import android.util.Log
import io.ofloo.pagerapp.data.Store

/**
 * Phone-side owner of PagerMCP 0.9.0 webclient read-copy leases.
 *
 * While a browser handoff session is active, every mailbox the browser can read must have
 * `webclient: true` before the phone may ACK anything. The browser never enables the mode itself.
 */
object WebclientMode {
    private const val TAG = "PagerAPP"
    private const val RECHECK_SECONDS = 300L
    private const val SETTINGS_RECHECK_MS = 10 * 60_000L

    private val modeExpiries = mutableMapOf<String, Long>()
    private val settingsCache = mutableMapOf<String, Pair<Long, ServerSettings>>()
    private var lastGoodCheckAt = 0L
    private var lastTargetsKey = ""

    /** All mailboxes a browser can poll: the private one, extra page addresses and contacts. */
    fun targets(store: Store): List<Pair<String, String>> = buildList {
        store.uuid?.takeIf { it.isNotBlank() }?.let { add(store.serverUrl to it) }
        store.contacts().forEach { add(it.serverUrl to it.inbox) }
    }.distinctBy { it.first to it.second }

    /**
     * Makes sure one mailbox has an active webclient lease. A broken contact server must not stop
     * the private mailbox from being processed, so polling uses this per-target check.
     */
    @Synchronized
    fun ensureTarget(store: Store, server: String, mailbox: String): Boolean {
        val now = System.currentTimeMillis() / 1000L
        if (!store.webSessionActive(now)) return true
        val settings = settings(server) ?: return false
        if (settings.webclientTtlSeconds <= 0L) return true
        val key = "$server|$mailbox"
        if ((modeExpiries[key] ?: 0L) > now + RECHECK_SECONDS) return true
        val result = runCatching {
            val current = PagerClient.webclientMode(server, mailbox)
            if (current.webclient && (current.expires ?: 0L) > now + RECHECK_SECONDS) current
            else PagerClient.setWebclientMode(server, mailbox, true)
        }.onFailure {
            Log.w(TAG, "webclient mode failed for $mailbox: ${it.message}")
        }.getOrNull()
        if (result?.webclient == true) {
            modeExpiries[key] = result.expires ?: (now + settings.webclientTtlSeconds)
            return true
        }
        return false
    }

    /**
     * Makes sure every target has an active webclient lease. Returns false when a lease could not
     * be established, in which case the caller must not ACK (the browser may still need the copy).
     */
    @Synchronized
    fun ensure(store: Store, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis() / 1000L
        if (!store.webSessionActive(now)) {
            modeExpiries.clear()
            lastGoodCheckAt = 0L
            lastTargetsKey = ""
            return true
        }
        val targets = targets(store)
        val targetsKey = targets.joinToString("|") { "${it.first}::${it.second}" }
        if (!force && lastGoodCheckAt > 0L && now - lastGoodCheckAt < RECHECK_SECONDS &&
            targetsKey == lastTargetsKey
        ) {
            return true
        }

        var ready = true
        for ((server, mailbox) in targets) {
            val settings = settings(server) ?: run {
                ready = false
                continue
            }
            if (settings.webclientTtlSeconds <= 0L) {
                // Old server: it has no read-copy mode to protect. The web client detects this and
                // stays read-only; normal phone ACK behaviour remains unchanged.
                continue
            }
            val key = "$server|$mailbox"
            if (!force && (modeExpiries[key] ?: 0L) > now + RECHECK_SECONDS) continue
            val ok = runCatching {
                val current = PagerClient.webclientMode(server, mailbox)
                if (current.webclient && (current.expires ?: 0L) > now + RECHECK_SECONDS) {
                    current
                } else {
                    PagerClient.setWebclientMode(server, mailbox, true)
                }
            }.onFailure {
                Log.w(TAG, "webclient mode failed for $mailbox: ${it.message}")
            }.getOrNull()
            if (ok?.webclient == true) {
                modeExpiries[key] = ok.expires ?: (now + settings.webclientTtlSeconds)
            } else {
                ready = false
            }
        }
        lastGoodCheckAt = if (ready) now else 0L
        lastTargetsKey = if (ready) targetsKey else ""
        return ready
    }

    /** Renewal QR path: re-check the server now instead of trusting the short-lived cache. */
    fun renew(store: Store): Boolean = ensure(store, force = true)

    /**
     * Ends every webclient lease and forgets the local browser session. The phone then resumes
     * normal destructive ACK behaviour and may delete share-host attachments again.
     */
    @Synchronized
    fun clearAll(store: Store): Boolean {
        var ok = true
        for ((server, mailbox) in targets(store)) {
            runCatching { PagerClient.setWebclientMode(server, mailbox, false) }
                .onFailure {
                    ok = false
                    Log.w(TAG, "could not disable webclient mode for $mailbox: ${it.message}")
                }
        }
        store.clearWebSession()
        modeExpiries.clear()
        lastGoodCheckAt = 0L
        lastTargetsKey = ""
        return ok
    }

    private fun settings(server: String): ServerSettings? {
        val now = System.currentTimeMillis()
        settingsCache[server]?.let { (at, cached) ->
            if (now - at < SETTINGS_RECHECK_MS) return cached
        }
        val fresh = runCatching { PagerClient.settings(server) }
            .onFailure { Log.w(TAG, "webclient settings failed for $server: ${it.message}") }
            .getOrNull()
            ?: return settingsCache[server]?.second
        settingsCache[server] = now to fresh
        return fresh
    }
}
