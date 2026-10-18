package io.ofloo.pagerapp.push

import android.content.Context
import android.util.Log
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.net.PagerClient

/**
 * Keeps this device's push subscription alive for its mailbox.
 *
 * The server wakes the device with a data-only trigger (`{"type":"page",…}`) and never the content;
 * the page is then fetched through the normal path. The subscription is a **lease**: it must be
 * renewed before `expires` (30 days by default) or push turns itself off, which is deliberate — a
 * mailbox nobody looks after should not keep a permanent hook.
 *
 * Renewal happens whenever the app runs and the lease is close to lapsing, and immediately on the
 * server's `expiring` warning, so a working device keeps push without anyone thinking about it.
 */
object PushManager {

    private const val TAG = "PagerAPP"

    /** The channel name the server knows for a generic HTTP endpoint (e.g. a distributor URL). */
    const val CHANNEL = "unifiedpush"

    /** Renew when this many days or fewer are left. */
    private const val RENEW_LEAD_DAYS = 5L

    /**
     * Registers [endpoint] (a UnifiedPush distributor URL) for the private mailbox and records the
     * lease. Returns true when the server accepted it.
     */
    fun subscribe(context: Context, endpoint: String, serverUrl: String? = null): Boolean {
        val store = Store(context)
        val mailbox = store.uuid ?: return false
        val server = serverUrl ?: store.serverUrl
        return runCatching {
            val reply = PagerClient.subscribe(server, mailbox, CHANNEL, endpoint)
            store.pushId = reply.optString("id")
            store.pushExpires = (reply.opt("expires") as? Number)?.toLong() ?: 0L
            store.pushEndpoint = endpoint
            store.pushEnabled = true
            Log.i(TAG, "push registered, expires in ${store.pushDaysLeft()} days")
            true
        }.onFailure { Log.w(TAG, "push subscribe failed: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Renews the lease if it is close to lapsing (or when [force] is set, e.g. after the server's
     * expiring warning). Returns true when a renewal was performed and accepted.
     */
    fun renewIfNeeded(context: Context, force: Boolean = false): Boolean {
        val store = Store(context)
        if (!store.pushEnabled) return false
        val id = store.pushId
        if (id.isBlank()) {
            // Nothing registered but push is wanted: register again from the stored endpoint.
            val endpoint = store.pushEndpoint
            return endpoint.isNotBlank() && subscribe(context, endpoint, store.serverUrl)
        }
        if (!force && store.pushDaysLeft() > RENEW_LEAD_DAYS) return false

        val mailbox = store.uuid ?: return false
        return runCatching {
            val reply = PagerClient.renewSubscription(store.serverUrl, mailbox, id)
            store.pushExpires = (reply.opt("expires") as? Number)?.toLong() ?: store.pushExpires
            Log.i(TAG, "push renewed, expires in ${store.pushDaysLeft()} days")
            true
        }.onFailure { Log.w(TAG, "push renew failed: ${it.message}") }
            .getOrDefault(false)
    }

    /** Turns push off for this mailbox. */
    fun disable(context: Context) {
        val store = Store(context)
        val mailbox = store.uuid
        val id = store.pushId
        if (mailbox != null && id.isNotBlank()) {
            runCatching { PagerClient.unsubscribe(store.serverUrl, mailbox, id) }
                .onFailure { Log.w(TAG, "push unsubscribe failed: ${it.message}") }
        }
        store.pushEnabled = false
        store.pushId = ""
        store.pushExpires = 0L
        store.pushEndpoint = ""
    }
}
