package com.abrignoni.knowndatalogger

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Records what the screen watch decided from, for replaying a session off the phone (testing only, off by
 * default). Each record is one JSON line with a counter ("n"), the elapsed-realtime clock the watch's time
 * windows use ("el") and a kind:
 *  - "reset": the session state was cleared (session start, stop, or the service reconnecting), with the settings
 *    that change decisions;
 *  - "rules": every rule in effect, at a reset and whenever the rules are reloaded;
 *  - "remember": a text put into one of the watch's recent-text lists ("own", "notified", "rule_labelled",
 *    "received_labelled"; the last two are the judge's own memory, rebuilt on replay);
 *  - "look": a look at an open screen, with the lines as the watch received them;
 *  - "skip": a look that ended before the watch saw it, and why.
 * Lines are kept as the app showed them, unmasked, so the file holds every line of text the watched apps show.
 * It sits beside actions-live.jsonl, outside exports and outside the hash chain.
 */
class LookLog(private val file: File) {
    private val io = Executors.newSingleThreadExecutor()
    private var n = 0L

    fun write(kind: String, el: Long, fields: JSONObject = JSONObject()) {
        fields.put("n", ++n).put("el", el).put("kind", kind)
        val line = fields.toString() + "\n"
        io.execute { runCatching { file.appendText(line) } }
    }

    fun close() = io.shutdown()

    /** For tests: waits until every record written so far is on disk. */
    internal fun awaitWritten() { io.shutdown(); io.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS) }

    companion object {
        fun rules(rules: List<Rule>): JSONObject = JSONObject().put("rules", JSONArray().apply {
            rules.forEach { r ->
                put(JSONObject().put("id", r.id).put("package", r.pkg).put("event", r.event).put("view_id", r.viewId ?: JSONObject.NULL)
                    .put("label_regex", r.labelRegex ?: JSONObject.NULL).put("action", r.action).put("content", r.content ?: JSONObject.NULL))
            }
        })

        fun look(pkg: String, key: Any, width: Int, atNewest: Boolean?, lines: List<ShownText>, authors: List<Pair<String, Int>>,
                 capped: Boolean): JSONObject = JSONObject()
            .put("package", pkg).put("key", key.toString()).put("width", width)
            .put("at_newest", atNewest ?: JSONObject.NULL).put("capped", capped)
            .put("lines", JSONArray().apply {
                lines.forEach { l ->
                    put(JSONArray().put(l.text).put(l.left).put(l.top).put(l.right).put(l.bottom).put(l.inList).put(l.leaf))
                }
            })
            .put("authors", JSONArray().apply { authors.forEach { put(JSONArray().put(it.first).put(it.second)) } })
    }
}
