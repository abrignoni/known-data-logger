package com.abrignoni.knowndatalogger

import org.json.JSONArray
import org.json.JSONObject

private fun Any?.orNull(): Any = this ?: JSONObject.NULL

/** One actions.jsonl object. Key order follows the spec table. */
fun Entry.toJson(): JSONObject = JSONObject().apply {
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
}

fun jsonl(rows: List<Entry>): String =
    rows.sortedBy { it.seq }.joinToString("") { it.toJson().toString() + "\n" }
