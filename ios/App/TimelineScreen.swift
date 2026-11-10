import KDLCore
import SwiftUI

struct TimelineScreen: View {
    @EnvironmentObject var model: AppModel
    @State private var filter = 0
    @State private var sheet: EntryMode?

    private var revisions: [String: Int] {
        Dictionary(grouping: model.rows, by: \.entryId).mapValues { $0.count }
    }

    var body: some View {
        NavigationStack {
            List {
                Picker("Show", selection: $filter) {
                    Text("To check (\(model.pending.count))").tag(0)
                    Text("Everything").tag(1)
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)

                let shown = (filter == 0 ? model.pending : model.current).reversed()
                if shown.isEmpty {
                    Text(filter == 0 ? "Nothing to check." : "Nothing logged yet.").foregroundStyle(.secondary)
                }
                ForEach(Array(shown), id: \.seq) { e in
                    Button { open(e) } label: { EntryRow(entry: e, revisions: revisions[e.entryId] ?? 1, apps: model.apps) }
                        .foregroundStyle(.primary)
                        .disabled(!editable(e))
                }
            }
            .navigationTitle("Timeline")
            .sheet(item: $sheet) { EntrySheet(mode: $0) }
            .refreshable { model.refresh() }
        }
    }

    /// Marks get a label (a new row); actions and notes get a revision. System rows are not edited.
    private func editable(_ e: Entry) -> Bool { e.isMark || e.kind == Kind.action || e.kind == Kind.note }

    private func open(_ e: Entry) {
        if e.isMark { sheet = .label(e) } else if editable(e) { sheet = .edit(e) }
    }
}

struct EntryRow: View {
    let entry: Entry
    let revisions: Int
    let apps: [AppUnderTest]

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(displayLabel(entry)).font(.headline)
                    .foregroundStyle(entry.isMark || entry.needsDetail ? .orange : .primary)
                Spacer()
                Text(clock(entry.stamp)).font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
            }
            let app = entry.appPackage.map { p in apps.first { $0.pkg == p }?.label ?? p }
            let parts = [app, entry.counterpart, entry.content, entry.windowS.map { String(format: "%.0f s", $0) }].compactMap { $0 }
            if !parts.isEmpty { Text(parts.joined(separator: " · ")).font(.subheadline).lineLimit(2) }
            if let d = entry.detail, entry.source == Source.system { Text(d).font(.caption).foregroundStyle(.secondary).lineLimit(3) }
            if revisions > 1 { Text("Edited, \(revisions) revisions").font(.caption).foregroundStyle(.secondary) }
        }
    }
}
