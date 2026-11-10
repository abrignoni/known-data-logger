import Foundation
import KDLCore
#if canImport(WidgetKit)
import WidgetKit
#endif

/// Shared by the app, its App Intents and the widget extension.
enum KDL {
    static let appGroup = "group.com.abrignoni.knowndatalogger"
    static let bundleId = "com.abrignoni.knowndatalogger"
    static let noSecrets = "No passwords or passcodes here. Exports get shared."

    /// The App Group container, where kl.db lives so the widget and controls can write to it.
    /// Falls back to the app's Application Support folder if the group is missing (the widget then cannot log).
    static var dataDir: URL {
        if let g = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) {
            return g.appendingPathComponent("Library/KDL", isDirectory: true)
        }
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("KDL", isDirectory: true)
    }

    /// Screenshots taken by the broadcast extension, named after the entry they belong to.
    static var screensDir: URL { dataDir.appendingPathComponent("screens", isDirectory: true) }

    /// Posted after every mark, so a running screen broadcast saves its latest frame for it.
    static let markNotification = "com.abrignoni.knowndatalogger.mark"

    static func postMark() {
        CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(),
                                             CFNotificationName(markNotification as CFString), nil, nil, true)
    }

    static var usesAppGroup: Bool {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) != nil
    }

    /// Exports go to the app's own Documents folder: the Files app shows it (On My iPhone > KDL), and
    /// `devicectl device copy from` can read it, which it cannot do for the App Group container.
    static var exportsDir: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Exports", isDirectory: true)
    }

    private static let lock = NSLock()
    private static var cached: Store?

    static func store() throws -> Store {
        lock.lock(); defer { lock.unlock() }
        if let cached { return cached }
        let s = try Store(url: dataDir.appendingPathComponent(Store.fileName))
        cached = s
        return s
    }

    static var logger: LoggerInfo {
        LoggerInfo(package: bundleId, versionName: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String)
    }

    /// Tells widgets and controls that the log changed.
    static func reloadSurfaces() {
        #if canImport(WidgetKit)
        WidgetCenter.shared.reloadAllTimelines()
        if #available(iOS 18.0, *) { ControlCenter.shared.reloadAllControls() }
        #endif
    }
}
