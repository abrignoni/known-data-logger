import AppIntents
import Foundation
import KDLCore

/// Quick-logging actions. They are compiled into the app and the widget extension, so a widget or a
/// Control Center button runs them without opening KDL. Each one stamps the clocks when it runs.

struct MarkIntent: AppIntent {
    static var title: LocalizedStringResource = "Mark"
    static var description = IntentDescription("Stamps \"this is the action\" now. Label it later in KDL's Timeline.")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult {
        try KDL.store().log(Draft(action: AutoAction.mark, source: Source.mark), kind: Kind.autoEvent)
        KDL.reloadSurfaces()
        return .result()
    }
}

struct StampIntent: AppIntent {
    static var title: LocalizedStringResource = "Stamp now"
    static var description = IntentDescription("Stamps an action with no description yet. Fill it in later in KDL.")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult {
        try KDL.store().log(Draft(action: ""))
        KDL.reloadSurfaces()
        return .result()
    }
}

/// For Shortcuts, including a personal automation on the App trigger ("Is Opened" / "Is Closed"),
/// which does not pass the app's name: fill in Action and App in the automation itself.
struct LogActionIntent: AppIntent {
    static var title: LocalizedStringResource = "Log action"
    static var description = IntentDescription("Logs one action in KDL with the time it runs.")
    static var openAppWhenRun = false

    @Parameter(title: "Action", description: "A template key such as sent_message, or any text")
    var action: String

    @Parameter(title: "App", description: "Bundle id or name of the app under test, as listed in KDL")
    var app: String?

    @Parameter(title: "Content", description: "Exact text sent, file name or caption. No passwords.")
    var content: String?

    @Parameter(title: "Counterpart", description: "The other party")
    var counterpart: String?

    @Parameter(title: "Note")
    var note: String?

    static var parameterSummary: some ParameterSummary {
        Summary("Log \(\.$action) in \(\.$app)") {
            \.$content
            \.$counterpart
            \.$note
        }
    }

    func perform() async throws -> some IntentResult {
        let a = action.trimmingCharacters(in: .whitespacesAndNewlines)
        try KDL.store().log(Draft(appPackage: app, action: a, content: content, counterpart: counterpart,
                                  direction: directionFor(a), detail: note))
        KDL.reloadSurfaces()
        return .result()
    }
}
