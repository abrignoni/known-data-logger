import Foundation

/// Same schema number as the Android app (Model.kt SCHEMA_VERSION): the rows and the export share one format.
public let schemaVersion = 3

/// Allowed values for Entry.kind (Model.kt Kind).
public enum Kind {
    public static let action = "action"
    public static let systemEvent = "system_event"
    public static let clockChange = "clock_change"
    public static let export = "export"
    public static let note = "note"
    public static let tombstone = "tombstone"
    /// Raw event recorded by the app itself (a mark; in Phase 2 a screenshot or an app switch).
    public static let autoEvent = "auto_event"
}

/// Where a row came from (Model.kt Source).
public enum Source {
    public static let manual = "manual"
    public static let autoEvent = "auto_event"
    public static let autoRule = "auto_rule"
    public static let mark = "mark"
    public static let system = "system"
}

/// Raw event actions (Model.kt AutoAction). iOS writes `mark` now; `screen` with the app's bundle id
/// as app_package is kept for the app-switch rows of the Phase 2 screen broadcast.
public enum AutoAction {
    public static let mark = "mark"
    public static let screen = "screen"
}

public enum TimeQuality {
    public static let exact = "exact"
    public static let after = "after"
    public static let before = "before"
    public static let approximate = "approximate"
    public static let all = [exact, after, before, approximate]
}

/// Default checker tolerances written into every manifest. Chosen defaults, not measured (Model.kt Tolerance).
public enum Tolerance {
    public static let exactS = 120
    public static let beforeAfterS = 300
    public static let approximateS = 900
}

/// iOS has no boot counter (none found in Apple's documentation; `kern.bootsessionuuid` is refused).
/// Rows carry -1, the value the Android app writes when Settings.Global.BOOT_COUNT is missing.
public let bootCountUnavailable = -1

/// Clock readings taken at one tap.
public struct Stamp: Equatable, Codable, Sendable {
    public var utcMs: Int64
    public var tzId: String
    public var tzOffsetMin: Int
    /// Time since boot, including sleep (mach_continuous_time).
    public var elapsedMs: Int64
    public var bootCount: Int
    public var networkTimeMs: Int64?
    public var gnssTimeMs: Int64?
    /// `kern.boottime` in epoch ms. Kept on the device for restart detection; not part of a row.
    public var kernBootMs: Int64?

    public init(utcMs: Int64, tzId: String, tzOffsetMin: Int, elapsedMs: Int64, bootCount: Int = bootCountUnavailable,
                networkTimeMs: Int64? = nil, gnssTimeMs: Int64? = nil, kernBootMs: Int64? = nil) {
        self.utcMs = utcMs; self.tzId = tzId; self.tzOffsetMin = tzOffsetMin; self.elapsedMs = elapsedMs
        self.bootCount = bootCount; self.networkTimeMs = networkTimeMs; self.gnssTimeMs = gnssTimeMs
        self.kernBootMs = kernBootMs
    }

    /// Wall clock minus time since boot. Constant within a boot unless the clock was set.
    public var bootWallMs: Int64 { utcMs - elapsedMs }
}

public struct Entry: Equatable, Sendable {
    public var seq: Int64 = 0
    public var entryId: String
    public var revisionN: Int = 0
    public var kind: String
    public var scenarioId: String
    public var deviceLabel: String
    public var profileId: Int64?
    public var stamp: Stamp
    public var timeQuality: String = TimeQuality.exact
    public var windowS: Double?
    public var editedUtcMs: Int64
    public var appPackage: String?
    public var appVersion: String?
    public var account: String?
    public var action: String = ""
    public var content: String?
    public var counterpart: String?
    public var direction: String?
    public var conversationId: String?
    public var refersTo: String?
    public var expectedArtifacts: [String] = []
    public var detail: String?
    public var source: String = Source.manual
    /// False only for auto-labelled actions nobody has accepted yet.
    public var reviewed: Bool = true
    public var prevHash: String?
    public var rowHash: String?

    public init(seq: Int64 = 0, entryId: String, revisionN: Int = 0, kind: String, scenarioId: String, deviceLabel: String,
                profileId: Int64?, stamp: Stamp, timeQuality: String = TimeQuality.exact, windowS: Double? = nil,
                editedUtcMs: Int64, appPackage: String? = nil, appVersion: String? = nil, account: String? = nil,
                action: String = "", content: String? = nil, counterpart: String? = nil, direction: String? = nil,
                conversationId: String? = nil, refersTo: String? = nil, expectedArtifacts: [String] = [],
                detail: String? = nil, source: String = Source.manual, reviewed: Bool = true,
                prevHash: String? = nil, rowHash: String? = nil) {
        self.seq = seq; self.entryId = entryId; self.revisionN = revisionN; self.kind = kind
        self.scenarioId = scenarioId; self.deviceLabel = deviceLabel; self.profileId = profileId; self.stamp = stamp
        self.timeQuality = timeQuality; self.windowS = windowS; self.editedUtcMs = editedUtcMs
        self.appPackage = appPackage; self.appVersion = appVersion; self.account = account; self.action = action
        self.content = content; self.counterpart = counterpart; self.direction = direction
        self.conversationId = conversationId; self.refersTo = refersTo; self.expectedArtifacts = expectedArtifacts
        self.detail = detail; self.source = source; self.reviewed = reviewed; self.prevHash = prevHash; self.rowHash = rowHash
    }

    public var isMark: Bool { kind == Kind.autoEvent && action == AutoAction.mark }
    /// A stamped entry that still has no action written.
    public var needsDetail: Bool { kind == Kind.action && action.trimmingCharacters(in: .whitespaces).isEmpty }

    /// Free text cleaned of unpaired UTF-16 surrogates before it is stored and hashed (Model.kt Entry.cleaned).
    public func cleaned() -> Entry {
        var e = self
        e.action = cleanText(action) ?? ""
        e.content = cleanText(content); e.counterpart = cleanText(counterpart)
        e.conversationId = cleanText(conversationId); e.detail = cleanText(detail); e.account = cleanText(account)
        e.appVersion = cleanText(appVersion); e.expectedArtifacts = expectedArtifacts.map { cleanText($0) ?? "" }
        e.scenarioId = cleanText(scenarioId) ?? ""; e.deviceLabel = cleanText(deviceLabel) ?? ""
        return e
    }
}

/// Replaces unpaired UTF-16 surrogates with U+FFFD. A Swift String cannot hold one, so this only
/// matters for text that arrives as raw UTF-16; it is kept so the rule matches the Android app.
public func cleanText(_ s: String?) -> String? {
    guard let s else { return nil }
    let units = Array(s.utf16)
    var out = [UInt16](); out.reserveCapacity(units.count)
    var i = 0
    while i < units.count {
        let c = units[i]
        if UTF16.isLeadSurrogate(c), i + 1 < units.count, UTF16.isTrailSurrogate(units[i + 1]) {
            out.append(c); out.append(units[i + 1]); i += 2; continue
        }
        out.append(UTF16.isLeadSurrogate(c) || UTF16.isTrailSurrogate(c) ? 0xFFFD : c)
        i += 1
    }
    return String(decoding: out, as: UTF16.self)
}

public enum Timeline {
    /// Ordering key: boot, then time since boot, then insertion order (Model.kt Timeline.ORDER).
    public static func ordered(_ a: Entry, _ b: Entry) -> Bool {
        if a.stamp.bootCount != b.stamp.bootCount { return a.stamp.bootCount < b.stamp.bootCount }
        if a.stamp.elapsedMs != b.stamp.elapsedMs { return a.stamp.elapsedMs < b.stamp.elapsedMs }
        return a.seq < b.seq
    }

    /// Highest revision of each entry, dropping entries whose latest revision is a tombstone.
    public static func current(_ rows: [Entry]) -> [Entry] {
        var best: [String: Entry] = [:]
        for r in rows {
            if let b = best[r.entryId], (b.revisionN, b.seq) >= (r.revisionN, r.seq) { continue }
            best[r.entryId] = r
        }
        return best.values.filter { $0.kind != Kind.tombstone }.sorted(by: ordered)
    }

    /// Rows to log before a new stamp: a restart, a wall clock set, or a time zone change since the last stamp.
    ///
    /// Android compares boot counts; iOS has none, so a restart is detected when time since boot went
    /// backwards (mach_continuous_time restarts at boot). A restart that is not caught that way (the
    /// previous row was logged soon after its own boot) shows up as a wall clock move, and the detail says so.
    /// The detail also gives how far `kern.boottime` moved, so the two readings can be compared.
    public static func detectChanges(last: Stamp?, next: Stamp, thresholdMs: Int64 = 2000) -> [(kind: String, action: String, detail: String)] {
        guard let last else { return [] }
        var out: [(String, String, String)] = []
        let kern: String = {
            guard let a = last.kernBootMs, let b = next.kernBootMs else { return "" }
            return "; kern.boottime moved \(b - a) ms"
        }()
        if next.bootCount >= 0, last.bootCount >= 0, next.bootCount != last.bootCount {
            out.append((Kind.systemEvent, "boot_detected", "boot_count changed from \(last.bootCount) to \(next.bootCount)"))
        } else if next.elapsedMs < last.elapsedMs {
            out.append((Kind.systemEvent, "boot_detected",
                        "time since boot went back from \(last.elapsedMs) to \(next.elapsedMs) ms: the phone restarted\(kern)"))
        } else {
            let delta = next.bootWallMs - last.bootWallMs
            if abs(delta) > thresholdMs {
                var text = "wall clock moved \(delta) ms relative to the monotonic clock since the previous entry\(kern)"
                if next.bootWallMs > last.utcMs {
                    text += "; a restart since the previous entry would look the same"
                }
                out.append((Kind.clockChange, "clock_change_detected", text))
            }
        }
        if next.tzId != last.tzId || next.tzOffsetMin != last.tzOffsetMin {
            out.append((Kind.clockChange, "clock_change_detected",
                        "time zone changed from \(last.tzId) (\(last.tzOffsetMin) min) to \(next.tzId) (\(next.tzOffsetMin) min)"))
        }
        return out
    }
}

/// What still needs a person (Model.kt Review): unlabelled marks and stamped-only entries.
public enum Review {
    public static func pending(_ rows: [Entry]) -> [Entry] {
        let cur = Timeline.current(rows)
        let resolved = Set(cur.compactMap(\.refersTo))
        return cur.filter {
            ($0.kind == Kind.action && $0.source == Source.autoRule && !$0.reviewed) ||
                ($0.isMark && !resolved.contains($0.entryId)) || $0.needsDetail
        }
    }
}

/// A screenshot tied to one entry (Model.kt Shot). Status is ok, blank, failed:<reason> or unsupported.
/// Nothing writes these yet on iOS; the Phase 2 broadcast extension will.
public struct Shot: Equatable, Sendable {
    public var entryId: String
    public var file: String?
    public var sha256: String?
    public var status: String
    public var utcMs: Int64
    public init(entryId: String, file: String?, sha256: String?, status: String, utcMs: Int64) {
        self.entryId = entryId; self.file = file; self.sha256 = sha256; self.status = status; self.utcMs = utcMs
    }
}

/// Action templates offered on the Log screen (Model.kt ACTION_TEMPLATES). Keys are written as is.
public let actionTemplates = [
    "sent_message", "received_message", "edited_message", "deleted_message",
    "outgoing_call", "incoming_call", "missed_call",
    "took_photo", "recorded_video", "recorded_audio", "sent_media", "received_media",
    "visited_url", "searched", "viewed_location", "navigated",
    "added_contact", "deleted_item", "created_account", "logged_in", "logged_out",
    "installed_app", "uninstalled_app",
    "airplane_mode_on", "airplane_mode_off", "connected_wifi", "connected_bluetooth",
    "changed_clock", "changed_time_zone", "rebooted", "powered_off", "powered_on",
    "inserted_sim", "removed_sim", "factory_reset",
]

public let factoryReset = "factory_reset"

public func templateLabel(_ key: String) -> String { key.replacingOccurrences(of: "_", with: " ") }

/// "in" for the received templates, "out" for any other action, none while the action is blank (Model.kt directionFor).
public func directionFor(_ action: String) -> String? {
    switch action.trimmingCharacters(in: .whitespaces) {
    case "received_message", "incoming_call", "missed_call", "received_media": return "in"
    case "": return nil
    default: return "out"
    }
}

private let systemLabels: [String: String] = [
    "capture_session_start": "Session started", "capture_session_stop": "Session stopped",
    "boot_detected": "Phone restarted", "clock_change_detected": "Clock changed", "export": "Exported",
    "export_failed": "Export failed", "profile_snapshot": "Device snapshot",
    "screen_capture_started": "Screen capture started", "screen_capture_stopped": "Screen capture stopped",
]

/// The words shown for an entry in the interface (the log keeps the action key).
public func displayLabel(_ e: Entry) -> String {
    if e.isMark { return "Mark" }
    if e.needsDetail { return "Stamped, no action" }
    if let s = systemLabels[e.action] { return s }
    let t = templateLabel(e.action)
    return t.prefix(1).uppercased() + t.dropFirst()
}
