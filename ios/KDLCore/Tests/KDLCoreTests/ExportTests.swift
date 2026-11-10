import XCTest
@testable import KDLCore

final class ExportTests: XCTestCase {
    var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("kdl-export-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: dir) }

    /// A store with the kinds of rows a hand-logged iOS session writes.
    func filledStore() throws -> Store {
        let s = try Store(url: dir.appendingPathComponent("data/kl.db"))
        s.scenarioId = "ios-test"; s.deviceLabel = "iphoneA"
        try s.addProfile(Profile(manufacturer: "Apple", model: "iPhone14,5", device: "iPhone", release: "26.6",
                                 incremental: "23G71", kernel: "25.6.0", carrier: "TestCarrier"))
        try s.upsertApp(AppUnderTest(pkg: "com.apple.MobileSMS", label: "Messages", versionName: "26.6", addedUtcMs: 1))
        try s.startSession()
        let sent = try s.log(Draft(appPackage: "com.apple.MobileSMS", action: "sent_message", content: "Lunch at 1? 🍕 \"ok\", =1+1",
                                   counterpart: "phone B", direction: "out", expectedArtifacts: ["ileapp:sms"])).last!
        try s.revise(sent, Draft(appPackage: "com.apple.MobileSMS", action: "sent_message", content: "Lunch at 1:30?",
                                 counterpart: "phone B", direction: "out"))
        let start = Clocks.stamp()
        try s.log(Draft(appPackage: "com.apple.MobileSMS", action: "outgoing_call", counterpart: "phone B", direction: "out"),
                  stamp: start, windowS: 12.345, detect: false)
        let wrong = try s.log(Draft(action: "took_photo")).last!
        try s.tombstone(wrong)
        try s.log(Draft(action: AutoAction.mark, source: Source.mark), kind: Kind.autoEvent)
        try s.log(Draft(action: ""))
        try s.stopSession()
        return s
    }

    func testExportLayoutAndChain() throws {
        let s = try filledStore()
        let out = try Exporter.export(store: s, to: dir, logger: LoggerInfo(package: "com.abrignoni.knowndatalogger", versionName: "0.1.0"))
        let names = try FileManager.default.contentsOfDirectory(atPath: out.path).sorted()
        XCTAssertEqual(["actions.csv", "actions.jsonl", "manifest.json"], names)
        XCTAssertTrue(out.lastPathComponent.hasPrefix("kdl_ios-test_iphoneA_"))
        let raw = try Data(contentsOf: out.appendingPathComponent("actions.jsonl"))
        let rows = try Row.parse(jsonl: raw)
        XCTAssertEqual(s.entries().count, rows.count)
        XCTAssertNil(Chain.firstBreak(rows))
        XCTAssertEqual("export", rows.last?.action, "the export lists itself")
        let csv = try String(contentsOf: out.appendingPathComponent("actions.csv"), encoding: .utf8)
        XCTAssertEqual(rows.count + 1, csv.components(separatedBy: "\r\n").filter { !$0.isEmpty }.count)
        XCTAssertTrue(csv.hasPrefix(Csv.header.joined(separator: ",") + "\r\n"))
    }

    func testCsvTimesHaveFixedWidth() {
        XCTAssertEqual("2023-11-14T22:13:20.123Z", Csv.utcIso(1_700_000_000_123))
        XCTAssertEqual("2023-11-14T17:13:20.001-05:00", Csv.localIso(1_700_000_000_001, offsetMin: -300))
        XCTAssertEqual("2023-11-14T22:13:20.999+00:00", Csv.localIso(1_700_000_000_999, offsetMin: 0))
    }

    /// The repository's own verifier must accept an iOS export: exit 0 and no WARNING line.
    func testMakeDocumentationAcceptsTheExport() throws {
        let tool = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("tools/make_documentation.py")
        guard FileManager.default.fileExists(atPath: tool.path) else { throw XCTSkip("tools/make_documentation.py not found") }
        let s = try filledStore()
        let out = try Exporter.export(store: s, to: dir, logger: LoggerInfo(package: "com.abrignoni.knowndatalogger", versionName: "0.1.0"))
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        p.arguments = ["python3", tool.path, out.path, "-o", dir.appendingPathComponent("doc.xlsx").path,
                       "--expect-hash", s.lastRowHash()!]
        let pipe = Pipe()
        p.standardOutput = pipe; p.standardError = pipe
        p.standardInput = FileHandle.nullDevice
        try p.run(); p.waitUntilExit()
        let text = String(decoding: pipe.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        print(text)
        if text.contains("openpyxl is required") { throw XCTSkip("openpyxl is not installed for python3") }
        XCTAssertEqual(0, p.terminationStatus, text)
        XCTAssertFalse(text.contains("WARNING"), text)
    }
}
