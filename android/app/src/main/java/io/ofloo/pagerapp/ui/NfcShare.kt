package io.ofloo.pagerapp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.io.ByteArrayOutputStream

/** Finds the Activity that owns reader mode from a (possibly wrapped) Context. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * NFC transport for contact invitations: write the same `pagerapp://contact?…` payload the QR
 * carries onto a tag, or read one back.
 *
 * A plain NDEF Text record is used, so any NFC app can read the tag and the payload stays
 * interchangeable with the QR code.
 */
object NfcShare {

    /** Private MIME type so Android offers PagerAPP (and only us) for our tags. */
    const val MIME = "application/io.ofloo.pagerapp.contact"

    fun isAvailable(context: Context): Boolean =
        runCatching { NfcAdapter.getDefaultAdapter(context)?.isEnabled == true }.getOrDefault(false)

    /**
     * Starts reader mode. [onTag] runs on a binder thread and returns true to consume the tag
     * (which also stops the session), false to keep listening.
     */
    fun startReader(activity: Activity, onTag: (Tag) -> Boolean): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
        if (!adapter.isEnabled) return false
        adapter.enableReaderMode(
            activity,
            { tag ->
                if (onTag(tag)) activity.runOnUiThread { stopReader(activity) }
            },
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )
        return true
    }

    fun stopReader(activity: Activity) {
        runCatching { NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity) }
    }

    /** Reads a tag written by [writeTag]. Returns null for a foreign or empty tag. */
    fun readText(tag: Tag): String? = runCatching {
        val message = Ndef.get(tag)?.let { ndef ->
            ndef.connect()
            try {
                ndef.ndefMessage
            } finally {
                runCatching { ndef.close() }
            }
        } ?: return null
        textFrom(message)
    }.getOrNull()

    /** Pulls our payload out of an NDEF message (MIME record first, then plain text). */
    fun textFrom(message: NdefMessage): String? {
        // Our own record type.
        message.records.firstOrNull { record ->
            record.tnf == NdefRecord.TNF_MIME_MEDIA &&
                String(record.type, Charsets.US_ASCII).equals(MIME, ignoreCase = true)
        }?.let { return String(it.payload, Charsets.UTF_8) }

        // Tags written by older builds (plain text record).
        return message.records.firstNotNullOfOrNull { record ->
            if (record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals(NdefRecord.RTD_TEXT)
            ) decodeText(record.payload) else null
        }
    }

    /** Writes [text] as our MIME record. Returns a failure reason, or null on success. */
    fun writeTag(tag: Tag, text: String): String? {
        val record = NdefRecord(
            NdefRecord.TNF_MIME_MEDIA,
            MIME.toByteArray(Charsets.US_ASCII),
            ByteArray(0),
            text.toByteArray(Charsets.UTF_8),
        )
        val message = NdefMessage(arrayOf(record))

        Ndef.get(tag)?.let { ndef ->
            return runCatching {
                ndef.connect()
                try {
                    if (!ndef.isWritable) return "That tag is read-only."
                    if (ndef.maxSize < message.toByteArray().size) return "That tag is too small."
                    ndef.writeNdefMessage(message)
                    null
                } finally {
                    runCatching { ndef.close() }
                }
            }.getOrElse { "Write failed: ${it.message}" }
        }

        val formatable = NdefFormatable.get(tag) ?: return "That tag is not NDEF-compatible."
        return runCatching {
            formatable.connect()
            try {
                formatable.format(message)
                null
            } finally {
                runCatching { formatable.close() }
            }
        }.getOrElse { "Format/write failed: ${it.message}" }
    }

    // --- NDEF text record codec (status byte + language code + UTF-8 text) ---

    private fun encodeText(text: String): ByteArray {
        val lang = "en".toByteArray(Charsets.US_ASCII)
        val body = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(0x02 or (lang.size and 0x3F))   // UTF-8, language-code length
        out.write(lang)
        out.write(body)
        return out.toByteArray()
    }

    private fun decodeText(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val langLength = payload[0].toInt() and 0x3F
        if (1 + langLength > payload.size) return null
        return String(payload, 1 + langLength, payload.size - 1 - langLength, Charsets.UTF_8)
    }
}
