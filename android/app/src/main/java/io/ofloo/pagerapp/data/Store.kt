package io.ofloo.pagerapp.data

import android.content.Context
import android.content.SharedPreferences
import io.ofloo.pagerapp.ThemeMode
import io.ofloo.pagerapp.shared.Contact
import io.ofloo.pagerapp.shared.ContactKind
import io.ofloo.pagerapp.shared.LEVEL_NORMAL
import io.ofloo.pagerapp.shared.Pgp
import io.ofloo.pagerapp.shared.Page
import io.ofloo.pagerapp.shared.P2PEnvelope
import io.ofloo.pagerapp.shared.PagerConfig
import io.ofloo.pagerapp.shared.p2pRouteHash
import io.ofloo.pagerapp.shared.defaultLevelOrder
import io.ofloo.pagerapp.shared.sanitizeLevelOrder
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class FcmLease(
    val serverUrl: String,
    val mailbox: String,
    val subscriptionId: String,
    val expiresAt: Long,
    /** The route hash used when this lease was registered (null while UUID mode is active). */
    val route: String? = null,
)

/** An extra PagerMCP mailbox used as a page address, e.g. one per logging source. */
data class PageMailbox(
    val name: String,
    val uuid: String,
    val serverUrl: String,
    val levelOrder: List<String> = defaultLevelOrder(),
) {
    val id: String get() = "$serverUrl|$uuid"
}

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

    /** Display name this device announces when it sends a page to a contact. */
    var displayName: String
        get() = prefs.getString(KEY_NAME, "")!!
        set(v) = prefs.edit().putString(KEY_NAME, v).apply()

    /** UI language: "system" or one of en/nl/fr/de/es. */
    var appLanguage: String
        get() = prefs.getString(KEY_LANGUAGE, "system")!!
        set(v) = prefs.edit().putString(KEY_LANGUAGE, v).apply()

    /** Colour-scheme preference; defaults to following the OS. */
    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null)!!) }
            .getOrDefault(ThemeMode.SYSTEM)
        set(v) = prefs.edit().putString(KEY_THEME, v.name).apply()

    /** A contact payload delivered by an NFC tap; the Contacts screen consumes it once. */
    var pendingContact: String?
        get() = prefs.getString(KEY_PENDING_CONTACT, null)
        set(v) = prefs.edit().putString(KEY_PENDING_CONTACT, v).apply()

    /** Optional second PagerMCP base URL, used when the primary cannot be reached. */
    var alternateServer: String
        get() = prefs.getString(KEY_ALT_SERVER, "")!!
        set(v) = prefs.edit().putString(KEY_ALT_SERVER, v).apply()

    /**
     * transfer.sh-compatible host for larger photos. The image is GPG-encrypted before upload, so
     * the host only sees ciphertext. Both sides must use the same host to auto-download.
     */
    var photoShareUrl: String
        get() = prefs.getString(KEY_PHOTO_SHARE, DEFAULT_PHOTO_SHARE)!!.trimEnd('/')
        set(v) = prefs.edit().putString(KEY_PHOTO_SHARE, v.trim().trimEnd('/')).apply()

    // --- Push subscription (per mailbox; for now the private one) ---

    /** Push is on unless the user turns it off: nothing to remember, nothing to switch on. */
    var pushEnabled: Boolean
        get() = prefs.getBoolean(KEY_PUSH_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_PUSH_ENABLED, v).apply()

    /** Server-side subscription id, and the lease we must renew before it lapses. */
    var pushId: String
        get() = prefs.getString(KEY_PUSH_ID, "")!!
        set(v) = prefs.edit().putString(KEY_PUSH_ID, v).apply()

    var pushExpires: Long
        get() = prefs.getLong(KEY_PUSH_EXPIRES, 0L)
        set(v) = prefs.edit().putLong(KEY_PUSH_EXPIRES, v).apply()

    /** The distributor endpoint we handed to the server, so we can re-register after a change. */
    var pushEndpoint: String
        get() = prefs.getString(KEY_PUSH_ENDPOINT, "")!!
        set(v) = prefs.edit().putString(KEY_PUSH_ENDPOINT, v).apply()

    /**
     * When the current browser handoff session ends. While this is in the future the phone owns a
     * PagerMCP webclient-mode lease, so it must not delete mail before the browser has its copy.
     */
    var webSessionExpiresAt: Long
        get() = prefs.getLong(KEY_WEB_SESSION_EXPIRES, 0L)
        set(v) = prefs.edit().putLong(KEY_WEB_SESSION_EXPIRES, v).apply()

    fun webSessionActive(now: Long = System.currentTimeMillis() / 1000L): Boolean =
        webSessionExpiresAt > now

    fun clearWebSession() {
        prefs.edit().remove(KEY_WEB_SESSION_EXPIRES).apply()
    }

    /** Wake-ups through Google's push service instead of our own connection. Off unless asked. */
    var fcmEnabled: Boolean
        get() = prefs.getBoolean(KEY_FCM, false)
        set(v) = prefs.edit().putBoolean(KEY_FCM, v).apply()

    /** User opt-in for P2P envelopes; normal messages are unchanged when this is off. */
    var p2pModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_P2P, false)
        set(v) = prefs.edit().putBoolean(KEY_P2P, v).apply()

    /** Last /settings p2p.enabled value; P2P fields are only sent when the server supports them. */
    var p2pServerEnabled: Boolean
        get() = prefs.getBoolean(KEY_P2P_SERVER, false)
        set(v) = prefs.edit().putBoolean(KEY_P2P_SERVER, v).apply()

    /** The server's maximum accepted message age, cached from /settings. */
    var p2pMaxAgeSeconds: Long
        get() = prefs.getLong(KEY_P2P_MAX_AGE, 0L)
        set(v) = prefs.edit().putLong(KEY_P2P_MAX_AGE, v).apply()

    /**
     * Push-subscription identity. Defaults to the mailbox UUID; the server can advertise the
     * anonymised route hash (optionally rotating) and the client follows it automatically.
     */
    var pushIdentityMode: String
        get() = prefs.getString(KEY_PUSH_IDENTITY, "uuid") ?: "uuid"
        set(v) = prefs.edit().putString(KEY_PUSH_IDENTITY, v).apply()

    var pushIdentityPeriodSeconds: Long
        get() = prefs.getLong(KEY_PUSH_IDENTITY_PERIOD, 0L)
        set(v) = prefs.edit().putLong(KEY_PUSH_IDENTITY_PERIOD, v).apply()

    /**
     * Server clock minus device clock, in seconds, from the last `GET /settings`. A rotating push
     * identity has to roll over on the server's period boundaries, not the phone's.
     */
    var serverClockOffsetSeconds: Long
        get() = prefs.getLong(KEY_SERVER_CLOCK_OFFSET, 0L)
        set(v) = prefs.edit().putLong(KEY_SERVER_CLOCK_OFFSET, v).apply()

    /** Server-aligned "now" used for rotating identities. */
    private fun pushNowMillis(): Long =
        (System.currentTimeMillis() / 1000L + serverClockOffsetSeconds) * 1000L

    /** The routes to send with a push subscription, or null while the UUID contract is in use. */
    fun pushRoutes(mailbox: String): List<String>? =
        if (pushIdentityMode == io.ofloo.pagerapp.shared.PushIdentity.MODE_HASH) {
            io.ofloo.pagerapp.shared.PushIdentity.rotatingRoutes(
                mailbox,
                pushIdentityPeriodSeconds,
                pushNowMillis(),
            )
        } else {
            null
        }

    /** The current bucket's route, used to notice a rollover and renew in time. */
    fun pushRoute(mailbox: String): String? =
        if (pushIdentityMode == io.ofloo.pagerapp.shared.PushIdentity.MODE_HASH) {
            io.ofloo.pagerapp.shared.PushIdentity.rotatingRoute(
                mailbox,
                pushIdentityPeriodSeconds,
                pushNowMillis(),
            )
        } else {
            null
        }

    /** Applies the server's advertised push identity (older servers keep reporting nothing). */
    fun cachePushIdentity(settings: io.ofloo.pagerapp.net.ServerSettings) {
        // Sanity-clamp: a bogus clock must not make the route jump buckets.
        val serverTime = settings.serverTimeSeconds
        val offset = if (serverTime != null) {
            val raw = serverTime - System.currentTimeMillis() / 1000L
            raw.coerceIn(-86_400L * 365L, 86_400L * 365L)
        } else {
            serverClockOffsetSeconds
        }
        prefs.edit()
            .putString(KEY_PUSH_IDENTITY, settings.pushIdentityMode)
            .putLong(KEY_PUSH_IDENTITY_PERIOD, settings.pushIdentityPeriodSeconds)
            .putLong(KEY_SERVER_CLOCK_OFFSET, offset)
            .apply()
    }

    /** When `/settings` was last fetched, so the identity/clock does not need a poll every time. */
    var settingsCheckedAt: Long
        get() = prefs.getLong(KEY_SETTINGS_CHECKED, 0L)
        set(v) = prefs.edit().putLong(KEY_SETTINGS_CHECKED, v).apply()

    fun cacheP2pSettings(settings: io.ofloo.pagerapp.net.ServerSettings) {
        prefs.edit()
            .putBoolean(KEY_P2P_SERVER, settings.p2pEnabled)
            .putLong(KEY_P2P_MAX_AGE, settings.p2pMaxMessageAgeSeconds)
            .putBoolean(KEY_P2P_GPG, settings.p2pGpgEnabled)
            .putString(KEY_P2P_PUBLIC_KEY, settings.p2pPublicKey)
            .putString(KEY_P2P_KEY_ID, settings.p2pKeyId)
            .apply()
    }

    fun p2pGpgEnabled(): Boolean = prefs.getBoolean(KEY_P2P_GPG, false)
    fun p2pPublicKey(): String = prefs.getString(KEY_P2P_PUBLIC_KEY, "").orEmpty()
    fun p2pKeyId(): String = prefs.getString(KEY_P2P_KEY_ID, "").orEmpty()

    /**
     * Mailboxes this installation owns and polls: the private mailbox, every page address and
     * every contact channel inbox. Traffic to one of these never needs a gateway, and a P2P
     * envelope addressed to one of them is delivered locally.
     */
    fun localMailboxIds(): Set<String> = buildSet {
        uuid?.takeIf { it.isNotBlank() }?.let(::add)
        pageMailboxes().map { it.uuid }.filter { it.isNotBlank() }.forEach(::add)
        contacts().map { it.inbox }.filter { it.isNotBlank() }.forEach(::add)
    }

    fun isLocalMailbox(id: String?): Boolean =
        !id.isNullOrBlank() && id in localMailboxIds()

    /** When we last checked whether our mailbox really has an fcm subscription. */
    var fcmCheckedAt: Long
        get() = prefs.getLong(KEY_FCM_CHECKED, 0L)
        set(v) = prefs.edit().putLong(KEY_FCM_CHECKED, v).apply()

    var fcmToken: String
        get() = prefs.getString(KEY_FCM_TOKEN, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_FCM_TOKEN, v).apply()

    fun fcmLeases(): List<FcmLease> {
        val result = runCatching {
            val array = JSONArray(prefs.getString(KEY_FCM_LEASES, "[]"))
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val server = item.optString("server")
                val mailbox = item.optString("mailbox")
                val id = item.optString("id")
                if (server.isBlank() || mailbox.isBlank() || id.isBlank()) null
                else FcmLease(
                    server,
                    mailbox,
                    id,
                    item.optLong("expires"),
                    item.optString("route").takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }.getOrDefault(emptyList())
        val primary = uuid
        if (primary != null && pushChannel == "fcm" && pushId.isNotBlank() &&
            result.none { it.serverUrl == serverUrl && it.mailbox == primary }
        ) {
            return result + FcmLease(serverUrl, primary, pushId, pushExpires)
        }
        return result
    }

    fun removeFcmLease(serverUrl: String, mailbox: String) {
        saveFcmLeases(fcmLeases().filterNot { it.serverUrl == serverUrl && it.mailbox == mailbox })
        if (uuid == mailbox && this.serverUrl == serverUrl && pushChannel == "fcm") {
            pushId = ""
            pushExpires = 0L
            pushChannel = ""
        }
    }

    fun saveFcmLeases(leases: List<FcmLease>) {
        val array = JSONArray()
        val unique = leases.distinctBy { it.serverUrl to it.mailbox }
        unique.forEach { lease ->
            if (lease.subscriptionId.isNotBlank()) {
                array.put(
                    JSONObject()
                        .put("server", lease.serverUrl)
                        .put("mailbox", lease.mailbox)
                        .put("id", lease.subscriptionId)
                        .put("expires", lease.expiresAt)
                        .put("route", lease.route),
                )
            }
        }
        prefs.edit().putString(KEY_FCM_LEASES, array.toString()).apply()
    }

    /** The channel the server accepted it on (`unifiedpush`, `http` or `ntfy`), for display. */
    var pushChannel: String
        get() = prefs.getString(KEY_PUSH_CHANNEL, "")!!
        set(v) = prefs.edit().putString(KEY_PUSH_CHANNEL, v).apply()

    /** Whole days until the push lease ends; a large number when none is registered. */
    fun pushDaysLeft(): Long {
        val expires = pushExpires
        if (expires <= 0) return Long.MAX_VALUE
        return (expires - System.currentTimeMillis() / 1000L) / 86_400L
    }

    // --- Our OpenPGP identity (chat encryption) ---

    /** How long a newly generated key lives. Clamped to 6-12 months by [Pgp]. */
    var keyExpiryMonths: Int
        get() = prefs.getInt(KEY_EXP_MONTHS, Pgp.DEFAULT_EXPIRY_MONTHS)
        set(v) = prefs.edit()
            .putInt(KEY_EXP_MONTHS, v.coerceIn(Pgp.MIN_EXPIRY_MONTHS, Pgp.MAX_EXPIRY_MONTHS))
            .apply()

    val ownPublicKey: String get() = prefs.getString(KEY_PUB, "").orEmpty()
    val ownSecretKey: String get() = prefs.getString(KEY_SEC, "").orEmpty()
    val ownFingerprint: String get() = prefs.getString(KEY_FPR, "").orEmpty()
    val ownKeyExpiresAt: Long get() = prefs.getLong(KEY_EXP, 0L)

    /**
     * Returns our key material, generating it on first use. Generating RSA keys takes a moment,
     * so call this from a background thread.
     */
    fun ensureOwnKeys(): Pgp.KeyMaterial {
        val existing = Pgp.KeyMaterial(
            publicArmored = ownPublicKey,
            secretArmored = ownSecretKey,
            fingerprint = ownFingerprint,
            expiresAtEpochSeconds = ownKeyExpiresAt,
        )
        return if (existing.publicArmored.isNotBlank() && existing.secretArmored.isNotBlank()) {
            existing
        } else {
            generateKeys()
        }
    }

    /** Replaces the key ring — used when the old one expires or the user asks for a new one. */
    fun renewKeys(): Pgp.KeyMaterial = generateKeys()

    /** RSA size for newly generated keys. Larger is slower to generate, not weaker. */
    var keyBits: Int
        get() = prefs.getInt(KEY_BITS, DEFAULT_KEY_BITS_PREF)
        set(v) = prefs.edit()
            .putInt(KEY_BITS, v.coerceIn(Pgp.MIN_KEY_BITS, Pgp.MAX_KEY_BITS))
            .apply()

    /**
     * Extends the current key's expiry, keeping the same fingerprint. This is the default way to
     * keep a key current: contacts see no change, so nothing is re-announced or re-encrypted.
     */
    fun extendOwnKeys(): Pgp.KeyMaterial {
        val current = ensureOwnKeys()
        val extended = Pgp.extend(current.secretArmored, keyExpiryMonths)
        return storeKeys(extended)
    }

    /** Whole days until the current key expires; a large number when none is known. */
    fun keyDaysLeft(): Long {
        val expires = ownKeyExpiresAt
        if (expires <= 0) return Long.MAX_VALUE
        return (expires - System.currentTimeMillis() / 1000L) / 86_400L
    }

    private fun generateKeys(): Pgp.KeyMaterial =
        storeKeys(Pgp.generate("PagerAPP <pagerapp>", keyExpiryMonths, keyBits = keyBits))

    private fun storeKeys(keys: Pgp.KeyMaterial): Pgp.KeyMaterial {
        prefs.edit()
            .putString(KEY_PUB, keys.publicArmored)
            .putString(KEY_SEC, keys.secretArmored)
            .putString(KEY_FPR, keys.fingerprint)
            .putLong(KEY_EXP, keys.expiresAtEpochSeconds)
            .apply()
        return keys
    }

    // --- Key material per mailbox ---
    //
    // A mailbox is an address *and* an identity: it carries its own key pair, so an expiry or a leak
    // in one channel never touches the others. The four properties above stay the primary mailbox's
    // shorthand — and the key that existed before this change *is* that primary mailbox's key, so an
    // older install keeps working without a migration step.

    /** True for the mailbox the app created for itself — the one older installs keep their key in. */
    fun isPrimaryMailbox(mailbox: String): Boolean = mailbox.isNotBlank() && mailbox == uuid

    fun publicKeyOf(mailbox: String): String =
        storedKey(KEY_PUB, mailbox).ifBlank { legacyKey(KEY_PUB, mailbox) }

    fun secretKeyOf(mailbox: String): String =
        storedKey(KEY_SEC, mailbox).ifBlank { legacyKey(KEY_SEC, mailbox) }

    fun fingerprintOf(mailbox: String): String =
        storedKey(KEY_FPR, mailbox).ifBlank { legacyKey(KEY_FPR, mailbox) }

    fun keyExpiryOf(mailbox: String): Long {
        val own = prefs.getLong("$KEY_EXP:$mailbox", 0L)
        if (own > 0L) return own
        return if (isPrimaryMailbox(mailbox)) prefs.getLong(KEY_EXP, 0L) else 0L
    }

    /**
     * Key material for [mailbox], generating it on first use so no mailbox is ever keyless.
     * Generating RSA keys takes a moment, so call this from a background thread.
     */
    fun ensureKeysFor(mailbox: String): Pgp.KeyMaterial {
        val existing = Pgp.KeyMaterial(
            publicArmored = publicKeyOf(mailbox),
            secretArmored = secretKeyOf(mailbox),
            fingerprint = fingerprintOf(mailbox),
            expiresAtEpochSeconds = keyExpiryOf(mailbox),
        )
        return if (existing.publicArmored.isNotBlank() && existing.secretArmored.isNotBlank()) {
            existing
        } else {
            storeKeysFor(mailbox, Pgp.generate("PagerAPP <pagerapp>", keyExpiryMonths, keyBits = keyBits))
        }
    }

    /** Extends that mailbox's key in place, keeping its fingerprint. */
    fun extendKeysFor(mailbox: String): Pgp.KeyMaterial =
        storeKeysFor(mailbox, Pgp.extend(ensureKeysFor(mailbox).secretArmored, keyExpiryMonths))

    private fun storedKey(suffix: String, mailbox: String): String =
        prefs.getString("$suffix:$mailbox", "").orEmpty()

    private fun legacyKey(suffix: String, mailbox: String): String =
        if (isPrimaryMailbox(mailbox)) prefs.getString(suffix, "").orEmpty() else ""

    private fun storeKeysFor(mailbox: String, keys: Pgp.KeyMaterial): Pgp.KeyMaterial {
        prefs.edit()
            .putString("$KEY_PUB:$mailbox", keys.publicArmored)
            .putString("$KEY_SEC:$mailbox", keys.secretArmored)
            .putString("$KEY_FPR:$mailbox", keys.fingerprint)
            .putLong("$KEY_EXP:$mailbox", keys.expiresAtEpochSeconds)
            .apply()
        return keys
    }

    /** Extra page addresses (beyond the primary mailbox), e.g. one per logging source. */
    fun pageMailboxes(): List<PageMailbox> {
        val raw = prefs.getString(KEY_PAGE_MAILBOXES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val uuid = o.optString("uuid")
                val server = o.optString("server")
                if (uuid.isBlank() || server.isBlank()) null
                else PageMailbox(
                    name = o.optString("name").ifBlank { "Page address" },
                    uuid = uuid,
                    serverUrl = server,
                    levelOrder = o.optJSONArray("level_order")?.let { levels ->
                        sanitizeLevelOrder((0 until levels.length()).map { levels.optString(it) })
                    } ?: defaultLevelOrder(),
                )
            }.distinctBy { it.id }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addPageMailbox(name: String, uuid: String, serverUrl: String) {
        val clean = uuid.trim()
        if (clean.isBlank()) return
        val server = serverUrl.trim().trimEnd('/').ifBlank { this.serverUrl }
        val mailboxes = pageMailboxes().filterNot { it.uuid == clean && it.serverUrl == server }
        savePageMailboxes(
            mailboxes + PageMailbox(name.trim().ifBlank { "Page address" }, clean, server),
        )
    }

    fun removePageMailbox(id: String) {
        savePageMailboxes(pageMailboxes().filterNot { it.id == id })
    }

    /** Stores a page address's own level order (the only per-address rule preference). */
    fun setPageMailboxLevelOrder(id: String, levelOrder: List<String>) {
        val clean = sanitizeLevelOrder(levelOrder)
        savePageMailboxes(
            pageMailboxes().map { if (it.id == id) it.copy(levelOrder = clean) else it },
        )
    }

    private fun savePageMailboxes(list: List<PageMailbox>) {
        val arr = JSONArray()
        list.forEach { mailbox ->
            arr.put(
                JSONObject()
                    .put("name", mailbox.name)
                    .put("uuid", mailbox.uuid)
                    .put("server", mailbox.serverUrl)
                    .put("level_order", JSONArray(mailbox.levelOrder)),
            )
        }
        prefs.edit().putString(KEY_PAGE_MAILBOXES, arr.toString()).apply()
    }

    /** People this device can exchange pages with. */
    fun contacts(): List<Contact> {
        val raw = prefs.getString(KEY_CONTACTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val uuid = o.optString("uuid")
                val server = o.optString("server")
                if (uuid.isBlank() || server.isBlank()) null
                else Contact(
                    name = o.optString("name"),
                    serverUrl = server,
                    uuid = uuid,
                    senderId = o.optString("sender_id"),
                    mySenderId = o.optString("my_sender_id"),
                    publicKey = o.optString("public_key"),
                    kind = runCatching { ContactKind.valueOf(o.optString("kind")) }
                        .getOrDefault(ContactKind.PERSON),
                    level = o.optString("level").takeIf { it.isNotBlank() } ?: LEVEL_NORMAL,
                    sendTo = o.optString("send_to"),
                    receiveOn = o.optString("receive_on"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun upsertContact(contact: Contact) {
        val merged = (contacts().filterNot { it.id == contact.id } + contact)
        saveContacts(merged)
    }

    fun removeContact(id: String) {
        saveContacts(contacts().filterNot { it.id == id })
    }

    private fun saveContacts(list: List<Contact>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("name", c.name)
                    .put("server", c.serverUrl)
                    .put("uuid", c.uuid)
                    .put("sender_id", c.senderId)
                    .put("my_sender_id", c.mySenderId)
                    .put("public_key", c.publicKey)
                    .put("kind", c.kind.name)
                    .put("level", c.level)
                    .put("send_to", c.sendTo)
                    .put("receive_on", c.receiveOn),
            )
        }
        prefs.edit().putString(KEY_CONTACTS, arr.toString()).apply()
    }

    var pollSeconds: Int
        get() = prefs.getInt(KEY_POLL, DEFAULT_POLL_SECONDS)
        set(v) = prefs.edit().putInt(KEY_POLL, v.coerceIn(MIN_POLL, MAX_POLL)).apply()

    var bypassMode: Boolean
        get() = prefs.getBoolean(KEY_BYPASS, false)
        set(v) = prefs.edit().putBoolean(KEY_BYPASS, v).apply()

    var silenceMode: Boolean
        get() = prefs.getBoolean(KEY_SILENCE, false)
        set(v) = prefs.edit().putBoolean(KEY_SILENCE, v).apply()

    /**
     * True (default) = the app keeps its own connection to PagerMCP in a foreground service, so a
     * page arrives in about a second even with the app closed. Android only allows that while a
     * notification is shown — ours is silent, without a status-bar icon.
     *
     * False = plain background work: no notification at all, but Android's 15-minute floor and
     * Doze can delay a page for many minutes.
     */
    var reliableDelivery: Boolean
        get() = prefs.getBoolean(KEY_RELIABLE, true)
        set(v) = prefs.edit().putBoolean(KEY_RELIABLE, v).apply()

    /**
     * Puts every **setting** back to its default: rules and active hours, theme, poll interval,
     * delivery mode, on-call/silence, and the key preferences (size and lifetime).
     *
     * Deliberately untouched: the mailbox UUID and the server it lives on — that address is what
     * others write to, so pointing the app elsewhere would orphan it — the encryption keys, the
     * contacts and the history (those are data, not settings).
     *
     * The push settings go back to their defaults too, so nothing of an earlier configuration
     * survives. [PushManager] clears the matching registrations on the server, so no stale endpoint
     * or token is left behind either; the app registers afresh on its next poll.
     */
    fun resetSettings() {
        prefs.edit()
            .remove(KEY_CONFIG)
            .remove(KEY_THEME)
            .remove(KEY_POLL)
            .remove(KEY_BYPASS)
            .remove(KEY_SILENCE)
            .remove(KEY_RELIABLE)
            .remove(KEY_ALARM)
            .remove(KEY_EXP_MONTHS)
            .remove(KEY_BITS)
            .remove(KEY_PENDING_CONTACT)
            .remove(KEY_PUSH_ENABLED)
            .remove(KEY_FCM)
            .remove(KEY_FCM_CHECKED)
            .remove(KEY_PHOTO_SHARE)
            .apply()
    }

    /**
     * Wake-yourself-up polling: an alarm every [pollSeconds], no notification, nothing from Google.
     * Enabling alarm polling also disables [reliableDelivery].
     */
    var alarmPolling: Boolean
        get() = prefs.getBoolean(KEY_ALARM, false)
        set(v) {
            prefs.edit().putBoolean(KEY_ALARM, v).apply()
            if (v) {
                prefs.edit().putBoolean(KEY_RELIABLE, false).apply()
            }
        }

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

    /**
     * Registers [onChange] to fire whenever any of [keys] change in this process (e.g. the poll
     * service recording a page). Keep the returned listener and pass it to [unregisterListener];
     * SharedPreferences holds listeners weakly.
     */
    fun registerListener(
        keys: Set<String>,
        onChange: () -> Unit,
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in keys) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun history(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .map { i -> HistoryEntry.fromJson(arr.getJSONObject(i)) }
                .distinctBy { it.id }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addHistory(entries: List<HistoryEntry>) {
        // The same message can be fetched again after the dedupe window has rolled over. Keep
        // history keyed by id: LazyColumn keys must be unique or Compose crashes on open.
        val merged = (entries + history()).distinctBy { it.id }.take(MAX_HISTORY)
        val arr = JSONArray()
        merged.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    /**
     * Collapses rows that describe the same chat message into one. The same message can be
     * delivered over P2P and later arrive through the gateway; both copies carry the same `mid`.
     * Flags are merged so a receipt from either copy is not lost.
     */
    fun collapseDuplicateMids() {
        val items = history()
        val index = HashMap<Pair<String, String>, Int>()
        val out = ArrayList<HistoryEntry>(items.size)
        for (entry in items) {
            val mid = entry.mid
            val from = entry.from
            if (mid.isNullOrBlank() || from.isNullOrBlank()) {
                out.add(entry)
                continue
            }
            val key = mid to from
            val at = index[key]
            if (at == null) {
                index[key] = out.size
                out.add(entry)
                continue
            }
            val kept = out[at]
            out[at] = kept.copy(
                acked = kept.acked || entry.acked,
                remoteDelivered = kept.remoteDelivered || entry.remoteDelivered,
                delivered = kept.delivered || entry.delivered,
                pending = kept.pending && entry.pending,
                imagePath = kept.imagePath ?: entry.imagePath,
                filePath = kept.filePath ?: entry.filePath,
                fileName = kept.fileName ?: entry.fileName,
                fileMime = kept.fileMime ?: entry.fileMime,
                sig = kept.sig ?: entry.sig,
                signatureValid = kept.signatureValid ?: entry.signatureValid,
            )
        }
        if (out.size == items.size) return
        val arr = JSONArray()
        out.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    /** P2P envelopes waiting for a peer, a gateway, or expiry. */
    @Synchronized
    fun p2pQueue(): List<P2PEnvelope> {
        val raw = prefs.getString(KEY_P2P_QUEUE, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .map { i -> p2pEnvelopeFromJson(arr.getJSONObject(i)) }
                .filter { it.id.isNotBlank() && it.expiresAt > System.currentTimeMillis() / 1000L }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun enqueueP2P(envelope: P2PEnvelope) {
        val items = (p2pQueue().filterNot { it.id == envelope.id } + envelope)
            .sortedBy { it.createdAt }
            .takeLast(MAX_P2P_QUEUE)
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_P2P_QUEUE, arr.toString()).apply()
    }

    @Synchronized
    fun removeP2P(id: String) {
        val items = p2pQueue().filterNot { it.id == id }
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_P2P_QUEUE, arr.toString()).apply()
    }

    @Synchronized
    fun markP2PDelivered(id: String) {
        removeP2P(id)
        rewriteHistory {
            if (it.mid == id) it.copy(pending = false, delivered = true) else it
        }
    }

    /**
     * A peer confirmed custody. The envelope stays in the P2P queue (it may still need a gateway),
     * but it is no longer "waiting on this phone" — the clock clears and the bubble shows the
     * underway envelope until a delivery receipt arrives.
     */
    @Synchronized
    fun markP2PCustody(id: String) {
        rewriteHistory {
            if (it.mid == id) it.copy(pending = false, delivered = false) else it
        }
    }

    /** Route hashes of every mailbox this installation owns; safe to keep locally. */
    fun localRouteHashes(): Set<String> = localMailboxIds().map(::p2pRouteHash).toSet()

    fun ownRouteHash(): String = uuid?.takeIf { it.isNotBlank() }?.let(::p2pRouteHash).orEmpty()

    /** Messages built while the gateway was unreachable, waiting for a retry. */
    fun queuedSends(): List<QueuedSend> {
        val raw = prefs.getString(KEY_OUTBOX, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> QueuedSend.fromJson(arr.getJSONObject(i)) }
                .sortedBy { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun enqueueSend(item: QueuedSend) {
        val items = (queuedSends().filterNot { it.id == item.id } + item)
            .sortedBy { it.createdAt }
            .takeLast(MAX_OUTBOX)
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_OUTBOX, arr.toString()).apply()
    }

    fun failQueuedSend(id: String, error: String?) {
        val items = queuedSends().map {
            if (it.id == id) it.copy(attempts = it.attempts + 1, lastError = error) else it
        }
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_OUTBOX, arr.toString()).apply()
    }

    fun completeQueuedSend(id: String) {
        removeQueuedSend(id)
        // The optimistic history row is the same id that was queued; flip it to sent.
        rewriteHistory {
            if (it.id == id) it.copy(pending = false, delivered = true, sendError = null) else it
        }
    }

    fun failQueuedSendPermanent(id: String, error: String?) {
        removeQueuedSend(id)
        rewriteHistory {
            if (it.id == id) it.copy(pending = false, delivered = false, sendError = error) else it
        }
    }

    fun removeQueuedSend(id: String) {
        val items = queuedSends().filterNot { it.id == id }
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_OUTBOX, arr.toString()).apply()
    }

    /** Marks one history entry as acknowledged (does not touch the server). */
    fun markAcked(id: String) {
        rewriteHistory { if (it.id == id) it.copy(acked = true) else it }
    }

    /** Marks the sender's own message as read when a peer's receipt carries its [mid]. */
    fun markReadByMid(mid: String) {
        if (mid.isBlank()) return
        rewriteHistory {
            if (it.mid == mid) {
                it.copy(pending = false, remoteDelivered = true, acked = true, sendError = null)
            } else {
                it
            }
        }
        removeP2P(mid)
    }

    fun markDeliveredByMid(mid: String) {
        if (mid.isBlank()) return
        rewriteHistory {
            if (it.mid == mid) {
                it.copy(pending = false, remoteDelivered = true, sendError = null)
            } else {
                it
            }
        }
        removeP2P(mid)
    }

    /** Attaches a locally downloaded/assembled photo to its history entry. */
    fun attachImage(id: String, path: String) {
        rewriteHistory { if (it.id == id) it.copy(imagePath = path) else it }
    }

    /** Attaches a locally downloaded file (and its metadata) to its history entry. */
    fun attachFile(id: String, path: String, name: String, mime: String) {
        rewriteHistory {
            if (it.id == id) it.copy(filePath = path, fileName = name, fileMime = mime) else it
        }
    }

    /** Moves one history entry into (or out of) the archive. */
    fun setArchived(id: String, archived: Boolean) {
        rewriteHistory { if (it.id == id) it.copy(archived = archived) else it }
    }

    /** Removes one history entry for good. */
    fun deleteEntry(id: String) {
        removeQueuedSend(id)
        rewriteHistory { if (it.id == id) null else it }
    }

    /** Clears a whole conversation's local history for the two sender identities. */
    fun deleteThread(senderId: String, mySenderId: String) {
        rewriteHistory { entry ->
            val theirs = senderId.isNotBlank() && entry.from == senderId
            val mine = mySenderId.isNotBlank() && entry.from == mySenderId
            if (theirs || mine) null else entry
        }
    }

    /**
     * A portable snapshot of the user-visible state. It deliberately excludes device-bound push
     * leases and FCM tokens. The file contains the mailbox bearer UUID and the OpenPGP private key,
     * so the UI must warn the user to keep it private.
     */
    fun exportBackup(): String {
        val json = JSONObject()
        json.put("format", BACKUP_FORMAT)
        json.put("version", BACKUP_VERSION)
        json.put("created_at", System.currentTimeMillis())
        val data = JSONObject()
        // These two values can be defaults that were never written to SharedPreferences; the
        // web handoff needs them explicitly, so always include their effective values.
        data.put(KEY_SERVER, serverUrl)
        uuid?.takeIf { it.isNotBlank() }?.let { data.put(KEY_UUID, it) }
        for (key in BACKUP_KEYS) {
            if (!prefs.contains(key)) continue
            when (val value = prefs.all[key]) {
                is String -> data.put(key, value)
                is Int -> data.put(key, value)
                is Long -> data.put(key, value)
                is Boolean -> data.put(key, value)
                is Float -> data.put(key, value.toDouble())
                is Set<*> -> data.put(key, JSONArray(value.toList()))
            }
        }
        json.put("data", data)
        return json.toString(2)
    }

    /** Replaces the portable state from [exportBackup]. Device push/FCM state is reset. */
    fun importBackup(text: String): Result<Unit> = runCatching {
        val root = JSONObject(text)
        require(root.optString("format") == BACKUP_FORMAT) { "Not a PagerAPP backup" }
        require(root.optInt("version", 0) == BACKUP_VERSION) { "Unsupported backup version" }
        val data = root.optJSONObject("data") ?: error("Backup has no data")

        val edit = prefs.edit()
        for (key in BACKUP_KEYS) edit.remove(key)
        // These belong to this installation, not to the backup.
        edit.remove(KEY_PUSH_ID)
        edit.remove(KEY_PUSH_EXPIRES)
        edit.remove(KEY_PUSH_ENDPOINT)
        edit.remove(KEY_FCM_TOKEN)
        edit.remove(KEY_FCM_LEASES)
        edit.remove(KEY_PUSH_IDENTITY)
        edit.remove(KEY_PUSH_IDENTITY_PERIOD)
        edit.remove(KEY_SERVER_CLOCK_OFFSET)
        edit.remove(KEY_SETTINGS_CHECKED)
        edit.remove(KEY_FCM_CHECKED)
        edit.remove(KEY_PENDING_CONTACT)
        edit.remove(KEY_WEB_SESSION_EXPIRES)

        for (key in BACKUP_KEYS) {
            if (!data.has(key)) continue
            when (val value = data.get(key)) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Double -> edit.putFloat(key, value.toFloat())
                is JSONArray -> {
                    val values = (0 until value.length()).map { value.optString(it) }.toSet()
                    edit.putStringSet(key, values)
                }
                else -> Unit
            }
        }
        check(edit.commit()) { "Could not write the restored settings" }
    }

    private fun rewriteHistory(transform: (HistoryEntry) -> HistoryEntry?) {
        val arr = JSONArray()
        history().mapNotNull(transform).forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    companion object {
        const val DEFAULT_SERVER = "https://pager.ofloo.io"
        const val DEFAULT_PHOTO_SHARE = "https://share.h4ck.io"
        const val DEFAULT_POLL_SECONDS = 60
        const val MIN_POLL = 30
        const val MAX_POLL = 300
        private const val MAX_SEEN = 1000
        private const val MAX_HISTORY = 200

        private const val KEY_SERVER = "server_url"
        private const val KEY_UUID = "pager_uuid"
        private const val KEY_NAME = "display_name"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_PENDING_CONTACT = "pending_contact"
        private const val KEY_PUB = "pgp_public"
        private const val KEY_SEC = "pgp_secret"
        private const val KEY_FPR = "pgp_fingerprint"
        private const val KEY_EXP = "pgp_expires_at"
        private const val KEY_EXP_MONTHS = "pgp_expiry_months"
        private const val KEY_ALT_SERVER = "alternate_server"
        private const val KEY_PHOTO_SHARE = "photo_share_url"
        private const val KEY_PUSH_ENABLED = "push_enabled"
        private const val KEY_PUSH_ID = "push_id"
        private const val KEY_PUSH_EXPIRES = "push_expires"
        private const val KEY_PUSH_ENDPOINT = "push_endpoint"
        private const val KEY_PUSH_CHANNEL = "push_channel"
        private const val KEY_FCM = "fcm_enabled"
        private const val KEY_FCM_CHECKED = "fcm_checked_at"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_P2P = "p2p_enabled"
        private const val KEY_P2P_SERVER = "p2p_server_enabled"
        private const val KEY_P2P_MAX_AGE = "p2p_max_age_seconds"
        private const val KEY_P2P_GPG = "p2p_gpg_enabled"
        private const val KEY_P2P_PUBLIC_KEY = "p2p_public_key"
        private const val KEY_P2P_KEY_ID = "p2p_key_id"
        private const val KEY_FCM_LEASES = "fcm_leases"
        private const val KEY_PUSH_IDENTITY = "push_identity"
        private const val KEY_PUSH_IDENTITY_PERIOD = "push_identity_period_seconds"
        private const val KEY_SERVER_CLOCK_OFFSET = "server_clock_offset_seconds"
        private const val KEY_SETTINGS_CHECKED = "settings_checked_at"
        private const val KEY_WEB_SESSION_EXPIRES = "web_session_expires"
        private const val KEY_BITS = "pgp_bits"

        /** 8192 by request; generating that on a phone can take minutes, so it is changeable. */
        private const val DEFAULT_KEY_BITS_PREF = 8192
        private const val KEY_CONTACTS = "contacts"
        private const val KEY_PAGE_MAILBOXES = "page_mailboxes"
        private const val KEY_POLL = "poll_seconds"
        private const val KEY_CONFIG = "config"
        private const val KEY_BYPASS = "bypass"
        private const val KEY_SILENCE = "silence"
        private const val KEY_RELIABLE = "reliable_delivery"
        private const val KEY_ALARM = "alarm_polling"
        private const val KEY_SEEN = "seen_ids"
        private const val KEY_HISTORY = "history"
        private const val KEY_OUTBOX = "outbox"
        private const val KEY_P2P_QUEUE = "p2p_queue"
        private const val MAX_OUTBOX = 200
        private const val MAX_P2P_QUEUE = 5000

        private const val BACKUP_FORMAT = "pagerapp-backup"
        private const val BACKUP_VERSION = 1
        private val BACKUP_KEYS = listOf(
            KEY_SERVER, KEY_UUID, KEY_NAME, KEY_THEME, KEY_LANGUAGE, KEY_ALT_SERVER, KEY_PHOTO_SHARE,
            KEY_PUB, KEY_SEC, KEY_FPR, KEY_EXP, KEY_EXP_MONTHS, KEY_BITS,
            KEY_CONTACTS, KEY_PAGE_MAILBOXES, KEY_POLL, KEY_CONFIG, KEY_BYPASS, KEY_SILENCE,
            KEY_RELIABLE, KEY_ALARM, KEY_PUSH_ENABLED, KEY_FCM, KEY_P2P, KEY_SEEN, KEY_HISTORY,
        )

        /** Preference keys whose change should refresh the Pages list. */
        val HISTORY_KEYS = setOf(KEY_HISTORY, KEY_SEEN)

        /** Preference keys whose change should refresh the Contacts list. */
        val CONTACT_KEYS = setOf(KEY_CONTACTS)
    }
}

/** An outgoing page held locally until the PagerMCP gateway answers again. */
data class QueuedSend(
    val id: String,
    val historyId: String,
    val serverUrl: String,
    val mailbox: String,
    val payloadJson: String,
    val createdAt: Double,
    val attempts: Int = 0,
    val lastError: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("history_id", historyId)
        .put("server", serverUrl)
        .put("mailbox", mailbox)
        .put("payload", payloadJson)
        .put("created_at", createdAt)
        .put("attempts", attempts)
        .put("last_error", lastError)

    companion object {
        fun fromJson(o: JSONObject) = QueuedSend(
            id = o.optString("id"),
            historyId = o.optString("history_id"),
            serverUrl = o.optString("server"),
            mailbox = o.optString("mailbox"),
            payloadJson = o.optString("payload"),
            createdAt = o.optDouble("created_at", 0.0),
            attempts = o.optInt("attempts", 0),
            lastError = o.optString("last_error").takeIf { it.isNotEmpty() && it != "null" },
        )
    }
}

/** One row in the in-app history list. */
data class HistoryEntry(
    val id: String,
    val status: Int,
    val prio: String? = null,
    val jobId: String?,
    val project: String?,
    val message: String?,
    val logs: String? = null,
    val replyTo: String? = null,
    val replyPreview: String? = null,
    val replyFrom: String? = null,
    val replyFromName: String? = null,
    /** True once a delivery receipt for this outgoing mid arrived from the peer's phone. */
    val remoteDelivered: Boolean = false,
    /** Detached signature over the plaintext; null for legacy unsigned messages. */
    val sig: String? = null,
    /** True/false when a signature was present and checked, null when unsigned. */
    val signatureValid: Boolean? = null,
    val createdAt: Double,
    val ackUuid: String?,
    val replyUuid: String? = null,
    val ackToken: String? = null,
    val mailbox: String? = null,
    val serverUrl: String? = null,
    /** Local JPEG for a chat photo (direct chunks or a downloaded share link). */
    val imagePath: String? = null,
    /** Client-side id of a chat message, used for read receipts. */
    val mid: String? = null,
    /** Local file for a shared attachment and its original name/mime. */
    val filePath: String? = null,
    val fileName: String? = null,
    val fileMime: String? = null,
    /** When this device received the page (Unix seconds); null for messages we sent. */
    val receivedAt: Double? = null,
    /** Key size of the OpenPGP key that protected the body, when known. */
    val keyBits: Int? = null,
    val delivered: Boolean,
    /** True while an outgoing message is waiting for the gateway. */
    val pending: Boolean = false,
    /** Set when the gateway permanently rejected a queued message. */
    val sendError: String? = null,
    val from: String? = null,
    val fromName: String? = null,
    val acked: Boolean = false,
    val archived: Boolean = false,
    /** True when this message arrived encrypted (or was sent encrypted) — shown as a padlock. */
    val encrypted: Boolean = false,
    /** True for a protocol notice ("learned their key", …) — shown centred, not as a bubble. */
    val notice: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("status", status)
        .put("prio", prio)
        .put("job", jobId)
        .put("project", project)
        .put("message", message)
        .put("logs", logs)
        .put("reply_to", replyTo)
        .put("reply_preview", replyPreview)
        .put("reply_from", replyFrom)
        .put("reply_from_name", replyFromName)
        .put("remote_delivered", remoteDelivered)
        .put("sig", sig)
        .put("signature_valid", signatureValid)
        .put("created_at", createdAt)
        .put("ack_uuid", ackUuid)
        .put("reply_uuid", replyUuid)
        .put("ack_token", ackToken)
        .put("mailbox", mailbox)
        .put("server", serverUrl)
        .put("image_path", imagePath)
        .put("mid", mid)
        .put("file_path", filePath)
        .put("file_name", fileName)
        .put("file_mime", fileMime)
        .put("received_at", receivedAt)
        .put("key_bits", keyBits)
        .put("delivered", delivered)
        .put("pending", pending)
        .put("send_error", sendError)
        .put("from", from)
        .put("from_name", fromName)
        .put("acked", acked)
        .put("archived", archived)
        .put("encrypted", encrypted)
        .put("notice", notice)

    companion object {
        fun fromJson(o: JSONObject) = HistoryEntry(
            id = o.optString("id"),
            status = o.optInt("status"),
            prio = o.optString("prio").takeIf { it.isNotEmpty() && it != "null" },
            jobId = o.optString("job").takeIf { it.isNotEmpty() && it != "null" },
            project = o.optString("project").takeIf { it.isNotEmpty() && it != "null" },
            message = o.optString("message").takeIf { it.isNotEmpty() && it != "null" },
            logs = o.optString("logs").takeIf { it.isNotEmpty() && it != "null" },
            replyTo = o.optString("reply_to").takeIf { it.isNotEmpty() && it != "null" },
            replyPreview = o.optString("reply_preview").takeIf { it.isNotEmpty() && it != "null" },
            replyFrom = o.optString("reply_from").takeIf { it.isNotEmpty() && it != "null" },
            replyFromName = o.optString("reply_from_name").takeIf { it.isNotEmpty() && it != "null" },
            remoteDelivered = o.optBoolean("remote_delivered", false),
            sig = o.optString("sig").takeIf { it.isNotEmpty() && it != "null" },
            signatureValid = if (o.has("signature_valid")) o.optBoolean("signature_valid") else null,
            createdAt = o.optDouble("created_at", 0.0),
            ackUuid = o.optString("ack_uuid").takeIf { it.isNotEmpty() && it != "null" },
            replyUuid = o.optString("reply_uuid").takeIf { it.isNotEmpty() && it != "null" },
            ackToken = o.optString("ack_token").takeIf { it.isNotEmpty() && it != "null" },
            mailbox = o.optString("mailbox").takeIf { it.isNotEmpty() && it != "null" },
            serverUrl = o.optString("server").takeIf { it.isNotEmpty() && it != "null" },
            imagePath = o.optString("image_path").takeIf { it.isNotEmpty() && it != "null" },
            mid = o.optString("mid").takeIf { it.isNotEmpty() && it != "null" },
            filePath = o.optString("file_path").takeIf { it.isNotEmpty() && it != "null" },
            fileName = o.optString("file_name").takeIf { it.isNotEmpty() && it != "null" },
            fileMime = o.optString("file_mime").takeIf { it.isNotEmpty() && it != "null" },
            receivedAt = o.optDouble("received_at", 0.0).takeIf { it > 0.0 },
            keyBits = o.optInt("key_bits", 0).takeIf { it > 0 },
            delivered = o.optBoolean("delivered", false),
            pending = o.optBoolean("pending", false),
            sendError = o.optString("send_error").takeIf { it.isNotEmpty() && it != "null" },
            from = o.optString("from").takeIf { it.isNotEmpty() && it != "null" },
            fromName = o.optString("from_name").takeIf { it.isNotEmpty() && it != "null" },
            acked = o.optBoolean("acked", false),
            archived = o.optBoolean("archived", false),
            encrypted = o.optBoolean("encrypted", false),
            notice = o.optBoolean("notice", false),
        )

        fun from(
            page: Page,
            delivered: Boolean,
            mailbox: String? = null,
            serverUrl: String? = null,
            imagePath: String? = null,
            mid: String? = null,
            filePath: String? = null,
            fileName: String? = null,
            fileMime: String? = null,
            receivedAt: Double? = null,
            keyBits: Int? = null,
            signatureValid: Boolean? = null,
        ) = HistoryEntry(
            id = page.id,
            status = page.status,
            prio = page.prio,
            jobId = page.jobId,
            project = page.project,
            message = page.message,
            logs = page.logs,
            replyTo = page.replyTo,
            replyPreview = page.replyPreview,
            replyFrom = page.replyFrom,
            replyFromName = page.replyFromName,
            sig = page.sig,
            createdAt = when {
                page.sentAt > 0.0 -> page.sentAt
                page.createdAt > 0.0 -> page.createdAt
                else -> Instant.now().epochSecond.toDouble()
            },
            ackUuid = page.ackUuid,
            replyUuid = page.replyUuid,
            ackToken = page.ackToken,
            mailbox = mailbox,
            serverUrl = serverUrl,
            imagePath = imagePath,
            mid = mid ?: page.mid,
            filePath = filePath,
            fileName = fileName,
            fileMime = fileMime,
            receivedAt = receivedAt,
            keyBits = keyBits,
            signatureValid = signatureValid,
            delivered = delivered,
            from = page.from,
            fromName = page.fromName,
            encrypted = !page.enc.isNullOrBlank(),
        )
    }
}
