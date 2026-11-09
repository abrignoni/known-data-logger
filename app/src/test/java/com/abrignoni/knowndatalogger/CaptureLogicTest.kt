package com.abrignoni.knowndatalogger

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLogicTest {
    private val s = Stamp(1_000, "UTC", 0, 100, 3, null, null)
    private var seq = 0L
    private fun e(id: String, rev: Int = 0, kind: String = Kind.ACTION, action: String = "a", source: String = Source.MANUAL,
                  reviewed: Boolean = true, refersTo: String? = null) =
        Entry(seq = ++seq, entryId = id, revisionN = rev, kind = kind, scenarioId = "sc", deviceLabel = "A", profileId = 1,
            stamp = s.copy(elapsedMs = s.elapsedMs + seq), editedUtcMs = 0, action = action, source = source,
            reviewed = reviewed, refersTo = refersTo)

    @Test fun reviewListsUnacceptedRulesUnlabelledMarksAndStamps() {
        val rows = listOf(
            e("rule1", source = Source.AUTO_RULE, reviewed = false, action = "sent_message"),
            e("rule2", source = Source.AUTO_RULE, reviewed = false, action = "took_photo"),
            e("rule2", rev = 1, source = Source.AUTO_RULE, reviewed = true, action = "took_photo"),
            e("mark1", kind = Kind.AUTO_EVENT, action = AutoAction.MARK, source = Source.MARK),
            e("mark2", kind = Kind.AUTO_EVENT, action = AutoAction.MARK, source = Source.MARK),
            e("label2", action = "sent_media", source = Source.MARK, refersTo = "mark2"),
            e("stamp", action = ""),
            e("tap", kind = Kind.AUTO_EVENT, action = AutoAction.TAP, source = Source.AUTO_EVENT),
        )
        assertEquals(setOf("rule1", "mark1", "stamp"), Review.pending(rows).map { it.entryId }.toSet())
    }

    @Test fun withheldNotificationWaitsForReviewUntilLabelled() {
        val n = e("n1", kind = Kind.AUTO_EVENT, action = AutoAction.NOTIFICATION, source = Source.AUTO_EVENT)
            .copy(detail = NotificationMap.WITHHELD)
        val normal = e("n2", kind = Kind.AUTO_EVENT, action = AutoAction.NOTIFICATION, source = Source.AUTO_EVENT)
            .copy(detail = "category msg")
        assertEquals(setOf("n1"), Review.pending(listOf(n, normal)).map { it.entryId }.toSet())
        val label = e("l1", action = "received_message", refersTo = "n1")
        assertTrue(Review.pending(listOf(n, normal, label)).isEmpty())
    }

    @Test fun tombstonedMarkLeavesReview() {
        val rows = listOf(
            e("mark1", kind = Kind.AUTO_EVENT, action = AutoAction.MARK, source = Source.MARK),
            e("mark1", rev = 1, kind = Kind.TOMBSTONE, action = AutoAction.MARK, source = Source.MARK),
        )
        assertTrue(Review.pending(rows).isEmpty())
    }

    private val send = Rule("send", "com.example.chat", "Compose:Draft:Send", null, "sent_message", "last_typed", null)
    private val shutter = Rule("shut", "com.example.cam", null, "^shutter$", "took_photo", null, null)

    @Test fun ruleMatchesByViewIdAndPackage() {
        assertEquals(send, Rules.match(listOf(send, shutter), "com.example.chat", RuleEvent.TAP, "Compose:Draft:Send", null))
        assertNull(Rules.match(listOf(send), "com.other", RuleEvent.TAP, "Compose:Draft:Send", null))
        assertNull(Rules.match(listOf(send), "com.example.chat", RuleEvent.TAP, "other", "Send"))
    }

    @Test fun ruleMatchesLabelCaseInsensitive() {
        assertEquals(shutter, Rules.match(listOf(shutter), "com.example.cam", RuleEvent.TAP, null, "Shutter"))
        assertNull(Rules.match(listOf(shutter), "com.example.cam", RuleEvent.TAP, null, "Shutter speed"))
        assertNull(Rules.match(listOf(shutter), "com.example.cam", RuleEvent.TAP, null, null))
    }

    @Test fun ruleWithNoMatcherNeverMatches() {
        val empty = Rule("x", "com.example.chat", null, null, "a", null, null)
        assertNull(Rules.match(listOf(empty), "com.example.chat", RuleEvent.TAP, "any", "any"))
    }

    @Test fun ruleOnlyMatchesItsEventType() {
        val cleared = Rule("c", "com.example.chat", "field", null, "sent_message", "last_typed", null, RuleEvent.FIELD_CLEARED)
        assertEquals(cleared, Rules.match(listOf(cleared), "com.example.chat", RuleEvent.FIELD_CLEARED, "field", null))
        assertNull(Rules.match(listOf(cleared), "com.example.chat", RuleEvent.TAP, "field", null))
    }

    @Test fun bulkClearNeedsAllTextRemovedInOneChange() {
        assertTrue(Rules.isBulkClear("kdl two", 0, 7))
        assertFalse(Rules.isBulkClear("kdl two", 0, 1))   // one backspace
        assertFalse(Rules.isBulkClear("k", 0, 1))         // last character by backspace
        assertFalse(Rules.isBulkClear("abc", 1, 3))       // replaced, not cleared
        assertFalse(Rules.isBulkClear(null, 0, 0))
    }

    @Test fun overrideReplacesRuleById() {
        val changed = send.copy(action = "sent_sms")
        val merged = Rules.merge(listOf(send, shutter), listOf(changed))
        assertEquals(listOf("sent_sms", "took_photo"), merged.map { it.action })
    }

    @Test fun parsesRulesFile() {
        val r = parseRules("""{"version":1,"rules":[{"id":"a","package":"p","view_id":"v","label_regex":null,"action":"x","content":"last_typed","event":"field_cleared","verified_with":"emu"}]}""")
        assertEquals(1, r.size)
        assertEquals("v", r[0].viewId); assertNull(r[0].labelRegex); assertEquals("last_typed", r[0].content); assertEquals(RuleEvent.FIELD_CLEARED, r[0].event)
    }

    @Test fun textBufferKeepsFinalValueStampAndSkipsPasswords() {
        val b = TextBuffer()
        val s1 = s.copy(elapsedMs = 500); val s2 = s.copy(elapsedMs = 900)
        assertNull(b.update("p", "f", "k1", "h", false, s1))
        assertNull(b.update("p", "f", "k1", "hello", false, s2))
        assertNull(b.update("p", "pw", "k2-ignored", "secret", true, s2))
        assertEquals("hello", b.currentOrLast("p"))
        assertEquals(Typed("p", "f", "hello", s2), b.flush())
        assertNull(b.flush())
        assertEquals("hello", b.currentOrLast("p"))
    }

    @Test fun lastTypedOnlyFromSamePackageAndForgettable() {
        val b = TextBuffer()
        b.update("chat", "f", "k1", "hi there", false); b.flush()
        assertNull(b.currentOrLast("camera"))
        assertEquals("hi there", b.currentOrLast("chat"))
        b.forgetLast()
        assertNull(b.currentOrLast("chat"))
    }

    @Test fun discardDropsPendingText() {
        val b = TextBuffer()
        b.update("p", "f", "k1", "1234", false)
        b.discard()
        assertNull(b.flush())
    }

    @Test fun textBufferFlushesPreviousFieldOnSwitch() {
        val b = TextBuffer()
        b.update("p", "a", "k1", "first", false)
        assertEquals("first", b.update("p", "b", "k2", "s", false)?.text)
    }

    @Test fun emptyTextIsNotFlushed() {
        val b = TextBuffer()
        b.update("p", "a", "k1", "", false)
        assertNull(b.flush())
    }

    @Test fun invalidRegexRuleIsDropped() {
        val r = parseRules("""{"rules":[{"id":"bad","package":"p","label_regex":"([","action":"x"},{"id":"ok","package":"p","label_regex":"send","action":"y"}]}""")
        assertEquals(listOf("ok"), r.map { it.id })
    }

    @Test fun detectFromNullLastIsEmpty() {
        assertTrue(Timeline.detectChangesFrom(null, s).isEmpty())
    }

    @Test fun screenshotOnOriginalRowInJsonButEveryRevisionInCsv() {
        val rows = listOf(e("x"), e("x", rev = 1), e("lab", refersTo = "x"))
        val shots = mapOf("x" to Shot("x", "x.png", "ab", "ok", 1))
        val lines = jsonl(rows, shots).trim().split("\n").map { JSONObject(it) }
        assertEquals("x.png", lines[0].getJSONObject("screenshot").getString("file"))
        assertTrue(lines[1].isNull("screenshot"))
        assertTrue(lines[2].isNull("screenshot"))
        val csv = Csv.document(rows, shots).trim().split("\r\n")
        val idx = Csv.HEADER.indexOf("screenshot_file")
        assertEquals(listOf("x.png", "x.png", "x.png"), csv.drop(1).map { it.split(",")[idx] })
        assertTrue(lines[0].getBoolean("reviewed"))
    }
}
