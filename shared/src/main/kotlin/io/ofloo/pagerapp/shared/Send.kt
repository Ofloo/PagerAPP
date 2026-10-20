package io.ofloo.pagerapp.shared

/** Numeric status used for a plain chat message between contacts (custom range, silent). */
const val STATUS_MESSAGE = 10

/** Direct chat photos are capped at this many JPEG bytes; larger ones use the share-link path. */
const val PHOTO_MAX_BYTES = 1_000_000

/** One PagerMCP message can carry this many armored characters (64 KiB body with JSON overhead). */
const val PHOTO_CHUNK_CHARS = 30_000

/** A photo split into chat messages, plus the id and hash needed to reassemble it. */
data class OutgoingPhoto(
    val imageId: String,
    val sha256: String,
    val chunks: List<Map<String, Any?>>,
)

/**
 * Encrypts [imageJpeg] to the peer and splits the armored ciphertext into numbered chat pages.
 * Each page stays well under PagerMCP's 64 KiB body limit. Returns null when the peer's key is
 * unknown, the image is empty, or it exceeds [PHOTO_MAX_BYTES].
 */
fun buildOutgoingPhotoChunks(
    to: Contact,
    ownName: String?,
    imageJpeg: ByteArray,
    imageId: String,
    myPublicArmored: String? = null,
): OutgoingPhoto? {
    if (imageJpeg.isEmpty() || imageJpeg.size > PHOTO_MAX_BYTES) return null
    if (to.publicKey.isBlank()) return null
    val armor = Pgp.encryptBytes(imageJpeg, to.publicKey)
    val pieces = armor.chunked(PHOTO_CHUNK_CHARS)
    val sha = sha256Hex(armor)
    val chunks = pieces.mapIndexed { index, piece ->
        linkedMapOf<String, Any?>(
            "status" to STATUS_MESSAGE,
            "from" to to.mySenderId,
            "from_name" to ownName,
            "image_id" to imageId,
            "image_part" to index + 1,
            "image_total" to pieces.size,
            "image_chunk" to piece,
            "image_sha256" to sha,
        ).apply {
            if (index == 0 && !myPublicArmored.isNullOrBlank()) this["pub"] = myPublicArmored
        }
    }
    return OutgoingPhoto(imageId, sha, chunks)
}

/** Lower-case SHA-256 of [bytes] or of the UTF-8 encoding of [text]. */
fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

/**
 * Builds the page body for a message sent to the channel in [to].
 *
 * `from` carries this device's per-channel [Contact.mySenderId] — never a mailbox UUID — so the
 * peer can tell the two sides apart without learning anything about your private mailbox.
 *
 * [myPublicArmored] is attached to **every** message (as `pub`), so the peer picks the key up
 * automatically on the first message and again whenever it is rotated. When the peer's key is
 * already known the body goes out encrypted as `enc`; until then it travels as plain `message`
 * (the peer can answer encrypted, having just learned our key).
 */
fun buildOutgoing(
    to: Contact,
    ownName: String?,
    message: String,
    myPublicArmored: String? = null,
    status: Int = STATUS_MESSAGE,
    mid: String? = null,
): Map<String, Any?> = linkedMapOf<String, Any?>("status" to status).apply {
    if (to.mySenderId.isNotBlank()) this["from"] = to.mySenderId
    if (!ownName.isNullOrBlank()) this["from_name"] = ownName
    if (!mid.isNullOrBlank()) this["mid"] = mid
    if (!myPublicArmored.isNullOrBlank()) this["pub"] = myPublicArmored

    if (to.publicKey.isNotBlank()) {
        this["enc"] = Pgp.encrypt(message, to.publicKey)
    } else {
        this["message"] = message
    }
}

/**
 * A receipt-only page: "I have read your message [mid]". It carries no body, so the peer's app
 * can turn ✓ into ✓✓ without treating it as a new message.
 */
fun buildReadReceipt(
    to: Contact,
    mid: String,
    ownName: String? = null,
    status: Int = STATUS_MESSAGE,
): Map<String, Any?> = linkedMapOf<String, Any?>(
    "status" to status,
    "read" to mid,
).apply {
    if (to.mySenderId.isNotBlank()) this["from"] = to.mySenderId
    if (!ownName.isNullOrBlank()) this["from_name"] = ownName
}

/** The peer's public key carried by [page], when it looks like an OpenPGP block. */
fun publicKeyFrom(page: Page): String? =
    page.pub?.takeIf { it.contains("BEGIN PGP PUBLIC KEY") }

/** The readable text of a page: decrypts `enc` when present, else falls back to `message`. */
fun readableText(page: Page, mySecretArmored: String?): String? {
    val enc = page.enc
    if (enc.isNullOrBlank()) return page.message
    if (mySecretArmored.isNullOrBlank()) return null
    return runCatching { Pgp.decrypt(enc, mySecretArmored) }.getOrNull()
}

/** Pages the peer sent on a channel: anything not stamped with our own sender id. */
fun peerMessages(contact: Contact, pages: List<Page>): List<Page> =
    pages.filter { it.from != contact.mySenderId }.sortedBy { it.createdAt }

/** Pages this device sent on a channel. */
fun ownMessages(contact: Contact, pages: List<Page>): List<Page> =
    pages.filter { it.from?.isNotBlank() == true && it.from == contact.mySenderId }
        .sortedBy { it.createdAt }

/**
 * Whether this device should acknowledge [page] in a shared channel.
 *
 * Both sides read the same mailbox, so only the receiver may ack: acking our own message would
 * delete it before the peer ever sees it.
 */
fun shouldAck(contact: Contact, page: Page): Boolean =
    page.from != contact.mySenderId

/** Resolves which channel a page belongs to, by its `from` id. */
fun contactFor(page: Page, contacts: List<Contact>): Contact? =
    page.from?.let { from -> contacts.firstOrNull { it.senderId.isNotBlank() && it.senderId == from } }
