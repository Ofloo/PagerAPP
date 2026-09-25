package io.ofloo.pagerapp.shared

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActiveWindowTest {
    @Test
    fun normalWindow() {
        val w = ActiveWindow(LocalTime.of(7, 0), LocalTime.of(23, 0))
        assertFalse(w.contains(LocalTime.of(6, 59)))
        assertTrue(w.contains(LocalTime.of(7, 0)))
        assertTrue(w.contains(LocalTime.of(22, 59)))
        assertFalse(w.contains(LocalTime.of(23, 0)))
    }

    @Test
    fun windowWrappingMidnight() {
        val w = ActiveWindow(LocalTime.of(22, 0), LocalTime.of(6, 0))
        assertTrue(w.contains(LocalTime.of(23, 0)))
        assertTrue(w.contains(LocalTime.of(1, 0)))
        assertFalse(w.contains(LocalTime.of(12, 0)))
    }
}

class DefaultRuleTest {
    @Test
    fun defaultStatusTableMatchesAgentsMd() {
        assertEquals(TimeBound.NEVER, defaultRuleFor(0).timeBound)          // Success
        assertEquals(NotifyAction.SILENT, defaultRuleFor(0).action)
        assertEquals(TimeBound.ACTIVE_HOURS, defaultRuleFor(1).timeBound)   // Warning
        assertEquals(TimeBound.ACTIVE_HOURS, defaultRuleFor(2).timeBound)   // Failed
        assertEquals(NotifyAction.URGENT, defaultRuleFor(3).action)         // Critical
        assertEquals(TimeBound.ALWAYS, defaultRuleFor(3).timeBound)
        assertEquals(TimeBound.NEVER, defaultRuleFor(4).timeBound)          // In Progress
        assertEquals(TimeBound.NEVER, defaultRuleFor(5).timeBound)          // Queued
        assertEquals(TimeBound.NEVER, defaultRuleFor(42).timeBound)         // unconfigured custom
    }
}

class DecideTest {
    private val night = LocalTime.of(3, 0)
    private val day = LocalTime.of(12, 0)
    private val pageStatus = { s: Int -> Page(id = "m-$s", status = s) }

    @Test
    fun warningSuppressedAtNightShownByDay() {
        val cfg = PagerConfig()
        assertFalse(decide(pageStatus(1), cfg, night).notify)
        assertTrue(decide(pageStatus(1), cfg, day).notify)
    }

    @Test
    fun criticalAlwaysNotifiesEvenAtNight() {
        val d = decide(pageStatus(3), PagerConfig(), night)
        assertTrue(d.notify)
        assertEquals(NotifyAction.URGENT, d.action)
    }

    @Test
    fun successAndInProgressNeverNotify() {
        assertFalse(decide(pageStatus(0), PagerConfig(), day).notify)
        assertFalse(decide(pageStatus(4), PagerConfig(), day).notify)
        assertFalse(decide(pageStatus(5), PagerConfig(), day).notify)
    }

    @Test
    fun bypassOverridesTimeBoundButNotSilence() {
        val bypass = PagerConfig(bypassMode = true)
        assertTrue(decide(pageStatus(1), bypass, night).notify)

        val both = PagerConfig(bypassMode = true, silenceMode = true)
        assertFalse(decide(pageStatus(3), both, day).notify) // silence wins
    }

    @Test
    fun silenceShowsNothing() {
        val cfg = PagerConfig(silenceMode = true)
        assertFalse(decide(pageStatus(3), cfg, day).notify)
        assertTrue(decide(pageStatus(3), cfg, day).channels.isEmpty())
    }

    @Test
    fun userOverrideChangesActionAndBound() {
        val cfg = PagerConfig(
            statusRules = mapOf(
                2 to StatusRule(NotifyAction.URGENT, TimeBound.ALWAYS),
                7 to StatusRule(NotifyAction.SOUND_VIBRATE, TimeBound.ACTIVE_HOURS),
            ),
        )
        assertTrue(decide(pageStatus(2), cfg, night).notify) // overridden to always
        assertFalse(decide(pageStatus(7), cfg, night).notify) // custom, active hours only
        assertTrue(decide(pageStatus(7), cfg, day).notify)
    }

    @Test
    fun watchModeSelectsChannels() {
        val phoneOnly = decide(pageStatus(3), PagerConfig(watchMode = WatchMode.OFF), day)
        assertEquals(setOf(Channel.PHONE), phoneOnly.channels)

        val bothCh = decide(pageStatus(3), PagerConfig(watchMode = WatchMode.PHONE_AND_WATCH), day)
        assertEquals(setOf(Channel.PHONE, Channel.WATCH), bothCh.channels)

        val watchOnly = decide(pageStatus(3), PagerConfig(watchMode = WatchMode.WATCH_ONLY), day)
        assertEquals(setOf(Channel.WATCH), watchOnly.channels)
    }

    @Test
    fun multipleActiveWindows() {
        val cfg = PagerConfig(
            activeWindows = listOf(
                ActiveWindow(LocalTime.of(7, 0), LocalTime.of(12, 0)),
                ActiveWindow(LocalTime.of(13, 0), LocalTime.of(23, 0)),
            ),
        )
        assertFalse(decide(pageStatus(1), cfg, LocalTime.of(12, 30)).notify)
        assertTrue(decide(pageStatus(1), cfg, LocalTime.of(13, 30)).notify)
    }
}

class DeduplicatorTest {
    @Test
    fun repeatedIdIsNotNew() {
        val d = Deduplicator()
        assertTrue(d.isNew("a"))
        assertFalse(d.isNew("a"))
        assertTrue(d.isNew("b"))
    }

    @Test
    fun restoresFromSnapshot() {
        val d = Deduplicator(listOf("a", "b"))
        assertFalse(d.isNew("a"))
        assertTrue(d.isNew("c"))
    }
}

class PageParserTest {
    @Test
    fun parsesNumericStatusAndOptionals() {
        val p = PageParser.parse(
            mapOf(
                "id" to "m1",
                "status" to 2,
                "PROJECT" to "build",
                "JOB_ID" to "build-1",
                "message" to "failed",
                "logs" to "line",
                "prio" to "high",
                "ack_uuid" to "peer-uuid",
            ),
        )
        assertEquals("m1", p.id)
        assertEquals(2, p.status)
        assertEquals("build", p.project)
        assertEquals("build-1", p.jobId)
        assertEquals("high", p.prio)
        assertEquals("peer-uuid", p.ackUuid)
    }

    @Test
    fun parsesStringEncodedStatus() {
        assertEquals(3, PageParser.parse(mapOf("id" to "m", "status" to "3")).status)
    }
}

class AckTest {
    private fun page(ackUuid: String?) =
        Page(id = "m1", status = 2, jobId = "job-9", ackUuid = ackUuid)

    @Test
    fun unavailableWithoutAckUuid() {
        assertFalse(page(null).ackAvailable)
        assertFalse(page("  ").ackAvailable)
        assertTrue(page("peer").ackAvailable)
    }

    @Test
    fun noAckBuiltWithoutAckUuid() {
        assertEquals(null, buildAck(page(null), ownUuid = "me"))
    }

    @Test
    fun ackTargetsTheAckUuidAndCarriesCorrelation() {
        val ack = buildAck(page("peer"), ownUuid = "me")
        assertEquals("peer", ack!!.targetUuid)
        assertEquals(STATUS_ACKNOWLEDGED, ack.payload["status"])
        assertEquals("me", ack.payload["acknowledged_by"])
        assertEquals("job-9", ack.payload["JOB_ID"])
        assertEquals("m1", ack.payload["in_reply_to"])
    }
}
