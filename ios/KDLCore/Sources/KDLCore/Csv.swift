import Foundation

/// actions.csv, the same columns and order as the Android app (Model.kt Csv).
public enum Csv {
    public static let header = [
        "local_time", "utc_time",
        "action", "app_package", "content", "counterpart", "direction", "conversation_id",
        "is_current", "reviewed", "source", "kind", "time_quality", "window_s",
        "screenshot_file", "screenshot_status",
        "detail", "refers_to", "expected_artifacts", "account", "app_version", "device_label", "scenario_id",
        "tz_id", "tz_offset_min", "network_time_utc", "boot_count", "elapsed_ms", "utc_ms", "boot_wall_ms",
        "network_time_ms", "gnss_time_ms", "edited_utc_ms",
        "entry_id", "revision_n", "seq", "profile_id", "screenshot_sha256", "prev_hash", "row_hash",
    ]

    public static func field(_ v: String?) -> String {
        let s = v ?? ""
        if s.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" || $0 == "\r\n" }) {
            return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return s
    }

    private static func formatter(_ pattern: String, offsetMin: Int) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = TimeZone(secondsFromGMT: offsetMin * 60)
        f.dateFormat = pattern
        return f
    }

    /// Fixed millisecond width, so every value has the same shape and sorts as text.
    public static func localIso(_ utcMs: Int64, offsetMin: Int) -> String {
        formatter("yyyy-MM-dd'T'HH:mm:ss.SSSxxx", offsetMin: offsetMin).string(from: date(utcMs))
    }

    public static func utcIso(_ utcMs: Int64) -> String {
        formatter("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", offsetMin: 0).string(from: date(utcMs))
    }

    /// Whole milliseconds as a Date. A Double cannot hold most millisecond values exactly, and the
    /// formatter cuts .SSS rather than rounding, so 0.4 ms is added to land inside the right millisecond.
    private static func date(_ ms: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(ms) / 1000 + 0.0004)
    }

    public static func values(_ e: Entry, isCurrent: Bool, shot: Shot?) -> [String: String?] {
        func n(_ x: Int64?) -> String? { x.map { String($0) } }
        return [
            "local_time": localIso(e.stamp.utcMs, offsetMin: e.stamp.tzOffsetMin), "utc_time": utcIso(e.stamp.utcMs),
            "action": e.action, "app_package": e.appPackage, "content": e.content, "counterpart": e.counterpart,
            "direction": e.direction, "conversation_id": e.conversationId,
            "is_current": isCurrent ? "true" : "false", "reviewed": e.reviewed ? "true" : "false",
            "source": e.source, "kind": e.kind, "time_quality": e.timeQuality, "window_s": e.windowS.map { "\($0)" },
            "screenshot_file": shot?.file, "screenshot_status": shot?.status,
            "detail": e.detail, "refers_to": e.refersTo, "expected_artifacts": e.expectedArtifacts.joined(separator: ";"),
            "account": e.account, "app_version": e.appVersion, "device_label": e.deviceLabel, "scenario_id": e.scenarioId,
            "tz_id": e.stamp.tzId, "tz_offset_min": String(e.stamp.tzOffsetMin),
            "network_time_utc": e.stamp.networkTimeMs.map(utcIso),
            "boot_count": String(e.stamp.bootCount), "elapsed_ms": String(e.stamp.elapsedMs), "utc_ms": String(e.stamp.utcMs),
            "boot_wall_ms": String(e.stamp.bootWallMs), "network_time_ms": n(e.stamp.networkTimeMs),
            "gnss_time_ms": n(e.stamp.gnssTimeMs), "edited_utc_ms": String(e.editedUtcMs),
            "entry_id": e.entryId, "revision_n": String(e.revisionN), "seq": String(e.seq), "profile_id": n(e.profileId),
            "screenshot_sha256": shot?.sha256, "prev_hash": e.prevHash, "row_hash": e.rowHash,
        ]
    }

    /// All rows, every revision, in insertion order, with is_current marking the live version. Every
    /// revision shows its entry's screenshot, and a label made from a mark shows the mark's.
    public static func document(_ rows: [Entry], shots: [String: Shot] = [:]) -> String {
        let currentSeqs = Set(Timeline.current(rows).map(\.seq))
        var out = header.joined(separator: ",") + "\r\n"
        for e in rows.sorted(by: { $0.seq < $1.seq }) {
            let shot = shots[e.entryId] ?? e.refersTo.flatMap { shots[$0] }
            let v = values(e, isCurrent: currentSeqs.contains(e.seq), shot: shot)
            out += header.map { field(v[$0] ?? nil) }.joined(separator: ",") + "\r\n"
        }
        return out
    }
}
