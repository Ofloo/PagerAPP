package io.ofloo.pagerapp.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PushIdentityTest {

    private val uuid = "3f4a6e9e-0f2b-4b9b-9f7a-2c2a7f1e1234"

    @Test
    fun staticRouteIsTheBleHash() {
        assertEquals(p2pRouteHash(uuid), PushIdentity.route(uuid))
        assertEquals(PushIdentity.route(uuid), PushIdentity.rotatingRoute(uuid, 0L, 1_700_000_000_000L))
    }

    @Test
    fun rotationUsesThePeriodAndStaysStableInsideIt() {
        val hour = 3_600L
        val first = PushIdentity.rotatingRoute(uuid, hour, 1_700_000_000_000L)
        val sameBucket = PushIdentity.rotatingRoute(uuid, hour, 1_700_000_000_000L + 60_000L)
        val nextBucket = PushIdentity.rotatingRoute(uuid, hour, 1_700_003_600_000L)
        assertEquals(first, sameBucket)
        assertNotEquals(first, nextBucket)
        assertNotEquals(PushIdentity.route(uuid), first)
    }

    @Test
    fun allThreeBucketsAreRegisteredAroundTheCurrentOne() {
        val hour = 3_600L
        val now = 1_700_000_000_000L
        val routes = PushIdentity.rotatingRoutes(uuid, hour, now)
        assertEquals(3, routes.size)
        assertEquals(PushIdentity.rotatingRoute(uuid, hour, now), routes[1])
        assertEquals(PushIdentity.rotatingRoute(uuid, hour, now - hour * 1_000L), routes[0])
        assertEquals(PushIdentity.rotatingRoute(uuid, hour, now + hour * 1_000L), routes[2])
        assertEquals(routes.toSet().size, 3)
        // A static identity has nothing to overlap.
        assertEquals(listOf(PushIdentity.route(uuid)), PushIdentity.rotatingRoutes(uuid, 0L, now))
    }

    @Test
    fun bucketIsThePeriodCount() {
        assertEquals(0L, PushIdentity.bucket(1_700_000_000_000L, 0L))
        assertEquals(1_700_000_000L / 3_600L, PushIdentity.bucket(1_700_000_000_000L, 3_600L))
    }
}
