import ActivityKit
import Foundation

/// The Live Activity shown on the Lock Screen while a session runs, with Mark and Stamp buttons.
struct SessionActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// Rows logged since the session started.
        var logged: Int
    }

    var scenarioId: String
    var deviceLabel: String
    /// Session start, for the timer on the Lock Screen.
    var started: Date
}
