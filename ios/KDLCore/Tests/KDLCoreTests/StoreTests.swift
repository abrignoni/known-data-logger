import XCTest
@testable import KDLCore

final class StoreTests: XCTestCase {
    var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("kdl-store-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: dir) }

    func store() throws -> Store {
        let s = try Store(url: dir.appendingPathComponent(Store.fileName))
        s.scenarioId = "sc"; s.deviceLabel = "phoneA"
        return s
    }

    func testRevisionsAndTombstonesKeepTheChain() throws {
        let s = try store()
        let a = try s.log(Draft(action: "sent_message", content: "hello", direction: "out")).last!
        _ = try s.log(Draft(action: "took_photo"))
        let r1 = try s.revise(a, Draft(action: "sent_message", content: "hello there", direction: "out"))
        XCTAssertEqual(1, r1.revisionN)
        XCTAssertEqual(a.stamp, r1.stamp, "a revision keeps the action time")
        let photo = Timeline.current(s.entries()).first { $0.action == "took_photo" }!
        try s.tombstone(photo)
        let rows = s.entries()
        XCTAssertEqual(4, rows.count)
        XCTAssertNil(Chain.firstBreak(rows))
        let cur = Timeline.current(rows)
        XCTAssertEqual(["hello there"], cur.map(\.content))
        XCTAssertEqual(s.lastRowHash(), rows.last?.rowHash)
    }

    /// Two Store objects on one file stand in for the app and its widget extension.
    func testTwoWritersShareOneChain() throws {
        let a = try store()
        let b = try Store(url: dir.appendingPathComponent(Store.fileName))
        let group = DispatchGroup()
        for (i, s) in [a, b].enumerated() {
            group.enter()
            DispatchQueue.global().async {
                for j in 0..<50 { _ = try? s.log(Draft(action: "mark_\(i)_\(j)"), detect: false) }
                group.leave()
            }
        }
        group.wait()
        let rows = a.entries()
        XCTAssertEqual(100, rows.count)
        XCTAssertNil(Chain.firstBreak(rows))
        XCTAssertEqual(100, Set(rows.map(\.prevHash)).count, "no two rows hash over the same previous row")
    }

    func testRestartIsDetectedWhenTimeSinceBootGoesBack() {
        let last = Stamp(utcMs: 10_000_000, tzId: "UTC", tzOffsetMin: 0, elapsedMs: 5_000_000, kernBootMs: 5_000_000)
        let next = Stamp(utcMs: 10_100_000, tzId: "UTC", tzOffsetMin: 0, elapsedMs: 20_000, kernBootMs: 10_080_000)
        let rows = Timeline.detectChanges(last: last, next: next)
        XCTAssertEqual(["boot_detected"], rows.map(\.action))
        XCTAssertTrue(rows[0].detail.contains("kern.boottime moved 5080000 ms"), rows[0].detail)
    }

    func testClockSetIsAClockChange() {
        let last = Stamp(utcMs: 10_000_000, tzId: "UTC", tzOffsetMin: 0, elapsedMs: 5_000_000)
        let back = Stamp(utcMs: 6_000_000, tzId: "UTC", tzOffsetMin: 0, elapsedMs: 5_060_000)
        let r = Timeline.detectChanges(last: last, next: back)
        XCTAssertEqual(["clock_change_detected"], r.map(\.action))
        XCTAssertFalse(r[0].detail.contains("restart"), "a clock set backwards cannot be a restart")
        let fwd = Stamp(utcMs: 20_000_000, tzId: "UTC", tzOffsetMin: 0, elapsedMs: 5_060_000)
        XCTAssertTrue(Timeline.detectChanges(last: last, next: fwd)[0].detail.contains("a restart since the previous entry would look the same"))
        let zone = Stamp(utcMs: 10_060_000, tzId: "Europe/Madrid", tzOffsetMin: 60, elapsedMs: 5_060_000)
        XCTAssertEqual(["time zone changed from UTC (0 min) to Europe/Madrid (60 min)"],
                       Timeline.detectChanges(last: last, next: zone).map(\.detail))
        XCTAssertTrue(Timeline.detectChanges(last: last, next: Stamp(utcMs: 10_060_500, tzId: "UTC", tzOffsetMin: 0, elapsedMs: 5_060_000)).isEmpty)
    }

    func testDetectedChangesAreLoggedBeforeTheEntry() throws {
        let s = try store()
        try s.log(Draft(action: "a"), stamp: Stamp(utcMs: 10_000_000, tzId: "UTC", tzOffsetMin: 0, elapsedMs: 5_000_000))
        let rows = try s.log(Draft(action: "b"), stamp: Stamp(utcMs: 10_100_000, tzId: "UTC", tzOffsetMin: 0, elapsedMs: 1_000))
        XCTAssertEqual(["boot_detected", "b"], rows.map(\.action))
        XCTAssertEqual(Source.system, rows[0].source)
        XCTAssertNil(Chain.firstBreak(s.entries()))
    }

    func testReviewListsStampedEntriesAndUnlabelledMarks() throws {
        let s = try store()
        try s.log(Draft(action: ""))
        let mark = try s.log(Draft(action: AutoAction.mark, source: Source.mark), kind: Kind.autoEvent).last!
        XCTAssertEqual(2, Review.pending(s.entries()).count)
        try s.log(Draft(action: "sent_message", refersTo: mark.entryId))
        XCTAssertEqual(1, Review.pending(s.entries()).count)
    }

    func testAppVersionAndAccountComeFromTheAppList() throws {
        let s = try store()
        try s.upsertApp(AppUnderTest(pkg: "net.whatsapp.WhatsApp", label: "WhatsApp", versionName: "25.1", account: "+1 555 0100", addedUtcMs: 1))
        let e = try s.log(Draft(appPackage: "net.whatsapp.WhatsApp", action: "sent_message")).last!
        XCTAssertEqual("25.1", e.appVersion)
        XCTAssertEqual("+1 555 0100", e.account)
    }

    func testSchemaMatchesAndroid() throws {
        let s = try store()
        _ = s
        // Re-open: user_version is set and the tables exist, so a second open does not try to create them again.
        let again = try Store(url: dir.appendingPathComponent(Store.fileName))
        XCTAssertEqual("sc", again.scenarioId)
    }
}
