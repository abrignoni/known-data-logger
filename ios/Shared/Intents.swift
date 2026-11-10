import AppIntents
import Foundation
import KDLCore

/// Mark, the one quick-logging action. It is compiled into the app and the widget extension, so Back
/// Tap, Shortcuts, a widget or a Control Center button runs it without opening KDL. It stamps the
/// clocks when it runs; a running screen capture saves a screenshot for it.

struct MarkIntent: AppIntent {
    static var title: LocalizedStringResource = "Mark"
    static var description = IntentDescription("Stamps \"this is the action\" now. Label it later in KDL's Review.")
    static var openAppWhenRun = false

    /// Shortcuts and Back Tap show the dialog as a short banner, so the tester knows the tap counted.
    /// Widgets and controls do not show it.
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let m = try KDL.store().mark()
        KDL.postMark()
        KDL.reloadSurfaces()
        let t = Date(timeIntervalSince1970: TimeInterval(m.stamp.utcMs) / 1000).formatted(date: .omitted, time: .standard)
        return .result(dialog: "Marked \(t)")
    }
}
