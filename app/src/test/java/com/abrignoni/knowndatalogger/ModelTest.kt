package com.abrignoni.knowndatalogger

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private fun stamp(utc: Long, elapsed: Long, boot: Int = 5, tz: String = "UTC", off: Int = 0) =
        Stamp(utc, tz, off, elapsed, boot, null, null)

    private var seq = 0L
    private fun entry(id: String, rev: Int, s: Stamp, kind: String = Kind.ACTION, action: String = "a") =
        Entry(seq = ++seq, entryId = id, revisionN = rev, kind = kind, scenarioId = "sc", deviceLabel = "A",
            profileId = 1, stamp = s, editedUtcMs = s.utcMs, action = action)

    @Test fun currentTakesHighestRevisionAndDropsTombstones() {
        val s1 = stamp(1_000, 100); val s2 = stamp(2_000, 1_100)
        val rows = listOf(
            entry("x", 0, s1, action = "first"),
            entry("y", 0, s2),
            entry("x", 1, s1, action = "edited"),
            entry("y", 1, s2, kind = Kind.TOMBSTONE),
        )
        val cur = Timeline.current(rows)
        assertEquals(1, cur.size)
        assertEquals("edited", cur[0].action)
        assertEquals(1, cur[0].revisionN)
    }

    @Test fun orderIsBootThenElapsedNotWallClock() {
        // Wall clock set back between the two taps; elapsed time keeps the true order.
        val a = entry("a", 0, stamp(utc = 10_000, elapsed = 1_000))
        val b = entry("b", 0, stamp(utc = 5_000, elapsed = 2_000))
        val c = entry("c", 0, stamp(utc = 1_000, elapsed = 50, boot = 6))
        assertEquals(listOf("a", "b", "c"), Timeline.current(listOf(c, b, a)).map { it.entryId })
    }

    @Test fun noChangeWhenClocksAgree() {
        val rows = listOf(entry("a", 0, stamp(10_000, 1_000)))
        assertTrue(Timeline.detectChanges(rows, stamp(15_000, 6_000)).isEmpty())
    }

    @Test fun detectsClockSetWithinBoot() {
        val rows = listOf(entry("a", 0, stamp(10_000, 1_000)))
        val out = Timeline.detectChanges(rows, stamp(15_000 + 3_600_000, 6_000))
        assertEquals(1, out.size)
        assertEquals(Kind.CLOCK_CHANGE, out[0].first)
        assertTrue(out[0].second.contains("3600000"))
    }

    @Test fun detectsRebootNotClockJump() {
        val rows = listOf(entry("a", 0, stamp(10_000, 900_000, boot = 5)))
        val out = Timeline.detectChanges(rows, stamp(20_000, 30, boot = 6))
        assertEquals(listOf(Kind.SYSTEM_EVENT), out.map { it.first })
    }

    @Test fun detectsTimeZoneChange() {
        val rows = listOf(entry("a", 0, stamp(10_000, 1_000, tz = "America/New_York", off = -300)))
        val out = Timeline.detectChanges(rows, stamp(11_000, 2_000, tz = "America/Chicago", off = -360))
        assertEquals(listOf(Kind.CLOCK_CHANGE), out.map { it.first })
    }

    @Test fun csvQuotesCommasQuotesAndNewlines() {
        assertEquals("plain", Csv.field("plain"))
        assertEquals("\"a,b\"", Csv.field("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.field("say \"hi\""))
        assertEquals("\"two\nlines\"", Csv.field("two\nlines"))
        assertEquals("", Csv.field(null))
    }

    @Test fun csvLocalTimeUsesStoredOffset() {
        assertEquals("2026-11-08T09:00:00-05:00", Csv.localIso(1_794_146_400_000, -300))
        assertEquals("2026-11-08T14:00:00Z", Csv.utcIso(1_794_146_400_000))
    }

    @Test fun csvMarksOnlyCurrentRevision() {
        val s = stamp(1_000, 100)
        val doc = Csv.document(listOf(entry("x", 0, s), entry("x", 1, s))).trim().split("\r\n")
        assertEquals(3, doc.size)
        assertEquals(Csv.HEADER.size, Csv.HEADER.toSet().size)
        assertTrue(doc[1].split(",")[3] == "false")
        assertTrue(doc[2].split(",")[3] == "true")
    }

    @Test fun jsonCarriesNullsAndBootWall() {
        val e = entry("x", 0, stamp(10_000, 1_000))
        val o = JSONObject(jsonl(listOf(e)).trim())
        assertEquals(9_000L, o.getLong("boot_wall_ms"))
        assertTrue(o.isNull("network_time_ms"))
        assertTrue(o.isNull("content"))
        assertEquals(0, o.getJSONArray("expected_artifacts").length())
        assertEquals(SCHEMA_VERSION, o.getInt("schema"))
    }

    @Test fun needsDetailOnlyForBlankActions() {
        assertTrue(entry("x", 0, stamp(1, 1), action = "").needsDetail)
        assertFalse(entry("y", 0, stamp(1, 1), action = "sent_message").needsDetail)
        assertFalse(entry("z", 0, stamp(1, 1), kind = Kind.EXPORT, action = "").needsDetail)
    }

    @Test fun sha256KnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".toByteArray()))
    }
}
