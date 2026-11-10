import KDLCore
import SwiftUI

/// The actions offered as one-tap buttons. The full template list is in the form's picker.
private let common = [
    "sent_message", "received_message", "outgoing_call", "incoming_call", "took_photo", "recorded_video",
    "visited_url", "searched", "deleted_item", "logged_in",
]

struct LogView: View {
    @EnvironmentObject var model: AppModel
    @State private var sheet: EntryMode?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if model.sessionActive, let start = model.sessionStart {
                        HStack {
                            Image(systemName: "record.circle").foregroundStyle(.red)
                            Text("Session").font(.headline)
                            Spacer()
                            Text(start, style: .timer).monospacedDigit()
                        }
                        Button(role: .destructive) { model.stopSession() } label: {
                            Label("Stop session", systemImage: "stop.fill")
                        }
                    } else {
                        Button { model.startSession() } label: { Label("Start session", systemImage: "record.circle") }
                            .disabled(model.store?.scenarioId.isEmpty ?? true)
                        if model.store?.scenarioId.isEmpty ?? true {
                            Text("Set a scenario id and device label on Setup first.").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                } footer: {
                    Text("A session frames the test. Logging works with or without one; the Lock Screen shows Mark and Stamp while it runs.")
                }

                Section {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Screen capture").font(.headline)
                            Text(model.broadcastOn ? "On: each Mark saves a screenshot" : "Off")
                                .font(.subheadline).foregroundStyle(model.broadcastOn ? .red : .secondary)
                        }
                        Spacer()
                        BroadcastPicker().frame(width: 44, height: 44)
                    }
                } footer: {
                    Text("Tap the round button, pick KDL Screen, then Start Broadcast. While it runs, every Mark saves a screenshot and each app switch is logged. Stop it with the same button or the red indicator at the top of the screen.")
                }

                Section {
                    HStack(spacing: 12) {
                        BigButton(title: "Mark", icon: "flag.fill", tint: .orange) { model.mark() }
                        BigButton(title: "Stamp now", icon: "clock.badge.checkmark", tint: .blue) { model.stamp() }
                    }
                    .listRowInsets(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))
                } footer: {
                    Text("Mark and Stamp record the time only. Describe them later under Timeline, To check.")
                }

                Section("Log an action") {
                    if let w = model.windowStart {
                        HStack {
                            Text("Timing since \(clock(w))")
                            Spacer()
                            Button("Done") { sheet = .windowDone }.buttonStyle(.borderedProminent)
                        }
                    }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 8)], spacing: 8) {
                        ForEach(common, id: \.self) { key in
                            Button { sheet = .new(action: key, stamp: Clocks.stamp()) } label: {
                                Text(templateLabel(key).capitalizedFirst).frame(maxWidth: .infinity, minHeight: 36)
                            }
                            .buttonStyle(.bordered)
                        }
                        Button { sheet = .new(action: "", stamp: Clocks.stamp()) } label: {
                            Text("Other").frame(maxWidth: .infinity, minHeight: 36)
                        }
                        .buttonStyle(.bordered)
                    }
                    .listRowInsets(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))
                    if let d = model.lastDraft {
                        Button { sheet = .new(action: d.action, stamp: Clocks.stamp()) } label: {
                            Label("Repeat \(templateLabel(d.action))", systemImage: "arrow.clockwise")
                        }
                    }
                    if model.windowStart == nil {
                        Button { model.startWindow() } label: {
                            Label("Start timing (calls, recordings)", systemImage: "timer")
                        }
                    }
                }

                if let last = model.current.last {
                    Section("Last logged") {
                        Text("\(displayLabel(last)) at \(clock(last.stamp))").foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("KDL")
            .sheet(item: $sheet) { EntrySheet(mode: $0) }
        }
    }
}

struct BigButton: View {
    let title: String
    let icon: String
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.title2)
                Text(title).font(.headline)
            }
            .frame(maxWidth: .infinity, minHeight: 72)
        }
        .buttonStyle(.borderedProminent)
        .tint(tint)
    }
}
