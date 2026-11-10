import XCTest
@testable import KDLCore

final class ChainTests: XCTestCase {
    /// Same row as ChainTest.canonicalMatchesPython (Android) and test_chain_vector_matches_kotlin (Python).
    static let fixed = Entry(
        seq: 7, entryId: "e1", revisionN: 0, kind: "action", scenarioId: "sc", deviceLabel: "phoneA", profileId: 1,
        stamp: Stamp(utcMs: 1_700_000_000_123, tzId: "America/New_York", tzOffsetMin: -300, elapsedMs: 123_456, bootCount: 3,
                     networkTimeMs: 1_700_000_000_000, gnssTimeMs: nil),
        timeQuality: "exact", windowS: 5.894, editedUtcMs: 1_700_000_000_500, appPackage: "com.example.chat",
        appVersion: "1.2", account: nil, action: "sent_message",
        content: "Hé \"q\" \\ line\nnext 😀 bell\u{07} del\u{7f}", counterpart: "phone B", direction: "out",
        conversationId: nil, refersTo: nil, expectedArtifacts: ["aleapp:x", "y"], detail: nil, source: "manual", reviewed: true)

    func testCanonicalMatchesAndroidAndPython() {
        XCTAssertEqual(
            "{\"account\":null,\"action\":\"sent_message\",\"app_package\":\"com.example.chat\",\"app_version\":\"1.2\"," +
                "\"boot_count\":3,\"content\":\"H\\u00e9 \\\"q\\\" \\\\ line\\nnext \\ud83d\\ude00 bell\\u0007 del\\u007f\"," +
                "\"conversation_id\":null,\"counterpart\":\"phone B\",\"detail\":null,\"device_label\":\"phoneA\"," +
                "\"direction\":\"out\",\"edited_utc_ms\":1700000000500,\"elapsed_ms\":123456,\"entry_id\":\"e1\"," +
                "\"expected_artifacts\":[\"aleapp:x\",\"y\"],\"gnss_time_ms\":null,\"kind\":\"action\"," +
                "\"network_time_ms\":1700000000000,\"prev_hash\":\"abc\",\"profile_id\":1,\"refers_to\":null," +
                "\"reviewed\":true,\"revision_n\":0,\"scenario_id\":\"sc\",\"seq\":7,\"source\":\"manual\"," +
                "\"time_quality\":\"exact\",\"tz_id\":\"America/New_York\",\"tz_offset_min\":-300," +
                "\"utc_ms\":1700000000123,\"window_ms\":5894}",
            Chain.canonical(Self.fixed, prevHash: "abc"))
        XCTAssertEqual("23843a77b64530aba2b30d3ddaac1b19013cfcf924101dd6e799fef6ae99ac44", Chain.rowHash(Self.fixed, prevHash: "abc"))
    }

    /// The vector survives a write to actions.jsonl and a read back, so a verifier reading the file gets the same hash.
    func testVectorSurvivesJsonlRoundTrip() throws {
        var e = Self.fixed
        e.prevHash = "abc"; e.rowHash = Chain.rowHash(e, prevHash: "abc")
        let back = try Row.parse(jsonl: Data((Row.json(e) + "\n").utf8))
        XCTAssertEqual(1, back.count)
        XCTAssertEqual(e.rowHash, Chain.rowHash(back[0], prevHash: "abc"))
    }

    func testWindowRoundsLikeJavaMathRound() {
        var e = Self.fixed
        for (w, ms) in [(0.0005, "1"), (1.2345, "1235"), (2.0, "2000"), (0.0004, "0")] {
            e.windowS = w
            XCTAssertTrue(Chain.canonical(e, prevHash: "").hasSuffix("\"window_ms\":\(ms)}"), "\(w)")
        }
        e.windowS = nil
        XCTAssertTrue(Chain.canonical(e, prevHash: "").hasSuffix("\"window_ms\":null}"))
    }

    private func chain(_ n: Int) -> [Entry] {
        var prev = Chain.genesis
        return (1...n).map { i in
            var e = Self.fixed
            e.seq = Int64(i); e.entryId = "e\(i)"; e.content = "row \(i)"; e.prevHash = prev
            let h = Chain.rowHash(e, prevHash: prev)
            prev = h
            e.rowHash = h
            return e
        }
    }

    func testIntactChainPasses() { XCTAssertNil(Chain.firstBreak(chain(5))) }

    func testEditedRowBreaksAtThatRow() {
        var rows = chain(5)
        rows[2].content = "changed"
        XCTAssertEqual(3, Chain.firstBreak(rows))
    }

    func testRemovedRowBreaksTheNextRow() {
        var rows = chain(5)
        rows.remove(at: 1)
        XCTAssertEqual(3, Chain.firstBreak(rows))
    }

    func testRehashingOneRowStillBreaksTheNext() {
        var rows = chain(5)
        var edited = rows[1]
        edited.content = "changed"
        edited.rowHash = Chain.rowHash(edited, prevHash: edited.prevHash!)
        rows[1] = edited
        XCTAssertEqual(3, Chain.firstBreak(rows))
    }
}
