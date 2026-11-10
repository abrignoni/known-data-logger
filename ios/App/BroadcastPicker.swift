import ReplayKit
import SwiftUI

/// The system button that starts and stops the screen broadcast, pointed at KDL's extension.
struct BroadcastPicker: UIViewRepresentable {
    static let extensionId = KDL.broadcastId

    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let v = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 44, height: 44))
        v.preferredExtension = Self.extensionId
        v.showsMicrophoneButton = false
        return v
    }

    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {}
}
