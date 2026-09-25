import XCTest
@testable import PagerApp

final class LogicTests: XCTestCase {

    func testWarningSuppressedAtNightShownByDay() {
        let page = Page(id: "m1", status: 1)
        XCTAssertFalse(decide(page, PagerConfig(), nowMinutes: 3 * 60).notify)
        XCTAssertTrue(decide(page, PagerConfig(), nowMinutes: 12 * 60).notify)
    }

    func testCriticalAlwaysNotifies() {
        let d = decide(Page(id: "m", status: 3), PagerConfig(), nowMinutes: 3 * 60)
        XCTAssertTrue(d.notify)
        XCTAssertEqual(d.action, .urgent)
    }

    func testSilenceWinsOverBypass() {
        var cfg = PagerConfig()
        cfg.bypassMode = true
        cfg.silenceMode = true
        XCTAssertFalse(decide(Page(id: "m", status: 3), cfg, nowMinutes: 12 * 60).notify)
    }

    func testBypassOverridesTimeBound() {
        var cfg = PagerConfig()
        cfg.bypassMode = true
        XCTAssertTrue(decide(Page(id: "m", status: 1), cfg, nowMinutes: 3 * 60).notify)
    }

    func testWindowWrappingMidnight() {
        let w = ActiveWindow(startMinutes: 22 * 60, endMinutes: 6 * 60)
        XCTAssertTrue(w.contains(minutes: 23 * 60))
        XCTAssertTrue(w.contains(minutes: 60))
        XCTAssertFalse(w.contains(minutes: 12 * 60))
    }

    func testWatchModeChannels() {
        var cfg = PagerConfig(); cfg.watchMode = .off
        XCTAssertEqual(decide(Page(id: "m", status: 3), cfg, nowMinutes: 600).channels, [.phone])
        cfg.watchMode = .phoneAndWatch
        XCTAssertEqual(decide(Page(id: "m", status: 3), cfg, nowMinutes: 600).channels, [.phone, .watch])
        cfg.watchMode = .watchOnly
        XCTAssertEqual(decide(Page(id: "m", status: 3), cfg, nowMinutes: 600).channels, [.watch])
    }

    func testDeduplicator() {
        let d = Deduplicator()
        XCTAssertTrue(d.isNew("a"))
        XCTAssertFalse(d.isNew("a"))
    }

    func testAckRequiresAckUuid() {
        XCTAssertNil(buildAck(Page(id: "m", status: 2), ownUuid: "me"))
        let ack = buildAck(Page(id: "m", status: 2, jobId: "j1", ackUuid: "peer"), ownUuid: "me")
        XCTAssertEqual(ack?.targetUuid, "peer")
        XCTAssertEqual(ack?.payload["status"] as? Int, STATUS_ACKNOWLEDGED)
        XCTAssertEqual(ack?.payload["acknowledged_by"] as? String, "me")
        XCTAssertEqual(ack?.payload["JOB_ID"] as? String, "j1")
    }

    func testPageParser() throws {
        let page = try PageParser.parse(["id": "m1", "status": 2, "PROJECT": "build"])
        XCTAssertEqual(page.id, "m1")
        XCTAssertEqual(page.status, 2)
        XCTAssertEqual(page.project, "build")
    }
}
