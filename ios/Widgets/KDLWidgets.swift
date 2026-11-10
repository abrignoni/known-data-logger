import ActivityKit
import AppIntents
import KDLCore
import SwiftUI
import WidgetKit

@main
struct KDLWidgets: WidgetBundle {
    var body: some Widget {
        QuickLogWidget()
        SessionLiveActivity()
        if #available(iOS 18.0, *) {
            MarkControl()
            StampControl()
        }
    }
}

// MARK: Home Screen and Lock Screen widget

struct QuickLogEntry: TimelineEntry {
    let date: Date
    let session: Date?
    let toCheck: Int
    let last: String?
}

struct QuickLogProvider: TimelineProvider {
    static let empty = QuickLogEntry(date: .now, session: nil, toCheck: 0, last: nil)

    func placeholder(in context: Context) -> QuickLogEntry { Self.empty }

    func getSnapshot(in context: Context, completion: @escaping (QuickLogEntry) -> Void) { completion(read()) }

    func getTimeline(in context: Context, completion: @escaping (WidgetKit.Timeline<QuickLogEntry>) -> Void) {
        completion(WidgetKit.Timeline(entries: [read()], policy: .never))  // reloaded by the app and the intents
    }

    private func read() -> QuickLogEntry {
        guard let s = try? KDL.store() else { return Self.empty }
        let rows = s.entries()
        let last = rows.last.map { e -> String in
            let t = Date(timeIntervalSince1970: TimeInterval(e.stamp.utcMs) / 1000).formatted(date: .omitted, time: .standard)
            return "\(displayLabel(e)) \(t)"
        }
        let start = s.sessionActive ? s.sessionStart().map { Date(timeIntervalSince1970: TimeInterval($0.utcMs) / 1000) } : nil
        return QuickLogEntry(date: .now, session: start, toCheck: Review.pending(rows).count, last: last)
    }

}

struct QuickLogView: View {
    let entry: QuickLogEntry
    @Environment(\.widgetFamily) var family

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("KDL").font(.headline)
                Spacer()
                if let s = entry.session {
                    Text(s, style: .timer).font(.caption.monospacedDigit()).foregroundStyle(.red)
                } else {
                    Text("No session").font(.caption).foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 8) {
                Button(intent: MarkIntent()) {
                    Label("Mark", systemImage: "flag.fill").frame(maxWidth: .infinity)
                }.tint(.orange)
                Button(intent: StampIntent()) {
                    Label("Stamp", systemImage: "clock.badge.checkmark").frame(maxWidth: .infinity)
                }.tint(.blue)
            }
            .buttonStyle(.borderedProminent)
            .labelStyle(family == .systemSmall ? AnyLabelStyle(.iconOnly) : AnyLabelStyle(.titleAndIcon))
            Spacer(minLength: 0)
            if let last = entry.last { Text(last).font(.caption2).foregroundStyle(.secondary).lineLimit(1) }
            if entry.toCheck > 0 { Text("\(entry.toCheck) to check").font(.caption2).foregroundStyle(.orange) }
        }
        .containerBackground(.fill.tertiary, for: .widget)
    }
}

struct AnyLabelStyle: LabelStyle {
    private let make: (Configuration) -> AnyView
    init<S: LabelStyle>(_ s: S) { make = { AnyView(s.makeBody(configuration: $0)) } }
    func makeBody(configuration: Configuration) -> some View { make(configuration) }
}

struct QuickLogWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "QuickLog", provider: QuickLogProvider()) { QuickLogView(entry: $0) }
            .configurationDisplayName("KDL quick log")
            .description("Mark or stamp an action without opening KDL.")
            .supportedFamilies([.systemSmall, .systemMedium])
    }
}

// MARK: Control Center, Lock Screen and Action button controls (iOS 18)

@available(iOS 18.0, *)
struct MarkControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "com.abrignoni.knowndatalogger.mark") {
            ControlWidgetButton(action: MarkIntent()) { Label("KDL Mark", systemImage: "flag.fill") }
        }
        .displayName("KDL Mark")
        .description("Stamps \"this is the action\" now.")
    }
}

@available(iOS 18.0, *)
struct StampControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "com.abrignoni.knowndatalogger.stamp") {
            ControlWidgetButton(action: StampIntent()) { Label("KDL Stamp", systemImage: "clock.badge.checkmark") }
        }
        .displayName("KDL Stamp")
        .description("Stamps an action to describe later.")
    }
}

// MARK: Live Activity while a session runs

struct SessionLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SessionActivityAttributes.self) { context in
            HStack {
                VStack(alignment: .leading) {
                    Text("KDL session").font(.headline)
                    Text(context.attributes.started, style: .timer).font(.subheadline.monospacedDigit())
                    Text("\(context.attributes.scenarioId) · \(context.attributes.deviceLabel)").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button(intent: MarkIntent()) { Label("Mark", systemImage: "flag.fill") }.tint(.orange)
                Button(intent: StampIntent()) { Label("Stamp", systemImage: "clock.badge.checkmark") }.tint(.blue)
            }
            .buttonStyle(.borderedProminent)
            .padding()
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Text(context.attributes.started, style: .timer).monospacedDigit() }
                DynamicIslandExpandedRegion(.trailing) {
                    Button(intent: MarkIntent()) { Label("Mark", systemImage: "flag.fill") }.tint(.orange)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Button(intent: StampIntent()) { Label("Stamp", systemImage: "clock.badge.checkmark") }.tint(.blue)
                }
            } compactLeading: {
                Image(systemName: "record.circle").foregroundStyle(.red)
            } compactTrailing: {
                Text(context.attributes.started, style: .timer).monospacedDigit().frame(maxWidth: 52)
            } minimal: {
                Image(systemName: "record.circle").foregroundStyle(.red)
            }
        }
    }
}
