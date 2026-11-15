package com.abrignoni.knowndatalogger

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LookLogTest {
    @Test fun aLookKeepsEveryLineAsTheWatchReceivedIt() {
        val j = LookLog.look("com.example.chat", "com.example.chat" to "Chat", 1080, false,
            listOf(ShownText("Online", 200, 40, 400, 80, inList = false), ShownText("Hi 123456", 60, 300, 600, 380, leaf = false)),
            listOf("Ana" to 280), capped = false)
        assertEquals(false, j.getBoolean("at_newest"))
        val second = j.getJSONArray("lines").getJSONArray(1)
        // Unmasked: replay needs the text the watch matched against.
        assertEquals("Hi 123456", second.getString(0))
        assertEquals(false, second.getBoolean(6))
        assertEquals(false, j.getJSONArray("lines").getJSONArray(0).getBoolean(5))
        assertEquals("Ana", j.getJSONArray("authors").getJSONArray(0).getString(0))
    }

    @Test fun recordsAreWrittenInOrderWithACounter() {
        val f = File.createTempFile("looks", ".jsonl"); f.deleteOnExit()
        val log = LookLog(f)
        log.write("reset", 10)
        log.write("rules", 11, LookLog.rules(listOf(Rule("r", "p", "v", null, "received_message", "line", null, RuleEvent.MESSAGE_AUTHOR))))
        log.write("remember", 12, JSONObject().put("list", "own").put("text", "hello"))
        log.awaitWritten()
        val rows = f.readLines().map { JSONObject(it) }
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.getLong("n") })
        assertEquals(listOf("reset", "rules", "remember"), rows.map { it.getString("kind") })
        assertEquals("line", rows[1].getJSONArray("rules").getJSONObject(0).getString("content"))
    }
}
