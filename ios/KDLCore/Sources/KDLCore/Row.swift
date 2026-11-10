import Foundation

/// One actions.jsonl object as written and read. Keys and order follow the Android app's Json.kt
/// (Entry.toJson), which follows the spec table.
public enum Row {
    /// Writes one row as a JSON line (no trailing newline). Non-ASCII text is written as UTF-8.
    public static func json(_ e: Entry, shot: Shot? = nil) -> String {
        var w = JSONWriter()
        w.int("schema", Int64(schemaVersion)); w.int("seq", e.seq); w.str("entry_id", e.entryId)
        w.int("revision_n", Int64(e.revisionN)); w.str("kind", e.kind); w.str("scenario_id", e.scenarioId)
        w.str("device_label", e.deviceLabel); w.int("profile_id", e.profileId); w.int("utc_ms", e.stamp.utcMs)
        w.str("tz_id", e.stamp.tzId); w.int("tz_offset_min", Int64(e.stamp.tzOffsetMin)); w.int("elapsed_ms", e.stamp.elapsedMs)
        w.int("boot_count", Int64(e.stamp.bootCount)); w.int("boot_wall_ms", e.stamp.bootWallMs)
        w.int("network_time_ms", e.stamp.networkTimeMs); w.int("gnss_time_ms", e.stamp.gnssTimeMs)
        w.str("time_quality", e.timeQuality); w.double("window_s", e.windowS); w.int("edited_utc_ms", e.editedUtcMs)
        w.str("app_package", e.appPackage); w.str("app_version", e.appVersion); w.str("account", e.account)
        w.str("action", e.action); w.str("content", e.content); w.str("counterpart", e.counterpart)
        w.str("direction", e.direction); w.str("conversation_id", e.conversationId); w.str("refers_to", e.refersTo)
        w.list("expected_artifacts", e.expectedArtifacts); w.str("detail", e.detail); w.str("source", e.source)
        w.bool("reviewed", e.reviewed); w.str("prev_hash", e.prevHash); w.str("row_hash", e.rowHash)
        if let shot {
            var s = JSONWriter()
            s.str("file", shot.file); s.str("sha256", shot.sha256); s.str("status", shot.status)
            w.raw("screenshot", s.text)
        } else {
            w.raw("screenshot", "null")
        }
        return w.text
    }

    /// The whole actions.jsonl: every row in seq order, screenshots on the original row (revision 0) only.
    public static func jsonl(_ rows: [Entry], shots: [String: Shot] = [:]) -> String {
        rows.sorted { $0.seq < $1.seq }.map { json($0, shot: $0.revisionN == 0 ? shots[$0.entryId] : nil) + "\n" }.joined()
    }

    /// Decoded form of a row, typed so booleans stay booleans and integers stay integers.
    struct Decoded: Decodable {
        let seq: Int64; let entry_id: String; let revision_n: Int; let kind: String
        let scenario_id: String; let device_label: String; let profile_id: Int64?
        let utc_ms: Int64; let tz_id: String; let tz_offset_min: Int; let elapsed_ms: Int64; let boot_count: Int
        let network_time_ms: Int64?; let gnss_time_ms: Int64?; let time_quality: String; let window_s: Double?
        let edited_utc_ms: Int64; let app_package: String?; let app_version: String?; let account: String?
        let action: String; let content: String?; let counterpart: String?; let direction: String?
        let conversation_id: String?; let refers_to: String?; let expected_artifacts: [String]?; let detail: String?
        let source: String?; let reviewed: Bool?; let prev_hash: String?; let row_hash: String?
    }

    /// Parses an actions.jsonl file (an Android or an iOS export).
    public static func parse(jsonl data: Data) throws -> [Entry] {
        let dec = JSONDecoder()
        return try data.split(separator: 0x0A).filter { !$0.allSatisfy { $0 == 0x20 || $0 == 0x0D } }.map { line in
            let d = try dec.decode(Decoded.self, from: Data(line))
            return Entry(
                seq: d.seq, entryId: d.entry_id, revisionN: d.revision_n, kind: d.kind, scenarioId: d.scenario_id,
                deviceLabel: d.device_label, profileId: d.profile_id,
                stamp: Stamp(utcMs: d.utc_ms, tzId: d.tz_id, tzOffsetMin: d.tz_offset_min, elapsedMs: d.elapsed_ms,
                             bootCount: d.boot_count, networkTimeMs: d.network_time_ms, gnssTimeMs: d.gnss_time_ms),
                timeQuality: d.time_quality, windowS: d.window_s, editedUtcMs: d.edited_utc_ms, appPackage: d.app_package,
                appVersion: d.app_version, account: d.account, action: d.action, content: d.content,
                counterpart: d.counterpart, direction: d.direction, conversationId: d.conversation_id,
                refersTo: d.refers_to, expectedArtifacts: d.expected_artifacts ?? [], detail: d.detail,
                source: d.source ?? Source.manual, reviewed: d.reviewed ?? true, prevHash: d.prev_hash, rowHash: d.row_hash)
        }
    }
}

/// Minimal ordered JSON object writer. Escapes what JSON requires and nothing else.
struct JSONWriter {
    private var parts: [String] = []
    var text: String { "{" + parts.joined(separator: ",") + "}" }

    static func quote(_ s: String) -> String {
        var out = "\""
        for u in s.unicodeScalars {
            switch u.value {
            case 0x22: out += "\\\""
            case 0x5C: out += "\\\\"
            case 0x0A: out += "\\n"
            case 0x0D: out += "\\r"
            case 0x09: out += "\\t"
            case 0x00..<0x20, 0x7F, 0x2028, 0x2029: out += String(format: "\\u%04x", u.value)
            default: out.unicodeScalars.append(u)
            }
        }
        return out + "\""
    }

    mutating func raw(_ k: String, _ v: String) { parts.append(Self.quote(k) + ":" + v) }
    mutating func str(_ k: String, _ v: String?) { raw(k, v.map(Self.quote) ?? "null") }
    mutating func int(_ k: String, _ v: Int64?) { raw(k, v.map { String($0) } ?? "null") }
    mutating func bool(_ k: String, _ v: Bool) { raw(k, v ? "true" : "false") }
    /// Shortest text that reads back as the same double, as Swift prints it ("5.894").
    mutating func double(_ k: String, _ v: Double?) {
        guard let v else { raw(k, "null"); return }
        precondition(v.isFinite, "window_s must be finite")
        raw(k, "\(v)")
    }
    mutating func list(_ k: String, _ v: [String]) { raw(k, "[" + v.map(Self.quote).joined(separator: ",") + "]") }
}
