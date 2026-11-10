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

struct RootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        TabView {
            LogView().tabItem { Label("Log", systemImage: "square.and.pencil") }
            TimelineScreen().tabItem { Label("Timeline", systemImage: "list.bullet") }
                .badge(model.pending.count)
            SetupView().tabItem { Label("Setup", systemImage: "gearshape") }
        }
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
    /// The last draft saved, for Repeat.
    @Published var lastDraft: Draft?
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
    }

    var current: [Entry] { Timeline.current(rows) }
    var pending: [Entry] { Review.pending(rows) }
    var apps: [AppUnderTest] { store?.apps() ?? [] }
    var sessionActive: Bool { store?.sessionActive ?? false }

    /// The screenshot shown for an entry: its own, or the one of the mark it labels.
    func shot(for e: Entry) -> Shot? { shots[e.entryId] ?? e.refersTo.flatMap { shots[$0] } }

    func image(for e: Entry) -> UIImage? {
        guard let f = shot(for: e)?.file else { return nil }
        return UIImage(contentsOfFile: KDL.screensDir.appendingPathComponent(f).path)
    }
    var sessionStart: Date? { store?.sessionStart().map { Date(ms: $0.utcMs) } }

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
        objectWillChange.send()
    }

    /// Runs a write, shows any error, refreshes the screens and the widgets.
    func run(_ what: String, _ body: (Store) throws -> Void) {
        guard let store else { return }
        do { try body(store) } catch { message = Message(title: "\(what) failed", body: "\(error)") }
        refresh()
        KDL.reloadSurfaces()
    }

    // MARK: logging

    func log(_ d: Draft, at stamp: Stamp) {
        run("Logging") { try $0.log(d, stamp: stamp) }
        if !d.action.isEmpty { lastDraft = d }
    }

    func mark() {
        run("Mark") { try $0.mark() }
        KDL.postMark()
    }

    func stamp() { run("Stamp") { try $0.log(Draft(action: "")) } }

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
        lastDraft = d
    }

    /// A label for a mark is its own action row at the mark's time, pointing back at the mark.
    func label(_ mark: Entry, with d: Draft) {
        var d = d
        d.refersTo = mark.entryId
        run("Labelling") { try $0.log(d, stamp: mark.stamp, detect: false) }
    }

    func revise(_ e: Entry, _ d: Draft) { run("Saving the edit") { try $0.revise(e, d) } }

    func remove(_ e: Entry) { run("Removing") { try $0.tombstone(e) } }

    // MARK: sessions

    // MARK: setup

    func takeSnapshot(carrier: String, notes: String) {
        let d = UIDevice.current
        let p = Profile(manufacturer: "Apple", model: Clocks.sysctlString("hw.machine"), device: d.model,
                        release: d.systemVersion, incremental: Clocks.sysctlString("kern.osversion"),
                        kernel: Clocks.sysctlString("kern.osrelease"), carrier: carrier, notes: notes)
        run("Device snapshot") { try $0.addProfile(p) }
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

struct KDLShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: MarkIntent(), phrases: ["Mark in \(.applicationName)"], shortTitle: "Mark", systemImageName: "flag.fill")
        AppShortcut(intent: StampIntent(), phrases: ["Stamp now in \(.applicationName)"], shortTitle: "Stamp now",
                    systemImageName: "clock.badge.checkmark")
        AppShortcut(intent: LogActionIntent(), phrases: ["Log an action in \(.applicationName)"], shortTitle: "Log action",
                    systemImageName: "square.and.pencil")
    }
}

extension View {
    /// A Done button above the keyboard and swipe-down to close it, so the tab bar is never stuck behind the keyboard.
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
