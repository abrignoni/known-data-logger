import CoreMedia
import ImageIO
import KDLCore
import ReplayKit
import UniformTypeIdentifiers
import VideoToolbox

/// The screen broadcast the tester starts from KDL. It keeps only the latest video frame, and when a
/// mark is logged anywhere (app, widget, control, Live Activity, Shortcuts) it saves that frame as a
/// PNG for the mark, with its SHA-256. It also logs each app switch ReplayKit reports.
///
/// Rows it writes: screen_capture_started / screen_capture_stopped (system rows; a start with no stop
/// means iOS ended the extension without telling it), and an auto_event "screen" row per app switch
/// with the bundle id in app_package.
final class SampleHandler: RPBroadcastSampleHandler {
    private let lock = NSLock()
    private var latest: CVPixelBuffer?
    private var latestAtMs: Int64 = 0
    private var latestOrientation: CGImagePropertyOrientation = .up
    private let work = DispatchQueue(label: "com.abrignoni.knowndatalogger.broadcast")
    private var startedAtMs: Int64 = 0

    // Counters reported in the stop row.
    private var saved = 0
    private var failed = 0
    private var switches = 0
    /// Largest gap, either way, between a mark's stamp and the arrival of the frame saved for it.
    private var maxFrameGapMs: Int64 = 0
    private var peakFootprint: UInt64 = 0

    private var store: Store? { try? KDL.store() }

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        startedAtMs = Clocks.stamp().utcMs
        try? FileManager.default.createDirectory(at: KDL.screensDir, withIntermediateDirectories: true)
        try? store?.putSetting("broadcast_active", "1")
        _ = try? store?.log(Draft(action: "screen_capture_started", detail: "screen broadcast started", source: Source.system),
                            kind: Kind.systemEvent)
        let observer = Unmanaged.passUnretained(self).toOpaque()
        CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(), observer, { _, observer, _, _, _ in
            guard let observer else { return }
            Unmanaged<SampleHandler>.fromOpaque(observer).takeUnretainedValue().markLogged()
        }, KDL.markNotification as CFString, nil, .deliverImmediately)
        KDL.reloadSurfaces()
    }

    override func broadcastFinished() {
        CFNotificationCenterRemoveEveryObserver(CFNotificationCenterGetDarwinNotifyCenter(), Unmanaged.passUnretained(self).toOpaque())
        work.sync {}  // let a screenshot in progress finish
        try? store?.putSetting("broadcast_active", "0")
        let detail = "screen broadcast stopped; \(saved) screenshot(s) saved, \(failed) failed, \(switches) app switch(es); " +
            "largest gap between a mark and its frame \(maxFrameGapMs) ms; peak memory footprint \(peakFootprint / 1_048_576) MB"
        _ = try? store?.log(Draft(action: "screen_capture_stopped", detail: detail, source: Source.system), kind: Kind.systemEvent)
        KDL.reloadSurfaces()
    }

    override func broadcastAnnotated(withApplicationInfo applicationInfo: [AnyHashable: Any]) {
        guard let id = applicationInfo[RPApplicationInfoBundleIdentifierKey] as? String else { return }
        switches += 1
        _ = try? store?.log(Draft(appPackage: id, action: AutoAction.screen, detail: "app in front, reported by the screen broadcast",
                                  source: Source.autoEvent), kind: Kind.autoEvent)
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video, let px = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let raw = (CMGetAttachment(sampleBuffer, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil) as? NSNumber)?.uint32Value
        let now = Clocks.stamp().utcMs
        lock.lock()
        latest = px
        latestAtMs = now
        latestOrientation = raw.flatMap(CGImagePropertyOrientation.init(rawValue:)) ?? .up
        lock.unlock()
    }

    /// Called for every mark notification. Each mark logged since the broadcast started that has no
    /// screenshot yet gets the latest frame (or a failed row when there is none).
    private func markLogged() {
        work.async { [self] in
            guard let store else { return }
            lock.lock()
            let frame = latest, frameAt = latestAtMs, orientation = latestOrientation
            lock.unlock()
            for mark in store.marksWithoutShot(sinceUtcMs: startedAtMs) {
                autoreleasepool { save(frame: frame, frameAt: frameAt, orientation: orientation, for: mark, in: store) }
            }
        }
    }

    private func save(frame: CVPixelBuffer?, frameAt: Int64, orientation: CGImagePropertyOrientation, for mark: Entry, in store: Store) {
        let now = Clocks.stamp().utcMs
        guard let frame else {
            failed += 1
            try? store.putShot(Shot(entryId: mark.entryId, file: nil, sha256: nil, status: "failed:no frame received yet", utcMs: now))
            return
        }
        var cg: CGImage?
        VTCreateCGImageFromCVPixelBuffer(frame, options: nil, imageOut: &cg)
        let data = NSMutableData()
        guard let cg, let dest = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil) else {
            failed += 1
            try? store.putShot(Shot(entryId: mark.entryId, file: nil, sha256: nil, status: "failed:could not convert the frame", utcMs: now))
            return
        }
        let props: [CFString: Any] = orientation == .up ? [:] : [kCGImagePropertyOrientation: orientation.rawValue]
        CGImageDestinationAddImage(dest, cg, props as CFDictionary)
        guard CGImageDestinationFinalize(dest) else {
            failed += 1
            try? store.putShot(Shot(entryId: mark.entryId, file: nil, sha256: nil, status: "failed:could not encode the frame", utcMs: now))
            return
        }
        let name = "\(mark.entryId).png"
        do {
            try (data as Data).write(to: KDL.screensDir.appendingPathComponent(name), options: .atomic)
            let status = isBlank(cg) ? "blank" : "ok"
            try store.putShot(Shot(entryId: mark.entryId, file: name, sha256: sha256Hex(data as Data), status: status, utcMs: now))
            saved += 1
            maxFrameGapMs = max(maxFrameGapMs, abs(mark.stamp.utcMs - frameAt))
        } catch {
            failed += 1
            try? store.putShot(Shot(entryId: mark.entryId, file: nil, sha256: nil, status: "failed:could not write the file", utcMs: now))
        }
        peakFootprint = max(peakFootprint, footprint())
    }

    /// Every sampled pixel black or near black, as the Android app checks: a protected screen comes back blank.
    private func isBlank(_ image: CGImage) -> Bool {
        let n = 16
        var px = [UInt8](repeating: 0, count: n * n * 4)
        guard let ctx = CGContext(data: &px, width: n, height: n, bitsPerComponent: 8, bytesPerRow: n * 4,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: n, height: n))
        return stride(from: 0, to: px.count, by: 4).allSatisfy { px[$0] < 16 && px[$0 + 1] < 16 && px[$0 + 2] < 16 }
    }

    /// The memory figure iOS uses to decide whether an extension is over its limit.
    private func footprint() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let rc = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) }
        }
        return rc == KERN_SUCCESS ? info.phys_footprint : 0
    }
}
