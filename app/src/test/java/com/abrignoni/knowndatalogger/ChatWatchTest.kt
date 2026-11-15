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
}
