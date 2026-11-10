import KDLCore
import SwiftUI
import UIKit

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

/// One form for every entry. Ordered as the tester works from a screenshot: picture, what happened,
/// the text that matters, then the rest under More.
struct EntrySheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let mode: EntryMode
    /// Called after a successful save, before the form closes (Review uses it to open the next mark).
    var onSaved: () -> Void = {}

    @State private var action = ""
    @State private var app = ""
    @State private var content = ""
    @State private var counterpart = ""
    @State private var conversation = ""
    @State private var note = ""
    @State private var expected = ""
    @State private var quality = TimeQuality.exact
    @State private var more = false
    @State private var confirmReset = false
    @State private var confirmRemove = false
    @State private var zoom = false
    @State private var loaded = false

    private var title: String {
        switch mode {
        case .new: return action.isEmpty ? "Log an action" : actionWords(action)
        case .windowDone: return "Done timing"
        case .label: return "What happened?"
        case .edit: return "Edit"
        }
    }

    private var when: String {
        switch mode {
        case .new(_, let s): return "Time \(clock(s)), taken when you tapped."
        case .windowDone: return model.windowStart.map { "From \(clock($0)) to now." } ?? ""
        case .label(let e): return "Marked at \(clock(e.stamp)). The label gets this time."
        case .edit(let e): return "Time \(clock(e.stamp)). Saving keeps this time and adds a revision."
        }
    }

    private var suggestions: [String] {
        let s = AppNames.suggestions[app] ?? AppNames.fallbackSuggestions
        return s.filter { $0 != action }
    }

    /// Apps offered: those under test, those the capture saw, and the entry's own.
    private var appChoices: [String] {
        var out = model.apps.map(\.pkg)
        for p in model.appsUsed where !out.contains(p) { out.append(p) }
        if !app.isEmpty && !out.contains(app) { out.append(app) }
        return out
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if let e = entry, let img = model.image(for: e) {
                        Button { zoom = true } label: {
                            Image(uiImage: img).resizable().scaledToFit().frame(maxHeight: 420).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.plain)
                        Text("Tap the picture to zoom.").font(.caption).foregroundStyle(.secondary)
                    } else if let e = entry, e.isMark {
                        Text(model.shot(for: e).map { "No screenshot (\($0.status))." } ?? "No screenshot: screen capture was off.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    Text(when).font(.footnote).foregroundStyle(.secondary)
                }

                Section {
                    TextField("Action, e.g. sent_message", text: $action)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(suggestions, id: \.self) { s in
                                Button(actionWords(s)) { action = s }.buttonStyle(.bordered).controlSize(.small)
                            }
                            Menu {
                                ForEach(actionTemplates, id: \.self) { t in Button(actionWords(t)) { action = t } }
                            } label: { Label("All", systemImage: "list.bullet") }
                            .buttonStyle(.bordered).controlSize(.small)
                        }
                    }
                    Picker("App", selection: $app) {
                        Text("None (device or system)").tag("")
                        ForEach(appChoices, id: \.self) { Text(model.appName($0) ?? $0).tag($0) }
                    }
                } header: { Text("Action") } footer: {
                    if needsAction && action.trimmingCharacters(in: .whitespaces).isEmpty { Text("Pick or type an action to save.") }
                }

                Section {
                    TextField("Content: the exact text, URL or file name", text: $content, axis: .vertical)
                    TextField("Counterpart: the other person or account", text: $counterpart)
                } footer: { Text(KDL.noSecrets) }

                Section {
                    DisclosureGroup("More", isExpanded: $more) {
                        TextField("Conversation", text: $conversation)
                        TextField("Note", text: $note, axis: .vertical)
                        TextField("Expected artifacts, e.g. ileapp:key", text: $expected)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        Picker("The time is", selection: $quality) {
                            Text("Exact").tag(TimeQuality.exact)
                            Text("After the act").tag(TimeQuality.after)
                            Text("Before the act").tag(TimeQuality.before)
                            Text("Approximate").tag(TimeQuality.approximate)
                        }
                    }
                }

                if let e = entry {
                    Section {
                        Button(e.isMark ? "Remove this mark" : "Remove entry", role: .destructive) { confirmRemove = true }
                    } footer: {
                        Text("Removing adds a tombstone revision; the earlier rows stay in the log and the export.")
                    }
                }
            }
            .keyboardDone()
            .navigationTitle(title)
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
            .confirmationDialog("Remove this?", isPresented: $confirmRemove, titleVisibility: .visible) {
                Button("Remove", role: .destructive) {
                    if let e = entry { model.remove(e); onSaved() }
                    dismiss()
                }
            }
            .fullScreenCover(isPresented: $zoom) {
                if let e = entry, let img = model.image(for: e) { ZoomView(image: img) }
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
        case .windowDone:
            action = ""
        case .label(let m):
            app = m.appPackage ?? ""
            // A mark is tapped right after the act (Back Tap once it is done), so its label's time is "after".
            quality = TimeQuality.after
        case .edit(let e):
            action = e.action; app = e.appPackage ?? ""; content = e.content ?? ""; counterpart = e.counterpart ?? ""
            conversation = e.conversationId ?? ""; note = e.detail ?? ""; expected = e.expectedArtifacts.joined(separator: " ")
            quality = e.timeQuality
            more = !conversation.isEmpty || !note.isEmpty || !expected.isEmpty
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
        onSaved()
        dismiss()
    }
}

/// A screenshot full screen, with pinch to zoom.
struct ZoomView: View {
    let image: UIImage
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZoomableImage(image: image)
                .ignoresSafeArea(edges: .bottom)
                .background(Color.black)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

struct ZoomableImage: UIViewRepresentable {
    let image: UIImage

    func makeUIView(context: Context) -> UIScrollView {
        let s = UIScrollView()
        s.minimumZoomScale = 1
        s.maximumZoomScale = 5
        s.delegate = context.coordinator
        s.backgroundColor = .black
        let v = UIImageView(image: image)
        v.contentMode = .scaleAspectFit
        v.frame = s.bounds
        v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        s.addSubview(v)
        context.coordinator.view = v
        return s
    }

    func updateUIView(_ uiView: UIScrollView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        weak var view: UIView?
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { view }
    }
}
