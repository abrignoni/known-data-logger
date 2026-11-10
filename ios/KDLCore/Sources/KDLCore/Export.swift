import Foundation

/// The logger's own identity, written into the manifest.
public struct LoggerInfo: Sendable {
    public var package: String
    public var versionName: String?
    public init(package: String, versionName: String?) { self.package = package; self.versionName = versionName }
}

/// Writes an export folder with the Android layout (Repo.kt writeExport): manifest.json,
/// actions.jsonl, actions.csv and, when there are screenshots, screens/.
public enum Exporter {
    public static func folderName(scenarioId: String, deviceLabel: String, stamp: Stamp) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        func safe(_ x: String) -> String {
            let t = x.isEmpty ? "unnamed" : x
            return String(t.unicodeScalars.map { c -> Character in
                let ok = (c.value < 128) && (CharacterSet.alphanumerics.contains(c) || c == "." || c == "_" || c == "-")
                return ok ? Character(c) : "_"
            })
        }
        return "kdl_\(safe(scenarioId))_\(safe(deviceLabel))_\(f.string(from: Date(timeIntervalSince1970: TimeInterval(stamp.utcMs) / 1000)))"
    }

    /// Text for humans in the manifest's row_rules field: the Android sentence, then what differs on iOS.
    static let rowRules = "Every revision is a row. The current version of an entry is its highest revision_n; " +
        "a tombstone as the highest revision removes the entry. Action time fields never change across revisions. " +
        "Order rows by (boot_count, elapsed_ms, seq). " +
        "iOS: boot_count is -1 on every row because iOS exposes no boot counter. elapsed_ms is mach_continuous_time " +
        "(time since boot, sleep included) and starts again at a restart, so that order puts rows logged after a " +
        "restart before earlier ones; across a boot_detected row, read rows in seq order. " +
        "network_time_ms and gnss_time_ms are always null (no iOS source)."

    /// Logs the export as its own entry, then writes the folder under `parent`. A failure is logged
    /// as export_failed and rethrown. Returns the folder's URL.
    @discardableResult
    public static func export(store: Store, to parent: URL, logger: LoggerInfo, screensDir: URL? = nil) throws -> URL {
        let s = Clocks.stamp()
        let name = folderName(scenarioId: store.scenarioId, deviceLabel: store.deviceLabel, stamp: s)
        // The export row comes first so the export lists itself.
        try store.log(Draft(action: "export", detail: "folder \(name)"), stamp: s, kind: Kind.export)
        do {
            return try write(store: store, to: parent.appendingPathComponent(name, isDirectory: true), logger: logger,
                             stamp: s, screensDir: screensDir)
        } catch {
            _ = try? store.log(Draft(action: "export_failed", detail: "folder \(name): \(type(of: error))", source: Source.system),
                           kind: Kind.systemEvent)
            throw error
        }
    }

    static func write(store: Store, to dir: URL, logger: LoggerInfo, stamp s: Stamp, screensDir: URL?) throws -> URL {
        let fm = FileManager.default
        try fm.createDirectory(at: dir, withIntermediateDirectories: false)
        let rows = store.entries()
        let shots = store.shots()

        var exportHash: [String: String] = [:]
        for sh in shots.values.sorted(by: { $0.utcMs < $1.utcMs }) {
            guard let file = sh.file, let src = screensDir?.appendingPathComponent((file as NSString).lastPathComponent),
                  let data = try? Data(contentsOf: src) else { continue }
            let screens = dir.appendingPathComponent("screens", isDirectory: true)
            try fm.createDirectory(at: screens, withIntermediateDirectories: true)
            try data.write(to: screens.appendingPathComponent(src.lastPathComponent))
            exportHash[sh.entryId] = sha256Hex(data)
        }

        let jsonl = Data(Row.jsonl(rows, shots: shots).utf8)
        try jsonl.write(to: dir.appendingPathComponent("actions.jsonl"))
        try Data(Csv.document(rows, shots: shots).utf8).write(to: dir.appendingPathComponent("actions.csv"))

        func n(_ v: Any?) -> Any { v ?? NSNull() }
        let last = rows.max { $0.seq < $1.seq }
        let manifest: [String: Any] = [
            "format": "known-data-logger",
            "schema_version": schemaVersion,
            "logger": ["package": logger.package, "version_name": n(logger.versionName),
                       "first_install_ms": NSNull(), "last_update_ms": NSNull()],
            "scenario_id": store.scenarioId,
            "device_label": store.deviceLabel,
            "profiles": store.profiles().map { p -> [String: Any] in
                ["id": n(p.id), "utc_ms": p.utcMs, "boot_count": p.bootCount, "manufacturer": n(p.manufacturer),
                 "model": n(p.model), "device": n(p.device), "fingerprint": n(p.fingerprint), "release": n(p.release),
                 "incremental": n(p.incremental), "sdk_int": n(p.sdkInt), "security_patch": n(p.securityPatch),
                 "kernel": n(p.kernel), "baseband": n(p.baseband), "carrier": n(p.carrier), "notes": n(p.notes)]
            },
            // iOS cannot read another app's version, so version_name_at_export stays null.
            "apps_under_test": store.apps().map { a -> [String: Any] in
                ["package": a.pkg, "label": a.label, "version_name_at_add": n(a.versionName),
                 "version_code_at_add": n(a.versionCode), "version_name_at_export": NSNull(),
                 "first_install_ms": n(a.firstInstallMs), "last_update_ms": n(a.lastUpdateMs),
                 "account": n(a.account), "added_utc_ms": a.addedUtcMs]
            },
            "rules": [Any](),
            "tolerances_s": ["exact": Tolerance.exactS, "before_after": Tolerance.beforeAfterS,
                             "approximate": Tolerance.approximateS, "windowed": "window_s"],
            "row_rules": rowRules,
            "export": ["utc_ms": s.utcMs, "elapsed_ms": s.elapsedMs, "boot_count": s.bootCount,
                       "tz_id": s.tzId, "tz_offset_min": s.tzOffsetMin],
            "files": ["actions.jsonl": ["sha256": sha256Hex(jsonl), "rows": rows.count]],
            "hash_chain": ["rows": rows.count, "last_seq": n(last?.seq), "last_row_hash": n(last?.rowHash),
                           "first_break_seq": n(Chain.firstBreak(rows))],
            "screenshots": shots.values.sorted(by: { $0.utcMs < $1.utcMs }).map { sh -> [String: Any] in
                let now = exportHash[sh.entryId]
                let match: Any = sh.sha256 == nil ? NSNull() : (now == nil ? false : sh.sha256 == now)
                return ["entry_id": sh.entryId, "file": n(sh.file), "status": sh.status,
                        "sha256_at_capture": n(sh.sha256), "sha256_at_export": n(now), "hash_match": match]
            },
        ]
        let data = try JSONSerialization.data(withJSONObject: manifest, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
        try data.write(to: dir.appendingPathComponent("manifest.json"))  // written last, as on Android
        return dir
    }
}
