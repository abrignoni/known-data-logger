package com.abrignoni.knowndatalogger

import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

const val SCHEMA_VERSION = 2

/** Allowed values for Entry.kind. */
object Kind {
    const val ACTION = "action"
    const val SYSTEM_EVENT = "system_event"
    const val CLOCK_CHANGE = "clock_change"
    const val EXPORT = "export"
    const val NOTE = "note"
    const val TOMBSTONE = "tombstone"
    /** Raw event seen by the capture service (a tap, typed text, a screen change, a mark). */
    const val AUTO_EVENT = "auto_event"
}

/** Where a row came from. */
object Source {
    const val MANUAL = "manual"
    const val AUTO_EVENT = "auto_event"
    const val AUTO_RULE = "auto_rule"
    const val MARK = "mark"
    const val SYSTEM = "system"
}

/** Actions written for raw capture events. */
object AutoAction {
    const val TAP = "tap"
    const val TYPED_TEXT = "typed_text"
    const val SCREEN = "screen"
    const val MARK = "mark"
    /** A text field emptied in one step (all text removed by a single change), the usual sign of a send. */
    const val FIELD_CLEARED = "field_cleared"
}

/** Events a rule can match. Compose apps report no tap events, so their sends match FIELD_CLEARED. */
object RuleEvent {
    const val TAP = "tap"
    const val FIELD_CLEARED = "field_cleared"
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
    val source: String = Source.MANUAL,
    /** False only for auto-labelled actions nobody has accepted yet. */
    val reviewed: Boolean = true,
) {
    val isMark: Boolean get() = kind == Kind.AUTO_EVENT && action == AutoAction.MARK

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
    fun detectChanges(rows: List<Entry>, next: Stamp, thresholdMs: Long = 2000): List<Pair<String, String>> =
        detectChangesFrom(originals(rows).lastOrNull()?.stamp, next, thresholdMs)

    /** Same check against a known last stamp, so a busy capture session does not re-read the table. */
    fun detectChangesFrom(last: Stamp?, next: Stamp, thresholdMs: Long = 2000): List<Pair<String, String>> {
        if (last == null) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        if (next.bootCount >= 0 && last.bootCount >= 0 && next.bootCount != last.bootCount) {
            out += Kind.SYSTEM_EVENT to
                "boot_count changed from ${last.bootCount} to ${next.bootCount}"
        } else {
            val delta = next.bootWallMs - last.bootWallMs
            if (kotlin.math.abs(delta) > thresholdMs) {
                out += Kind.CLOCK_CHANGE to
                    "wall clock moved ${delta} ms relative to the monotonic clock since the previous entry"
            }
        }
        if (next.tzId != last.tzId || next.tzOffsetMin != last.tzOffsetMin) {
            out += Kind.CLOCK_CHANGE to
                "time zone changed from ${last.tzId} (${last.tzOffsetMin} min) to ${next.tzId} (${next.tzOffsetMin} min)"
        }
        return out
    }
}

/** What still needs a person: unaccepted auto labels, unlabelled marks, stamped-only entries. */
object Review {
    fun pending(rows: List<Entry>): List<Entry> {
        val current = Timeline.current(rows)
        val resolvedMarks = current.mapNotNull { it.refersTo }.toSet()
        return current.filter {
            (it.kind == Kind.ACTION && it.source == Source.AUTO_RULE && !it.reviewed) ||
                (it.isMark && it.entryId !in resolvedMarks) ||
                it.needsDetail
        }
    }
}

/** A screenshot tied to one entry. Status is ok, blank, failed:<reason> or unsupported. */
data class Shot(
    val entryId: String,
    val file: String?,
    val sha256: String?,
    val status: String,
    val utcMs: Long,
)

/**
 * One auto-label rule. Matches one event type (tap or field_cleared) in one package by view id
 * and/or label (regex, case insensitive). content = "last_typed" copies the text typed just
 * before the tap, or the text that was cleared.
 */
data class Rule(
    val id: String,
    val pkg: String,
    val viewId: String?,
    val labelRegex: String?,
    val action: String,
    val content: String?,
    val verifiedWith: String?,
    val event: String = RuleEvent.TAP,
) {
    /** Compiled once; an invalid pattern is rejected when the rules file is parsed. */
    private val regex: Regex? by lazy { labelRegex?.let { Regex(it, RegexOption.IGNORE_CASE) } }

    fun matches(pkg: String, event: String, viewId: String?, label: String?): Boolean {
        if (pkg != this.pkg || event != this.event) return false
        if (this.viewId == null && labelRegex == null) return false
        if (this.viewId != null && this.viewId != viewId) return false
        val re = regex
        if (re != null && (label == null || !re.containsMatchIn(label))) return false
        return true
    }
}

object Rules {
    fun match(rules: List<Rule>, pkg: String, event: String, viewId: String?, label: String?): Rule? =
        rules.firstOrNull { it.matches(pkg, event, viewId, label) }

    /** True when one text change removed all of the previous text (at least 2 characters). */
    fun isBulkClear(before: String?, added: Int, removed: Int): Boolean =
        before != null && before.length >= 2 && added == 0 && removed == before.length

    /** Later lists override earlier ones rule by rule (same id). */
    fun merge(vararg lists: List<Rule>): List<Rule> {
        val byId = LinkedHashMap<String, Rule>()
        lists.forEach { l -> l.forEach { byId[it.id] = it } }
        return byId.values.toList()
    }
}

/** One typed value, stamped at its last change (not when it was flushed). */
data class Typed(val pkg: String, val viewId: String?, val text: String, val stamp: Stamp?)

/**
 * Holds typed text until the field is done: the next tap, screen change, focus change or session
 * stop flushes it as one typed_text event. Password fields never enter the buffer.
 */
class TextBuffer {
    private var key: String? = null
    private var text: String? = null
    private var pkg: String? = null
    private var viewId: String? = null
    private var stamp: Stamp? = null
    private var lastFlushed: Typed? = null

    fun update(pkg: String, viewId: String?, fieldKey: String, value: String?, isPassword: Boolean, at: Stamp? = null): Typed? {
        if (isPassword) return null
        val flushed = if (key != null && key != fieldKey) flush() else null
        key = fieldKey; this.pkg = pkg; this.viewId = viewId; text = value; stamp = at
        return flushed
    }

    /** Returns the pending non-empty value, then clears it. */
    fun flush(): Typed? {
        val t = text; val p = pkg; val v = viewId; val st = stamp
        key = null; text = null; pkg = null; viewId = null; stamp = null
        if (t.isNullOrEmpty() || p == null) return null
        return Typed(p, v, t, st).also { lastFlushed = it }
    }

    /** Drops anything pending without recording it (used when a field turns out to be a password). */
    fun discard() { key = null; text = null; pkg = null; viewId = null; stamp = null }

    /** Forget the last flushed value, so it cannot be attached to a later action elsewhere. */
    fun forgetLast() { lastFlushed = null }

    /** Text pending, else the last flushed value, only if it came from the same package. */
    fun currentOrLast(pkg: String): String? {
        if (this.pkg == pkg) text?.takeIf { it.isNotEmpty() }?.let { return it }
        return lastFlushed?.takeIf { it.pkg == pkg }?.text
    }
}

object Csv {
    /**
     * Column order follows what an examiner reads first: when, what happened, its status, the
     * screenshot, context, then clock internals and identifiers. Raw epoch values come last.
     */
    val HEADER = listOf(
        "local_time", "utc_time",
        "action", "app_package", "content", "counterpart", "direction", "conversation_id",
        "is_current", "reviewed", "source", "kind", "time_quality", "window_s",
        "screenshot_file", "screenshot_status",
        "detail", "refers_to", "expected_artifacts", "account", "app_version", "device_label", "scenario_id",
        "tz_id", "tz_offset_min", "network_time_utc", "boot_count", "elapsed_ms", "utc_ms", "boot_wall_ms",
        "network_time_ms", "gnss_time_ms", "edited_utc_ms",
        "entry_id", "revision_n", "seq", "profile_id", "screenshot_sha256",
    )

    fun field(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

    // Fixed millisecond width, so every value has the same shape and sorts as text.
    private val LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
    private val UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun localIso(utcMs: Long, offsetMin: Int): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(utcMs), ZoneOffset.ofTotalSeconds(offsetMin * 60)).format(LOCAL)

    fun utcIso(utcMs: Long): String = UTC.format(Instant.ofEpochMilli(utcMs))

    /** Values by column name; written in HEADER order. */
    fun values(e: Entry, isCurrent: Boolean, shot: Shot? = null): Map<String, Any?> = mapOf(
        "local_time" to localIso(e.stamp.utcMs, e.stamp.tzOffsetMin), "utc_time" to utcIso(e.stamp.utcMs),
        "action" to e.action, "app_package" to e.appPackage, "content" to e.content, "counterpart" to e.counterpart,
        "direction" to e.direction, "conversation_id" to e.conversationId,
        "is_current" to isCurrent, "reviewed" to e.reviewed, "source" to e.source, "kind" to e.kind,
        "time_quality" to e.timeQuality, "window_s" to e.windowS,
        "screenshot_file" to shot?.file, "screenshot_status" to shot?.status,
        "detail" to e.detail, "refers_to" to e.refersTo, "expected_artifacts" to e.expectedArtifacts.joinToString(";"),
        "account" to e.account, "app_version" to e.appVersion, "device_label" to e.deviceLabel, "scenario_id" to e.scenarioId,
        "tz_id" to e.stamp.tzId, "tz_offset_min" to e.stamp.tzOffsetMin,
        "network_time_utc" to e.stamp.networkTimeMs?.let { utcIso(it) },
        "boot_count" to e.stamp.bootCount, "elapsed_ms" to e.stamp.elapsedMs, "utc_ms" to e.stamp.utcMs,
        "boot_wall_ms" to e.stamp.bootWallMs, "network_time_ms" to e.stamp.networkTimeMs,
        "gnss_time_ms" to e.stamp.gnssTimeMs, "edited_utc_ms" to e.editedUtcMs,
        "entry_id" to e.entryId, "revision_n" to e.revisionN, "seq" to e.seq, "profile_id" to e.profileId,
        "screenshot_sha256" to shot?.sha256,
    )

    fun row(e: Entry, isCurrent: Boolean, shot: Shot? = null): String {
        val v = values(e, isCurrent, shot)
        return HEADER.joinToString(",") { field(v[it]) }
    }

    /**
     * All rows, every revision, in insertion order, with is_current marking the live version.
     * Every revision shows its entry's screenshot, and a label made from a mark or captured event
     * shows that event's, so filtering to current rows keeps the screenshots.
     */
    fun document(rows: List<Entry>, shots: Map<String, Shot> = emptyMap()): String {
        val currentSeqs = Timeline.current(rows).map { it.seq }.toSet()
        val sb = StringBuilder(HEADER.joinToString(",")).append("\r\n")
        rows.sortedBy { it.seq }.forEach {
            val shot = shots[it.entryId] ?: it.refersTo?.let { r -> shots[r] }
            sb.append(row(it, it.seq in currentSeqs, shot)).append("\r\n")
        }
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
