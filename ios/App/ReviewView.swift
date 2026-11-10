import KDLCore
import SwiftUI

/// After the test: label each mark from its screenshot, fill in the apps used, then export.
struct ReviewView: View {
    @EnvironmentObject var model: AppModel
    @State private var filter = 0
    @State private var sheet: EntryMode?
    /// The form to open once the current one closes (the next item to label).
    @State private var queued: EntryMode?
    @State private var appsSheet = false
    /// Shown for a few seconds after a save that leaves nothing else to label.
    @State private var savedNote = false

    var body: some View {
        NavigationStack {
            List {
                Picker("Show", selection: $filter) {
                    Text("To label (\(model.pending.count))").tag(0)
                    Text("Everything").tag(1)
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)

                if !model.appsMissingVersion.isEmpty {
                    Button { appsSheet = true } label: {
                        Label("\(model.appsUsed.count) app\(model.appsUsed.count == 1 ? "" : "s") used, \(model.appsMissingVersion.count) missing a version",
                              systemImage: "app.badge")
                    }
                    .foregroundStyle(.orange)
                }

                if savedNote {
                    Label("Saved. It is under Everything, where you can change it.", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                }

                if filter == 0 {
                    if model.pending.isEmpty {
                        Text(model.current.isEmpty ? "Nothing logged yet." : "All labelled. To change one, open Everything.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(model.pending, id: \.seq) { e in
                        Button { open(e) } label: { EntryRow(entry: e, revisions: 1) }.foregroundStyle(.primary)
                    }
                } else {
                    let shown = everything
                    if shown.isEmpty { Text("Nothing logged yet.").foregroundStyle(.secondary) }
                    ForEach(shown, id: \.seq) { e in
                        Button { open(e) } label: { EntryRow(entry: e, revisions: revisions[e.entryId] ?? 1) }
                            .foregroundStyle(.primary)
                            .disabled(!editable(e))
                    }
                }
            }
            .navigationTitle("Review")
            .refreshable { model.refresh() }
            .safeAreaInset(edge: .bottom) { ExportBar().padding(.horizontal).padding(.bottom, 4) }
            .sheet(item: $sheet, onDismiss: {
                if let q = queued { queued = nil; DispatchQueue.main.async { sheet = q } }
            }) { mode in
                EntrySheet(mode: mode, onSaved: {
                    queued = model.nextToLabel()
                    if queued == nil { showSavedNote() }
                })
            }
            .sheet(isPresented: $appsSheet) { AppsUsedSheet() }
        }
    }

    private func showSavedNote() {
        savedNote = true
        Task { try? await Task.sleep(for: .seconds(4)); savedNote = false }
    }

    private var revisions: [String: Int] { Dictionary(grouping: model.rows, by: \.entryId).mapValues { $0.count } }

    /// Newest first. A mark that has a label is shown through its label row (same time, same screenshot).
    private var everything: [Entry] {
        let labelled = Set(model.current.compactMap(\.refersTo))
        return model.current.filter { !($0.isMark && labelled.contains($0.entryId)) && $0.kind != Kind.export }.reversed()
    }

    /// Marks get a label (a new row); actions and notes get a revision. System rows are not edited.
    private func editable(_ e: Entry) -> Bool { e.isMark || e.kind == Kind.action || e.kind == Kind.note }

    private func open(_ e: Entry) {
        if e.isMark { sheet = .label(e) } else if editable(e) { sheet = .edit(e) }
    }
}

/// The last step of the workflow, always in view: what is left, then Export.
struct ExportBar: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(spacing: 6) {
            let left = model.pending.count
            Text(left == 0 ? "Ready to export" : "\(left) not labelled yet. You can still export.")
                .font(.footnote).foregroundStyle(left == 0 ? .green : .orange)
            Button { model.export() } label: {
                Label("Export", systemImage: "square.and.arrow.up.on.square").frame(maxWidth: .infinity, minHeight: 32)
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.sessionActive)
            if model.sessionActive {
                Text("Stop the session before exporting.").font(.caption).foregroundStyle(.secondary)
            } else if let url = model.lastExport {
                HStack {
                    Text("Saved to Files > On My iPhone > KDL > Exports").font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }
                }
            }
        }
        .padding(12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
    }
}

struct EntryRow: View {
    @EnvironmentObject var model: AppModel
    let entry: Entry
    let revisions: Int

    private var title: String {
        if entry.kind == Kind.autoEvent && entry.action == AutoAction.screen {
            return "Switched to \(model.appName(entry.appPackage) ?? "an app")"
        }
        if entry.isMark { return "Mark" }
        if entry.needsDetail { return "Stamped, no action" }
        if entry.kind == Kind.action || entry.kind == Kind.note { return actionWords(entry.action) }
        return displayLabel(entry)
    }

    private var isSwitch: Bool { entry.kind == Kind.autoEvent && entry.action == AutoAction.screen }
    private var isSystem: Bool { entry.source == Source.system || entry.kind == Kind.systemEvent || entry.kind == Kind.clockChange }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(title).font(isSwitch ? .subheadline : .headline)
                    .foregroundStyle(entry.isMark || entry.needsDetail ? .orange : (isSwitch || isSystem ? .secondary : .primary))
                Spacer()
                Text(clock(entry.stamp)).font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
            }
            if !isSwitch {
                let app = entry.appPackage.flatMap { model.appName($0) }
                let parts = [app, entry.counterpart, entry.content, entry.windowS.map { String(format: "%.0f s", $0) }].compactMap { $0 }
                if !parts.isEmpty { Text(parts.joined(separator: " · ")).font(.subheadline).lineLimit(2) }
            }
            // Restarts and clock changes matter to the tester; capture statistics stay in the export.
            if entry.kind == Kind.clockChange || entry.action == "boot_detected", let d = entry.detail {
                Text(d).font(.caption).foregroundStyle(.secondary).lineLimit(3)
            }
            if let s = model.shot(for: entry) {
                Label(s.file != nil ? "Screenshot" : "No screenshot (\(s.status))", systemImage: "photo")
                    .font(.caption).foregroundStyle(.secondary)
            } else if entry.isMark {
                Label("No screenshot (screen capture was off)", systemImage: "photo").font(.caption).foregroundStyle(.secondary)
            }
            if revisions > 1 { Text("Edited, \(revisions) revisions").font(.caption).foregroundStyle(.secondary) }
        }
    }
}

/// Apps the screen capture saw, with the version and account only the tester can supply.
struct AppsUsedSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var editing: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(model.appsUsed, id: \.self) { pkg in
                        let a = model.apps.first { $0.pkg == pkg }
                        Button { editing = pkg } label: {
                            VStack(alignment: .leading) {
                                Text(model.appName(pkg) ?? pkg).foregroundStyle(.primary)
                                Text(a?.versionName.map { "Version \($0)" } ?? "Version missing")
                                    .font(.caption).foregroundStyle(a?.versionName == nil ? .orange : .secondary)
                            }
                        }
                    }
                } footer: {
                    Text("iOS does not let KDL read other apps' versions. Find it in Settings > General > iPhone Storage > the app, or on its App Store page.")
                }
            }
            .navigationTitle("Apps used")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .sheet(item: Binding(get: { editing.map(PkgId.init) }, set: { editing = $0?.id })) { AppEditSheet(pkg: $0.id) }
        }
    }
}

struct PkgId: Identifiable { let id: String }

/// Name, version and account for one app.
struct AppEditSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let pkg: String
    @State private var name = ""
    @State private var version = ""
    @State private var account = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $name)
                    TextField("Version", text: $version).keyboardType(.numbersAndPunctuation)
                    TextField("Account handle", text: $account).textInputAutocapitalization(.never).autocorrectionDisabled()
                } header: { Text(pkg) } footer: { Text(KDL.noSecrets) }
            }
            .keyboardDone()
            .navigationTitle(model.appName(pkg) ?? pkg)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { model.saveApp(pkg: pkg, name: name, version: version, account: account); dismiss() }
                }
            }
            .onAppear {
                let a = model.apps.first { $0.pkg == pkg }
                name = a?.label ?? AppNames.names[pkg] ?? ""
                version = a?.versionName ?? ""
                account = a?.account ?? ""
            }
        }
    }
}
