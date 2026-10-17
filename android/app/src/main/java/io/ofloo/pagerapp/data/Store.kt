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
     * True = foreground service (poll every 30s–5min, one low-importance notification).
     * False = background work (no notification, Android's 15-minute floor).
     */
    var reliableDelivery: Boolean
        get() = prefs.getBoolean(KEY_RELIABLE, false)
        set(v) = prefs.edit().putBoolean(KEY_RELIABLE, v).apply()

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

    /** Marks one history entry as acknowledged (does not touch the server). */
    fun markAcked(id: String) {
        rewriteHistory { if (it.id == id) it.copy(acked = true) else it }
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
        private const val KEY_BITS = "pgp_bits"

        /** 8192 by request; generating that on a phone can take minutes, so it is changeable. */
        private const val DEFAULT_KEY_BITS_PREF = 8192
        private const val KEY_CONTACTS = "contacts"
        private const val KEY_POLL = "poll_seconds"
        private const val KEY_CONFIG = "config"
        private const val KEY_BYPASS = "bypass"
        private const val KEY_SILENCE = "silence"
        private const val KEY_RELIABLE = "reliable_delivery"
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
    val jobId: String?,
    val project: String?,
    val message: String?,
    val createdAt: Double,
    val ackUuid: String?,
    val delivered: Boolean,
    val from: String? = null,
    val fromName: String? = null,
    val acked: Boolean = false,
    val archived: Boolean = false,
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
        .put("from", from)
        .put("from_name", fromName)
        .put("acked", acked)
        .put("archived", archived)

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
            from = o.optString("from").takeIf { it.isNotEmpty() && it != "null" },
            fromName = o.optString("from_name").takeIf { it.isNotEmpty() && it != "null" },
            acked = o.optBoolean("acked", false),
            archived = o.optBoolean("archived", false),
        )

        fun from(page: Page, delivered: Boolean) = HistoryEntry(
            id = page.id,
            status = page.status,
            jobId = page.jobId,
            project = page.project,
            message = page.message,
            createdAt = if (page.createdAt > 0.0) page.createdAt else Instant.now().epochSecond.toDouble(),
            ackUuid = page.ackUuid,
            delivered = delivered,
            from = page.from,
            fromName = page.fromName,
        )
    }
}
