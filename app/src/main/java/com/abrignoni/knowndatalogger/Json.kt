package com.abrignoni.knowndatalogger

import org.json.JSONArray
import org.json.JSONObject

private fun Any?.orNull(): Any = this ?: JSONObject.NULL

/** One actions.jsonl object. Key order follows the spec table. */
fun Entry.toJson(shot: Shot? = null, tree: TreeFile? = null): JSONObject = JSONObject().apply {
    put("schema", SCHEMA_VERSION)
    put("seq", seq)
    put("entry_id", entryId)
    put("revision_n", revisionN)
    put("kind", kind)
    put("scenario_id", scenarioId)
    put("device_label", deviceLabel)
    put("profile_id", profileId.orNull())
    put("utc_ms", stamp.utcMs)
    put("tz_id", stamp.tzId)
    put("tz_offset_min", stamp.tzOffsetMin)
    put("elapsed_ms", stamp.elapsedMs)
    put("boot_count", stamp.bootCount)
    put("boot_wall_ms", stamp.bootWallMs)
    put("network_time_ms", stamp.networkTimeMs.orNull())
    put("gnss_time_ms", stamp.gnssTimeMs.orNull())
    put("time_quality", timeQuality)
    put("window_s", windowS.orNull())
    put("edited_utc_ms", editedUtcMs)
    put("app_package", appPackage.orNull())
    put("app_version", appVersion.orNull())
    put("account", account.orNull())
    put("action", action)
    put("content", content.orNull())
    put("counterpart", counterpart.orNull())
    put("direction", direction.orNull())
    put("conversation_id", conversationId.orNull())
    put("refers_to", refersTo.orNull())
    put("expected_artifacts", JSONArray(expectedArtifacts))
    put("detail", detail.orNull())
    put("source", source)
    put("reviewed", reviewed)
    put("prev_hash", prevHash.orNull())
    put("row_hash", rowHash.orNull())
    put("screenshot", shot?.let {
        JSONObject().apply { put("file", it.file.orNull()); put("sha256", it.sha256.orNull()); put("status", it.status) }
    }.orNull())
    put("view_tree", tree?.let {
        JSONObject().apply { put("file", it.file.orNull()); put("sha256", it.sha256.orNull()); put("status", it.status) }
    }.orNull())
}

/** Parses a rules file: {"version": 1, "rules": [{"id", "package", "view_id", "label_regex", "action", "content", "event", "verified_with"}]}. */
fun parseRules(text: String): List<Rule> {
    val arr = JSONObject(text).getJSONArray("rules")
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        fun opt(k: String): String? = if (o.isNull(k) || !o.has(k)) null else o.getString(k)
        Rule(
            id = o.getString("id"), pkg = o.getString("package"), viewId = opt("view_id"),
            labelRegex = opt("label_regex"), action = o.getString("action"), content = opt("content"),
            verifiedWith = opt("verified_with"), event = opt("event") ?: RuleEvent.TAP,
        ).takeIf { r -> r.labelRegex == null || runCatching { Regex(r.labelRegex) }.isSuccess }
    }
}

/** Rules read from a file someone else made, with how many of its rules could not be used. */
data class RulesRead(val rules: List<Rule>, val skipped: Int)

/**
 * Reads a rules file for import. Unlike parseRules, one bad rule does not fail the file: it is
 * counted and left out. A rule is used only when it could ever match and write something: it needs
 * an id, a package, an action, a known event, a view id or a label pattern that compiles, and
 * content that is absent or "last_typed". Throws IllegalArgumentException when the text is not a
 * rules file at all.
 */
fun readRulesForImport(text: String): RulesRead {
    val arr = try { JSONObject(text).getJSONArray("rules") } catch (e: Exception) {
        throw IllegalArgumentException("Not a KDL rules file")
    }
    val out = mutableListOf<Rule>()
    var skipped = 0
    for (i in 0 until arr.length()) {
        val r = try {
            val o = arr.getJSONObject(i)
            fun opt(k: String): String? = if (o.isNull(k) || !o.has(k)) null else o.getString(k)
            Rule(
                id = o.getString("id").trim(), pkg = o.getString("package").trim(), viewId = opt("view_id")?.ifBlank { null },
                labelRegex = opt("label_regex")?.ifBlank { null }, action = o.getString("action").trim(), content = opt("content"),
                verifiedWith = opt("verified_with"), event = opt("event") ?: RuleEvent.TAP,
            )
        } catch (e: Exception) { null }
        val usable = r != null && r.id.isNotEmpty() && r.pkg.isNotEmpty() && r.action.isNotEmpty() &&
            r.event in setOf(RuleEvent.TAP, RuleEvent.FIELD_CLEARED) &&
            (r.viewId != null || r.labelRegex != null) &&
            (r.labelRegex == null || runCatching { Regex(r.labelRegex) }.isSuccess) &&
            (r.content == null || r.content == "last_typed")
        if (usable) out += r!! else skipped++
    }
    return RulesRead(out, skipped)
}

/** Screenshots belong to the original row (revision 0) of their entry. */
fun jsonl(rows: List<Entry>, shots: Map<String, Shot> = emptyMap(), trees: Map<String, TreeFile> = emptyMap()): String =
    rows.sortedBy { it.seq }.joinToString("") {
        it.toJson(if (it.revisionN == 0) shots[it.entryId] else null,
            if (it.revisionN == 0) trees[it.entryId] else null).toString() + "\n"
    }

/** Writes rules in the same format parseRules reads. */
fun rulesToJson(rules: List<Rule>): String = JSONObject().apply {
    put("version", 1)
    put("rules", JSONArray(rules.map { r ->
        JSONObject().apply {
            put("id", r.id); put("package", r.pkg); put("event", r.event)
            put("view_id", r.viewId ?: JSONObject.NULL); put("label_regex", r.labelRegex ?: JSONObject.NULL)
            put("action", r.action); put("content", r.content ?: JSONObject.NULL)
            put("verified_with", r.verifiedWith ?: JSONObject.NULL)
        }
    }))
}.toString(2)
