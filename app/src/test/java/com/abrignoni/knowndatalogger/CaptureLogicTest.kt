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

    @Test fun fieldEmptiedWithoutATextChangeGivesWhatWasTyped() {
        val b = TextBuffer()
        val at = s.copy(elapsedMs = 700)
        b.update("chat", "composer", "k1", "see you at six", false, at)
        assertTrue(b.hasPending())
        assertNull(b.clearedWithoutChange("k1", false))          // cursor moved, text still there
        assertNull(b.clearedWithoutChange("k2", true))           // another field is empty
        assertEquals(Typed("chat", "composer", "see you at six", at), b.clearedWithoutChange("k1", true))
        assertFalse(b.hasPending())
        assertNull(b.clearedWithoutChange("k1", true))           // reported once
        assertNull(b.flush())                                    // nothing left to write twice
        assertEquals("see you at six", b.currentOrLast("chat"))
    }

    @Test fun emptiedFieldNeedsTwoCharactersLikeABulkClear() {
        val b = TextBuffer()
        b.update("p", "f", "k1", "a", false)
        assertNull(b.clearedWithoutChange("k1", true))
        assertEquals("a", b.flush()?.text)                       // still written as typed text
    }

    @Test fun emptiedFieldAfterATextChangeSaidSoIsNotReportedAgain() {
        val b = TextBuffer()
        b.update("p", "f", "k1", "hello", false)
        b.flush()                                                // what a bulk clear does first
        assertNull(b.clearedWithoutChange("k1", true))
        b.update("p", "f", "k1", "hello", false)
        b.update("p", "f", "k1", "", false)                      // deleted key by key
        assertFalse(b.hasPending())
        assertNull(b.clearedWithoutChange("k1", true))
    }

    @Test fun passwordTextNeverReachesTheEmptiedFieldPath() {
        val b = TextBuffer()
        b.update("p", "pw", "k1", "hunter22", true)
        assertFalse(b.hasPending())
        assertNull(b.clearedWithoutChange("k1", true))
        b.update("p", "f", "k2", "1234", false); b.discard()
        assertNull(b.clearedWithoutChange("k2", true))
    }

    @Test fun picturesNamedByTheirDescriptionAreLabelled() {
        val shipped = parseRules(java.io.File("src/main/assets/rules/default.json").readText())
        val j = WatchJudge(); j.rules = shipped
        val gm = "com.google.android.apps.messaging"
        fun l(t: String, top: Int, left: Int) = ShownText(t, left, top, left + 500, top + 400)
        j.look(0, gm, "chat", 720, true, listOf(l("Test 490 hi", 100, 200)), emptyList())
        val out = j.look(1000, gm, "chat", 720, true, listOf(l("Test 490 hi", 50, 200),
            l("(689) 325-6699 sent an image    5:25 PM .", 500, 13), l("You sent an image    5:26 PM .", 950, 179)), emptyList())
        assertEquals(listOf("received_message", "sent_message"), out.filterIsInstance<WatchJudge.Out.Label>().map { it.action })
    }

    @Test fun typedTextThatComesIntoTheListIsASendWhereARuleSaysSo() {
        val shipped = parseRules(java.io.File("src/main/assets/rules/default.json").readText())
        val j = WatchJudge(); j.rules = shipped
        fun l(t: String, top: Int, left: Int, inList: Boolean = true) = ShownText(t, left, top, left + 400, top + 60, inList)
        for (pkg in listOf("com.wire", "com.example.other")) {
            j.reset(true)
            j.look(0, pkg, "chat", 720, true, listOf(l("Test 600 hi", 300, 36)), emptyList())
            j.rememberOwn("Test 601 delta echo", 1000)
            val out = j.look(2000, pkg, "chat", 720, true, listOf(l("Test 600 hi", 200, 36), l("Test 601 delta echo", 300, 300),
                l("Test 601 delta echo", 1500, 100, inList = false)), emptyList())
            val labels = out.filterIsInstance<WatchJudge.Out.Label>().map { it.action to it.content }
            // Wire has the rule; an app without one still drops its own line, as before.
            assertEquals(pkg, if (pkg == "com.wire") listOf("sent_message" to "Test 601 delta echo") else emptyList(), labels)
            // Seen again on the next look, it is not labelled twice.
            val again = j.look(3000, pkg, "chat", 720, true, listOf(l("Test 600 hi", 100, 36), l("Test 601 delta echo", 200, 300)), emptyList())
            assertTrue(again.filterIsInstance<WatchJudge.Out.Label>().isEmpty())
        }
    }

    @Test fun aLineEqualToTheBoxTextIsASendWhenNoClearIsReported() {
        // TikTok: no clear is reported, and the typed text is written only after the sent line is on the screen.
        val shipped = parseRules(java.io.File("src/main/assets/rules/default.json").readText())
        val j = WatchJudge(); j.rules = shipped
        val pkg = "com.zhiliaoapp.musically"
        fun l(t: String, top: Int, left: Int) = ShownText(t, left, top, left + 400, top + 60)
        j.look(0, pkg, "chat", 720, true, listOf(l("Test 650 hi", 300, 36)), emptyList())
        for (t in listOf("T", "Test", "Test 653 delta")) j.setPendingOwn(t, 1000)
        j.setPendingOwn("", 1500)
        val out = j.look(2000, pkg, "chat", 720, true, listOf(l("Test 650 hi", 200, 36), l("Test 653 delta", 300, 300)), emptyList())
        assertEquals(listOf("sent_message" to "Test 653 delta"), out.filterIsInstance<WatchJudge.Out.Label>().map { it.action to it.content })
        // A received line that only starts with what was typed is not this phone's.
        val more = j.look(3000, pkg, "chat", 720, true, listOf(l("Test 653 delta", 200, 300), l("Test 653 delta echo", 300, 36)), emptyList())
        assertEquals(listOf("received_message"), more.filterIsInstance<WatchJudge.Out.Label>().map { it.action })
    }

    @Test fun aTypingNotificationIsNotAMessage() {
        for (t in listOf("is typing...", "Ana is typing…", "are typing", " is typing... ")) assertTrue(t, NotificationMap.isTypingOnly(t))
        for (t in listOf("sent you a chat", "I am typing a letter", "typing", "Is typing hard?")) assertTrue(t, !NotificationMap.isTypingOnly(t))
    }

    @Test fun snapchatAuthorsComeFromTheirWords() {
        val shipped = parseRules(java.io.File("src/main/assets/rules/default.json").readText())
        val j = WatchJudge(); j.rules = shipped
        val pkg = "com.snapchat.android"
        fun l(t: String, top: Int, inList: Boolean = true) = ShownText(t, 54, top, 1000, top + 60, inList)
        val title = l("Testing Motto", 150, inList = false)
        j.look(0, pkg, "chat", 1080, true, listOf(title, l("TODAY", 260)), emptyList())
        val out = j.look(1000, pkg, "chat", 1080, true, listOf(title, l("TODAY", 260),
            l("TESTING MOTTO", 300), l("Test 12 hello", 360), l("ME", 420), l("Test 13 hi", 480)), emptyList())
        val labels = out.filterIsInstance<WatchJudge.Out.Label>().map { it.action to it.content }
        assertEquals(listOf("received_message" to "Test 12 hello", "sent_message" to "Test 13 hi"), labels)
        // A send whose "ME" has scrolled off above it is still a send.
        j.rememberOwn("Test 14 again", 2000)
        val more = j.look(3000, pkg, "chat", 1080, true, listOf(title, l("Test 13 hi", 260), l("Test 14 again", 320)), emptyList())
        assertEquals(listOf("sent_message" to "Test 14 again"), more.filterIsInstance<WatchJudge.Out.Label>().map { it.action to it.content })
        val sc = shipped.filter { it.pkg == pkg && it.event == RuleEvent.NOTIFICATION }
        assertEquals("received_message", Rules.match(sc, pkg, RuleEvent.NOTIFICATION, null, "sent you a chat")?.action)
        assertEquals("received_message", Rules.match(sc, pkg, RuleEvent.NOTIFICATION, null, "sent you a Snap")?.action)
        assertNull(Rules.match(sc, pkg, RuleEvent.NOTIFICATION, null, "is typing..."))
        assertNull(Rules.match(sc, pkg, RuleEvent.NOTIFICATION, null, "This lake is unreal"))
    }

    @Test fun aZangiNotificationWithNoCategoryIsAReceive() {
        val shipped = parseRules(java.io.File("src/main/assets/rules/default.json").readText())
        val r = Rules.match(shipped, "com.beint.zangi", RuleEvent.NOTIFICATION, null, "Test 11 lima mike november")
        assertEquals("received_message", r?.action)
        assertNull(Rules.match(shipped, "com.example.other", RuleEvent.NOTIFICATION, null, "Test 11"))
        assertNull(Rules.match(shipped, "com.beint.zangi", RuleEvent.NOTIFICATION, null, ""))
        val send = Rules.match(shipped, "com.beint.zangi", RuleEvent.FIELD_CLEARED, "com.beint.zangi:id/bottom_sheet_message_input_id", null)
        assertEquals("sent_message", send?.action)
        val read = readRulesForImport("""{"rules":[{"id":"n","package":"p","event":"notification","label_regex":".","action":"received_message"}]}""")
        assertEquals(RuleEvent.NOTIFICATION, read.rules.single().event)
    }

    @Test fun shippedRulesParseAndTheInstagramSendMatchesItsField() {
        val shipped = parseRules(java.io.File("src/main/assets/rules/default.json").readText())
        assertEquals(listOf("google_messages_send", "aosp_camera_shutter", "instagram_send", "telegram_receive", "telegram_send", "discord_author", "zangi_send", "zangi_notification", "line_send", "threads_send", "tiktok_send", "signal_send", "whatsapp_send", "messenger_send", "wire_send", "snapchat_author", "snapchat_notification", "google_messages_image_sent", "google_messages_image_received"), shipped.map { it.id })
        val field = "com.instagram.android:id/row_thread_composer_edittext"
        val hit = Rules.match(shipped, "com.instagram.android", RuleEvent.FIELD_CLEARED, field, null)
        assertEquals("sent_message", hit?.action); assertEquals("last_typed", hit?.content)
        assertNull(Rules.match(shipped, "com.instagram.android", RuleEvent.TAP, field, null))
        assertNull(Rules.match(shipped, "com.instagram.android", RuleEvent.FIELD_CLEARED, "com.instagram.android:id/search", null))
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
