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

class LevelOrderTest {
    @Test
    fun defaultsToTheFamiliarProLogDisplayOrder() {
        assertEquals(listOf("info", "warning", "alarm", "critical", "debug"), defaultLevelOrder())
    }

    @Test
    fun movesLevelsWithoutLosingAny() {
        assertEquals(
            listOf("critical", "debug", "info", "warning", "alarm"),
            sanitizeLevelOrder(listOf("critical", "debug", "bogus", "debug")),
        )
    }

    @Test
    fun unknownOrderFallsBackToTheDefaultOrder() {
        assertEquals(defaultLevelOrder(), sanitizeLevelOrder(null))
        assertEquals(defaultLevelOrder(), sanitizeLevelOrder(emptyList()))
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

    @Test
    fun urgencyOverridesOutcomeStatus() {
        val critical = Page(id = "m", status = 0, prio = "critical")
        val decision = decide(critical, PagerConfig(), night)
        assertTrue(decision.notify)
        assertEquals(NotifyAction.URGENT, decision.action)

        val debug = Page(id = "d", status = 0, prio = "debug")
        assertFalse(decide(debug, PagerConfig(), day).notify)
    }

    @Test
    fun levelCanUseItsOwnScheduleInsteadOfTheGeneralOne() {
        val rule = StatusRule(
            action = NotifyAction.SOUND_VIBRATE,
            timeBound = TimeBound.ACTIVE_HOURS,
            followsGeneralSchedule = false,
            customWindows = listOf(ActiveWindow(LocalTime.of(22, 0), LocalTime.of(6, 0))),
        )
        val cfg = PagerConfig(statusRules = mapOf(1 to rule))
        assertTrue(decide(pageStatus(1), cfg, LocalTime.of(23, 30)).notify)
        assertFalse(decide(pageStatus(1), cfg, day).notify)
    }

    @Test
    fun repeatAlertFlowsIntoTheDecision() {
        val rule = StatusRule(
            action = NotifyAction.URGENT,
            timeBound = TimeBound.ALWAYS,
            repeat = true,
        )
        val cfg = PagerConfig(statusRules = mapOf(3 to rule))
        assertTrue(decide(pageStatus(3), cfg, night).repeat)
    }

    @Test
    fun emptyCustomScheduleFallsBackToTheGeneralOne() {
        val rule = StatusRule(
            action = NotifyAction.SOUND_VIBRATE,
            timeBound = TimeBound.ACTIVE_HOURS,
            followsGeneralSchedule = false,
            customWindows = emptyList(),
        )
        val cfg = PagerConfig(statusRules = mapOf(1 to rule))
        assertTrue(decide(pageStatus(1), cfg, day).notify)
        assertFalse(decide(pageStatus(1), cfg, night).notify)
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

    @Test
    fun mapsTextualStatusesFromPagerSh() {
        assertEquals(0, PageParser.parse(mapOf("id" to "m", "status" to "success")).status)
        assertEquals(1, PageParser.parse(mapOf("id" to "m", "status" to "warning")).status)
        assertEquals(2, PageParser.parse(mapOf("id" to "m", "status" to "failed")).status)
        assertEquals(3, PageParser.parse(mapOf("id" to "m", "status" to "critical")).status)
        assertEquals(4, PageParser.parse(mapOf("id" to "m", "status" to "in progress")).status)
        assertEquals(5, PageParser.parse(mapOf("id" to "m", "status" to "queued")).status)
        assertEquals(PageParser.parse(mapOf("id" to "m", "status" to "Success")).status, 0)
    }

    @Test
    fun unknownTextStatusIsSurfacedNotDropped() {
        assertEquals(STATUS_UNKNOWN_TEXT, PageParser.parse(mapOf("id" to "m", "status" to "banana")).status)
    }

    @Test
    fun parsesPrologLevelFromTitleAndAckFooter() {
        val reply = "9d01837b-ecdc-4aad-bcaa-6e82daf75f51"
        val p = PageParser.parse(
            mapOf(
                "id" to "m1",
                "status" to "success",
                "PROJECT" to "ProLog",
                "JOB_ID" to "prolog-2-4321",
                "message" to "[CRITICAL] ACTIEF · Slacht · 4321",
                "logs" to "body\n\n------------------------\n" +
                    "ACK verplicht. Antwoord met een bericht naar mailbox:\n" +
                    "$reply\nmet in de tekst: ack 1a2b3c4d",
            ),
        )
        assertEquals(3, p.status)
        assertEquals("critical", p.prio)
        assertEquals("body", p.logs)
        assertEquals(reply, p.replyUuid)
        assertEquals("1a2b3c4d", p.ackToken)
        assertTrue(p.prologAckAvailable)
    }

    @Test
    fun topLevelPrioAndAckFieldsWin() {
        val p = PageParser.parse(
            mapOf(
                "id" to "m1",
                "status" to "success",
                "PROJECT" to "ProLog",
                "message" to "[CRITICAL] ACTIEF · Slacht · 4321",
                "prio" to "warning",
                "reply_uuid" to "reply-box",
                "ack_token" to "deadbeef",
            ),
        )
        assertEquals(1, p.status)
        assertEquals("warning", p.prio)
        assertEquals("reply-box", p.replyUuid)
        assertEquals("deadbeef", p.ackToken)
    }

    @Test
    fun ignoresTitleLevelOutsideProlog() {
        val p = PageParser.parse(
            mapOf(
                "id" to "m1",
                "status" to 0,
                "PROJECT" to "Other",
                "message" to "[CRITICAL] not a ProLOG page",
            ),
        )
        assertEquals(0, p.status)
        assertEquals(null, p.prio)
    }

    @Test
    fun parsesPhotoChunkFields() {
        val page = PageParser.parse(
            mapOf(
                "id" to "m",
                "status" to 10,
                "image_id" to "img-1",
                "image_part" to "2",
                "image_total" to 5,
                "image_chunk" to "armor",
                "image_sha256" to "a".repeat(64),
            ),
        )
        assertEquals("img-1", page.imageId)
        assertEquals(2, page.imagePart)
        assertEquals(5, page.imageTotal)
        assertEquals("armor", page.imageChunk)
        assertEquals("a".repeat(64), page.imageSha256)
    }

    @Test
    fun mapsDebugToItsOwnSilentStatus() {
        val p = PageParser.parse(mapOf("id" to "m", "status" to "success", "prio" to "debug"))
        assertEquals(STATUS_DEBUG, p.status)
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
        assertEquals("Acknowledged", ack.payload["message"])
    }

    @Test
    fun ackCarriesTheOptionalNote() {
        val withNote = buildAck(page("peer"), ownUuid = "me", note = "  looking into it  ")
        assertEquals("looking into it", withNote!!.payload["message"])

        val blankNote = buildAck(page("peer"), ownUuid = "me", note = "   ")
        assertEquals("Acknowledged", blankNote!!.payload["message"])
    }

    @Test
    fun prologAckIsAPlainTokenReply() {
        val prologPage = Page(
            id = "m1",
            status = 3,
            jobId = "prolog-2-4321",
            replyUuid = "reply-box",
            ackToken = "1a2b3c4d",
        )
        val ack = buildPrologAck(prologPage)!!
        assertEquals("reply-box", ack.targetUuid)
        assertEquals("ack 1a2b3c4d", ack.payload["message"])
        assertEquals("ack 1a2b3c4d", ack.payload["JOB_ID"])
        assertEquals(0, ack.payload["status"])
        assertEquals(null, buildPrologAck(Page(id = "m2", status = 3)))
    }
}

class PhotoTest {
    private fun contact(publicKey: String = "peer-key") = Contact(
        name = "Peer",
        serverUrl = "https://pager.example",
        uuid = "channel",
        senderId = "peer-sid",
        mySenderId = "my-sid",
        publicKey = publicKey,
    )

    @Test
    fun splitsAnEncryptedPhotoIntoReassemblableChunks() {
        val keys = Pgp.generate("Peer <pagerapp>")
        val image = ByteArray(2_000) { (it * 17 % 251).toByte() }
        val photo = buildOutgoingPhotoChunks(
            contact(keys.publicArmored),
            ownName = "Me",
            imageJpeg = image,
            imageId = "img-1",
            myPublicArmored = keys.publicArmored,
        )!!
        assertEquals("img-1", photo.imageId)
        assertTrue(photo.chunks.isNotEmpty())
        assertEquals(1, photo.chunks.first()["image_part"])
        assertEquals(photo.chunks.size, photo.chunks.last()["image_total"])
        assertTrue(photo.chunks.first()["pub"] != null)
        assertTrue(photo.chunks.drop(1).all { it["pub"] == null })

        val armor = photo.chunks.joinToString("") { it["image_chunk"] as String }
        assertEquals(photo.sha256, sha256Hex(armor))
        assertTrue(Pgp.decryptBytes(armor, keys.secretArmored).contentEquals(image))
    }

    @Test
    fun rejectsPhotosOverTheDirectLimit() {
        val keys = Pgp.generate("Peer <pagerapp>")
        val tooBig = ByteArray(PHOTO_MAX_BYTES + 1)
        assertEquals(
            null,
            buildOutgoingPhotoChunks(contact(keys.publicArmored), "Me", tooBig, "img-2"),
        )
    }

    @Test
    fun fileLinkRoundTripsWithNameAndMime() {
        val link = FileLink(
            "https://share.h4ck.io/abc/report.asc",
            "a".repeat(64),
            days = 3,
            name = "report final.pdf",
            mime = "application/pdf",
        )
        val parsed = FileLink.parse(link.encode())!!
        assertEquals(link.url, parsed.url)
        assertEquals(link.sha256, parsed.sha256)
        assertEquals("report final.pdf", parsed.name)
        assertEquals("application/pdf", parsed.mime)
        assertEquals(3, parsed.days)
        assertEquals(null, FileLink.parse("https://share.h4ck.io/abc/report.asc"))
    }

    @Test
    fun photoLinkRoundTrips() {
        val link = PhotoLink("https://share.h4ck.io/abc/photo.asc", "a".repeat(64), days = 3)
        val parsed = PhotoLink.parse(link.encode())!!
        assertEquals(link.url, parsed.url)
        assertEquals(link.sha256, parsed.sha256)
        assertEquals(3, parsed.days)
        assertEquals(null, PhotoLink.parse("https://share.h4ck.io/abc/photo.asc"))
    }
}

class ContactQrTest {
    private val server = "https://pager.ofloo.io"

    @Test
    fun roundTripsChannelNameAndSenderId() {
        val inviter = Contact(
            name = "Alice van Dijk",
            serverUrl = server,
            uuid = "chan-1",
            mySenderId = "alice-sid",
        )
        val scanned = ContactQr.decode(
            ContactQr.encode(inviter),
            fallbackServer = "x",
            mySenderId = "bob-sid",
        )!!
        assertEquals("chan-1", scanned.uuid)
        assertEquals(server, scanned.serverUrl)
        assertEquals("Alice van Dijk", scanned.name)
        assertEquals("alice-sid", scanned.senderId)
        assertEquals("bob-sid", scanned.mySenderId)
        assertFalse(scanned.awaitingReply)
    }

    @Test
    fun encodesSpecialCharactersSafely() {
        val c = Contact(name = "A&B = C/D", serverUrl = server, uuid = "u-1", mySenderId = "s&1")
        val encoded = ContactQr.encode(c)
        assertEquals(1, encoded.count { it == '?' })
        val decoded = ContactQr.decode(encoded, fallbackServer = "x")!!
        assertEquals("A&B = C/D", decoded.name)
        assertEquals("s&1", decoded.senderId)
    }

    @Test
    fun bareUuidUsesFallbackServerAndNeedsAReply() {
        val c = ContactQr.decode("  dddd-eeee-ffff  ", fallbackServer = server)!!
        assertEquals("dddd-eeee-ffff", c.uuid)
        assertEquals(server, c.serverUrl)
        assertTrue(c.awaitingReply)
    }

    @Test
    fun acceptsUrlShapedInput() {
        val c = ContactQr.decode("$server/mailboxes/1234-5678", fallbackServer = "other")!!
        assertEquals("1234-5678", c.uuid)
        assertEquals("other", c.serverUrl)
    }

    @Test
    fun rejectsEmptyInput() {
        assertEquals(null, ContactQr.decode("   ", fallbackServer = server))
    }

    @Test
    fun twoMailboxInviteCarriesBothDirections() {
        // Alice invites: she reads on alice-inbox and prepared bob-inbox for the scanner.
        val inviter = Contact(
            name = "Alice",
            serverUrl = server,
            uuid = "alice-inbox",
            mySenderId = "alice-sid",
            sendTo = "bob-inbox",
            receiveOn = "alice-inbox",
        )
        val scanned = ContactQr.decode(
            ContactQr.encode(inviter),
            fallbackServer = "x",
            mySenderId = "bob-sid",
        )!!

        assertEquals("alice-inbox", scanned.sendTo, "we write to the inviter's inbox")
        assertEquals("bob-inbox", scanned.receiveOn, "we read on the mailbox she gave us")
        assertEquals("alice-inbox", scanned.outbox)
        assertEquals("bob-inbox", scanned.inbox)
        assertEquals("alice-sid", scanned.senderId)
        assertEquals("bob-sid", scanned.mySenderId)
    }

    @Test
    fun legacyInviteStillSharesOneMailbox() {
        val legacy = "pagerapp://contact?u=shared&s=https%3A%2F%2Fpager.ofloo.io&n=Bob&i=bob-sid"
        val c = ContactQr.decode(legacy, fallbackServer = "x")!!

        assertEquals("shared", c.outbox)
        assertEquals("shared", c.inbox, "old chats keep the shared mailbox in both directions")
        assertEquals("bob-sid", c.senderId)
    }
}

class SendTest {
    private val server = "https://pager.ofloo.io"
    private val bob = Contact(
        name = "Bob",
        serverUrl = server,
        uuid = "bob-channel",
        senderId = "bob-sid",
        mySenderId = "me-sid",
    )

    @Test
    fun outgoingStampsOurSenderIdNeverAMailbox() {
        val p = buildOutgoing(bob, ownName = "Me", message = "hi")
        assertEquals("me-sid", p["from"])
        assertEquals("Me", p["from_name"])
        assertEquals("hi", p["message"])
        assertEquals(STATUS_MESSAGE, p["status"])
    }

    @Test
    fun onlyTheReceiverAcks() {
        val mine = Page(id = "1", status = STATUS_MESSAGE, from = "me-sid")
        val theirs = Page(id = "2", status = STATUS_MESSAGE, from = "bob-sid")
        assertFalse(shouldAck(bob, mine))
        assertTrue(shouldAck(bob, theirs))
    }

    @Test
    fun splitsChannelIntoBothDirectionsOldestFirst() {
        val pages = listOf(
            Page(id = "t2", status = STATUS_MESSAGE, from = "bob-sid", createdAt = 20.0),
            Page(id = "m1", status = STATUS_MESSAGE, from = "me-sid", createdAt = 10.0),
            Page(id = "t1", status = STATUS_MESSAGE, from = "bob-sid", createdAt = 5.0),
        )
        assertEquals(listOf("t1", "t2"), peerMessages(bob, pages).map { it.id })
        assertEquals(listOf("m1"), ownMessages(bob, pages).map { it.id })
    }

    @Test
    fun resolvesContactBySenderId() {
        val fromBob = Page(id = "1", status = STATUS_MESSAGE, from = "bob-sid")
        assertEquals(bob, contactFor(fromBob, listOf(bob)))

        val unknown = Page(id = "2", status = STATUS_MESSAGE, from = "carol-sid")
        assertEquals(null, contactFor(unknown, listOf(bob)))
    }
}
