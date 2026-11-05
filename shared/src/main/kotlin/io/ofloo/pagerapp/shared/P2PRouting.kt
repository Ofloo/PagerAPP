package io.ofloo.pagerapp.shared

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A stable route hash for a mailbox. The raw mailbox UUID/bearer is never broadcast. */
fun p2pRouteHash(mailboxUuid: String): String =
    sha256Hex(mailboxUuid.trim().lowercase())

/**
 * Privacy-preserving route hash for a contact/epoch. Only peers that share [secret] can
 * recognise the tag; the raw UUID still never leaves the device.
 */
fun p2pRouteHash(mailboxUuid: String, secret: String, epoch: Long): String {
    require(secret.isNotEmpty()) { "secret must not be empty" }
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val digest = mac.doFinal("$epoch|${mailboxUuid.trim().lowercase()}".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/** A peer endpoint is a transport address: BLE device id, Wi-Fi host:port, ... */
data class P2PNeighbor(
    val endpoint: String,
    val seenAt: Long,
    val expiresAt: Long,
)

/** One ARP-like request/reply pair. The reply is only trusted after authentication. */
data class P2PWhoHas(
    val requestId: String,
    val routeHash: String,
)

data class P2PIsAt(
    val requestId: String,
    val routeHash: String,
    val endpoint: String,
    val proof: String? = null,
)

sealed interface P2PRoute {
    data object Local : P2PRoute
    data class Peer(val endpoint: String) : P2PRoute
    data object Lookup : P2PRoute
    data object Gateway : P2PRoute
    data object Queue : P2PRoute
}

/**
 * Small application-ARP table. Entries are intentionally short-lived; a negative entry prevents
 * spamming WHO_HAS on every message, but allows a later retry.
 */
class P2PNeighborTable(
    private val ttlSeconds: Long = 300L,
    private val negativeTtlSeconds: Long = 30L,
) {
    private val neighbors = mutableMapOf<String, P2PNeighbor>()
    private val missingUntil = mutableMapOf<String, Long>()

    @Synchronized
    fun record(routeHash: String, endpoint: String, now: Long = nowSeconds()) {
        if (routeHash.isBlank() || endpoint.isBlank()) return
        neighbors[routeHash] = P2PNeighbor(endpoint, now, now + ttlSeconds)
        missingUntil.remove(routeHash)
    }

    @Synchronized
    fun markMissing(routeHash: String, now: Long = nowSeconds()) {
        if (routeHash.isBlank()) return
        neighbors.remove(routeHash)
        missingUntil[routeHash] = now + negativeTtlSeconds
    }

    @Synchronized
    fun lookup(routeHash: String, now: Long = nowSeconds()): String? {
        val entry = neighbors[routeHash] ?: return null
        if (entry.expiresAt <= now) {
            neighbors.remove(routeHash)
            return null
        }
        return entry.endpoint
    }

    @Synchronized
    fun wasRecentlyMissing(routeHash: String, now: Long = nowSeconds()): Boolean {
        val until = missingUntil[routeHash] ?: return false
        if (until <= now) {
            missingUntil.remove(routeHash)
            return false
        }
        return true
    }

    @Synchronized
    fun expire(now: Long = nowSeconds()) {
        neighbors.entries.removeIf { it.value.expiresAt <= now }
        missingUntil.entries.removeIf { it.value <= now }
    }

    /**
     * Routing precedence: local target, known peer, then (and only then) gateway. Until a WHO_HAS
     * has timed out, [P2PRoute.Lookup] is returned so the caller can ask peers first.
     */
    @Synchronized
    fun route(
        routeHash: String,
        localHashes: Set<String>,
        gatewayAvailable: Boolean,
        now: Long = nowSeconds(),
    ): P2PRoute {
        if (routeHash in localHashes) return P2PRoute.Local
        lookup(routeHash, now)?.let { return P2PRoute.Peer(it) }
        if (wasRecentlyMissing(routeHash, now)) {
            return if (gatewayAvailable) P2PRoute.Gateway else P2PRoute.Queue
        }
        return P2PRoute.Lookup
    }

    companion object {
        fun nowSeconds(): Long = System.currentTimeMillis() / 1000L
    }
}
