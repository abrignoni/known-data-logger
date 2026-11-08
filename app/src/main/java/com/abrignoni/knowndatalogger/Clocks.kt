package com.abrignoni.knowndatalogger

import android.content.ContentResolver
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.time.ZoneId

/** Reads every clock the spec records, at one tap. Never touches the network. */
object Clocks {
    fun stamp(resolver: ContentResolver): Stamp {
        val elapsed = SystemClock.elapsedRealtime()
        val utc = System.currentTimeMillis()
        // Read the zone fresh at every tap; a long-lived process can hold a stale default.
        val zone = ZoneId.systemDefault()
        val offsetMin = zone.rules.getOffset(java.time.Instant.ofEpochMilli(utc)).totalSeconds / 60
        return Stamp(
            utcMs = utc,
            tzId = zone.id,
            tzOffsetMin = offsetMin,
            elapsedMs = elapsed,
            bootCount = bootCount(resolver),
            networkTimeMs = networkTime(),
            gnssTimeMs = gnssTime(),
        )
    }

    /** Settings.Global.BOOT_COUNT, or -1 when the device does not expose it. */
    fun bootCount(resolver: ContentResolver): Int =
        try {
            Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1)
        } catch (e: Exception) {
            -1
        }

    /** The system's last network time sync (API 33+). Null when unavailable. */
    private fun networkTime(): Long? =
        if (Build.VERSION.SDK_INT >= 33) {
            try { SystemClock.currentNetworkTimeClock().millis() } catch (e: Exception) { null }
        } else null

    /** Time from the last GNSS fix (API 29+). Null when unavailable. */
    private fun gnssTime(): Long? =
        try { SystemClock.currentGnssTimeClock().millis() } catch (e: Exception) { null }
}
