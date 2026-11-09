package com.abrignoni.knowndatalogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeachAndNotifyTest {
    private var seq = 0L
    private fun ev(id: String, action: String, elapsed: Long, pkg: String? = "com.chat", boot: Int = 2,
                   detail: String? = null, content: String? = null, kind: String = Kind.AUTO_EVENT) =
        Entry(seq = ++seq, entryId = id, kind = kind, scenarioId = "s", deviceLabel = "A", profileId = 1,
            stamp = Stamp(1_000 + elapsed, "UTC", 0, elapsed, boot, null, null), editedUtcMs = 0,
            appPackage = pkg, action = action, detail = detail, content = content, source = Source.AUTO_EVENT)

    @Test fun notificationCategoriesMapToActions() {
        assertEquals("received_message", NotificationMap.actionFor("msg"))
        assertEquals("incoming_call", NotificationMap.actionFor("call"))
        assertEquals("missed_call", NotificationMap.actionFor("missed_call"))
        assertNull(NotificationMap.actionFor("email"))
        assertNull(NotificationMap.actionFor(null))
    }

    @Test fun viewIdIsReadFromDetail() {
        assertEquals("com.chat:id/send", viewIdOf(ev("a", AutoAction.TAP, 1, detail = "view com.chat:id/send, class android.widget.Button")))
        assertEquals("Compose:Draft:Send", viewIdOf(ev("b", AutoAction.FIELD_CLEARED, 1, detail = "view Compose:Draft:Send")))
        assertNull(viewIdOf(ev("c", AutoAction.TAP, 1, detail = "class android.view.View")))
        assertNull(viewIdOf(ev("d", AutoAction.TAP, 1, detail = "label withheld: a password field had focus")))
    }

    @Test fun markFindsNewestTapInSameAppWithin30Seconds() {
        val rows = listOf(
            ev("old", AutoAction.TAP, 1_000),
            ev("other", AutoAction.TAP, 9_000, pkg = "com.cam"),
            ev("near", AutoAction.FIELD_CLEARED, 8_000),
            ev("screen", AutoAction.SCREEN, 9_500),
        )
        val mark = ev("m", AutoAction.MARK, 10_000)
        assertEquals("near", sourceEventFor(mark, rows + mark)?.entryId)
        assertNull(sourceEventFor(ev("late", AutoAction.MARK, 60_000), rows))
        assertNull(sourceEventFor(ev("reboot", AutoAction.MARK, 9_000, boot = 3), rows))
    }

    @Test fun rulesRoundTrip() {
        val r = listOf(
            Rule("t1", "com.chat", "com.chat:id/send", null, "sent_message", "last_typed", "taught", RuleEvent.TAP),
            Rule("t2", "com.cam", null, "^" + Regex.escape("Shutter (1)") + "$", "took_photo", null, null, RuleEvent.TAP),
        )
        val back = parseRules(rulesToJson(r))
        assertEquals(r, back)
        assertEquals(r[1], Rules.match(back, "com.cam", RuleEvent.TAP, null, "Shutter (1)"))
        assertNull(Rules.match(back, "com.cam", RuleEvent.TAP, null, "Shutter (12)"))
    }
}
