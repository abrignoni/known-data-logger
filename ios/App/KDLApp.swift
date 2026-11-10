import AppIntents
import KDLCore
import SwiftUI
import UIKit

@main
struct KDLApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .onChange(of: phase) { _, p in if p == .active { model.closeOrphanSession(); model.refresh() } }
        }
    }
}

/// Two screens in workflow order: Session (start and stop capture) and Review (label, then export).
/// Setup is done once per phone, so it sits behind a gear and opens by itself the first time.
struct RootView: View {
    @EnvironmentObject var model: AppModel
    @State private var showSetup = false

    var body: some View {
        TabView {
            SessionView(showSetup: $showSetup).tabItem { Label("Session", systemImage: "record.circle") }
            ReviewView().tabItem { Label("Review", systemImage: "checklist") }
                .badge(model.pending.count)
        }
        .sheet(isPresented: $showSetup) { SetupView() }
        .onAppear { if model.needsSetup { showSetup = true } }
        .alert(item: $model.message) { m in Alert(title: Text(m.title), message: Text(m.body)) }
    }
}

struct Message: Identifiable {
    let id = UUID()
    let title: String
    let body: String
}

/// Holds the store and what the screens show. Every write goes through KDLCore.Store.
@MainActor
final class AppModel: ObservableObject {
    @Published var rows: [Entry] = []
    @Published var message: Message?
    @Published var lastExport: URL?
    /// Start of an open Start/Done window (calls, recordings), kept in the database so it survives the app being closed.
    @Published var windowStart: Stamp?
    /// Screenshots by entry id.
    @Published var shots: [String: Shot] = [:]

    let store: Store?

    init() {
        do { store = try KDL.store() } catch {
            store = nil
            message = Message(title: "KDL cannot open its log", body: "\(error)")
        }
        windowStart = store?.stamp(forKey: "window_start")
        refresh()
        // A mark from Back Tap, Shortcuts, a widget or a control is written by another process; this
        // notification (posted after every mark) lets the screens update and the Back Tap check finish.
        let me = Unmanaged.passUnretained(self).toOpaque()
        CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(), me, { _, observer, _, _, _ in
            guard let observer else { return }
            let model = Unmanaged<AppModel>.fromOpaque(observer).takeUnretainedValue()
            Task { @MainActor in model.markArrived() }
        }, KDL.markNotification as CFString, nil, .deliverImmediately)
    }

    // MARK: Back Tap check

    /// When the tester is checking Back Tap: marks stamped at or after this time count as the check.
    @Published var backTapCheckSince: Int64?

    /// When a double tap last reached KDL during the check, if ever.
    var backTapWorked: Date? { store?.setting("back_tap_checked_utc_ms").flatMap(Int64.init).map { Date(ms: $0) } }

    func startBackTapCheck() { backTapCheckSince = Clocks.stamp().utcMs }

    /// A test mark proves Back Tap reaches KDL. It is not an action of the test, so it is removed
    /// with a tombstone right away; both rows stay in the log.
    func markArrived() {
        refresh()
        guard let since = backTapCheckSince,
              let m = current.last(where: { $0.isMark && $0.stamp.utcMs >= since }) else { return }
        backTapCheckSince = nil
        try? store?.putSetting("back_tap_checked_utc_ms", String(m.stamp.utcMs))
        run("Back Tap check") { try $0.tombstone(m) }
    }

    var current: [Entry] { Timeline.current(rows) }
    /// Unlabelled marks and stamped entries, oldest first: the order they are labelled in.
    var pending: [Entry] { Review.pending(rows) }
    var apps: [AppUnderTest] { store?.apps() ?? [] }
    var sessionActive: Bool { store?.sessionActive ?? false }
    var sessionStart: Date? { store?.sessionStart().map { Date(ms: $0.utcMs) } }
    var needsSetup: Bool { (store?.scenarioId.isEmpty ?? true) || (store?.deviceLabel.isEmpty ?? true) }

    /// The name shown for an app: the tester's name for it, else Apple's usual name, else the bundle id.
    func appName(_ pkg: String?) -> String? {
        guard let pkg else { return nil }
        return apps.first { $0.pkg == pkg }?.label ?? AppNames.names[pkg] ?? pkg
    }

    /// Apps the screen capture saw in front, KDL excluded, in the order first seen.
    var appsUsed: [String] {
        var seen: [String] = []
        for r in rows where r.kind == Kind.autoEvent && r.action == AutoAction.screen {
            if let p = r.appPackage, p != KDL.bundleId, !seen.contains(p) { seen.append(p) }
        }
        return seen
    }

    /// Apps used with no version typed yet (iOS cannot read another app's version).
    var appsMissingVersion: [String] {
        appsUsed.filter { p in apps.first { $0.pkg == p }?.versionName?.isEmpty ?? true }
    }

    /// Counts for the running session: marks, screenshots saved, apps seen.
    var sessionCounts: (marks: Int, shots: Int, apps: Int) {
        guard let startSeq = rows.last(where: { $0.action == "capture_session_start" })?.seq else { return (0, 0, 0) }
        let inside = rows.filter { $0.seq > startSeq && $0.revisionN == 0 }
        let marks = inside.filter(\.isMark)
        let apps = Set(inside.filter { $0.action == AutoAction.screen }.compactMap(\.appPackage)).subtracting([KDL.bundleId])
        return (marks.count, marks.filter { shots[$0.entryId]?.file != nil }.count, apps.count)
    }

    /// The screenshot shown for an entry: its own, or the one of the mark it labels.
    func shot(for e: Entry) -> Shot? { shots[e.entryId] ?? e.refersTo.flatMap { shots[$0] } }

    func image(for e: Entry) -> UIImage? {
        guard let f = shot(for: e)?.file else { return nil }
        return UIImage(contentsOfFile: KDL.screensDir.appendingPathComponent(f).path)
    }

    /// The form for the next item to label, if any.
    func nextToLabel() -> EntryMode? {
        guard let e = pending.first else { return nil }
        return e.isMark ? .label(e) : .edit(e)
    }

    /// A session the extension started but never stopped (iOS can end an extension without telling
    /// it) is closed when KDL opens and the screen is no longer being captured.
    func closeOrphanSession() {
        guard let store, store.sessionActive, !UIScreen.main.isCaptured else { return }
        run("Closing the session") {
            try $0.stopSession(detail: "screen capture was not running when KDL was opened; the capture ended without a stop from the extension")
        }
    }

    func refresh() {
        rows = store?.entries() ?? []
        shots = store?.shots() ?? [:]
        addAppleApps()
        objectWillChange.send()
    }

    /// Apple apps the capture saw are added to the app list with their version (see
    /// AppNames.appleVersion), so the tester never types them. Each app is added once at most
    /// (remembered in the "apps_auto_added" setting), so one the tester deletes stays deleted, and
    /// entries the tester made are left alone.
    private func addAppleApps() {
        guard let store else { return }
        let listed = Set(store.apps().map(\.pkg))
        var done = Set((store.setting("apps_auto_added") ?? "").split(separator: " ").map(String.init))
        // An app already on the list (typed by the tester) counts as handled, so deleting it later sticks.
        var changed = !listed.isSubset(of: done)
        done.formUnion(listed)
        let iOS = UIDevice.current.systemVersion
        for pkg in appsUsed where !listed.contains(pkg) && !done.contains(pkg) {
            guard let v = AppNames.appleVersion(pkg, iOS: iOS) else { continue }
            try? store.upsertApp(AppUnderTest(pkg: pkg, label: AppNames.names[pkg] ?? pkg, versionName: v,
                                              addedUtcMs: Clocks.stamp().utcMs))
            done.insert(pkg)
            changed = true
        }
        if changed { try? store.putSetting("apps_auto_added", done.sorted().joined(separator: " ")) }
    }

    /// The version on file for an app, if any.
    func version(of pkg: String) -> String? {
        apps.first { $0.pkg == pkg }?.versionName.flatMap { $0.isEmpty ? nil : $0 }
    }

    /// Runs a write, shows any error, refreshes the screens and the widgets.
    func run(_ what: String, _ body: (Store) throws -> Void) {
        guard let store else { return }
        do { try body(store) } catch { message = Message(title: "\(what) failed", body: "\(error)") }
        refresh()
        KDL.reloadSurfaces()
    }

    // MARK: logging

    func log(_ d: Draft, at stamp: Stamp) { run("Logging") { try $0.log(d, stamp: stamp) } }

    func startWindow() {
        let s = Clocks.stamp()
        run("Timing") { try $0.putStamp(s, forKey: "window_start") }
        windowStart = s
    }

    /// Closes the open window: one entry at the start time with the window's length.
    func finishWindow(_ d: Draft) {
        guard let w = windowStart else { return }
        let end = Clocks.stamp()
        let length = Double(end.elapsedMs - w.elapsedMs) / 1000
        run("Logging") { s in
            try s.log(d, stamp: w, windowS: length, detect: false)
            try s.putStamp(nil, forKey: "window_start")
        }
        windowStart = nil
    }

    /// A label for a mark is its own action row at the mark's time, pointing back at the mark.
    func label(_ mark: Entry, with d: Draft) {
        var d = d
        d.refersTo = mark.entryId
        run("Labelling") { try $0.log(d, stamp: mark.stamp, detect: false) }
    }

    func revise(_ e: Entry, _ d: Draft) { run("Saving the edit") { try $0.revise(e, d) } }

    func remove(_ e: Entry) { run("Removing") { try $0.tombstone(e) } }

    // MARK: setup

    func takeSnapshot(carrier: String, notes: String) {
        let d = UIDevice.current
        let p = Profile(manufacturer: "Apple", model: Clocks.sysctlString("hw.machine"), device: d.model,
                        release: d.systemVersion, incremental: Clocks.sysctlString("kern.osversion"),
                        kernel: Clocks.sysctlString("kern.osrelease"), carrier: carrier, notes: notes)
        run("Device snapshot") { try $0.addProfile(p) }
    }

    /// Records a version typed in the label form, keeping the app's name and account.
    func setVersion(_ v: String, for pkg: String) {
        let a = apps.first { $0.pkg == pkg }
        saveApp(pkg: pkg, name: a?.label ?? AppNames.names[pkg] ?? pkg, version: v, account: a?.account ?? "")
    }

    func saveApp(pkg: String, name: String, version: String, account: String) {
        let existing = apps.first { $0.pkg == pkg }
        run("Saving the app") {
            try $0.upsertApp(AppUnderTest(pkg: pkg, label: name.isEmpty ? pkg : name, versionName: version, account: account,
                                          addedUtcMs: existing?.addedUtcMs ?? Clocks.stamp().utcMs))
        }
    }

    func export() {
        guard let store else { return }
        do {
            try FileManager.default.createDirectory(at: KDL.exportsDir, withIntermediateDirectories: true)
            lastExport = try Exporter.export(store: store, to: KDL.exportsDir, logger: KDL.logger, screensDir: KDL.screensDir)
        } catch {
            message = Message(title: "Export failed", body: "\(error)")
        }
        refresh()
        KDL.reloadSurfaces()
    }
}

extension Date {
    init(ms: Int64) { self.init(timeIntervalSince1970: TimeInterval(ms) / 1000) }
}

func clock(_ s: Stamp) -> String {
    Date(ms: s.utcMs).formatted(.dateTime.hour().minute().second())
}

/// Words shown for an action key: "visited_url" reads "Visited URL".
func actionWords(_ key: String) -> String {
    templateLabel(key).capitalizedFirst.replacingOccurrences(of: " url", with: " URL")
}

struct KDLShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: MarkIntent(), phrases: ["Mark in \(.applicationName)"], shortTitle: "Mark", systemImageName: "flag.fill")
    }
}

extension String {
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}

extension View {
    /// A Done button above the keyboard and swipe-down to close it, so nothing stays stuck behind the keyboard.
    func keyboardDone() -> some View {
        scrollDismissesKeyboard(.interactively)
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") {
                        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                    }
                }
            }
    }
}
