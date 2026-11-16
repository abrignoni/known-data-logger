package com.abrignoni.knowndatalogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWatchTest {
    private val w = 1080
    private fun l(t: String, top: Int, left: Boolean = true) =
        if (left) ShownText(t, 60, top, 600, top + 80) else ShownText(t, 480, top, 1020, top + 80)

    @Test fun theFirstLookIsABaseline() {
        val c = ChatWatch()
        assertTrue(c.look("chat", listOf(l("a", 100), l("b", 300, false)), w).isEmpty())
    }

    @Test fun aMessageArrivingAtTheBottomIsNewWithItsSide() {
        val c = ChatWatch()
        c.look("chat", listOf(l("a", 100), l("b", 300, false)), w)
        // The list moves up to make room, as it does when a message arrives.
        val got = c.look("chat", listOf(l("a", 20), l("b", 220, false), l("c", 420)), w)
        assertEquals(listOf(Appeared("c", "left")), got)
        assertTrue(c.look("chat", listOf(l("a", 20), l("b", 220, false), l("c", 420)), w).isEmpty())
    }

    @Test fun backOnTheChatSoonAfterAPickerWhatCameInIsNew() {
        // Telegram, Galaxy A16 5G (15 November 2026): the picture this phone sent was already in the chat on the
        // first look after the picker closed, and a new baseline swallowed it.
        val c = ChatWatch()
        c.look("chat", listOf(l("a", 100), l("b", 300, false)), w, true, 1_000)
        c.look("picker", listOf(l("photo 1", 200), l("photo 2", 400)), w, null, 20_000)
        val got = c.look("chat", listOf(l("a", 20), l("b", 220, false), l("Photo", 420, false)), w, true, 36_000)
        assertEquals(listOf(Appeared("Photo", "right")), got)
        assertTrue(c.lastLookReturned)
    }

    @Test fun backOnTheChatLongAfterLeavingItIsABaseline() {
        val c = ChatWatch()
        c.look("chat", listOf(l("a", 100), l("b", 300, false)), w, true, 1_000)
        c.look("picker", listOf(l("photo 1", 200)), w, null, 20_000)
        assertTrue(c.look("chat", listOf(l("a", 20), l("b", 220, false), l("Photo", 420, false)), w, true, 62_000).isEmpty())
        assertTrue(!c.lastLookReturned)
    }

    @Test fun aReturnWithoutTimesIsABaseline() {
        val c = ChatWatch()
        c.look("chat", listOf(l("a", 100)), w)
        c.look("picker", listOf(l("photo 1", 200)), w)
        assertTrue(c.look("chat", listOf(l("a", 20), l("Photo", 420, false)), w).isEmpty())
    }

    @Test fun linesRevealedByScrollingUpAreNotNew() {
        val c = ChatWatch()
        c.look("chat", listOf(l("m5", 100), l("m6", 300)), w)
        assertTrue(c.look("chat", listOf(l("m3", 100), l("m4", 300), l("m5", 500), l("m6", 700)), w).isEmpty())
    }

    @Test fun lineScrolledAwayAndBackIsNotNew() {
        val c = ChatWatch()
        c.look("chat", listOf(l("m5", 100), l("m6", 300)), w)
        c.look("chat", listOf(l("m3", 100), l("m4", 300)), w)
        assertTrue(c.look("chat", listOf(l("m5", 100), l("m6", 300)), w).isEmpty())
    }

    @Test fun aLookAwayFromTheNewestEndReportsAndLearnsNothing() {
        val c = ChatWatch()
        c.look("chat", listOf(l("a", 100), l("b", 300, false)), w, atNewest = true)
        val shown = listOf(l("a", 20), l("b", 220, false), l("c", 420))
        // Reading history: what comes into view is not arriving.
        assertTrue(c.look("chat", shown, w, atNewest = false).isEmpty())
        // Back at the newest end, a message that arrived meanwhile is still new.
        assertEquals(listOf(Appeared("c", "left")), c.look("chat", shown, w, atNewest = true))
    }

    @Test fun aTitleBarLineAboveMessagesThatDidNotMoveIsNotAnArrival() {
        val c = ChatWatch()
        val title = ShownText("Alex M", 171, 85, 360, 120)
        val old = listOf(l("m336", 161), l("m337", 239), l("m338", 321), l("m339", 434), l("m340", 547))
        c.look("chat", listOf(title) + old, w)
        // The message comes in below the old ones, which stay where they were; the title bar gains a status line.
        val got = c.look("chat", listOf(title, ShownText("Active now", 171, 120, 276, 148)) + old + l("m381", 704), w)
        assertEquals(listOf(Appeared("m381", "left")), got)
    }

    @Test fun aBaselineTakenAwayFromTheEndIsTakenAgainAtTheEnd() {
        val c = ChatWatch()
        // The chat reopened scrolled up, where it was left.
        c.look("chat", listOf(l("m10", 100), l("m11", 300)), w, atNewest = false)
        // The list jumps to its end: older messages below the first baseline are not arrivals.
        assertTrue(c.look("chat", listOf(l("m48", 100), l("m49", 300), l("m50", 500)), w, atNewest = true).isEmpty())
        // From there on, an arrival is new.
        assertEquals(listOf(Appeared("m51", "left")),
            c.look("chat", listOf(l("m49", 100), l("m50", 300), l("m51", 500)), w, atNewest = true))
    }

    @Test fun anOldMessageScrolledBackIntoViewIsNotNew() {
        val c = ChatWatch()
        c.look("chat", listOf(l("m30", 100), l("m31", 300)), w, atNewest = true)
        // Scrolled up past the baseline, then partway back down: older lines show below one already seen.
        assertTrue(c.look("chat", listOf(l("m28", 100), l("m29", 300)), w, atNewest = false).isEmpty())
        assertTrue(c.look("chat", listOf(l("m30", 100), l("m29", 300)), w, atNewest = false).isEmpty())
        assertTrue(c.look("chat", listOf(l("m30", 100), l("m31", 300)), w, atNewest = true).isEmpty())
    }

    @Test fun textOutsideTheListIsReportedAsSuch() {
        val c = ChatWatch()
        c.look("chat", listOf(ShownText("Alex", 200, 40, 500, 90, inList = false), l("a", 300)), w)
        val got = c.look("chat", listOf(ShownText("Alex", 200, 40, 500, 90, inList = false),
            ShownText("online", 200, 95, 400, 130, inList = false), l("a", 300)), w)
        assertEquals(listOf(Appeared("online", "left", inList = false)), got)
    }

    @Test fun aMessageTakesTheAuthorShownNearestAbove() {
        val authors = listOf("Motto" to 266, "Samsung" to 443, "Motto" to 683)
        assertEquals("Samsung", ChatWatch.authorAbove(503, authors))
        assertEquals("Motto", ChatWatch.authorAbove(743, authors))
        // A follow-up in a group has no author line of its own: the one above it still names it.
        assertEquals("Motto", ChatWatch.authorAbove(1200, authors))
        assertNull(ChatWatch.authorAbove(100, authors))
    }

    @Test fun anArrivedLineKeepsWhereItSatAndWhetherItIsALeaf() {
        val c = ChatWatch()
        c.look("chat", listOf(l("a", 100)), w)
        val got = c.look("chat", listOf(l("a", 20), ShownText("Motto, 10:12 AM", 0, 260, 1080, 392, leaf = false)), w)
        assertEquals(260, got.single().top)
        assertTrue(!got.single().leaf)
    }

    @Test fun aPassingStatusLineIsKnown() {
        for (t in listOf("Downloading", "Sending…", "sending...", "Uploading", "Waiting for network", "Tap to download", "SAVED", "Read"))
            assertTrue(t, ChatWatch.isStatusOnly(t))
        for (t in listOf("Downloading the file now", "I am sending it", "Loading dock", "Test 12 sending", "Saved the file", "Read it later"))
            assertTrue(t, !ChatWatch.isStatusOnly(t))
    }

    @Test fun aTimeOrDateLineIsKnown() {
        for (t in listOf("8:21 AM", "8:21\u202fAM", "8:21 a.m.", "18:05", "Today", "Yesterday", "Saturday",
                "November 15", "November 15, 2026", "Now", "Just now", "5 min", "2h", "12 mins ago")) assertTrue(t, ChatWatch.isTimeOrDate(t))
        for (t in listOf("online", "Okay", "Test 29 delta", "Meet at 8:21 AM", "8:21 AM tomorrow", "May I?", "Now what", "5 more minutes"))
            assertTrue(t, !ChatWatch.isTimeOrDate(t))
    }

    @Test fun aSecondCopyOfTheSameTextCounts() {
        val c = ChatWatch()
        c.look("chat", listOf(l("ok", 100), l("x", 300, false)), w)
        assertEquals(listOf(Appeared("ok", "left")), c.look("chat", listOf(l("ok", 20), l("x", 220, false), l("ok", 420)), w))
    }

    @Test fun twoMessagesArrivingTogetherComeInOrder() {
        val c = ChatWatch()
        c.look("chat", listOf(l("a", 100)), w)
        assertEquals(listOf(Appeared("b", "left"), Appeared("c", "right")),
            c.look("chat", listOf(l("a", 100), l("b", 300), l("c", 500, false)), w))
    }

    @Test fun anotherScreenStartsAgainFromABaseline() {
        val c = ChatWatch()
        c.look("chat 1", listOf(l("a", 100)), w)
        assertTrue(c.look("chat 2", listOf(l("z", 100), l("y", 300)), w).isEmpty())
    }

    // Messenger: a title bar above the list and labelled buttons below it stay put on every look.
    private val chrome = listOf(ShownText("Chat title", 100, 20, 700, 80), ShownText("Open camera.", 100, 2000, 200, 2080),
        ShownText("Type a message", 220, 2000, 800, 2080))

    @Test fun theScreensFrameDoesNotHideAnArrival() {
        val c = ChatWatch()
        c.look("chat", chrome + listOf(l("a", 1500), l("b", 1700, false)), w)
        assertEquals(listOf(Appeared("c", "left")), c.look("chat", chrome + listOf(l("a", 1300), l("b", 1500, false), l("c", 1700)), w))
    }

    @Test fun theScreensFrameDoesNotLetAScrollRevealCount() {
        val c = ChatWatch()
        c.look("chat", chrome + listOf(l("m5", 300), l("m6", 500)), w)
        assertTrue(c.look("chat", chrome + listOf(l("m3", 300), l("m4", 500), l("m5", 700), l("m6", 900)), w).isEmpty())
    }

    @Test fun somethingAlreadySeenBelowANewMessageDoesNotHideIt() {
        // A read-receipt avatar moves down under the newest message.
        val c = ChatWatch()
        c.look("chat", chrome + listOf(l("a", 1300), l("b", 1500, false), ShownText("seen", 950, 1600, 1000, 1640)), w)
        assertEquals(listOf(Appeared("c", "left")),
            c.look("chat", chrome + listOf(l("a", 1100), l("b", 1300, false), l("c", 1500), ShownText("seen", 950, 1600, 1000, 1640)), w))
    }

    @Test fun aJumpThatReplacesTheWholeScreenIsNotArrivals() {
        val c = ChatWatch()
        c.look("chat", chrome + listOf(l("m8", 300), l("m9", 500)), w)
        assertTrue(c.look("chat", chrome + listOf(l("m1", 300), l("m2", 500)), w).isEmpty())
    }

    @Test fun theFirstMessageInAnEmptyChatStillCounts() {
        val c = ChatWatch()
        c.look("chat", chrome, w)
        assertEquals(listOf(Appeared("hi", "left")), c.look("chat", chrome + listOf(l("hi", 1700)), w))
    }

    @Test fun sideFollowsAppWalksRule() {
        assertEquals("left", ChatWatch.sideOf(ShownText("t", 60, 0, 600, 1), w))
        assertEquals("right", ChatWatch.sideOf(ShownText("t", 480, 0, 1020, 1), w))
        assertEquals("across", ChatWatch.sideOf(ShownText("t", 20, 0, 1060, 1), w))
    }
}

class TextAppearedRuleTest {
    private val shipped = parseRules(java.io.File("src/main/assets/rules/default.json").readText())

    @Test fun telegramsCellsAreLabelledByTheirOwnWords() {
        // Telegram 13.2.2 (Galaxy A16 5G and moto g play, 15 November 2026): full-width cells that say which way they went.
        val received = "Test 102 golf hotel\nReceived at 12:29 AM"
        val sent = "Test 101 foxtrot golf\nSent at 12:29 AM, Seen"
        assertEquals("received_message", Rules.match(shipped, "org.telegram.messenger", RuleEvent.TEXT_APPEARED, null, received)?.action)
        assertEquals("sent_message", Rules.match(shipped, "org.telegram.messenger", RuleEvent.TEXT_APPEARED, null, sent)?.action)
        assertEquals("line", Rules.match(shipped, "org.telegram.messenger", RuleEvent.TEXT_APPEARED, null, sent)?.content)
        assertEquals(null, Rules.match(shipped, "org.telegram.messenger", RuleEvent.TEXT_APPEARED, null, "Sent at the shop"))
        assertEquals(null, Rules.match(shipped, "org.telegram.messenger", RuleEvent.TEXT_APPEARED, null, "Test 103\nEdited"))
        assertEquals(null, Rules.match(shipped, "org.telegram.messenger", RuleEvent.TAP, null, received))
        assertEquals(null, Rules.match(shipped, "com.example", RuleEvent.TEXT_APPEARED, null, received))
    }

    @Test fun aSentLineThatChangesInPlaceKeepsOneKey() {
        // The line is rewritten once the other side reads it; the message is the same one.
        val rule = Rules.match(shipped, "org.telegram.messenger", RuleEvent.TEXT_APPEARED, null, "a\nSent at 5:34 AM, Not seen")!!
        val notSeen = Rules.lineKey(rule, "Test 101 foxtrot golf\nSent at 5:34 AM, Not seen")
        assertEquals(notSeen, Rules.lineKey(rule, "Test 101 foxtrot golf\nSent at 5:34 AM, Seen"))
        assertEquals("telegram_send|Test 101 foxtrot golf\nSent at 5:34", notSeen)
        // Another message, or the same words sent at another time, is another key. A 24-hour clock works too.
        assertNotEquals(notSeen, Rules.lineKey(rule, "Test 102 golf hotel\nSent at 5:34 AM, Seen"))
        assertNotEquals(notSeen, Rules.lineKey(rule, "Test 101 foxtrot golf\nSent at 5:35 AM, Seen"))
        assertEquals("telegram_send|ok\nSent at 17:34", Rules.lineKey(rule, "ok\nSent at 17:34, Seen"))
        val noRegex = Rule("r", "p", "p:id/x", null, "took_photo", null, null)
        assertNull(Rules.lineKey(noRegex, "anything"))
    }

    @Test fun aTextAppearedRuleWithLineContentCanBeImported() {
        val f = """{"version":1,"rules":[{"id":"r","package":"p","event":"text_appeared","label_regex":"Received at","action":"received_message","content":"line"}]}"""
        val read = readRulesForImport(f)
        assertEquals(0, read.skipped)
        assertEquals(RuleEvent.TEXT_APPEARED, read.rules.single().event)
    }

    @Test fun aMessageAuthorRuleNeedsAnAuthorViewOrPattern() {
        val ok = """{"rules":[{"id":"d","package":"com.discord","event":"message_author","view_id":"com.discord:id/author_name","action":"received_message","content":"line"}]}"""
        val byWords = """{"rules":[{"id":"d","package":"com.snapchat.android","event":"message_author","label_regex":"ME","action":"received_message"}]}"""
        val neither = """{"rules":[{"id":"d","package":"com.discord","event":"message_author","action":"received_message"}]}"""
        assertEquals(RuleEvent.MESSAGE_AUTHOR, readRulesForImport(ok).rules.single().event)
        assertEquals(0, readRulesForImport(byWords).skipped)
        assertEquals(1, readRulesForImport(neither).skipped)
    }
}
