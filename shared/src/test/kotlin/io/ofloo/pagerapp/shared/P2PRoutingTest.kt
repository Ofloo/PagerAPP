package io.ofloo.pagerapp.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class P2PRoutingTest {

    @Test
    fun routeHashIsStableAndDoesNotExposeTheUuid() {
        val uuid = "3f4a6e9e-0f2b-4b9b-9f7a-2c2a7f1e1234"
        val hash = p2pRouteHash(uuid)
        assertEquals(64, hash.length)
        assertEquals(hash, p2pRouteHash(" $uuid "))
        assertFalse(hash.contains(uuid))
        assertNotEquals(hash, p2pRouteHash(uuid, "contact-secret", 1))
        assertNotEquals(
            p2pRouteHash(uuid, "contact-secret", 1),
            p2pRouteHash(uuid, "contact-secret", 2),
        )
    }

    @Test
    fun localTargetAlwaysWins() {
        val table = P2PNeighborTable()
        val hash = p2pRouteHash("local")
        table.record(hash, "ble:abc", now = 100)
        assertEquals(P2PRoute.Local, table.route(hash, setOf(hash), gatewayAvailable = true, now = 100))
    }

    @Test
    fun unknownTargetIsLookedUpBeforeGateway() {
        val table = P2PNeighborTable()
        val hash = p2pRouteHash("remote")
        assertEquals(P2PRoute.Lookup, table.route(hash, emptySet(), gatewayAvailable = true, now = 100))
    }

    @Test
    fun peerReplyBecomesADirectRoute() {
        val table = P2PNeighborTable()
        val hash = p2pRouteHash("remote")
        table.markMissing(hash, now = 100)
        table.record(hash, "ble:peer", now = 101)
        assertFalse(table.wasRecentlyMissing(hash, now = 101))
        assertEquals(P2PRoute.Peer("ble:peer"), table.route(hash, emptySet(), true, now = 101))
    }

    @Test
    fun negativeCacheFallsBackToGatewayThenQueue() {
        val table = P2PNeighborTable(negativeTtlSeconds = 30)
        val hash = p2pRouteHash("nobody")
        table.markMissing(hash, now = 100)
        assertEquals(P2PRoute.Gateway, table.route(hash, emptySet(), true, now = 110))
        assertEquals(P2PRoute.Queue, table.route(hash, emptySet(), false, now = 110))
        assertTrue(table.wasRecentlyMissing(hash, now = 110))
        assertFalse(table.wasRecentlyMissing(hash, now = 131))
        assertEquals(P2PRoute.Lookup, table.route(hash, emptySet(), true, now = 131))
    }

    @Test
    fun neighborEntriesExpire() {
        val table = P2PNeighborTable(ttlSeconds = 10)
        val hash = p2pRouteHash("peer")
        table.record(hash, "wifi:192.0.2.1:9000", now = 100)
        assertEquals("wifi:192.0.2.1:9000", table.lookup(hash, now = 109))
        assertEquals(null, table.lookup(hash, now = 110))
    }
}
