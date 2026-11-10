import Foundation
import CryptoKit

public func sha256Hex(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
}

/// Tamper evidence, the same as Chain in the Android app's Model.kt. Each row's hash covers its own
/// fields plus the previous row's hash, in seq order.
///
/// The hashed text is a canonical JSON object that tools/make_documentation.py rebuilds byte for
/// byte: keys sorted, no spaces, ASCII only, other UTF-16 code units as lowercase \uXXXX escapes
/// (as Python's json.dumps(sort_keys=True, separators=(",", ":"), ensure_ascii=True) writes them).
/// It is written by hand because JSONEncoder and JSONSerialization escape "/" and do not escape non-ASCII.
/// window_s is hashed as window_ms (whole milliseconds, Java Math.round); boot_wall_ms and the
/// screenshot are not hashed.
public enum Chain {
    public static let genesis = ""

    enum V { case null, str(String), int(Int64), bool(Bool), list([String]) }

    static func fields(_ e: Entry, _ prev: String) -> [(String, V)] {
        func s(_ x: String?) -> V { x.map(V.str) ?? .null }
        func i(_ x: Int64?) -> V { x.map(V.int) ?? .null }
        let window: V = e.windowS.map { .int(Int64((($0 * 1000) + 0.5).rounded(.down))) } ?? .null
        // Already in sorted key order; canonical() asserts it.
        return [
            ("account", s(e.account)), ("action", .str(e.action)), ("app_package", s(e.appPackage)),
            ("app_version", s(e.appVersion)), ("boot_count", .int(Int64(e.stamp.bootCount))), ("content", s(e.content)),
            ("conversation_id", s(e.conversationId)), ("counterpart", s(e.counterpart)), ("detail", s(e.detail)),
            ("device_label", .str(e.deviceLabel)), ("direction", s(e.direction)), ("edited_utc_ms", .int(e.editedUtcMs)),
            ("elapsed_ms", .int(e.stamp.elapsedMs)), ("entry_id", .str(e.entryId)), ("expected_artifacts", .list(e.expectedArtifacts)),
            ("gnss_time_ms", i(e.stamp.gnssTimeMs)), ("kind", .str(e.kind)), ("network_time_ms", i(e.stamp.networkTimeMs)),
            ("prev_hash", .str(prev)), ("profile_id", i(e.profileId)), ("refers_to", s(e.refersTo)),
            ("reviewed", .bool(e.reviewed)), ("revision_n", .int(Int64(e.revisionN))), ("scenario_id", .str(e.scenarioId)),
            ("seq", .int(e.seq)), ("source", .str(e.source)), ("time_quality", .str(e.timeQuality)), ("tz_id", .str(e.stamp.tzId)),
            ("tz_offset_min", .int(Int64(e.stamp.tzOffsetMin))), ("utc_ms", .int(e.stamp.utcMs)), ("window_ms", window),
        ]
    }

    public static func canonical(_ e: Entry, prevHash: String) -> String {
        let f = fields(e, prevHash)
        assert(f.map(\.0) == f.map(\.0).sorted(), "chain fields must be in sorted order")
        var out = "{"
        for (n, (k, v)) in f.enumerated() {
            if n > 0 { out += "," }
            str(&out, k); out += ":"
            switch v {
            case .null: out += "null"
            case .str(let x): str(&out, x)
            case .int(let x): out += String(x)
            case .bool(let x): out += x ? "true" : "false"
            case .list(let xs):
                out += "["
                for (j, x) in xs.enumerated() { if j > 0 { out += "," }; str(&out, x) }
                out += "]"
            }
        }
        return out + "}"
    }

    public static func rowHash(_ e: Entry, prevHash: String) -> String {
        sha256Hex(Data(canonical(e, prevHash: prevHash).utf8))  // canonical text is pure ASCII
    }

    private static let hex = Array("0123456789abcdef")

    private static func str(_ out: inout String, _ s: String) {
        out += "\""
        for c in s.utf16 {
            switch c {
            case 0x22: out += "\\\""
            case 0x5C: out += "\\\\"
            case 0x0A: out += "\\n"
            case 0x0D: out += "\\r"
            case 0x09: out += "\\t"
            case 0x08: out += "\\b"
            case 0x0C: out += "\\f"
            case 0x20..<0x7F: out.unicodeScalars.append(Unicode.Scalar(UInt8(c)))
            default:
                out += "\\u"
                out.append(hex[Int(c >> 12) & 0xF]); out.append(hex[Int(c >> 8) & 0xF])
                out.append(hex[Int(c >> 4) & 0xF]); out.append(hex[Int(c) & 0xF])
            }
        }
        out += "\""
    }

    /// Checks rows in seq order. Returns the seq of the first bad row, or nil when the chain is intact.
    public static func firstBreak(_ rows: [Entry]) -> Int64? {
        var prev = genesis
        for r in rows.sorted(by: { $0.seq < $1.seq }) {
            guard r.prevHash == prev, let h = r.rowHash, h == rowHash(r, prevHash: prev) else { return r.seq }
            prev = h
        }
        return nil
    }
}
