package com.abrignoni.knowndatalogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class UxLogicTest {
    private val day = 86_400_000L

    private fun stamp(utc: Long, elapsed: Long, off: Int = 0) =
        Stamp(utcMs = utc, tzId = "UTC", tzOffsetMin = off, elapsedMs = elapsed, bootCount = 1, networkTimeMs = null, gnssTimeMs = null)

    private fun entry(id: String, s: Stamp) =
        Entry(entryId = id, kind = Kind.ACTION, scenarioId = "s", deviceLabel = "d", profileId = null, stamp = s, editedUtcMs = s.utcMs)

    // ---- direction after an edit ----

    @Test fun changedActionTakesItsOwnDirection() =
        assertEquals("in", directionAfterEdit("sent_message", "out", "received_message"))

    @Test fun unchangedActionKeepsRecordedDirection() =
        assertEquals("in", directionAfterEdit("custom_thing", "in", " custom_thing "))

    // Rows inferred before rule matches carried a direction have none; an edit fills it from the action.
    @Test fun unchangedActionWithoutDirectionIsFilled() =
        assertEquals("out", directionAfterEdit("sent_message", null, "sent_message"))

    @Test fun blankedActionHasNoDirection() = assertNull(directionAfterEdit("sent_message", "out", ""))

    @Test fun counterpartLabels() {
        assertEquals("From", counterpartLabel("in"))
        assertEquals("To", counterpartLabel("out"))
        assertEquals("To or from", counterpartLabel(null))
    }

    // ---- day headers ----

    @Test fun localDayUsesTheRecordedOffset() {
        // 23:30 UTC on 1 January 1970 is already 2 January at +01:00.
        val late = 23L * 3_600_000 + 30 * 60_000
        assertEquals(LocalDate.of(1970, 1, 1), localDay(stamp(late, 0)))
        assertEquals(LocalDate.of(1970, 1, 2), localDay(stamp(late, 0, off = 60)))
    }

    @Test fun firstRowAndEachNewDayStart() {
        val rows = listOf(entry("c", stamp(2 * day + 5, 30)), entry("b", stamp(day + 9, 20)), entry("a", stamp(day + 1, 10)), entry("z", stamp(5, 1)))
        assertEquals(setOf("c", "b", "z"), dayStarts(rows))
    }

    // Order is by monotonic time, so a clock set back can show an earlier day between rows of one day.
    @Test fun aDayThatComesBackStartsAgain() {
        val rows = listOf(entry("a", stamp(day + 1, 10)), entry("b", stamp(5, 20)), entry("c", stamp(day + 2, 30)))
        assertEquals(setOf("a", "b", "c"), dayStarts(rows))
    }

    @Test fun noRowsNoHeaders() = assertEquals(emptySet<String>(), dayStarts(emptyList()))

    // ---- app preselected when logging by hand ----

    private val listed = listOf("app.a", "app.b", "app.c")

    @Test fun frontAppWins() = assertEquals("app.c", defaultLogApp("app.c", "app.b", listed))

    @Test fun lastHandEntryWhenNothingWasInFront() = assertEquals("app.b", defaultLogApp(null, "app.b", listed))

    // The launcher or Settings in front (Capture all apps) is not an app under test.
    @Test fun unlistedFrontAppIsSkipped() = assertEquals("app.b", defaultLogApp("com.android.launcher3", "app.b", listed))

    @Test fun removedAppIsNeverPreselected() = assertEquals("app.a", defaultLogApp(null, "app.gone", listed))

    @Test fun noAppsNoPreselection() = assertNull(defaultLogApp("app.a", "app.b", emptyList()))

    // ---- app search ----

    private val apps = listOf("com.google.android.apps.messaging" to "Messages", "org.thoughtcrime.securesms" to "Signal", "com.android.camera2" to "Camera")

    @Test fun blankQueryShowsAll() = assertEquals(apps, filterApps(apps, "  "))

    @Test fun labelMatchIgnoresCase() = assertEquals(listOf(apps[1]), filterApps(apps, "SIG"))

    @Test fun packageMatches() = assertEquals(listOf(apps[1]), filterApps(apps, "thoughtcrime"))

    @Test fun noMatchIsEmpty() = assertEquals(emptyList<Pair<String, String>>(), filterApps(apps, "zzz"))

    // ---- export folder words ----

    @Test fun folderPathAfterTheVolume() = assertEquals("Documents/KDL", treeFolderName("primary:Documents/KDL"))

    @Test fun storageRootShowsTheVolume() = assertEquals("primary", treeFolderName("primary:"))

    @Test fun idWithoutAVolumeIsShownWhole() = assertEquals("downloads", treeFolderName("downloads"))
}
