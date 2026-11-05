package io.ofloo.pagerapp.shared

import java.util.zip.CRC32

/**
 * Transport-neutral P2P envelope. The payload is the already-built PagerMCP chat/page payload
 * JSON; a transport only moves these bytes and never needs to inspect them.
 *
 * [to] is a public routing id (normally a public-key fingerprint), never a PagerMCP mailbox
 * UUID/bearer. [expiresAt] is an absolute Unix timestamp in seconds.
 */
data class P2PEnvelope(
    val id: String,
    val from: String,
    val to: String,
    val createdAt: Long,
    val expiresAt: Long,
    val payload: String,
    val signature: String? = null,
    val hops: Int = 0,
    /**
     * The destination UUID, OpenPGP-encrypted to the PagerMCP server's public key. Relays can
     * upload the envelope without ever seeing which mailbox it is for; only the owning server
     * decrypts it. Null on servers without the GPG P2P path.
     */
    val mailboxEncrypted: String? = null,
    /** Which server key was used, so the owning server can pick the right private key. */
    val keyId: String? = null,
) {
    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000L): Boolean =
        expiresAt <= nowSeconds

    fun payloadSha256(): String = sha256Hex(payload)
}

/** An envelope is delivered locally when its routing id belongs to this installation. */
fun isLocalP2PTarget(to: String, localRoutingIds: Set<String>): Boolean =
    to.isNotBlank() && to in localRoutingIds

/** What the receiver still needs after comparing two compact queue-id sets. */
data class P2PSyncPlan(val want: List<String>, val have: List<String>) {
    companion object {
        fun between(localIds: Collection<String>, remoteIds: Collection<String>): P2PSyncPlan {
            val local = localIds.toSet()
            val remote = remoteIds.toSet()
            return P2PSyncPlan(
                want = remote.filterNot(local::contains).distinct().sorted(),
                have = local.filterNot(remote::contains).distinct().sorted(),
            )
        }
    }
}

/** One fragment of an envelope. [crc32] covers exactly [data]. */
data class P2PChunk(
    val transferId: String,
    val seq: Int,
    val total: Int,
    val data: ByteArray,
    val crc32: Long,
)

/** Splits a payload into transport-sized chunks with per-chunk CRCs. */
object P2PChunker {
    fun split(transferId: String, payload: ByteArray, chunkSize: Int): List<P2PChunk> {
        require(chunkSize > 0) { "chunkSize must be positive" }
        if (payload.isEmpty()) return emptyList()
        val total = (payload.size + chunkSize - 1) / chunkSize
        return (0 until total).map { seq ->
            val start = seq * chunkSize
            val end = minOf(start + chunkSize, payload.size)
            val data = payload.copyOfRange(start, end)
            P2PChunk(transferId, seq, total, data, crc32(data))
        }
    }

    fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value
}

/**
 * Reassembles a transfer without trusting the transport. Chunks may arrive out of order;
 * duplicate chunks are ignored. The SHA-256 of the complete payload must match [expectedSha256]
 * before the transfer is considered stored.
 */
class P2PAssembler(
    val transferId: String,
    val total: Int,
    private val expectedSha256: String,
) {
    private val chunks = arrayOfNulls<ByteArray>(total)
    private var received = 0

    val isComplete: Boolean get() = received == total

    /** Returns true when a new, valid chunk was accepted. */
    fun accept(chunk: P2PChunk): Boolean {
        if (chunk.transferId != transferId) return false
        if (chunk.total != total) return false
        if (chunk.seq !in 0 until total) return false
        if (chunk.crc32 != P2PChunker.crc32(chunk.data)) return false
        if (chunks[chunk.seq] != null) return true // duplicate is idempotent
        chunks[chunk.seq] = chunk.data
        received++
        return true
    }

    /** Returns the verified bytes, or null when incomplete/corrupt/wrong hash. */
    fun finish(): ByteArray? {
        if (!isComplete) return null
        val bytes = chunks.fold(ByteArray(0)) { acc, part -> acc + (part ?: return null) }
        return bytes.takeIf { sha256Hex(it) == expectedSha256 }
    }
}

/** Acknowledgement returned only after the receiver has persisted and verified the envelope. */
data class P2PAck(
    val transferId: String,
    val envelopeId: String,
    val sha256: String,
    val stored: Boolean,
)

fun P2PAck.matches(envelope: P2PEnvelope): Boolean =
    stored && envelopeId == envelope.id && sha256 == envelope.payloadSha256()
