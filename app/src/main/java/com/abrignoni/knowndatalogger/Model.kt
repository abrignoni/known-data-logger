package com.abrignoni.knowndatalogger

import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

const val SCHEMA_VERSION = 1

/** Allowed values for Entry.kind. */
object Kind {
    const val ACTION = "action"
    const val SYSTEM_EVENT = "system_event"
    const val CLOCK_CHANGE = "clock_change"
    const val EXPORT = "export"
    const val NOTE = "note"
    const val TOMBSTONE = "tombstone"
}

/** Allowed values for Entry.timeQuality. */
object TimeQuality {
    const val EXACT = "exact"
    const val AFTER = "after"
    const val BEFORE = "before"
    const val APPROXIMATE = "approximate"
    val ALL = listOf(EXACT, AFTER, BEFORE, APPROXIMATE)
}

/** Default checker tolerances, written into every manifest. Chosen defaults, not measured. */
object Tolerance {
    const val EXACT_S = 120
    const val BEFORE_AFTER_S = 300
    const val APPROXIMATE_S = 900
}

/** Clock readings taken at one tap. */
data class Stamp(
    val utcMs: Long,
    val tzId: String,
    val tzOffsetMin: Int,
    val elapsedMs: Long,
    val bootCount: Int,
    val networkTimeMs: Long?,
    val gnssTimeMs: Long?,
) {
    /** Wall clock minus time since boot. Constant within a boot unless the clock was set. */
    val bootWallMs: Long get() = utcMs - elapsedMs
}

data class Entry(
    val seq: Long = 0,
    val entryId: String,
    val revisionN: Int = 0,
    val kind: String,
    val scenarioId: String,
    val deviceLabel: String,
    val profileId: Long?,
    val stamp: Stamp,
    val timeQuality: String = TimeQuality.EXACT,
    val windowS: Double? = null,
    val editedUtcMs: Long,
    val appPackage: String? = null,
    val appVersion: String? = null,
    val account: String? = null,
    val action: String = "",
    val content: String? = null,
    val counterpart: String? = null,
    val direction: String? = null,
    val conversationId: String? = null,
    val refersTo: String? = null,
    val expectedArtifacts: List<String> = emptyList(),
    val detail: String? = null,
) {
    /** A stamped entry that still has no action written. */
    val needsDetail: Boolean get() = kind == Kind.ACTION && action.isBlank()
}

object Timeline {
    /** Ordering key: boot, then monotonic time within the boot, then insertion order. */
    val ORDER: Comparator<Entry> =
        compareBy<Entry>({ it.stamp.bootCount }, { it.stamp.elapsedMs }, { it.seq })

    /** Highest revision of each entry, dropping entries whose latest revision is a tombstone. */
    fun current(rows: List<Entry>): List<Entry> =
        rows.groupBy { it.entryId }
            .values
            .map { revs -> revs.maxWith(compareBy<Entry>({ it.revisionN }, { it.seq })) }
            .filter { it.kind != Kind.TOMBSTONE }
            .sortedWith(ORDER)

    /** Original (revision 0) rows carry the fixed time of each entry. */
    private fun originals(rows: List<Entry>): List<Entry> =
        rows.filter { it.revisionN == 0 }.sortedWith(ORDER)

    /**
     * Compares a new stamp with the last stamp already logged.
     * Returns system rows to insert before the new entry: a boot count change,
     * or a jump in boot_wall_ms within the same boot (the wall clock was set).
     */
    fun detectChanges(rows: List<Entry>, next: Stamp, thresholdMs: Long = 2000): List<Pair<String, String>> {
        val last = originals(rows).lastOrNull() ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        if (next.bootCount >= 0 && last.stamp.bootCount >= 0 && next.bootCount != last.stamp.bootCount) {
            out += Kind.SYSTEM_EVENT to
                "boot_count changed from ${last.stamp.bootCount} to ${next.bootCount}"
        } else {
            val delta = next.bootWallMs - last.stamp.bootWallMs
            if (kotlin.math.abs(delta) > thresholdMs) {
                out += Kind.CLOCK_CHANGE to
                    "wall clock moved ${delta} ms relative to the monotonic clock since the previous entry"
            }
        }
        if (next.tzId != last.stamp.tzId || next.tzOffsetMin != last.stamp.tzOffsetMin) {
            out += Kind.CLOCK_CHANGE to
                "time zone changed from ${last.stamp.tzId} (${last.stamp.tzOffsetMin} min) to ${next.tzId} (${next.tzOffsetMin} min)"
        }
        return out
    }
}

object Csv {
    val HEADER = listOf(
        "seq", "entry_id", "revision_n", "is_current", "kind", "scenario_id", "device_label", "profile_id",
        "local_time", "utc_time", "tz_id", "tz_offset_min", "utc_ms", "elapsed_ms", "boot_count",
        "boot_wall_ms", "network_time_ms", "gnss_time_ms", "time_quality", "window_s", "edited_utc_ms",
        "app_package", "app_version", "account", "action", "content", "counterpart", "direction",
        "conversation_id", "refers_to", "expected_artifacts", "detail",
    )

    fun field(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

    fun localIso(utcMs: Long, offsetMin: Int): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(utcMs), ZoneOffset.ofTotalSeconds(offsetMin * 60))
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun utcIso(utcMs: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(utcMs))

    fun row(e: Entry, isCurrent: Boolean): String = listOf(
        e.seq, e.entryId, e.revisionN, isCurrent, e.kind, e.scenarioId, e.deviceLabel, e.profileId,
        localIso(e.stamp.utcMs, e.stamp.tzOffsetMin), utcIso(e.stamp.utcMs), e.stamp.tzId,
        e.stamp.tzOffsetMin, e.stamp.utcMs, e.stamp.elapsedMs, e.stamp.bootCount, e.stamp.bootWallMs,
        e.stamp.networkTimeMs, e.stamp.gnssTimeMs, e.timeQuality, e.windowS, e.editedUtcMs,
        e.appPackage, e.appVersion, e.account, e.action, e.content, e.counterpart, e.direction,
        e.conversationId, e.refersTo, e.expectedArtifacts.joinToString(";"), e.detail,
    ).joinToString(",") { field(it) }

    /** All rows, every revision, in insertion order, with is_current marking the live version. */
    fun document(rows: List<Entry>): String {
        val currentSeqs = Timeline.current(rows).map { it.seq }.toSet()
        val sb = StringBuilder(HEADER.joinToString(",")).append("\r\n")
        rows.sortedBy { it.seq }.forEach { sb.append(row(it, it.seq in currentSeqs)).append("\r\n") }
        return sb.toString()
    }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Action templates offered on the Log screen. Keys are written to the log as is. */
val ACTION_TEMPLATES = listOf(
    "sent_message", "received_message", "edited_message", "deleted_message",
    "outgoing_call", "incoming_call", "missed_call",
    "took_photo", "recorded_video", "recorded_audio", "sent_media", "received_media",
    "visited_url", "searched", "viewed_location", "navigated",
    "added_contact", "deleted_item", "created_account", "logged_in", "logged_out",
    "installed_app", "uninstalled_app",
    "airplane_mode_on", "airplane_mode_off", "connected_wifi", "connected_bluetooth",
    "changed_clock", "changed_time_zone", "rebooted", "powered_off", "powered_on",
    "inserted_sim", "removed_sim", "factory_reset",
)

const val FACTORY_RESET = "factory_reset"

fun templateLabel(key: String): String = key.replace('_', ' ')
