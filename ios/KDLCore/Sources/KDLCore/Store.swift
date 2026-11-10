import Foundation
import SQLite3

/// What the Log screen, an App Intent or a widget hands over for one entry (Repo.kt Draft).
public struct Draft: Sendable {
    public var appPackage: String?
    public var action: String
    public var content: String?
    public var counterpart: String?
    public var direction: String?
    public var conversationId: String?
    public var timeQuality: String
    public var expectedArtifacts: [String]
    public var detail: String?
    public var refersTo: String?
    public var source: String
    public var reviewed: Bool

    public init(appPackage: String? = nil, action: String = "", content: String? = nil, counterpart: String? = nil,
                direction: String? = nil, conversationId: String? = nil, timeQuality: String = TimeQuality.exact,
                expectedArtifacts: [String] = [], detail: String? = nil, refersTo: String? = nil,
                source: String = Source.manual, reviewed: Bool = true) {
        self.appPackage = appPackage; self.action = action; self.content = content; self.counterpart = counterpart
        self.direction = direction; self.conversationId = conversationId; self.timeQuality = timeQuality
        self.expectedArtifacts = expectedArtifacts; self.detail = detail; self.refersTo = refersTo
        self.source = source; self.reviewed = reviewed
    }
}

/// One device profile snapshot. Same columns as the Android profiles table; on iOS, manufacturer is
/// "Apple", model is hw.machine, device is UIDevice.model, release is the iOS version, incremental is
/// kern.osversion (the build), kernel is kern.osrelease. Fingerprint, sdk_int, security_patch and
/// baseband have no iOS source and stay empty.
public struct Profile: Equatable, Sendable {
    public var id: Int64?
    public var utcMs: Int64 = 0
    public var elapsedMs: Int64 = 0
    public var bootCount: Int = bootCountUnavailable
    public var manufacturer: String?
    public var model: String?
    public var device: String?
    public var fingerprint: String?
    public var release: String?
    public var incremental: String?
    public var sdkInt: Int?
    public var securityPatch: String?
    public var kernel: String?
    public var baseband: String?
    public var carrier: String?
    public var notes: String?

    public init(manufacturer: String? = nil, model: String? = nil, device: String? = nil, release: String? = nil,
                incremental: String? = nil, kernel: String? = nil, carrier: String? = nil, notes: String? = nil) {
        self.manufacturer = manufacturer; self.model = model; self.device = device; self.release = release
        self.incremental = incremental; self.kernel = kernel; self.carrier = carrier; self.notes = notes
    }
}

/// An app under test. iOS cannot read another app's version or install time, so the tester types the
/// version; package holds the bundle id when the tester knows it, else the app's name.
public struct AppUnderTest: Equatable, Sendable, Identifiable {
    public var pkg: String
    public var label: String
    public var versionName: String?
    public var versionCode: Int64?
    public var firstInstallMs: Int64?
    public var lastUpdateMs: Int64?
    public var account: String?
    public var addedUtcMs: Int64
    public var id: String { pkg }

    public init(pkg: String, label: String, versionName: String? = nil, account: String? = nil, addedUtcMs: Int64) {
        self.pkg = pkg; self.label = label; self.versionName = versionName; self.account = account; self.addedUtcMs = addedUtcMs
    }
}

public enum StoreError: Error, CustomStringConvertible {
    case sqlite(String)
    public var description: String { switch self { case .sqlite(let m): return m } }
}

/// The logger's database, kl.db, with the Android app's schema 3 (docs/DATABASE.md). Rollback journal,
/// never WAL. The app, its App Intents and its widget extension each open it, so every write runs in
/// one `BEGIN IMMEDIATE` transaction that reads the newest hash and the last stamp, inserts, and
/// commits: two processes can never hash over the same previous row.
public final class Store: @unchecked Sendable {
    public let url: URL
    private var db: OpaquePointer?
    private let lock = NSLock()

    public static let fileName = "kl.db"

    public init(url: URL) throws {
        self.url = url
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            throw StoreError.sqlite("could not open \(url.lastPathComponent)")
        }
        sqlite3_busy_timeout(db, 10_000)
        _ = try query("PRAGMA journal_mode=DELETE")
        try migrate()
    }

    deinit { sqlite3_close(db) }

    // MARK: SQLite helpers

    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    enum Val { case null, int(Int64), real(Double), text(String) }

    private func err() -> StoreError { StoreError.sqlite(String(cString: sqlite3_errmsg(db))) }

    @discardableResult
    private func query(_ sql: String, _ args: [Val] = []) throws -> [[String: Val]] {
        var st: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK else { throw err() }
        defer { sqlite3_finalize(st) }
        for (i, a) in args.enumerated() {
            let n = Int32(i + 1)
            switch a {
            case .null: sqlite3_bind_null(st, n)
            case .int(let v): sqlite3_bind_int64(st, n, v)
            case .real(let v): sqlite3_bind_double(st, n, v)
            case .text(let v): sqlite3_bind_text(st, n, v, -1, Store.transient)
            }
        }
        var out: [[String: Val]] = []
        while true {
            let rc = sqlite3_step(st)
            if rc == SQLITE_DONE { break }
            guard rc == SQLITE_ROW else { throw err() }
            var row: [String: Val] = [:]
            for c in 0..<sqlite3_column_count(st) {
                let name = String(cString: sqlite3_column_name(st, c))
                switch sqlite3_column_type(st, c) {
                case SQLITE_INTEGER: row[name] = .int(sqlite3_column_int64(st, c))
                case SQLITE_FLOAT: row[name] = .real(sqlite3_column_double(st, c))
                case SQLITE_TEXT: row[name] = .text(String(cString: sqlite3_column_text(st, c)))
                default: row[name] = .null
                }
            }
            out.append(row)
        }
        return out
    }

    private func exec(_ sql: String) throws {
        guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else { throw err() }
    }

    /// Runs body inside BEGIN IMMEDIATE ... COMMIT, holding the database's write lock for its whole length.
    private func transaction<T>(_ body: () throws -> T) throws -> T {
        lock.lock(); defer { lock.unlock() }
        try exec("BEGIN IMMEDIATE")
        do {
            let v = try body()
            try exec("COMMIT")
            return v
        } catch {
            try? exec("ROLLBACK")
            written = []
            throw error
        }
    }

    private static func s(_ v: String?) -> Val { v.map(Val.text) ?? .null }
    private static func i(_ v: Int64?) -> Val { v.map(Val.int) ?? .null }

    // MARK: schema

    private func userVersion() throws -> Int {
        if case .int(let v)? = try query("PRAGMA user_version").first?["user_version"] { return Int(v) }
        return 0
    }

    private func migrate() throws {
        lock.lock(); defer { lock.unlock() }
        try exec("BEGIN IMMEDIATE")
        do {
            if try userVersion() == 0 {
                try exec("""
                CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT);
                CREATE TABLE profiles (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    utc_ms INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, boot_count INTEGER NOT NULL,
                    manufacturer TEXT, model TEXT, device TEXT, fingerprint TEXT, release TEXT,
                    incremental TEXT, sdk_int INTEGER, security_patch TEXT, kernel TEXT, baseband TEXT,
                    carrier TEXT, notes TEXT);
                CREATE TABLE apps (
                    package TEXT PRIMARY KEY, label TEXT, version_name TEXT, version_code INTEGER,
                    first_install_ms INTEGER, last_update_ms INTEGER, account TEXT,
                    added_utc_ms INTEGER NOT NULL);
                CREATE TABLE entries (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    entry_id TEXT NOT NULL, revision_n INTEGER NOT NULL, kind TEXT NOT NULL,
                    scenario_id TEXT NOT NULL, device_label TEXT NOT NULL, profile_id INTEGER,
                    utc_ms INTEGER NOT NULL, tz_id TEXT NOT NULL, tz_offset_min INTEGER NOT NULL,
                    elapsed_ms INTEGER NOT NULL, boot_count INTEGER NOT NULL,
                    network_time_ms INTEGER, gnss_time_ms INTEGER,
                    time_quality TEXT NOT NULL, window_s REAL, edited_utc_ms INTEGER NOT NULL,
                    app_package TEXT, app_version TEXT, account TEXT, action TEXT NOT NULL,
                    content TEXT, counterpart TEXT, direction TEXT, conversation_id TEXT,
                    refers_to TEXT, expected_artifacts TEXT NOT NULL, detail TEXT,
                    source TEXT NOT NULL DEFAULT 'manual', reviewed INTEGER NOT NULL DEFAULT 1,
                    prev_hash TEXT, row_hash TEXT);
                CREATE INDEX entries_entry_id ON entries(entry_id);
                CREATE TABLE screenshots (
                    entry_id TEXT PRIMARY KEY, file TEXT, sha256 TEXT, status TEXT NOT NULL,
                    utc_ms INTEGER NOT NULL);
                PRAGMA user_version = \(schemaVersion);
                """)
            }
            try exec("COMMIT")
        } catch {
            try? exec("ROLLBACK")
            throw error
        }
    }

    // MARK: settings

    public func setting(_ key: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        if case .text(let v)? = try? query("SELECT value FROM settings WHERE key=?", [.text(key)]).first?["value"] { return v }
        return nil
    }

    public func putSetting(_ key: String, _ value: String?) throws {
        lock.lock(); defer { lock.unlock() }
        try putSettingLocked(key, value)
    }

    private func putSettingLocked(_ key: String, _ value: String?) throws {
        if let value {
            try query("INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                      [.text(key), .text(value)])
        } else {
            try query("DELETE FROM settings WHERE key=?", [.text(key)])
        }
    }

    public var scenarioId: String {
        get { setting("scenario_id") ?? "" }
        set { try? putSetting("scenario_id", newValue.trimmingCharacters(in: .whitespacesAndNewlines)) }
    }

    public var deviceLabel: String {
        get { setting("device_label") ?? "" }
        set { try? putSetting("device_label", newValue.trimmingCharacters(in: .whitespacesAndNewlines)) }
    }

    public func stamp(forKey key: String) -> Stamp? {
        setting(key).flatMap { try? JSONDecoder().decode(Stamp.self, from: Data($0.utf8)) }
    }

    public func putStamp(_ s: Stamp?, forKey key: String) throws {
        try putSetting(key, s.flatMap { try? String(decoding: JSONEncoder().encode($0), as: UTF8.self) })
    }

    // MARK: profiles and apps

    public func profiles() -> [Profile] {
        lock.lock(); defer { lock.unlock() }
        return ((try? query("SELECT * FROM profiles ORDER BY id")) ?? []).map { r in
            var p = Profile()
            p.id = r.int("id"); p.utcMs = r.int("utc_ms") ?? 0; p.elapsedMs = r.int("elapsed_ms") ?? 0
            p.bootCount = Int(r.int("boot_count") ?? Int64(bootCountUnavailable))
            p.manufacturer = r.text("manufacturer"); p.model = r.text("model"); p.device = r.text("device")
            p.fingerprint = r.text("fingerprint"); p.release = r.text("release"); p.incremental = r.text("incremental")
            p.sdkInt = r.int("sdk_int").map(Int.init); p.securityPatch = r.text("security_patch")
            p.kernel = r.text("kernel"); p.baseband = r.text("baseband"); p.carrier = r.text("carrier"); p.notes = r.text("notes")
            return p
        }
    }

    /// Adds a profile snapshot (never overwrites) and logs a profile_snapshot row in the same transaction.
    @discardableResult
    public func addProfile(_ p: Profile, stamp s: Stamp = Clocks.stamp()) throws -> Int64 {
        try transaction {
            try query("""
                INSERT INTO profiles(utc_ms, elapsed_ms, boot_count, manufacturer, model, device, fingerprint, release,
                incremental, sdk_int, security_patch, kernel, baseband, carrier, notes)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, [.int(s.utcMs), .int(s.elapsedMs), .int(Int64(s.bootCount)), Self.s(p.manufacturer), Self.s(p.model),
                      Self.s(p.device), Self.s(p.fingerprint), Self.s(p.release), Self.s(p.incremental),
                      Self.i(p.sdkInt.map(Int64.init)), Self.s(p.securityPatch), Self.s(p.kernel), Self.s(p.baseband),
                      Self.s(p.carrier?.nilIfBlank), Self.s(p.notes?.nilIfBlank)])
            let id = sqlite3_last_insert_rowid(db)
            _ = try logLocked(Draft(action: "profile_snapshot", detail: "profile id \(id)"), stamp: s, windowS: nil,
                              kind: Kind.systemEvent, detect: true)
            return id
        }
    }

    public func apps() -> [AppUnderTest] {
        lock.lock(); defer { lock.unlock() }
        return ((try? query("SELECT * FROM apps ORDER BY label COLLATE NOCASE")) ?? []).map { r in
            var a = AppUnderTest(pkg: r.text("package") ?? "", label: r.text("label") ?? "", versionName: r.text("version_name"),
                                 account: r.text("account"), addedUtcMs: r.int("added_utc_ms") ?? 0)
            a.versionCode = r.int("version_code"); a.firstInstallMs = r.int("first_install_ms"); a.lastUpdateMs = r.int("last_update_ms")
            return a
        }
    }

    public func upsertApp(_ a: AppUnderTest) throws {
        lock.lock(); defer { lock.unlock() }
        try query("""
            INSERT INTO apps(package, label, version_name, version_code, first_install_ms, last_update_ms, account, added_utc_ms)
            VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(package) DO UPDATE SET label=excluded.label,
            version_name=excluded.version_name, account=excluded.account
            """, [.text(a.pkg), .text(a.label), Self.s(a.versionName?.nilIfBlank), Self.i(a.versionCode), Self.i(a.firstInstallMs),
                  Self.i(a.lastUpdateMs), Self.s(a.account?.nilIfBlank), .int(a.addedUtcMs)])
    }

    public func removeApp(_ pkg: String) throws {
        lock.lock(); defer { lock.unlock() }
        try query("DELETE FROM apps WHERE package=?", [.text(pkg)])
    }

    // MARK: entries

    public func entries() -> [Entry] {
        lock.lock(); defer { lock.unlock() }
        return entriesLocked()
    }

    private func entriesLocked() -> [Entry] {
        ((try? query("SELECT * FROM entries ORDER BY seq")) ?? []).map { r in
            let arts = (r.text("expected_artifacts")).flatMap { try? JSONDecoder().decode([String].self, from: Data($0.utf8)) } ?? []
            return Entry(
                seq: r.int("seq") ?? 0, entryId: r.text("entry_id") ?? "", revisionN: Int(r.int("revision_n") ?? 0),
                kind: r.text("kind") ?? "", scenarioId: r.text("scenario_id") ?? "", deviceLabel: r.text("device_label") ?? "",
                profileId: r.int("profile_id"),
                stamp: Stamp(utcMs: r.int("utc_ms") ?? 0, tzId: r.text("tz_id") ?? "", tzOffsetMin: Int(r.int("tz_offset_min") ?? 0),
                             elapsedMs: r.int("elapsed_ms") ?? 0, bootCount: Int(r.int("boot_count") ?? -1),
                             networkTimeMs: r.int("network_time_ms"), gnssTimeMs: r.int("gnss_time_ms")),
                timeQuality: r.text("time_quality") ?? TimeQuality.exact, windowS: r.real("window_s"),
                editedUtcMs: r.int("edited_utc_ms") ?? 0, appPackage: r.text("app_package"), appVersion: r.text("app_version"),
                account: r.text("account"), action: r.text("action") ?? "", content: r.text("content"),
                counterpart: r.text("counterpart"), direction: r.text("direction"), conversationId: r.text("conversation_id"),
                refersTo: r.text("refers_to"), expectedArtifacts: arts, detail: r.text("detail"),
                source: r.text("source") ?? Source.manual, reviewed: (r.int("reviewed") ?? 1) != 0,
                prevHash: r.text("prev_hash"), rowHash: r.text("row_hash"))
        }
    }

    public func shots() -> [String: Shot] {
        lock.lock(); defer { lock.unlock() }
        var out: [String: Shot] = [:]
        for r in (try? query("SELECT * FROM screenshots")) ?? [] {
            let id = r.text("entry_id") ?? ""
            out[id] = Shot(entryId: id, file: r.text("file"), sha256: r.text("sha256"), status: r.text("status") ?? "", utcMs: r.int("utc_ms") ?? 0)
        }
        return out
    }

    /// The newest row's hash, shown on the Setup tab so the tester can note it.
    public func lastRowHash() -> String? {
        lock.lock(); defer { lock.unlock() }
        if case .text(let h)? = try? query("SELECT row_hash FROM entries ORDER BY seq DESC LIMIT 1").first?["row_hash"] { return h }
        return nil
    }

    /// Inserts one row with its place in the chain. Caller holds the write transaction.
    private func writeLocked(_ raw: Entry) throws -> Entry {
        var e = raw.cleaned()
        var prev = Chain.genesis
        if case .text(let h)? = try query("SELECT row_hash FROM entries ORDER BY seq DESC LIMIT 1").first?["row_hash"] { prev = h }
        let arts = String(decoding: try JSONEncoder().encode(e.expectedArtifacts), as: UTF8.self)
        try query("""
            INSERT INTO entries(entry_id, revision_n, kind, scenario_id, device_label, profile_id, utc_ms, tz_id, tz_offset_min,
            elapsed_ms, boot_count, network_time_ms, gnss_time_ms, time_quality, window_s, edited_utc_ms, app_package,
            app_version, account, action, content, counterpart, direction, conversation_id, refers_to, expected_artifacts,
            detail, source, reviewed) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, [.text(e.entryId), .int(Int64(e.revisionN)), .text(e.kind), .text(e.scenarioId), .text(e.deviceLabel),
                  Self.i(e.profileId), .int(e.stamp.utcMs), .text(e.stamp.tzId), .int(Int64(e.stamp.tzOffsetMin)),
                  .int(e.stamp.elapsedMs), .int(Int64(e.stamp.bootCount)), Self.i(e.stamp.networkTimeMs), Self.i(e.stamp.gnssTimeMs),
                  .text(e.timeQuality), e.windowS.map(Val.real) ?? .null, .int(e.editedUtcMs), Self.s(e.appPackage),
                  Self.s(e.appVersion), Self.s(e.account), .text(e.action), Self.s(e.content), Self.s(e.counterpart),
                  Self.s(e.direction), Self.s(e.conversationId), Self.s(e.refersTo), .text(arts), Self.s(e.detail),
                  .text(e.source), .int(e.reviewed ? 1 : 0)])
        e.seq = sqlite3_last_insert_rowid(db)
        e.prevHash = prev
        let h = Chain.rowHash(e, prevHash: prev)
        try query("UPDATE entries SET prev_hash=?, row_hash=? WHERE seq=?", [.text(prev), .text(h), .int(e.seq)])
        e.rowHash = h
        written.append(e)
        return e
    }

    /// Rows written by the current transaction, appended to the live JSONL file once it commits.
    private var written: [Entry] = []

    private func base(_ kind: String, _ s: Stamp) -> Entry {
        let pid: Int64? = { if case .int(let v)? = try? query("SELECT MAX(id) AS m FROM profiles").first?["m"] { return v }; return nil }()
        return Entry(entryId: UUID().uuidString.lowercased(), kind: kind, scenarioId: setting2("scenario_id"),
                     deviceLabel: setting2("device_label"), profileId: pid, stamp: s, editedUtcMs: s.utcMs)
    }

    private func setting2(_ key: String) -> String {
        if case .text(let v)? = try? query("SELECT value FROM settings WHERE key=?", [.text(key)]).first?["value"] { return v }
        return ""
    }

    private func appLocked(_ pkg: String?) -> (version: String?, account: String?) {
        guard let pkg, let r = try? query("SELECT version_name, account FROM apps WHERE package=?", [.text(pkg)]).first else { return (nil, nil) }
        return (r.text("version_name"), r.text("account"))
    }

    /// Logs an entry. Change detection compares with the last stamp logged with detection on (kept in
    /// settings, so the app and its extensions share it) and writes any system rows first.
    /// Back-dated rows (a Start/Done window) pass detect: false. Returns the system rows, then the entry.
    @discardableResult
    public func log(_ d: Draft, stamp: Stamp = Clocks.stamp(), windowS: Double? = nil, kind: String = Kind.action,
                    detect: Bool = true) throws -> [Entry] {
        let rows = try transaction { try logLocked(d, stamp: stamp, windowS: windowS, kind: kind, detect: detect) }
        flushLive()
        return rows
    }

    private func logLocked(_ d: Draft, stamp: Stamp, windowS: Double?, kind: String, detect: Bool) throws -> [Entry] {
        var out: [Entry] = []
        if detect {
            let last: Stamp? = {
                guard case .text(let j)? = try? query("SELECT value FROM settings WHERE key='last_stamp'").first?["value"] else { return nil }
                return try? JSONDecoder().decode(Stamp.self, from: Data(j.utf8))
            }()
            for c in Timeline.detectChanges(last: last, next: stamp) {
                var e = base(c.kind, stamp)
                e.action = c.action; e.detail = c.detail; e.source = Source.system
                out.append(try writeLocked(e))
            }
            try putSettingLocked("last_stamp", String(decoding: try JSONEncoder().encode(stamp), as: UTF8.self))
        }
        var e = base(kind, stamp)
        let app = appLocked(d.appPackage)
        e.timeQuality = d.timeQuality; e.windowS = windowS; e.appPackage = d.appPackage?.nilIfBlank
        e.appVersion = app.version; e.account = app.account; e.action = d.action
        e.content = d.content?.nilIfBlank; e.counterpart = d.counterpart?.nilIfBlank; e.direction = d.direction?.nilIfBlank
        e.conversationId = d.conversationId?.nilIfBlank; e.refersTo = d.refersTo; e.expectedArtifacts = d.expectedArtifacts
        e.detail = d.detail?.nilIfBlank; e.source = d.source; e.reviewed = d.reviewed
        out.append(try writeLocked(e))
        return out
    }

    /// Writes a new revision. The action time (stamp) never changes across revisions.
    @discardableResult
    public func revise(_ current: Entry, _ d: Draft) throws -> Entry {
        let now = Clocks.stamp()
        let e = try transaction { () -> Entry in
            var r = current
            r.seq = 0; r.revisionN = current.revisionN + 1; r.editedUtcMs = now.utcMs
            if d.appPackage != current.appPackage {
                let app = appLocked(d.appPackage)
                r.appVersion = app.version; r.account = app.account
            }
            r.appPackage = d.appPackage?.nilIfBlank; r.action = d.action; r.content = d.content?.nilIfBlank
            r.counterpart = d.counterpart?.nilIfBlank; r.direction = d.direction?.nilIfBlank
            r.conversationId = d.conversationId?.nilIfBlank; r.timeQuality = d.timeQuality
            r.expectedArtifacts = d.expectedArtifacts; r.detail = d.detail?.nilIfBlank; r.reviewed = true
            r.prevHash = nil; r.rowHash = nil
            return try writeLocked(r)
        }
        flushLive()
        return e
    }

    /// Removes an entry by writing a tombstone revision; the earlier rows stay.
    @discardableResult
    public func tombstone(_ current: Entry) throws -> Entry {
        let now = Clocks.stamp()
        let e = try transaction { () -> Entry in
            var r = current
            r.seq = 0; r.revisionN = current.revisionN + 1; r.kind = Kind.tombstone; r.editedUtcMs = now.utcMs
            r.prevHash = nil; r.rowHash = nil
            return try writeLocked(r)
        }
        flushLive()
        return e
    }

    // MARK: sessions

    /// A session is the stretch of a test the tester is logging. On iOS nothing is captured
    /// automatically yet, so it only frames the hand-logged rows (and drives the Live Activity).
    public var sessionActive: Bool { setting("capture_session_active") == "1" }

    public func sessionStart() -> Stamp? { stamp(forKey: "session_start") }

    public func startSession() throws {
        let s = Clocks.stamp()
        let apps = self.apps().map(\.pkg).sorted().joined(separator: " ")
        try putSetting("capture_session_active", "1")
        try putStamp(s, forKey: "session_start")
        try log(Draft(action: "capture_session_start", detail: "apps: " + (apps.isEmpty ? "none listed" : apps) + "; hand logging only",
                      source: Source.system), stamp: s, kind: Kind.systemEvent)
    }

    public func stopSession() throws {
        try putSetting("capture_session_active", "0")
        try log(Draft(action: "capture_session_stop", source: Source.system), kind: Kind.systemEvent)
    }

    // MARK: live copy

    /// actions-live.jsonl beside kl.db: every committed row, appended as it is written.
    public var liveURL: URL { url.deletingLastPathComponent().appendingPathComponent("actions-live.jsonl") }

    private func flushLive() {
        lock.lock()
        let rows = written; written = []
        lock.unlock()
        guard !rows.isEmpty else { return }
        let data = Data(rows.map { Row.json($0) + "\n" }.joined().utf8)
        // The database row is the record; the live copy is a convenience, so a failure here is ignored.
        if let h = try? FileHandle(forWritingTo: liveURL) {
            _ = try? h.seekToEnd(); try? h.write(contentsOf: data); try? h.close()
        } else {
            try? data.write(to: liveURL)
        }
    }
}

extension Dictionary where Key == String, Value == Store.Val {
    func int(_ k: String) -> Int64? { if case .int(let v)? = self[k] { return v }; return nil }
    func real(_ k: String) -> Double? {
        switch self[k] { case .real(let v)?: return v; case .int(let v)?: return Double(v); default: return nil }
    }
    func text(_ k: String) -> String? { if case .text(let v)? = self[k] { return v }; return nil }
}

extension String {
    var nilIfBlank: String? { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self }
}
