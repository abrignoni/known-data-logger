package com.abrignoni.knowndatalogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FeatureLogicTest {
    private fun stamp(utc: Long) =
        Stamp(utcMs = utc, tzId = "UTC", tzOffsetMin = 0, elapsedMs = utc, bootCount = 1, networkTimeMs = null, gnssTimeMs = null)

    private fun row(seq: Long, kind: String = Kind.ACTION, action: String = "a", detail: String? = null) =
        Entry(seq = seq, entryId = "e$seq", kind = kind, scenarioId = "s", deviceLabel = "d", profileId = null,
            stamp = stamp(seq), editedUtcMs = seq, action = action, detail = detail)

    // ---- rows since the last export ----

    @Test fun neverExportedIsNull() = assertNull(rowsSinceLastExport(listOf(row(1), row(2))))

    @Test fun exportRowLastMeansNothingSince() =
        assertEquals(0, rowsSinceLastExport(listOf(row(1), row(2, Kind.EXPORT, "export", "folder kdl_a"))))

    @Test fun rowsAfterTheExportAreCounted() =
        assertEquals(2, rowsSinceLastExport(listOf(row(1), row(2, Kind.EXPORT, "export", "folder kdl_a"), row(3), row(4))))

    // The export row is written before the files, so a failed export is still a row; it does not count as an export.
    @Test fun failedExportDoesNotCount() {
        val rows = listOf(row(1), row(2, Kind.EXPORT, "export", "folder kdl_a"), row(3),
            row(4, Kind.EXPORT, "export", "folder kdl_b"), row(5, Kind.SYSTEM_EVENT, "export_failed", "folder kdl_b: IOException"))
        assertEquals(3, rowsSinceLastExport(rows))
    }

    @Test fun onlyAFailedExportIsNeverExported() {
        val rows = listOf(row(1), row(2, Kind.EXPORT, "export", "folder kdl_b"), row(3, Kind.SYSTEM_EVENT, "export_failed", "folder kdl_b: IOException"))
        assertNull(rowsSinceLastExport(rows))
    }

    // ---- reading a rules file for import ----

    private fun file(vararg rules: String) = """{"version":1,"rules":[${rules.joinToString(",")}]}"""
    private val good = """{"id":"r1","package":"app.a","event":"tap","view_id":"app.a:id/send","label_regex":null,"action":"sent_message","content":"last_typed","verified_with":"x"}"""

    @Test fun goodRuleIsRead() {
        val read = readRulesForImport(file(good))
        assertEquals(0, read.skipped)
        assertEquals(Rule("r1", "app.a", "app.a:id/send", null, "sent_message", "last_typed", "x", RuleEvent.TAP), read.rules.single())
    }

    @Test fun whatAppWritesReadsBack() {
        val rules = listOf(Rule("a", "p", "v", null, "x", null, null, RuleEvent.FIELD_CLEARED), Rule("b", "p", null, "^Send$", "y", "last_typed", "w"))
        assertEquals(RulesRead(rules, 0), readRulesForImport(rulesToJson(rules)))
    }

    @Test fun eventDefaultsToTap() =
        assertEquals(RuleEvent.TAP, readRulesForImport(file("""{"id":"r","package":"p","view_id":"v","action":"a"}""")).rules.single().event)

    @Test fun unusableRulesAreCountedNotFatal() {
        val read = readRulesForImport(file(
            good,
            """{"id":"no-target","package":"p","action":"a"}""",                               // would never match
            """{"id":"bad-regex","package":"p","label_regex":"(","action":"a"}""",
            """{"id":"bad-event","package":"p","view_id":"v","event":"swipe","action":"a"}""",
            """{"id":"bad-content","package":"p","view_id":"v","action":"a","content":"clipboard"}""",
            """{"id":"","package":"p","view_id":"v","action":"a"}""",
            """{"package":"p","view_id":"v","action":"a"}""",                                    // no id
            """{"id":"blank-action","package":"p","view_id":"v","action":"  "}""",
            """"not an object"""",
        ))
        assertEquals(listOf("r1"), read.rules.map { it.id })
        assertEquals(8, read.skipped)
    }

    @Test fun notARulesFileThrows() {
        assertThrows(IllegalArgumentException::class.java) { readRulesForImport("hello") }
        assertThrows(IllegalArgumentException::class.java) { readRulesForImport("""{"rules":"none"}""") }
        assertThrows(IllegalArgumentException::class.java) { readRulesForImport("""[1,2]""") }
    }

    @Test fun emptyRulesListIsEmptyNotAnError() = assertEquals(RulesRead(emptyList(), 0), readRulesForImport(file()))

    // ---- what an import would do ----

    private fun rule(id: String, action: String = "a") = Rule(id, "p", "v", null, action, null, null)

    @Test fun newAndReplacingRulesAreMarked() {
        val plan = planRuleImport(listOf(rule("builtin"), rule("mine"), rule("fresh")), inEffect = listOf(rule("builtin"), rule("mine"), rule("other")))
        assertEquals(listOf("builtin" to true, "mine" to true, "fresh" to false), plan.map { it.rule.id to it.replaces })
    }

    @Test fun aRepeatedIdKeepsItsLastRule() {
        val plan = planRuleImport(listOf(rule("x", "first"), rule("x", "second")), inEffect = emptyList())
        assertEquals(listOf("second"), plan.map { it.rule.action })
    }

    // ---- export progress words ----

    @Test fun progressWords() {
        assertEquals("Exporting", exportProgressWords(0, 0))
        assertEquals("Exporting: 0 of 5 files", exportProgressWords(0, 5))
        assertEquals("Exporting: 5 of 5 files", exportProgressWords(5, 5))
    }
}
