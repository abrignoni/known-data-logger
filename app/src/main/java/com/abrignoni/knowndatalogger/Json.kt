package com.abrignoni.knowndatalogger

import org.json.JSONArray
import org.json.JSONObject

private fun Any?.orNull(): Any = this ?: JSONObject.NULL

/** One actions.jsonl object. Key order follows the spec table. */
fun Entry.toJson(shot: Shot? = null): JSONObject = JSONObject().apply {
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
    put("screenshot", shot?.let {
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

/** Screenshots belong to the original row (revision 0) of their entry. */
fun jsonl(rows: List<Entry>, shots: Map<String, Shot> = emptyMap()): String =
    rows.sortedBy { it.seq }.joinToString("") {
        it.toJson(if (it.revisionN == 0) shots[it.entryId] else null).toString() + "\n"
    }
