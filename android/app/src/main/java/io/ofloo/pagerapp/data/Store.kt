package io.ofloo.pagerapp.data

import android.content.Context
import android.content.SharedPreferences
import io.ofloo.pagerapp.ThemeMode
import io.ofloo.pagerapp.shared.Contact
import io.ofloo.pagerapp.shared.ContactKind
import io.ofloo.pagerapp.shared.LEVEL_NORMAL
import io.ofloo.pagerapp.shared.Pgp
import io.ofloo.pagerapp.shared.Page
import io.ofloo.pagerapp.shared.PagerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class FcmLease(
    val serverUrl: String,
    val mailbox: String,
    val subscriptionId: String,
    val expiresAt: Long,
)

/** An extra PagerMCP mailbox used as a page address, e.g. one per logging source. */
data class PageMailbox(
    val name: String,
    val uuid: String,
    val serverUrl: String,
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

    /** Wake-ups through Google's push service instead of our own connection. Off unless asked. */
    var fcmEnabled: Boolean
        get() = prefs.getBoolean(KEY_FCM, false)
        set(v) = prefs.edit().putBoolean(KEY_FCM, v).apply()

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
                else FcmLease(server, mailbox, id, item.optLong("expires"))
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
                        .put("expires", lease.expiresAt),
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

    private fun savePageMailboxes(list: List<PageMailbox>) {
        val arr = JSONArray()
        list.forEach { mailbox ->
            arr.put(
                JSONObject()
                    .put("name", mailbox.name)
                    .put("uuid", mailbox.uuid)
                    .put("server", mailbox.serverUrl),
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
     * Mutually exclusive with [reliableDelivery] — turning one on turns the other off.
     */
    var alarmPolling: Boolean
        get() = prefs.getBoolean(KEY_ALARM, false)
        set(v) = prefs.edit().putBoolean(KEY_ALARM, v).apply()

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

    /** Marks one history entry as acknowledged (does not touch the server). */
    fun markAcked(id: String) {
        rewriteHistory { if (it.id == id) it.copy(acked = true) else it }
    }

    /** Marks the sender's own message as read when a peer's receipt carries its [mid]. */
    fun markReadByMid(mid: String) {
        if (mid.isBlank()) return
        rewriteHistory {
            if (it.mid == mid && !it.acked) it.copy(acked = true) else it
        }
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
        rewriteHistory { if (it.id == id) null else it }
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
        private const val KEY_FCM_LEASES = "fcm_leases"
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

        /** Preference keys whose change should refresh the Pages list. */
        val HISTORY_KEYS = setOf(KEY_HISTORY, KEY_SEEN)

        /** Preference keys whose change should refresh the Contacts list. */
        val CONTACT_KEYS = setOf(KEY_CONTACTS)
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
        ) = HistoryEntry(
            id = page.id,
            status = page.status,
            prio = page.prio,
            jobId = page.jobId,
            project = page.project,
            message = page.message,
            createdAt = if (page.createdAt > 0.0) page.createdAt else Instant.now().epochSecond.toDouble(),
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
            delivered = delivered,
            from = page.from,
            fromName = page.fromName,
            encrypted = !page.enc.isNullOrBlank(),
        )
    }
}
