package io.ofloo.pagerapp.push

import android.content.Context
import android.util.Log
import io.ofloo.pagerapp.data.FcmLease
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.net.PagerClient
import io.ofloo.pagerapp.net.SubscriptionNotFoundException
import io.ofloo.pagerapp.poll.Poller
import org.json.JSONArray
import org.json.JSONObject

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

    /** The channel where the server publishes to its own ntfy and the client only names a topic. */
    const val NTFY_CHANNEL = "ntfy"

    /** The channel where the server wakes us through Google's push service. */
    const val FCM_CHANNEL = "fcm"

    /** Renew when this many days or fewer are left. */
    private const val RENEW_LEAD_DAYS = 5L

    /**
     * Makes sure a distributor is chosen before the connector is asked to register. Without a saved
     * distributor the connector only broadcasts a "please pick one" request that nobody answers, so
     * the mailbox silently never gets an endpoint. With exactly one on the device — the usual case,
     * ntfy — we can pick it ourselves, and push works without the user ever seeing a picker.
     */
    fun ensureDistributor(context: Context): Boolean {
        val push = org.unifiedpush.android.connector.UnifiedPush
        val available = push.getDistributors(context)
        val saved = push.getSavedDistributor(context)
        // A saved distributor that is no longer installed would otherwise be trusted forever.
        if (!saved.isNullOrBlank() && saved in available) return true
        val first = available.firstOrNull() ?: return false
        return runCatching {
            push.saveDistributor(context, first)
            Log.i(TAG, "chose distributor $first")
            true
        }.getOrDefault(false)
    }

    /**
     * Asks the connector for an endpoint once more (idempotent). This is what the "Renew now"
     * button calls, so a user can force push on without waiting for the next poll.
     */
    fun requestRegistration(context: Context) {
        runCatching {
            ensureDistributor(context)
            org.unifiedpush.android.connector.UnifiedPush.register(context, Poller.PUSH_INSTANCE)
        }.onFailure { Log.w(TAG, "push register failed: ${it.message}") }
    }

    private fun mailboxesInUse(store: Store): List<Pair<String, String>> =
        (listOfNotNull(store.uuid?.let { store.serverUrl to it }) +
            store.pageMailboxes().map { it.serverUrl to it.uuid } +
            store.contacts().map { it.serverUrl to it.inbox })
            .filter { it.second.isNotBlank() }
            .distinct()

    @Synchronized
    fun reconcileFcm(
        context: Context,
        token: String? = null,
        force: Boolean = false,
        serverUrl: String? = null,
    ): Int {
        val store = Store(context)
        if (!store.fcmEnabled) return 0
        val fresh = token ?: runCatching {
            com.google.android.gms.tasks.Tasks.await(
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token,
                20,
                java.util.concurrent.TimeUnit.SECONDS,
            )
        }.getOrNull()
        if (fresh.isNullOrBlank()) {
            Log.w(TAG, "no fcm token available")
            return 0
        }
        val targets = if (serverUrl == null) mailboxesInUse(store) else {
            mailboxesInUse(store).filter { it.first == serverUrl }
        }
        val prior = store.fcmLeases().toMutableList()
        val desired = targets.toSet()
        val successfulTargets = mutableSetOf<Pair<String, String>>()
        targets.forEach { (server, mailbox) ->
            val subscriptions = runCatching { PagerClient.subscriptions(server, mailbox) }
                .onFailure { Log.w(TAG, "fcm subscription check failed for ${mailbox.take(8)}…: ${it.message}") }
                .getOrNull() ?: return@forEach
            val active = (0 until subscriptions.length()).mapNotNull { index ->
                val item = subscriptions.optJSONObject(index) ?: return@mapNotNull null
                if (item.optString("channel") != FCM_CHANNEL) return@mapNotNull null
                item.optString("id").takeIf(String::isNotBlank)?.let {
                    FcmLease(server, mailbox, it, item.optLong("expires"))
                }
            }
            val index = prior.indexOfFirst { it.serverUrl == server && it.mailbox == mailbox }
            val remembered = prior.getOrNull(index)
            val lease = active.firstOrNull { it.subscriptionId == remembered?.subscriptionId }
                ?: if (remembered == null) active.firstOrNull() else null
            if (index >= 0 && lease == null) prior.removeAt(index)
            val currentIndex = prior.indexOfFirst { it.serverUrl == server && it.mailbox == mailbox }
            if (lease != null && currentIndex < 0) prior += lease
            else if (lease != null && currentIndex >= 0) prior[currentIndex] = lease
            val localIndex = prior.indexOfFirst { it.serverUrl == server && it.mailbox == mailbox }
            val current = prior.getOrNull(localIndex)
            val matchingToken = store.fcmToken == fresh
            if (current != null && matchingToken && active.none { it.subscriptionId == current.subscriptionId }) {
                runCatching { PagerClient.unsubscribe(server, mailbox, current.subscriptionId) }
                    .onFailure { Log.w(TAG, "expired fcm lease cleanup failed: ${it.message}") }
            }
            val unchangedToken = store.fcmToken == fresh
            val validLease = current != null && active.any { it.subscriptionId == current.subscriptionId } &&
                current.expiresAt > System.currentTimeMillis() / 1000L + 5 * 86_400L
            if (current != null && unchangedToken && validLease && !force) {
                successfulTargets += server to mailbox
            } else {
                val actual = if (current == null) {
                    runCatching { PagerClient.subscribe(server, mailbox, FCM_CHANNEL, token = fresh) }
                        .onFailure { Log.w(TAG, "fcm subscribe failed for ${mailbox.take(8)}…: ${it.message}") }
                        .getOrNull()
                } else {
                    val renewal = runCatching {
                        PagerClient.renewFcmSubscription(server, mailbox, current.subscriptionId, fresh)
                    }
                    if (renewal.isSuccess) {
                        JSONObject(renewal.getOrThrow().toString()).put("id", current.subscriptionId)
                    } else if (renewal.exceptionOrNull() is SubscriptionNotFoundException) {
                        runCatching {
                            PagerClient.subscribe(server, mailbox, FCM_CHANNEL, token = fresh)
                        }.onFailure { Log.w(TAG, "fcm re-subscribe failed for ${mailbox.take(8)}…: ${it.message}") }
                            .getOrNull()
                    } else if (!matchingToken) {
                        runCatching {
                            PagerClient.renewFcmSubscription(server, mailbox, current.subscriptionId, fresh)
                        }.onFailure { Log.w(TAG, "fcm token replacement failed for ${mailbox.take(8)}…: ${it.message}") }
                            .getOrNull()?.let { JSONObject(it.toString()).put("id", current.subscriptionId) }
                    } else {
                        renewal.onFailure {
                            Log.w(TAG, "fcm renew failed for ${mailbox.take(8)}…: ${it.message}")
                        }
                        null
                    }
                }
                if (actual != null) {
                    val updated = FcmLease(
                        server,
                        mailbox,
                        actual.optString("id").ifBlank { lease?.subscriptionId.orEmpty() },
                        (actual.opt("expires") as? Number)?.toLong() ?: lease?.expiresAt ?: 0L,
                    )
                    val updatedIndex = prior.indexOfFirst { it.serverUrl == server && it.mailbox == mailbox }
                    if (updatedIndex >= 0) prior[updatedIndex] = updated else prior += updated
                    successfulTargets += server to mailbox
                }
            }
        }
        if (serverUrl == null) {
            val stale = prior.filter { (it.serverUrl to it.mailbox) !in desired }
            stale.forEach { lease ->
                runCatching { PagerClient.unsubscribe(lease.serverUrl, lease.mailbox, lease.subscriptionId) }
                    .onFailure { Log.w(TAG, "stale fcm cleanup failed for ${lease.mailbox.take(8)}…: ${it.message}") }
            }
            store.saveFcmLeases(prior.filter { (it.serverUrl to it.mailbox) in desired })
        } else {
            store.saveFcmLeases(prior)
        }
        if (successfulTargets.size == targets.size && serverUrl == null) {
            store.fcmToken = fresh
            store.fcmCheckedAt = System.currentTimeMillis()
        }
        val primary = store.uuid?.let { store.serverUrl to it }
        val primaryLease = prior.firstOrNull { (it.serverUrl to it.mailbox) == primary }
        if (serverUrl == null && primary != null && primary in successfulTargets && primaryLease != null &&
            (store.pushChannel.isBlank() || store.pushChannel == FCM_CHANNEL)
        ) {
            store.pushEnabled = true
            store.pushId = primaryLease.subscriptionId
            store.pushExpires = primaryLease.expiresAt
            store.pushChannel = FCM_CHANNEL
        }
        Log.i(TAG, "fcm leases reconciled for ${successfulTargets.size}/${targets.size} mailbox(es)")
        return successfulTargets.size
    }

    @Synchronized
    fun removeFcmTarget(context: Context, serverUrl: String, mailbox: String) {
        val store = Store(context)
        val leases = store.fcmLeases().filter { it.serverUrl == serverUrl && it.mailbox == mailbox }
        val serverLeases = runCatching { PagerClient.subscriptions(serverUrl, mailbox) }.getOrNull()
        val matchingIds = serverLeases?.let { subscriptions ->
            (0 until subscriptions.length()).mapNotNull { index ->
                val item = subscriptions.optJSONObject(index) ?: return@mapNotNull null
                if (item.optString("channel") == FCM_CHANNEL) item.optString("id") else null
            }
        }.orEmpty()
        (leases.map { it.subscriptionId } + matchingIds).distinct().forEach { id ->
            runCatching { PagerClient.unsubscribe(serverUrl, mailbox, id) }
                .onFailure { Log.w(TAG, "contact fcm unsubscribe failed: ${it.message}") }
        }
        store.removeFcmLease(serverUrl, mailbox)
    }

    @Synchronized
    fun disableFcm(context: Context) {
        val store = Store(context)
        val targets = mailboxesInUse(store)
        val orphaned = targets.flatMap { (server, mailbox) ->
            runCatching { PagerClient.subscriptions(server, mailbox) }.getOrNull()?.let { subscriptions ->
                (0 until subscriptions.length()).mapNotNull { index ->
                    val item = subscriptions.optJSONObject(index) ?: return@mapNotNull null
                    if (item.optString("channel") != FCM_CHANNEL) return@mapNotNull null
                    item.optString("id").takeIf(String::isNotBlank)?.let { FcmLease(server, mailbox, it, item.optLong("expires")) }
                }
            }.orEmpty()
        }
        val leases = (store.fcmLeases() + orphaned).distinctBy { it.serverUrl to it.subscriptionId }
        leases.forEach { lease ->
            runCatching { PagerClient.unsubscribe(lease.serverUrl, lease.mailbox, lease.subscriptionId) }
                .onFailure { Log.w(TAG, "fcm unsubscribe failed for ${lease.mailbox.take(8)}…: ${it.message}") }
        }
        store.saveFcmLeases(emptyList())
        store.fcmEnabled = false
        store.fcmToken = ""
        store.fcmCheckedAt = 0L
        if (store.pushChannel == FCM_CHANNEL) {
            store.pushId = ""
            store.pushExpires = 0L
            store.pushChannel = ""
        }
    }

    fun subscribeFcmAll(context: Context, token: String? = null, serverUrl: String? = null): Int =
        reconcileFcm(context, token = token, force = true, serverUrl = serverUrl)

    /** True when this build carries a Firebase configuration, i.e. FCM can be used at all. */
    fun fcmAvailable(): Boolean =
        runCatching { com.google.firebase.FirebaseApp.getInstance(); true }.getOrDefault(false)

    /**
     * Registers this device's FCM token with the mailbox, so the server can wake it through Google.
     * Returns true when the server accepted it (it needs an `fcm` channel for that).
     */
    fun subscribeFcm(context: Context, token: String? = null, serverUrl: String? = null): Boolean =
        subscribeFcmAll(context, token, serverUrl) > 0

    /** Names of the UnifiedPush distributors installed on this device, for the Settings card. */
    fun distributors(context: Context): List<String> =
        runCatching { org.unifiedpush.android.connector.UnifiedPush.getDistributors(context) }
            .getOrDefault(emptyList())

    /** The distributor the connector will use, or null when none is chosen (or installed). */
    fun chosenDistributor(context: Context): String? =
        runCatching { org.unifiedpush.android.connector.UnifiedPush.getSavedDistributor(context) }
            .getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Registers [endpoint] (a UnifiedPush distributor URL) for the private mailbox and records the
     * lease. Returns true when the server accepted it.
     */
    @Synchronized
    fun subscribe(context: Context, endpoint: String, serverUrl: String? = null): Boolean {
        val store = Store(context)
        val mailbox = store.uuid ?: return false
        val server = serverUrl ?: store.serverUrl
        // The same endpoint we already registered (a restart, a re-registration, a reinstall that
        // kept our data): renew that lease instead of adding a second identical subscription, which
        // would only make the server wake us twice for every page.
        if (store.pushId.isNotBlank() && store.pushEndpoint == endpoint && store.pushChannel != FCM_CHANNEL) {
            if (renewIfNeeded(context, force = true)) return true
            store.pushId = ""   // the server no longer knows it; register from scratch below
        }
        val topic = topicOf(endpoint)
        val settings = runCatching { PagerClient.settings(server) }.getOrNull()
        // When the server runs its own ntfy for wake-ups and the distributor is that same server,
        // registering just the topic is enough — and the server then never posts to a URL a client
        // handed it. Otherwise fall back to the full endpoint, which is what older servers expect.
        val useNtfy = topic != null && settings != null && settings.supportsNtfy &&
            sameHost(settings.ntfyUrl, endpoint)
        return runCatching {
            val reply = if (useNtfy) {
                PagerClient.subscribe(server, mailbox, NTFY_CHANNEL, topic = topic)
            } else {
                PagerClient.subscribe(server, mailbox, CHANNEL, endpoint = endpoint)
            }
            store.pushId = reply.optString("id")
            store.pushExpires = (reply.opt("expires") as? Number)?.toLong() ?: 0L
            store.pushEndpoint = endpoint
            store.pushChannel = if (useNtfy) NTFY_CHANNEL else CHANNEL
            store.pushEnabled = true
            Log.i(TAG, "push registered on ${store.pushChannel}, expires in ${store.pushDaysLeft()} days")
            true
        }.onFailure { Log.w(TAG, "push subscribe failed: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Renews the lease if it is close to lapsing (or when [force] is set, e.g. after the server's
     * expiring warning). Returns true when a renewal was performed and accepted.
     */
    @Synchronized
    fun renewIfNeeded(context: Context, force: Boolean = false): Boolean {
        val store = Store(context)
        if (!store.pushEnabled) return false
        if (store.fcmEnabled) return reconcileFcm(context, force = force) > 0
        val id = store.pushId
        if (id.isBlank()) {
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
        }.recoverCatching { error ->
            if (error is SubscriptionNotFoundException) {
                store.pushId = ""
                val endpoint = store.pushEndpoint
                endpoint.isNotBlank() && subscribe(context, endpoint, store.serverUrl)
            } else {
                throw error
            }
        }.onFailure { Log.w(TAG, "push renew failed: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Drops every registration this device has for its own mailbox, on the server as well, so a
     * reset cannot leave a stale endpoint or an old FCM token behind. Registrations are per mailbox,
     * so this clears the private one; a contact channel can be re-registered by the next poll if the
     * user turns push on again.
     */
    fun forget(context: Context) {
        val store = Store(context)
        val mailbox = store.uuid ?: return
        val fcm = store.fcmLeases()
        val otherRoutes = mailboxesInUse(store).flatMap { (server, target) ->
            val subscriptions = runCatching { PagerClient.subscriptions(server, target) }
                .getOrElse {
                    Log.w(TAG, "registration discovery failed for ${target.take(8)}…: ${it.message}")
                    JSONArray()
                }
            (0 until subscriptions.length()).mapNotNull { index ->
                val item = subscriptions.optJSONObject(index) ?: return@mapNotNull null
                if (item.optString("channel") == FCM_CHANNEL) return@mapNotNull null
                item.optString("id").takeIf(String::isNotBlank)?.let {
                    FcmLease(server, target, it, item.optLong("expires"))
                }
            }
        }
        val known = (fcm + otherRoutes + listOfNotNull(
            store.pushId.takeIf { it.isNotBlank() }?.let {
                FcmLease(store.serverUrl, mailbox, it, store.pushExpires)
            },
        )).distinctBy { it.serverUrl to it.subscriptionId }
        known.forEach { lease ->
            runCatching { PagerClient.unsubscribe(lease.serverUrl, lease.mailbox, lease.subscriptionId) }
                .onFailure { Log.w(TAG, "clearing registration failed: ${it.message}") }
        }
        store.saveFcmLeases(emptyList())
        store.fcmEnabled = false
        store.fcmToken = ""
        store.pushId = ""
        store.pushExpires = 0L
        store.pushEndpoint = ""
        store.pushChannel = ""
        Log.i(TAG, "push registrations cleared for the mailbox")
    }

    /** Turns the selected server push route off for this mailbox. */
    @Synchronized
    fun disable(context: Context) {
        val store = Store(context)
        val mailbox = store.uuid
        val id = store.pushId
        if (store.pushChannel == FCM_CHANNEL) {
            disableFcm(context)
            store.pushEnabled = false
            return
        }
        if (mailbox != null && id.isNotBlank()) {
            runCatching { PagerClient.unsubscribe(store.serverUrl, mailbox, id) }
                .onFailure { Log.w(TAG, "push unsubscribe failed: ${it.message}") }
        }
        store.pushEnabled = false
        store.pushId = ""
        store.pushExpires = 0L
        store.pushEndpoint = ""
        store.pushChannel = ""
    }

    /** The ntfy topic a distributor endpoint stands for (`https://host/upAb12` → `upAb12`). */
    private fun topicOf(endpoint: String): String? {
        val segment = runCatching { java.net.URI(endpoint).path?.trim('/') }.getOrNull() ?: return null
        return segment.takeIf { it.isNotBlank() && it.startsWith("up") && !it.contains('/') }
    }

    /** True when two URLs point at the same host, ignoring scheme, port and path. */
    private fun sameHost(a: String?, b: String): Boolean {
        val hostA = runCatching { java.net.URI(a).host }.getOrNull() ?: return false
        val hostB = runCatching { java.net.URI(b).host }.getOrNull() ?: return false
        return hostA.equals(hostB, ignoreCase = true)
    }
}
