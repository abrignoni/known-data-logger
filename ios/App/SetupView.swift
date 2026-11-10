import KDLCore
import SwiftUI
import UIKit

struct SetupView: View {
    @EnvironmentObject var model: AppModel
    @State private var scenario = ""
    @State private var device = ""
    @State private var carrier = ""
    @State private var notes = ""
    @State private var addingApp = false
    @State private var copied = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Scenario id, same on every phone", text: $scenario).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Device label, e.g. phone A", text: $device).autocorrectionDisabled()
                    Button("Save") {
                        model.store?.scenarioId = scenario
                        model.store?.deviceLabel = device
                        model.refresh()
                    }
                    .disabled(scenario == model.store?.scenarioId && device == model.store?.deviceLabel)
                } header: { Text("Scenario and device") }

                Section {
                    if let p = model.store?.profiles().last {
                        LabeledContent("Model", value: p.model ?? "")
                        LabeledContent("iOS", value: "\(p.release ?? "") (\(p.incremental ?? ""))")
                        LabeledContent("Taken", value: Date(ms: p.utcMs).formatted(date: .abbreviated, time: .shortened))
                    } else {
                        Text("No snapshot yet.").foregroundStyle(.secondary)
                    }
                    TextField("Carrier", text: $carrier)
                    TextField("Notes", text: $notes, axis: .vertical)
                    Button("Take snapshot") { model.takeSnapshot(carrier: carrier, notes: notes) }
                } header: { Text("Device profile") } footer: {
                    Text("Add a snapshot after an iOS update. Earlier snapshots are kept. \(KDL.noSecrets)")
                }

                Section {
                    ForEach(model.apps) { a in
                        VStack(alignment: .leading) {
                            Text(a.label)
                            Text([a.pkg, a.versionName.map { "version \($0)" }, a.account].compactMap { $0 }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .onDelete { idx in
                        let apps = model.apps
                        for i in idx { try? model.store?.removeApp(apps[i].pkg) }
                        model.refresh()
                    }
                    Button("Add app") { addingApp = true }
                } header: { Text("Apps under test") } footer: {
                    Text("iOS does not let KDL read other apps' versions. Type the version from the App Store page or Settings, General, iPhone Storage.")
                }

                Section {
                    Button("Export everything") { model.export() }
                    if let url = model.lastExport {
                        Text(url.lastPathComponent).font(.caption.monospaced())
                        ShareLink(item: url) { Label("Share or save the export", systemImage: "square.and.arrow.up") }
                    }
                } header: { Text("Export") } footer: {
                    Text("Exports go to On My iPhone > KDL > Exports in the Files app, with the hash chain. Each export is logged as its own entry.")
                }

                Section {
                    if let h = model.store?.lastRowHash() {
                        Text(h).font(.caption.monospaced()).textSelection(.enabled)
                        Button(copied ? "Copied" : "Copy") { UIPasteboard.general.string = h; copied = true }
                    } else {
                        Text("Nothing logged yet.").foregroundStyle(.secondary)
                    }
                } header: { Text("Latest row hash") } footer: {
                    Text("Note it with your documentation; it shows later whether rows were removed.")
                }

                Section {
                    let s = Clocks.stamp()
                    LabeledContent("Time zone", value: "\(s.tzId) (\(s.tzOffsetMin) min)")
                    LabeledContent("Time since boot", value: Duration.milliseconds(s.elapsedMs).formatted(.time(pattern: .hourMinuteSecond)))
                    if let b = s.kernBootMs { LabeledContent("Booted", value: Date(ms: b).formatted(date: .abbreviated, time: .standard)) }
                    LabeledContent("Log", value: KDL.usesAppGroup ? "shared with widgets" : "app only (no App Group)")
                } header: { Text("Clocks") } footer: {
                    Text("Time since boot counts sleep. iOS has no boot counter, so boot_count is -1 on every row; a restart is logged when time since boot goes back. KDL reads no network or GNSS time.")
                }
            }
            .navigationTitle("Setup")
            .keyboardDone()
            .onAppear {
                scenario = model.store?.scenarioId ?? ""
                device = model.store?.deviceLabel ?? ""
                if let p = model.store?.profiles().last { carrier = p.carrier ?? ""; notes = p.notes ?? "" }
            }
            .sheet(isPresented: $addingApp) { AddAppSheet() }
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
            .navigationTitle("Add app")
            .keyboardDone()
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        let id = bundleId.trimmingCharacters(in: .whitespaces)
                        let label = name.trimmingCharacters(in: .whitespaces)
                        try? model.store?.upsertApp(AppUnderTest(pkg: id.isEmpty ? label : id, label: label.isEmpty ? id : label,
                                                                 versionName: version, account: account,
                                                                 addedUtcMs: Clocks.stamp().utcMs))
                        model.refresh()
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty && bundleId.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
