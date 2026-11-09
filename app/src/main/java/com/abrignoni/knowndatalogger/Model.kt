package com.abrignoni.knowndatalogger

import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

const val SCHEMA_VERSION = 3

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
    /** A notification posted by an app under test (incoming message, call, missed call, other). */
    const val NOTIFICATION = "notification"
}

/**
 * Actions inferred from a notification's category (Notification.CATEGORY_*). A "call" notification
 * is an incoming call only when its CallStyle type says so (Notification.EXTRA_CALL_TYPE 1, API 31+):
 * dialers also post ongoing calls under "call". Anything else stays a raw event.
 */
object NotificationMap {
    const val CALL_TYPE_INCOMING = 1
    /** Detail on a notification event that arrived without its Notification (Android withheld it while locked). */
    const val WITHHELD = "notification details withheld by Android while locked"

    fun actionFor(category: String?, callType: Int? = null): String? = when (category) {
        "msg" -> "received_message"
        "call" -> if (callType == CALL_TYPE_INCOMING) "incoming_call" else null
        "missed_call" -> "missed_call"
        else -> null
    }

    private val CODE_WORDS = Regex("""\b(code|otp|passcode|pin|verification|verify|2fa|one[- ]time)\b""", RegexOption.IGNORE_CASE)
    private val DIGITS = Regex("""(?<!\d)\d{4,8}(?!\d)""")

    /** Withholds 4 to 8 digit runs in text that talks about codes. Returns the text and whether it changed. */
    fun maskCodes(text: String?): Pair<String?, Boolean> {
        if (text == null || !CODE_WORDS.containsMatchIn(text)) return text to false
        val out = DIGITS.replace(text, "[code withheld]")
        return out to (out != text)
    }
}

/** Replaces unpaired UTF-16 surrogates (from text cut mid-character) with U+FFFD, so every store reads the same text. */
fun cleanText(s: String?): String? {
    if (s == null) return null
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) { sb.append(c).append(s[i + 1]); i += 2; continue }
        sb.append(if (c.isSurrogate()) '\uFFFD' else c)
        i++
    }
    return sb.toString()
}

/** Cleans every free-text field of an entry before it is stored and hashed. */
fun Entry.cleaned(): Entry = copy(
    action = cleanText(action)!!, content = cleanText(content), counterpart = cleanText(counterpart),
    conversationId = cleanText(conversationId), detail = cleanText(detail), account = cleanText(account),
    appVersion = cleanText(appVersion), expectedArtifacts = expectedArtifacts.map { cleanText(it)!! },
    scenarioId = cleanText(scenarioId)!!, deviceLabel = cleanText(deviceLabel)!!,
)

/** View id recorded in a raw tap or field_cleared row's detail ("view <id>, class <name>"). */
fun viewIdOf(e: Entry): String? = e.detail?.let { Regex("""(?:^|, )view ([^,]+)""").find(it)?.groupValues?.get(1) }

/**
 * The captured event a mark most likely points at: the newest tap or cleared field in the same
 * app, in the same boot, at most 30 seconds before the mark.
 */
fun sourceEventFor(mark: Entry, rows: List<Entry>): Entry? =
    Timeline.current(rows).filter {
        it.kind == Kind.AUTO_EVENT && it.action in setOf(AutoAction.TAP, AutoAction.FIELD_CLEARED) &&
            it.appPackage != null && it.appPackage == mark.appPackage &&
            it.stamp.bootCount == mark.stamp.bootCount &&
            it.stamp.elapsedMs <= mark.stamp.elapsedMs && mark.stamp.elapsedMs - it.stamp.elapsedMs <= 30_000
    }.maxWithOrNull(Timeline.ORDER)

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
    /** Hash chain (schema 3): row_hash of the previous row by seq, and this row's own hash. */
    val prevHash: String? = null,
    val rowHash: String? = null,
) {
    val isMark: Boolean get() = kind == Kind.AUTO_EVENT && action == AutoAction.MARK

    /** A notification whose details Android withheld; only a person can say what it was. */
    val isWithheldNotification: Boolean
        get() = kind == Kind.AUTO_EVENT && action == AutoAction.NOTIFICATION && detail == NotificationMap.WITHHELD

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
                ((it.isMark || it.isWithheldNotification) && it.entryId !in resolvedMarks) ||
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
        "entry_id", "revision_n", "seq", "profile_id", "screenshot_sha256", "prev_hash", "row_hash",
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
        "screenshot_sha256" to shot?.sha256, "prev_hash" to e.prevHash, "row_hash" to e.rowHash,
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


/**
 * Tamper evidence for the log. Each row's hash covers its own fields plus the previous row's
 * hash, in seq order, so editing, removing or reordering a row breaks every hash after it.
 *
 * The hashed text is a canonical JSON object that tools/make_documentation.py rebuilds byte for
 * byte: keys sorted, no spaces, ASCII only (other characters as lowercase \uXXXX escapes, as
 * Python's json.dumps(sort_keys=True, separators=(",", ":"), ensure_ascii=True) writes them).
 * window_s is hashed as window_ms (whole milliseconds) so no decimal formatting is involved;
 * boot_wall_ms is derived and the screenshot is attached later, so neither is hashed.
 */
object Chain {
    const val GENESIS = ""

    fun fields(e: Entry, prevHash: String): Map<String, Any?> = mapOf(
        "account" to e.account, "action" to e.action, "app_package" to e.appPackage, "app_version" to e.appVersion,
        "boot_count" to e.stamp.bootCount, "content" to e.content, "conversation_id" to e.conversationId,
        "counterpart" to e.counterpart, "detail" to e.detail, "device_label" to e.deviceLabel,
        "direction" to e.direction, "edited_utc_ms" to e.editedUtcMs, "elapsed_ms" to e.stamp.elapsedMs,
        "entry_id" to e.entryId, "expected_artifacts" to e.expectedArtifacts, "gnss_time_ms" to e.stamp.gnssTimeMs,
        "kind" to e.kind, "network_time_ms" to e.stamp.networkTimeMs, "prev_hash" to prevHash,
        "profile_id" to e.profileId, "refers_to" to e.refersTo, "revision_n" to e.revisionN,
        "reviewed" to e.reviewed, "scenario_id" to e.scenarioId, "seq" to e.seq, "source" to e.source,
        "time_quality" to e.timeQuality, "tz_id" to e.stamp.tzId, "tz_offset_min" to e.stamp.tzOffsetMin,
        "utc_ms" to e.stamp.utcMs, "window_ms" to e.windowS?.let { Math.round(it * 1000) },
    )

    fun canonical(e: Entry, prevHash: String): String {
        val sb = StringBuilder("{")
        fields(e, prevHash).toSortedMap().entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            str(sb, k); sb.append(':'); value(sb, v)
        }
        return sb.append('}').toString()
    }

    fun rowHash(e: Entry, prevHash: String): String = sha256Hex(canonical(e, prevHash).toByteArray(Charsets.US_ASCII))

    private fun value(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long -> sb.append(v.toString())
            is List<*> -> { sb.append('['); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); value(sb, x) }; sb.append(']') }
            else -> error("unhashable ${v::class}")
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c.code < 0x20 || c.code >= 0x7f -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Checks a run of rows in seq order. Returns the seq of the first bad row, or null. */
    fun firstBreak(rows: List<Entry>): Long? {
        var prev = GENESIS
        for (r in rows.sortedBy { it.seq }) {
            if (r.prevHash != prev || r.rowHash != rowHash(r, prev)) return r.seq
            prev = r.rowHash
        }
        return null
    }
}


/** Counts on the Check tab and the stop sheet, by kind. */
data class PendingCounts(val inferred: Int, val marks: Int, val locked: Int, val stamped: Int) {
    val total get() = inferred + marks + locked + stamped

    companion object {
        fun of(pending: List<Entry>) = PendingCounts(
            inferred = pending.count { it.kind == Kind.ACTION && it.source == Source.AUTO_RULE && !it.reviewed },
            marks = pending.count { it.isMark },
            locked = pending.count { it.isWithheldNotification },
            stamped = pending.count { it.needsDetail },
        )
    }
}

/** One capture session: from its start row to its stop row (or now, if still running). */
data class SessionSummary(
    val start: Stamp,
    val end: Stamp?,
    val durationMs: Long?,
    val events: Int,
    val screenshots: Int,
)

/**
 * Summarizes the newest session. Events are the action and captured-event rows between start and
 * stop (system rows excluded, original rows only). Duration uses the monotonic clock when start and
 * stop are in the same boot, otherwise the wall clock.
 */
fun summarize(rows: List<Entry>, shots: Map<String, Shot>): SessionSummary? {
    val ordered = rows.filter { it.revisionN == 0 }.sortedBy { it.seq }
    val start = ordered.lastOrNull { it.action == "capture_session_start" } ?: return null
    val stop = ordered.firstOrNull { it.seq > start.seq && it.action == "capture_session_stop" }
    val inside = ordered.filter { it.seq > start.seq && (stop == null || it.seq < stop.seq) &&
        (it.kind == Kind.ACTION || it.kind == Kind.AUTO_EVENT) }
    val duration = stop?.let {
        if (it.stamp.bootCount == start.stamp.bootCount) it.stamp.elapsedMs - start.stamp.elapsedMs
        else it.stamp.utcMs - start.stamp.utcMs
    }
    val ids = inside.map { it.entryId }.toSet()
    return SessionSummary(start.stamp, stop?.stamp, duration, inside.size,
        shots.values.count { it.entryId in ids && it.file != null })
}


private val SYSTEM_LABELS = mapOf(
    "capture_session_start" to "Session started", "capture_session_stop" to "Session stopped",
    "capture_service_connected" to "Capture resumed", "capture_service_disconnected" to "Capture paused",
    "boot_detected" to "Phone restarted", "clock_change_detected" to "Clock changed", "export" to "Exported",
    "export_failed" to "Export failed", "profile_snapshot" to "Device snapshot", "app_installed" to "App installed",
    "app_updated" to "App updated", "app_removed" to "App removed", "app_data_cleared" to "App data cleared",
    "app_version_changed_since_last_seen" to "App version changed", "rule_taught" to "Rule taught",
    "rule_removed" to "Rule removed", "hash_chain_backfilled" to "Hash chain started",
)

/** The words shown for an entry in the interface (the log keeps the action key). */
fun displayLabel(e: Entry): String = when {
    e.isMark -> "Mark"
    e.isWithheldNotification -> "Notification"
    e.needsDetail -> "Stamped, no action"
    else -> SYSTEM_LABELS[e.action] ?: templateLabel(e.action).replaceFirstChar { it.uppercase() }
}

/** True for the pause and resume rows the capture service writes. */
val Entry.isCapturePause: Boolean get() = action == "capture_service_connected" || action == "capture_service_disconnected"

fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
