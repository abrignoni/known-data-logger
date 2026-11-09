package com.abrignoni.knowndatalogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChainTest {
    /** Same row as the vector in tools/test_make_documentation.py; both must give the same hash. */
    private val fixed = Entry(
        seq = 7, entryId = "e1", revisionN = 0, kind = "action", scenarioId = "sc", deviceLabel = "phoneA", profileId = 1,
        stamp = Stamp(1700000000123, "America/New_York", -300, 123456, 3, 1700000000000, null),
        timeQuality = "exact", windowS = 5.894, editedUtcMs = 1700000000500, appPackage = "com.example.chat",
        appVersion = "1.2", account = null, action = "sent_message",
        content = "Hé \"q\" \\ line\nnext 😀 bell\u0007", counterpart = "phone B", direction = "out",
        conversationId = null, refersTo = null, expectedArtifacts = listOf("aleapp:x", "y"), detail = null,
        source = "manual", reviewed = true,
    )

    @Test fun canonicalMatchesPython() {
        assertEquals(
            "{\"account\":null,\"action\":\"sent_message\",\"app_package\":\"com.example.chat\",\"app_version\":\"1.2\"," +
                "\"boot_count\":3,\"content\":\"H\\u00e9 \\\"q\\\" \\\\ line\\nnext \\ud83d\\ude00 bell\\u0007\"," +
                "\"conversation_id\":null,\"counterpart\":\"phone B\",\"detail\":null,\"device_label\":\"phoneA\"," +
                "\"direction\":\"out\",\"edited_utc_ms\":1700000000500,\"elapsed_ms\":123456,\"entry_id\":\"e1\"," +
                "\"expected_artifacts\":[\"aleapp:x\",\"y\"],\"gnss_time_ms\":null,\"kind\":\"action\"," +
                "\"network_time_ms\":1700000000000,\"prev_hash\":\"abc\",\"profile_id\":1,\"refers_to\":null," +
                "\"reviewed\":true,\"revision_n\":0,\"scenario_id\":\"sc\",\"seq\":7,\"source\":\"manual\"," +
                "\"time_quality\":\"exact\",\"tz_id\":\"America/New_York\",\"tz_offset_min\":-300," +
                "\"utc_ms\":1700000000123,\"window_ms\":5894}",
            Chain.canonical(fixed, "abc"),
        )
        assertEquals("02e5c1e190e0fd352d836bf5a0a315324f18cc18ac257edaf52dafb273bab7e1", Chain.rowHash(fixed, "abc"))
    }

    private fun chain(n: Int): List<Entry> {
        var prev = Chain.GENESIS
        return (1..n).map { i ->
            val e = fixed.copy(seq = i.toLong(), entryId = "e$i", content = "row $i", prevHash = prev)
            val h = Chain.rowHash(e, prev)
            prev = h
            e.copy(rowHash = h)
        }
    }

    @Test fun intactChainPasses() { assertNull(Chain.firstBreak(chain(5))) }

    @Test fun editedRowBreaksAtThatRow() {
        val rows = chain(5).toMutableList()
        rows[2] = rows[2].copy(content = "changed")
        assertEquals(3L, Chain.firstBreak(rows))
    }

    @Test fun removedRowBreaksTheNextRow() {
        val rows = chain(5).toMutableList()
        rows.removeAt(1)
        assertEquals(3L, Chain.firstBreak(rows))
    }

    @Test fun rehashingOneRowStillBreaksTheNext() {
        val rows = chain(5).toMutableList()
        val edited = rows[1].copy(content = "changed")
        rows[1] = edited.copy(rowHash = Chain.rowHash(edited, edited.prevHash!!))
        assertEquals(3L, Chain.firstBreak(rows))
    }
}
