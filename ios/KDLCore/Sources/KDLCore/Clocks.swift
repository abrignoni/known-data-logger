import Foundation
import Darwin

/// Reads the clocks for one stamp. Never touches the network.
///
/// Measured on one iPhone 13, iOS 26.6 (Phase 0, 9 November 2026): mach_continuous_time kept counting
/// while the phone slept and `ProcessInfo.systemUptime` did not, so time since boot comes from
/// mach_continuous_time. `kern.boottime` was readable and agreed with now minus mach_continuous_time
/// to within about 6 seconds. No boot counter was found; `kern.bootsessionuuid` was refused (EPERM).
/// There is no network time or GNSS time API, so those fields stay empty.
public enum Clocks {
    public static func stamp() -> Stamp {
        let elapsed = continuousMs()
        let utc = Int64((Date().timeIntervalSince1970 * 1000).rounded(.down))
        // A long-lived process keeps the zone it started with unless the cache is reset.
        NSTimeZone.resetSystemTimeZone()
        let tz = TimeZone.current
        let date = Date(timeIntervalSince1970: TimeInterval(utc) / 1000)
        return Stamp(utcMs: utc, tzId: tz.identifier, tzOffsetMin: tz.secondsFromGMT(for: date) / 60,
                     elapsedMs: elapsed, bootCount: bootCountUnavailable, networkTimeMs: nil, gnssTimeMs: nil,
                     kernBootMs: kernBootMs())
    }

    /// Time since boot, sleep included, in whole milliseconds.
    public static func continuousMs() -> Int64 {
        var tb = mach_timebase_info_data_t()
        mach_timebase_info(&tb)
        let ns = Double(mach_continuous_time()) * Double(tb.numer) / Double(tb.denom)
        return Int64((ns / 1_000_000).rounded(.down))
    }

    /// `kern.boottime` in epoch ms, or nil when the call fails.
    public static func kernBootMs() -> Int64? {
        var tv = timeval()
        var size = MemoryLayout<timeval>.size
        guard sysctlbyname("kern.boottime", &tv, &size, nil, 0) == 0 else { return nil }
        return Int64(tv.tv_sec) * 1000 + Int64(tv.tv_usec) / 1000
    }

    /// A sysctl string such as hw.machine ("iPhone14,5") or kern.osversion (the build, "23G71").
    public static func sysctlString(_ name: String) -> String? {
        var size = 0
        guard sysctlbyname(name, nil, &size, nil, 0) == 0, size > 0 else { return nil }
        var buf = [CChar](repeating: 0, count: size)
        guard sysctlbyname(name, &buf, &size, nil, 0) == 0 else { return nil }
        return String(cString: buf)
    }
}
