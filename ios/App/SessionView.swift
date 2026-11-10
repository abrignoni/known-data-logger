import KDLCore
import SwiftUI

/// Events with nothing on screen to capture: logged by hand, stamped when tapped.
private let noScreenshotActions = [
    "rebooted", "powered_off", "powered_on", "airplane_mode_on", "airplane_mode_off",
    "inserted_sim", "removed_sim", "changed_clock", "changed_time_zone", "incoming_call", "missed_call", "factory_reset",
]

/// Start and stop the test. Starting screen capture starts the session; stopping it ends the session.
struct SessionView: View {
    @EnvironmentObject var model: AppModel
    @Binding var showSetup: Bool
    @State private var handLog = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(alignment: .center) {
                        VStack(alignment: .leading, spacing: 4) {
                            if model.sessionActive, let start = model.sessionStart {
                                Text("Session running").font(.title3.bold())
                                Text(start, style: .timer).font(.title2.monospacedDigit()).foregroundStyle(.red)
                                let c = model.sessionCounts
                                Text("\(c.marks) mark\(c.marks == 1 ? "" : "s"), \(c.shots) screenshot\(c.shots == 1 ? "" : "s"), \(c.apps) app\(c.apps == 1 ? "" : "s")")
                                    .font(.subheadline).foregroundStyle(.secondary)
                            } else {
                                Text("Start a session").font(.title3.bold())
                                Text("Tap the round button, pick KDL Screen, then Start Broadcast.")
                                    .font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        BroadcastPicker().frame(width: 56, height: 56)
                    }
                    .padding(.vertical, 6)
                    if model.needsSetup {
                        Button { showSetup = true } label: {
                            Label("Set the scenario id and device label first", systemImage: "exclamationmark.triangle.fill")
                        }
                        .foregroundStyle(.orange)
                    }
                } footer: {
                    Text(model.sessionActive
                         ? "After each action, double-tap the back of the phone to mark it. Stop with the round button or the red indicator."
                         : "While a session runs, each mark saves a screenshot and every app switch is logged.")
                }

                Section {
                    Button { handLog = true } label: {
                        Label("Log something with no screenshot", systemImage: "square.and.pencil")
                    }
                } footer: {
                    Text("For events the screen does not show: a restart, airplane mode, a SIM swap, a call while locked.")
                }
            }
            .navigationTitle("KDL")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSetup = true } label: { Image(systemName: "gearshape") }.accessibilityLabel("Setup")
                }
            }
            .sheet(isPresented: $handLog) { HandLogSheet() }
        }
    }
}

/// Picks an event to log by hand. The time is stamped when the event is tapped.
struct HandLogSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var form: EntryMode?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(noScreenshotActions, id: \.self) { key in
                        Button(actionWords(key)) { form = .new(action: key, stamp: Clocks.stamp()) }
                    }
                    Button("Other") { form = .new(action: "", stamp: Clocks.stamp()) }
                } footer: {
                    Text("The time is taken when you tap the event.")
                }
                Section {
                    if let w = model.windowStart {
                        Button("Done timing (started \(clock(w)))") { form = .windowDone }
                    } else {
                        Button("Start timing a call or recording") { model.startWindow() }
                    }
                } footer: {
                    Text("Tap when it starts, then Done when it ends. One entry is logged with its length.")
                }
            }
            .navigationTitle("Log by hand")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .sheet(item: $form, onDismiss: { if model.windowStart == nil { dismiss() } }) { EntrySheet(mode: $0) }
        }
    }
}
