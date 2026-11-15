package com.abrignoni.knowndatalogger

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays recorded sessions (Record screen looks) through WatchJudge and requires the rows the phone wrote:
 * every text_appeared row, every label that refers to one, and the watch's two system rows, in order.
 * A replay folder holds looks.jsonl and that session's rows from its export (actions.jsonl, revision 0).
 * Folders on this computer: set KDL_REPLAY_DIR to a directory of replay folders. The made-up folder in the
 * test resources always runs.
 */
class ReplayTest {
    data class Row(val action: String, val content: String?, val detail: String?, val direction: String?)

    private fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else getString(k)

    fun replay(looks: List<JSONObject>): List<Row> {
        val judge = WatchJudge()
        val out = ArrayList<WatchJudge.Out>()
        for (r in looks) when (r.getString("kind")) {
            "reset" -> {
                judge.maskCodes = r.getBoolean("mask_codes"); judge.shotAuto = r.getBoolean("shot_auto")
                out += judge.reset(r.getBoolean("active"))
            }
            "rules" -> judge.rules = r.getJSONArray("rules").let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
                    Rule(o.getString("id"), o.getString("package"), o.str("view_id"), o.str("label_regex"), o.getString("action"),
                        o.str("content"), null, o.getString("event"))
                } }
            }
            "remember" -> when (r.getString("list")) {
                "own" -> judge.rememberOwn(r.getString("text"), r.getLong("el"))
                "notified" -> judge.rememberNotified(r.getString("text"), r.getLong("el"))
                // rule_labelled is the judge's own memory: it rebuilds it.
            }
            "look" -> {
                val ls = r.getJSONArray("lines").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { l ->
                    ShownText(l.getString(0), l.getInt(1), l.getInt(2), l.getInt(3), l.getInt(4), l.getBoolean(5), l.getBoolean(6)) } } }
                val au = r.getJSONArray("authors").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { it.getString(0) to it.getInt(1) } } }
                out += judge.look(r.getLong("el"), r.getString("package"), r.getString("key"), r.getInt("width"),
                    if (r.isNull("at_newest")) null else r.getBoolean("at_newest"), ls, au)
            }
        }
        return out.map { o -> when (o) {
            is WatchJudge.Out.Raw -> Row(AutoAction.TEXT_APPEARED, o.content, o.detail, null)
            is WatchJudge.Out.Label -> Row(o.action, o.content, o.detail, o.direction)
            is WatchJudge.Out.System -> Row(o.action, null, o.detail, null)
        } }
    }

    fun expected(actions: List<JSONObject>): List<Row> {
        val raws = actions.filter { it.str("action") == AutoAction.TEXT_APPEARED }.map { it.getString("entry_id") }.toSet()
        return actions.filter { a ->
            a.str("action") == AutoAction.TEXT_APPEARED || (a.str("refers_to") in raws && a.str("source") == "auto_rule") ||
                a.str("action") in setOf("screen_watch_active", "screen_watch_summary")
        }.map { a -> Row(a.getString("action"), a.str("content"), a.str("detail"),
            if (a.str("action") == AutoAction.TEXT_APPEARED || a.str("action")!!.startsWith("screen_watch")) null else a.str("direction")) }
    }

    private fun read(f: File) = f.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }

    private fun check(dir: File) {
        val got = replay(read(File(dir, "looks.jsonl")))
        val want = expected(read(File(dir, "actions.jsonl")))
        val firstDiff = (0 until maxOf(got.size, want.size)).firstOrNull { got.getOrNull(it) != want.getOrNull(it) }
        assertEquals("${dir.name}: rows differ first at $firstDiff: replay ${got.getOrNull(firstDiff ?: 0)} vs phone ${want.getOrNull(firstDiff ?: 0)}",
            want, got)
    }

    @Test fun aMadeUpSessionReplaysToItsRows() {
        check(File(javaClass.classLoader!!.getResource("replay/made-up-chat/looks.jsonl")!!.toURI()).parentFile)
    }

    @Test fun recordedSessionsReplayToTheRowsThePhoneWrote() {
        val root = System.getProperty("kdl.replay").orEmpty()
        assumeTrue("KDL_REPLAY_DIR not set", root.isNotBlank())
        val dirs = File(root).listFiles { f -> File(f, "looks.jsonl").exists() }!!.sortedBy { it.name }
        assumeTrue(dirs.isNotEmpty())
        val failures = dirs.mapNotNull { d -> runCatching { check(d) }.exceptionOrNull()?.message?.lineSequence()?.first() }
        println("replayed ${dirs.size} sessions, ${failures.size} differ")
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }
}
