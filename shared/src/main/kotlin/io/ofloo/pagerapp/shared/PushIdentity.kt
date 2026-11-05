package io.ofloo.pagerapp.shared

/**
 * Identity used when registering a push subscription.
 *
 * PagerMCP may stop storing the mailbox UUID in its push bookkeeping and use the same anonymised
 * route hash the BLE transport already advertises. The client keeps both modes so the change can
 * be switched on without a new app release:
 *
 * - [MODE_UUID]: the classic contract, the mailbox UUID is the subscription identity;
 * - [MODE_HASH]: send `sha256(mailbox)` (the same value as [p2pRouteHash]) as `route`.
 *
 * The hash may rotate. [MODE_HASH] with a positive period uses
 * `sha256("<bucket>|<uuid>")`, where the bucket is the server's period count. The server must
 * accept the previous bucket around a rollover, and both sides must derive the bucket from the
 * same clock (the server's), so the period is taken from `GET /settings`, never hardcoded.
 */
object PushIdentity {

    const val MODE_UUID = "uuid"
    const val MODE_HASH = "hash"

    /** Static route: exactly what a P2P peer advertises for this mailbox. */
    fun route(mailboxUuid: String): String = p2pRouteHash(mailboxUuid)

    /** Period number for a rotating route; a non-positive period means "do not rotate". */
    fun bucket(epochMillis: Long, periodSeconds: Long): Long =
        if (periodSeconds <= 0L) 0L else epochMillis / 1000L / periodSeconds

    /** Rotating route, or the static one when [periodSeconds] is zero. */
    fun rotatingRoute(mailboxUuid: String, periodSeconds: Long, epochMillis: Long): String {
        if (periodSeconds <= 0L) return route(mailboxUuid)
        val bucket = bucket(epochMillis, periodSeconds)
        return routeForBucket(mailboxUuid, bucket)
    }

    private fun routeForBucket(mailboxUuid: String, bucket: Long): String =
        sha256Hex("$bucket|${mailboxUuid.trim().lowercase()}")

    /**
     * Previous, current and next bucket, so the subscription still matches when the server and the
     * phone sit on opposite sides of a period boundary (or their clocks differ). Registering all
     * three costs nothing and removes the entire boundary problem.
     */
    fun rotatingRoutes(mailboxUuid: String, periodSeconds: Long, epochMillis: Long): List<String> {
        if (periodSeconds <= 0L) return listOf(route(mailboxUuid))
        val current = bucket(epochMillis, periodSeconds)
        return listOf(
            routeForBucket(mailboxUuid, current - 1),
            routeForBucket(mailboxUuid, current),
            routeForBucket(mailboxUuid, current + 1),
        )
    }
}
