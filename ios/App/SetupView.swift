import KDLCore
import SwiftUI
import UIKit

/// Once per phone: scenario and device, a device snapshot, and apps (optional; Review fills them in
/// from the apps the capture saw). Diagnostics are folded away.
struct SetupView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var scenario = ""
    @State private var device = ""
    @State private var carrier = ""
    @State private var notes = ""
    @State private var addingApp = false
    @State private var editing: String?
    @State private var copied = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Scenario id, same on every phone", text: $scenario)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .onChange(of: scenario) { _, v in model.store?.scenarioId = v }
                    TextField("Device label, e.g. phone A", text: $device).autocorrectionDisabled()
                        .onChange(of: device) { _, v in model.store?.deviceLabel = v }
                } header: { Text("Scenario and device") }

                BackTapCard()

                Section {
                    if let p = model.store?.profiles().last {
                        LabeledContent("Model", value: p.model ?? "")
                        LabeledContent("iOS", value: "\(p.release ?? "") (\(p.incremental ?? ""))")
                        LabeledContent("Taken", value: Date(ms: p.utcMs).formatted(date: .abbreviated, time: .shortened))
                    } else {
                        Text("No snapshot yet.").foregroundStyle(.orange)
                    }
                    TextField("Carrier", text: $carrier)
                    TextField("Notes", text: $notes, axis: .vertical)
                    Button("Take snapshot") { model.takeSnapshot(carrier: carrier, notes: notes) }
                } header: { Text("Device") } footer: {
                    Text("Take one now and again after an iOS update. Earlier snapshots are kept. \(KDL.noSecrets)")
                }

                Section {
                    ForEach(model.apps) { a in
                        Button { editing = a.pkg } label: {
                            VStack(alignment: .leading) {
                                Text(a.label).foregroundStyle(.primary)
                                Text([a.versionName.map { "version \($0)" } ?? "version missing", a.account].compactMap { $0 }.joined(separator: " · "))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onDelete { idx in
                        let apps = model.apps
                        for i in idx { try? model.store?.removeApp(apps[i].pkg) }
                        model.refresh()
                    }
                    Button("Add an app") { addingApp = true }
                } header: { Text("Apps") } footer: {
                    Text("Optional. Apps you use during a session are listed in Review, where you add their version.")
                }

                Section {
                    DisclosureGroup("Diagnostics") {
                        let s = Clocks.stamp()
                        LabeledContent("Time zone", value: "\(s.tzId) (\(s.tzOffsetMin) min)")
                        LabeledContent("Time since boot", value: Duration.milliseconds(s.elapsedMs).formatted(.time(pattern: .hourMinuteSecond)))
                        if let b = s.kernBootMs { LabeledContent("Booted", value: Date(ms: b).formatted(date: .abbreviated, time: .standard)) }
                        LabeledContent("Log", value: KDL.usesAppGroup ? "shared with widgets and capture" : "app only (no App Group)")
                        if let h = model.store?.lastRowHash() {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Latest row hash").font(.subheadline)
                                Text(h).font(.caption.monospaced()).textSelection(.enabled)
                                Button(copied ? "Copied" : "Copy") { UIPasteboard.general.string = h; copied = true }
                            }
                        }
                        Text("Note the latest row hash with your documentation; it shows later whether rows were removed. iOS has no boot counter, so boot_count is -1 on every row.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .keyboardDone()
            .navigationTitle("Setup")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { model.refresh(); dismiss() }
                }
            }
            .onAppear {
                scenario = model.store?.scenarioId ?? ""
                device = model.store?.deviceLabel ?? ""
                if let p = model.store?.profiles().last { carrier = p.carrier ?? ""; notes = p.notes ?? "" }
            }
            .sheet(isPresented: $addingApp) { AddAppSheet() }
            .sheet(item: Binding(get: { editing.map(PkgId.init) }, set: { editing = $0?.id })) { AppEditSheet(pkg: $0.id) }
        }
    }
}

struct AddAppSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var bundleId = ""
    @State private var version = ""
    @State private var account = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name, e.g. Messages", text: $name)
                TextField("Bundle id, if known (com.apple.MobileSMS)", text: $bundleId)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("Version", text: $version).keyboardType(.numbersAndPunctuation)
                TextField("Account handle", text: $account).textInputAutocapitalization(.never).autocorrectionDisabled()
                Text(KDL.noSecrets).font(.footnote).foregroundStyle(.secondary)
            }
            .keyboardDone()
            .navigationTitle("Add an app")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        let id = bundleId.trimmingCharacters(in: .whitespaces)
                        let label = name.trimmingCharacters(in: .whitespaces)
                        model.saveApp(pkg: id.isEmpty ? label : id, name: label.isEmpty ? id : label, version: version, account: account)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty && bundleId.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}

/// One-time phone setup for marking without covering the screen, with a live check.
struct BackTapCard: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Text("1. Open Settings > Accessibility > Touch > Back Tap > Double Tap.")
                Text("2. Under Shortcuts, pick Mark.")
                Text("If Mark is not listed: open Shortcuts > Library > All Shortcuts, tap +, search KDL, tap Mark, then tap < to save it. Then go back to step 1.")
                    .font(.footnote).foregroundStyle(.secondary)
                Text("3. Come back here and check it.")
            }
            .font(.subheadline)
            if model.backTapCheckSince != nil {
                HStack {
                    ProgressView()
                    Text("Double-tap the back of the phone now.").foregroundStyle(.orange)
                }
                Button("Cancel the check") { model.backTapCheckSince = nil }
            } else {
                Button(model.backTapWorked == nil ? "Check Back Tap" : "Check again") { model.startBackTapCheck() }
            }
            if let ok = model.backTapWorked, model.backTapCheckSince == nil {
                Label("Back Tap works (checked \(ok.formatted(date: .abbreviated, time: .shortened)))", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
            }
        } header: { Text("Back Tap") } footer: {
            Text("Marking with a double tap on the back leaves the screen as it is, so the screenshot shows the app. The check mark is removed right away; it stays in the log as a mark and its removal.")
        }
    }
}
