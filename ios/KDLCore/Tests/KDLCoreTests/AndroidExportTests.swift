import XCTest
@testable import KDLCore

/// Re-verifies the hash chain of a real export written by the Android app.
///
/// The export is not in the repository. Point KDL_ANDROID_EXPORT at an export folder (one holding
/// manifest.json and actions.jsonl), or keep the default path below; the test is skipped when neither exists.
final class AndroidExportTests: XCTestCase {
    static var folder: URL? {
        let env = ProcessInfo.processInfo.environment["KDL_ANDROID_EXPORT"]
        let fallback = NSHomeDirectory() + "/Documents/Working Files/known-data-logger/sample-export-0.3/kdl_test-2026-11-a_phoneA_20261109T011056Z"
        let path = env ?? fallback
        return FileManager.default.fileExists(atPath: path + "/manifest.json") ? URL(fileURLWithPath: path) : nil
    }

    func testChainOfAnAndroidExportVerifies() throws {
        guard let dir = Self.folder else { throw XCTSkip("no Android export found; set KDL_ANDROID_EXPORT") }
        let raw = try Data(contentsOf: dir.appendingPathComponent("actions.jsonl"))
        let manifest = try JSONSerialization.jsonObject(with: Data(contentsOf: dir.appendingPathComponent("manifest.json"))) as! [String: Any]
        let files = manifest["files"] as! [String: Any]
        XCTAssertEqual((files["actions.jsonl"] as! [String: Any])["sha256"] as? String, sha256Hex(raw))

        let rows = try Row.parse(jsonl: raw)
        XCTAssertNil(Chain.firstBreak(rows), "the Swift verifier found a break the Android app did not")
        let hc = manifest["hash_chain"] as! [String: Any]
        XCTAssertEqual(hc["rows"] as? Int, rows.count)
        XCTAssertEqual(hc["last_row_hash"] as? String, rows.max { $0.seq < $1.seq }?.rowHash)
        print("Export \(dir.lastPathComponent): \(rows.count) rows, chain intact, last hash \(rows.last!.rowHash!)")

        // Changing one character of one row must break the chain at that row.
        var tampered = rows
        let i = tampered.count / 2
        tampered[i].detail = (tampered[i].detail ?? "") + "x"
        XCTAssertEqual(tampered[i].seq, Chain.firstBreak(tampered))
    }
}
