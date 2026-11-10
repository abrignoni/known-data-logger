import KDLCore
import SwiftUI

/// What the entry form is for.
enum EntryMode: Identifiable {
    /// A new entry stamped when the tester tapped (stamp taken before the form opened).
    case new(action: String, stamp: Stamp)
    /// Closing a Start/Done window.
    case windowDone
    /// A label for a mark, at the mark's time.
    case label(Entry)
    /// A new revision of an existing entry.
    case edit(Entry)

    var id: String {
        switch self {
        case .new(let a, let s): return "new-\(a)-\(s.utcMs)"
        case .windowDone: return "window"
        case .label(let e): return "label-\(e.entryId)"
        case .edit(let e): return "edit-\(e.entryId)-\(e.revisionN)"
        }
    }
}

struct EntrySheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let mode: EntryMode

    @State private var action = ""
    @State private var app = ""
    @State private var content = ""
    @State private var counterpart = ""
    @State private var conversation = ""
    @State private var note = ""
    @State private var expected = ""
    @State private var quality = TimeQuality.exact
    @State private var confirmReset = false
    @State private var confirmRemove = false
    @State private var loaded = false

    private var title: String {
        switch mode {
        case .new: return action.isEmpty ? "Log an action" : templateLabel(action).capitalizedFirst
        case .windowDone: return "Done timing"
        case .label: return "Label the mark"
        case .edit: return "Edit"
        }
    }

    private var when: String {
        switch mode {
        case .new(_, let s): return "Time \(clock(s)), stamped when you tapped"
        case .windowDone: return model.windowStart.map { "From \(clock($0)) to now" } ?? ""
        case .label(let e): return "Time \(clock(e.stamp)), the mark's time. The mark stays as it is."
        case .edit(let e): return "Time \(clock(e.stamp)). An edit keeps this time and adds a revision."
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(when).font(.subheadline).foregroundStyle(.secondary)
                    if let e = entry, let img = model.image(for: e) {
                        Image(uiImage: img).resizable().scaledToFit().frame(maxHeight: 360).frame(maxWidth: .infinity)
                    } else if let e = entry, let s = model.shot(for: e) {
                        Text("Screenshot: \(s.status)").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Section("Action") {
                    Picker("Template", selection: $action) {
                        Text("Pick or type below").tag("")
                        ForEach(actionTemplates, id: \.self) { Text(templateLabel($0)).tag($0) }
                        if !action.isEmpty && !actionTemplates.contains(action) { Text(action).tag(action) }
                    }
                    TextField("Or type an action", text: $action).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Picker("App", selection: $app) {
                        Text("None (device or system)").tag("")
                        ForEach(model.apps) { Text($0.label).tag($0.pkg) }
                        if !app.isEmpty && !model.apps.contains(where: { $0.pkg == app }) { Text(app).tag(app) }
                    }
                }
                Section {
                    TextField("Content", text: $content, axis: .vertical)
                    TextField("Counterpart", text: $counterpart)
                    TextField("Conversation", text: $conversation)
                    TextField("Note", text: $note, axis: .vertical)
                    TextField("Expected artifacts, e.g. ileapp:key", text: $expected)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                } footer: { Text(KDL.noSecrets) }
                Section("The tap was") {
                    Picker("Time quality", selection: $quality) {
                        Text("Exact").tag(TimeQuality.exact)
                        Text("After the act").tag(TimeQuality.after)
                        Text("Before the act").tag(TimeQuality.before)
                        Text("Approximate").tag(TimeQuality.approximate)
                    }
                }
                if case .edit(let e) = mode {
                    Section {
                        Button("Remove entry", role: .destructive) { confirmRemove = true }
                    } footer: {
                        Text("Removing adds a tombstone revision. Revision \(e.revisionN) and earlier stay in the log.")
                    }
                }
            }
            .navigationTitle(title)
            .keyboardDone()
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { action.trimmingCharacters(in: .whitespaces) == factoryReset ? (confirmReset = true) : save() }
                        .disabled(needsAction && action.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .alert("Factory reset", isPresented: $confirmReset) {
                Button("Save") { save() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("A reset erases this log on the phone. Save this entry, then export and copy the export off the phone before resetting.")
            }
            .confirmationDialog("Remove this entry?", isPresented: $confirmRemove, titleVisibility: .visible) {
                Button("Remove", role: .destructive) {
                    if case .edit(let e) = mode { model.remove(e) }
                    dismiss()
                }
            }
            .onAppear(perform: load)
        }
    }

    /// The existing entry this form is about, if any.
    private var entry: Entry? {
        switch mode {
        case .label(let e), .edit(let e): return e
        default: return nil
        }
    }

    /// A label or a closed window needs an action; an edit of a stamped entry may stay blank.
    private var needsAction: Bool {
        if case .edit = mode { return false }
        return true
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        switch mode {
        case .new(let a, _):
            action = a
            app = model.lastDraft?.appPackage ?? model.apps.first?.pkg ?? ""
        case .windowDone:
            action = model.lastDraft?.action ?? ""
            app = model.lastDraft?.appPackage ?? ""
        case .label(let m):
            app = m.appPackage ?? model.lastDraft?.appPackage ?? ""
        case .edit(let e):
            action = e.action; app = e.appPackage ?? ""; content = e.content ?? ""; counterpart = e.counterpart ?? ""
            conversation = e.conversationId ?? ""; note = e.detail ?? ""; expected = e.expectedArtifacts.joined(separator: " ")
            quality = e.timeQuality
        }
    }

    private func draft() -> Draft {
        let a = action.trimmingCharacters(in: .whitespacesAndNewlines)
        return Draft(appPackage: app.isEmpty ? nil : app, action: a, content: content, counterpart: counterpart,
                     direction: directionFor(a), conversationId: conversation, timeQuality: quality,
                     expectedArtifacts: expected.split(whereSeparator: { $0 == "," || $0 == " " }).map(String.init),
                     detail: note)
    }

    private func save() {
        let d = draft()
        switch mode {
        case .new(_, let s): model.log(d, at: s)
        case .windowDone: model.finishWindow(d)
        case .label(let m): model.label(m, with: d)
        case .edit(let e): model.revise(e, d)
        }
        dismiss()
    }
}

extension String {
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}
