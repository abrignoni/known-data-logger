import Foundation

/// Display names and suggested actions for apps the screen capture reports by bundle id.
/// Display only: the log keeps the bundle id. The tester can rename an app in Setup.
/// Bundle ids seen on the test phone (9 November 2026): com.apple.MobileSMS, com.apple.mobilesafari,
/// com.apple.mobilenotes, com.apple.Preferences. The rest are Apple's usual ids, not checked on a device.
enum AppNames {
    static let names: [String: String] = [
        "com.apple.MobileSMS": "Messages", "com.apple.mobilesafari": "Safari", "com.apple.mobilenotes": "Notes",
        "com.apple.Preferences": "Settings", "com.apple.camera": "Camera", "com.apple.mobilephone": "Phone",
        "com.apple.facetime": "FaceTime", "com.apple.Maps": "Maps", "com.apple.mobilemail": "Mail",
        "com.apple.mobileslideshow": "Photos", "com.apple.MobileAddressBook": "Contacts", "com.apple.mobilecal": "Calendar",
        "com.apple.reminders": "Reminders", "com.apple.AppStore": "App Store", "com.apple.Health": "Health",
        "com.apple.shortcuts": "Shortcuts", "com.apple.DocumentsApp": "Files", "com.apple.findmy": "Find My",
        "com.apple.weather": "Weather", "com.apple.Music": "Music", "com.apple.podcasts": "Podcasts",
        "com.apple.news": "News", "com.apple.VoiceMemos": "Voice Memos", "com.apple.mobiletimer": "Clock",
        "com.apple.calculator": "Calculator", "com.apple.Passbook": "Wallet",
        KDL.bundleId: "KDL",
    ]

    /// Actions offered first when labelling a mark in this app. Every template stays available.
    static let suggestions: [String: [String]] = [
        "com.apple.MobileSMS": ["sent_message", "received_message", "sent_media"],
        "com.apple.mobilemail": ["sent_message", "received_message"],
        "com.apple.mobilesafari": ["visited_url", "searched"],
        "com.apple.camera": ["took_photo", "recorded_video"],
        "com.apple.mobilephone": ["outgoing_call", "incoming_call", "missed_call"],
        "com.apple.facetime": ["outgoing_call", "incoming_call", "missed_call"],
        "com.apple.Maps": ["searched", "viewed_location", "navigated"],
        "com.apple.MobileAddressBook": ["added_contact", "deleted_item"],
        "com.apple.Preferences": ["connected_wifi", "connected_bluetooth", "airplane_mode_on", "airplane_mode_off"],
        "com.apple.VoiceMemos": ["recorded_audio"],
    ]

    static let fallbackSuggestions = ["sent_message", "visited_url", "took_photo", "deleted_item"]

    static let safari = "com.apple.mobilesafari"

    /// The version KDL records for a built-in Apple app (one in `names`), or nil for any other app,
    /// including Apple apps that update through the App Store (Pages, iMovie): the tester types those.
    /// Safari: Apple's Safari 26.6 Release Notes say "Safari 26.6 is available for iOS 26.6"
    /// (https://developer.apple.com/documentation/safari-release-notes/safari-26_6-release-notes),
    /// so Safari is recorded with the iOS version. No Apple source gives the version of the other
    /// built-in apps, so they are recorded as "built into iOS <version>", a fact rather than a number.
    static func appleVersion(_ pkg: String, iOS: String) -> String? {
        guard names[pkg] != nil, pkg != KDL.bundleId else { return nil }
        return pkg == safari ? iOS : "built into iOS \(iOS)"
    }
}
