package com.vinz.music

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.SongEntity
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.screens.CalendarGrid
import com.vinz.music.ui.screens.ClockGrid
import com.vinz.music.ui.screens.ReplayContent
import com.vinz.music.ui.screens.ReplayTags
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Replay's 1.8.5 sections on the screen itself, with a seeded October 2026: the listening clock names the busiest hour
 * and weekday and lists an hour's songs on a tap; the calendar says a tapped day's time and plays; milestones show,
 * newest first with their "New" mark, and are marked seen; a month's recap request picks the month, opens its story and
 * is cleared; a light history shows none of the sections.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h4000dp-xhdpi", application = android.app.Application::class)
class ReplaySectionsTest {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val container by made

    @org.junit.After fun close() = made.closeAfterTest()

    private val october = "m2026-9"
    private val zone = ZoneId.systemDefault()
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun song(id: String, title: String) = SongEntity(id, title, "$title Artist", "UC$id", "Album", null, 200, null, primaryArtist = "$title Artist")

    /**
     * October 2026: Alpha four times on Wednesday the 14th at nine in the evening (10 min each), Bravo twice on Wednesday
     * the 21st at nine, Charlie three mornings' worth on Monday the 5th at eight, Delta once, and Echo every day from the
     * 1st to the 25th at ten (1 min each: its 25th play and a 7-day run are milestones).
     */
    private fun seedOctober() = runBlocking {
        container.db.songs().upsertAll(listOf(song("a", "Alpha"), song("b", "Bravo"), song("c", "Charlie"), song("d", "Delta"), song("e", "Echo")))
        val plays = container.db.plays()
        listOf(5, 15, 25, 35).forEach { plays.insert(PlayEventEntity(songId = "a", timestamp = at(2026, 10, 14, 21, it), playedMs = 600_000)) }
        listOf(30, 45).forEach { plays.insert(PlayEventEntity(songId = "b", timestamp = at(2026, 10, 21, 21, it), playedMs = 200_000)) }
        listOf(0, 10, 20).forEach { plays.insert(PlayEventEntity(songId = "c", timestamp = at(2026, 10, 5, 8, it), playedMs = 100_000)) }
        plays.insert(PlayEventEntity(songId = "d", timestamp = at(2026, 10, 10, 13, 0), playedMs = 100_000))
        (1..25).forEach { plays.insert(PlayEventEntity(songId = "e", timestamp = at(2026, 10, it, 10, 0), playedMs = 60_000)) }
    }

    /** Milestones before this were "already there": everything seeded here counts as new. */
    private fun milestonesNew() =
        RuntimeEnvironment.getApplication().getSharedPreferences("milestones", Context.MODE_PRIVATE).edit().putLong("since", 1L).commit()

    private fun show(startPeriod: String? = october, rtl: Boolean = false, fontScale: Float? = null, opened: (String) -> Unit = {}) {
        compose.setContent {
            val nav = Navigator(rememberNavController(), openPlayer = {})
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                LocalContainer provides container, LocalNavigator provides nav,
                androidx.compose.ui.platform.LocalLayoutDirection provides if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale ?: density.fontScale),
            ) {
                VinzTheme(AppSettings(), artworkColor = null) {
                    ReplayContent(0.dp, startPeriod = startPeriod, showStory = { s, _ -> opened(s.title) })
                }
            }
        }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun until(what: String, cond: () -> Boolean) {
        runCatching { compose.waitUntil(20_000) { cond() } }.onFailure {
            throw AssertionError("$what: not seen in\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 60))
        }
        compose.waitForIdle()
        assertTrue(what, cond())
    }

    /**
     * The page with its numbers. The list is nudged once: under Robolectric, the first test in a fresh JVM can keep the
     * list's item count from before the numbers came (header, chips, hero) until something remeasures it.
     */
    private fun loaded() {
        until("the page") {
            runCatching { compose.onAllNodes(hasScrollToIndexAction(), useUnmergedTree = true)[0].performScrollToIndex(0) }
            count(ReplayTags.HERO_LABEL) == 1 && compose.onAllNodes(androidx.compose.ui.test.hasText("Top songs"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun SemanticsNodeInteraction.text(): String = fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

    @Test fun theClockNamesTheBusiestHourAndDayAndATapListsThatHoursSongs() {
        seedOctober()
        show()
        loaded()
        until("the clock") { count(ReplayTags.CLOCK_BUSIEST) == 1 }
        val is24 = android.text.format.DateFormat.is24HourFormat(RuntimeEnvironment.getApplication())
        val nine = ClockGrid.hourLabel(21, is24, Locale.getDefault())
        val hourLine = node(ReplayTags.CLOCK_BUSIEST).text()
        assertTrue(hourLine, hourLine.contains(nine))
        // 40 min of Alpha and 6 of Bravo at nine.
        assertTrue(hourLine, hourLine.contains("46 min"))
        val dayLine = node(ReplayTags.CLOCK_BUSIEST_DAY).text()
        assertTrue(dayLine, dayLine.contains("Wednesday"))
        // TalkBack hears one description with both, not 168 cells.
        val said = node(ReplayTags.CLOCK_GRID).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        assertTrue(said, said.contains(hourLine) && said.contains(dayLine))
        assertEquals(0, count(ReplayTags.CLOCK_SONG))

        // A tap in the nine o'clock column lists its songs, most listened first.
        node(ReplayTags.CLOCK_GRID).performTouchInput { click(Offset(width * 21.5f / 24f, height / 2f)) }
        until("nine o'clock's songs") { count(ReplayTags.CLOCK_SONG) == 2 }
        val rows = compose.onAllNodes(hasTestTag(ReplayTags.CLOCK_SONG)).fetchSemanticsNodes()
            .map { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text } }
        assertTrue("$rows", rows[0].contains("Alpha") && rows[1].contains("Bravo"))
    }

    /** Right to left the hour labels run from the right (Row mirrors them): the grid and a tap must too, as in the story. */
    @Test fun rightToLeftTheClockRunsFromTheRightLikeItsLabels() {
        seedOctober()
        show(rtl = true)
        loaded()
        until("the clock") { count(ReplayTags.CLOCK_BUSIEST) == 1 }
        // Nine in the evening, counted from the right edge.
        node(ReplayTags.CLOCK_GRID).performTouchInput { click(Offset(width * (1f - 21.5f / 24f), height / 2f)) }
        until("nine o'clock's songs") { count(ReplayTags.CLOCK_SONG) == 2 }
        assertEquals(5, ClockGrid.hourAt(239f - 5.5f * 10f, 240f, rtl = true))
        assertEquals(0, ClockGrid.hourAt(239.9f, 240f, rtl = true))
        assertEquals(23, ClockGrid.hourAt(0f, 240f, rtl = true))
        assertEquals(230f, ClockGrid.hourLeft(0, 240f, rtl = true))
        assertEquals(0f, ClockGrid.hourLeft(23, 240f, rtl = true))
    }

    /** At twice the font size the weekday names fit their rows (18 dp clipped them from 1.3x). */
    @Test fun aLargeFontFitsTheClocksWeekdayNames() {
        seedOctober()
        show(fontScale = 2f)
        loaded()
        until("the clock") { count(ReplayTags.CLOCK_BUSIEST) == 1 }
        val names = compose.onAllNodes(hasTestTag(ReplayTags.CLOCK_DAY), useUnmergedTree = true)
        assertEquals(7, names.fetchSemanticsNodes().size)
        for (i in 0 until 7) {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            names[i].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val r = results.single()
            assertFalse(
                "${r.layoutInput.text} clipped: ${r.size} of ${r.multiParagraph.width} x ${r.multiParagraph.height}, lines ${r.lineCount}, " +
                    "exceeded ${r.multiParagraph.didExceedMaxLines}, ${r.layoutInput.constraints}, ${r.layoutInput.style.fontSize} ${r.layoutInput.density}",
                // (hasVisualOverflow can't be used: a Text narrower than its room reports its room's width as overflow.)
                r.multiParagraph.height > r.size.height + 0.5f || r.multiParagraph.didExceedMaxLines || r.multiParagraph.maxIntrinsicWidth > r.size.width + 0.5f,
            )
        }
    }

    /** At twice the font size the months' names get a taller row: before, it stayed 20 dp and the names ran into the first week. */
    @Test fun aLargeFontGivesTheCalendarsMonthNamesRoom() {
        seedOctober()
        show(fontScale = 2f)
        loaded()
        until("the calendar") { count(ReplayTags.CALENDAR_GRID) == 1 }
        val grid = node(ReplayTags.CALENDAR_GRID).fetchSemanticsNode()
        val dp = compose.density.density
        val fixed = CalendarGrid.Layout(grid.size.width.toFloat(), 1, dp)
        // labelSmall's line at 2x (Android 14's curve: about 29 dp) and 4 dp below it, against the old 20 dp.
        assertTrue("${grid.size.height} vs ${fixed.height}", grid.size.height >= fixed.height + 10 * dp)
    }

    /**
     * All time shows the last twelve months: plays from years before count neither toward the calendar's minimum nor in
     * TalkBack's "days with music".
     */
    @Test fun allTimeCountsOnlyTheDaysItsCalendarShows() {
        val today = LocalDate.now()
        val recent = listOf(today.minusDays(3), today.minusDays(5), today.minusDays(9))
        runBlocking {
            container.db.songs().upsertAll(listOf(song("a", "Alpha")))
            val plays = container.db.plays()
            suspend fun on(d: LocalDate, n: Int) = repeat(n) { plays.insert(PlayEventEntity(songId = "a", timestamp = d.atTime(12, it).atZone(zone).toInstant().toEpochMilli(), playedMs = 200_000)) }
            // Five days three years ago, four plays each.
            (0 until 5).forEach { on(today.minusYears(3).withDayOfMonth(1).plusDays(it.toLong()), 4) }
            recent.forEach { on(it, 1) }
        }
        show(startPeriod = "ALL")
        loaded()
        until("the calendar") { count(ReplayTags.CALENDAR_GRID) == 1 }
        val said = node(ReplayTags.CALENDAR_GRID).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        assertTrue(said, said.contains("Days with music: 3"))
    }

    @Test fun allTimeWithOnlyOldHistoryHasNoCalendar() {
        val old = LocalDate.now().minusYears(3).withDayOfMonth(1)
        runBlocking {
            container.db.songs().upsertAll(listOf(song("a", "Alpha")))
            val plays = container.db.plays()
            (0 until 5).forEach { d -> repeat(3) { plays.insert(PlayEventEntity(songId = "a", timestamp = old.plusDays(d.toLong()).atTime(12, it).atZone(zone).toInstant().toEpochMilli(), playedMs = 200_000)) } }
        }
        show(startPeriod = "ALL")
        loaded()
        compose.mainClock.advanceTimeBy(2_000)
        Thread.sleep(500)
        compose.waitForIdle()
        assertEquals(0, count(ReplayTags.CALENDAR_GRID))
    }

    @Test fun tappingADaySaysItsTimeAndPlays() {
        seedOctober()
        show()
        loaded()
        until("the calendar") { count(ReplayTags.CALENDAR_GRID) == 1 }
        // Before a tap: the busiest day (the 14th, Alpha's 40 minutes and Echo's one).
        assertTrue(node(ReplayTags.CALENDAR_DAY).text().contains("41 min"))

        val grid = node(ReplayTags.CALENDAR_GRID).fetchSemanticsNode()
        val layout = CalendarGrid.Layout(grid.size.width.toFloat(), 1, compose.density.density)
        val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        fun tap(day: LocalDate) {
            val (col, row) = CalendarGrid.cellOf(day, firstDay)
            val o = layout.cellOrigin(0, col, row)
            node(ReplayTags.CALENDAR_GRID).performTouchInput { click(Offset(o.x + layout.cell / 2, o.y + layout.cell / 2)) }
            compose.waitForIdle()
        }
        tap(LocalDate.of(2026, 10, 5))
        val monday = node(ReplayTags.CALENDAR_DAY).text()
        // Charlie's 5 minutes and Echo's one: 6 min, 4 plays.
        assertTrue(monday, monday.contains("6 min") && monday.contains("4 plays") && monday.contains("5"))
        tap(LocalDate.of(2026, 10, 30))
        assertTrue(node(ReplayTags.CALENDAR_DAY).text().contains("No music"))
    }

    @Test fun milestonesShowNewestFirstWithTheirMarkAndAreMarkedSeen() {
        seedOctober()
        milestonesNew()
        show()
        loaded()
        until("two milestones") { count(ReplayTags.MILESTONE) == 2 }
        val rows = compose.onAllNodes(hasTestTag(ReplayTags.MILESTONE)).fetchSemanticsNodes()
            .map { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text } }
        // Echo's 25th play (the 25th) is newer than the 7-day run (the 7th).
        assertTrue("$rows", rows[0].contains("Echo") && rows[0].contains("25"))
        assertTrue("$rows", rows[1].contains("7"))
        assertTrue("$rows", rows.all { it.contains("New milestone") })
        assertEquals("five or fewer: no \"more\"", 0, count(ReplayTags.MILESTONES_MORE))
        // Shown once: seen from now on.
        until("marked seen") { runBlocking { container.milestones.all() }.none { it.fresh } }
    }

    @Test fun aRecapRequestPicksTheMonthOpensItsStoryAndIsCleared() {
        seedOctober()
        var opened: String? = null
        container.recapRequest.value = YearMonth.of(2026, 10)
        show(startPeriod = null) { opened = it }
        until("the story opened") { opened != null }
        assertNull(container.recapRequest.value)
        loaded()
        val label = node(ReplayTags.HERO_LABEL).text()
        assertTrue(label, label.contains("OCTOBER 2026"))
    }

    @Test fun aLightHistoryHasNoClockCalendarOrMilestones() {
        runBlocking {
            container.db.songs().upsertAll(listOf(song("a", "Alpha")))
            container.db.plays().insert(PlayEventEntity(songId = "a", timestamp = at(2026, 10, 14, 21, 0), playedMs = 200_000))
        }
        milestonesNew()
        show()
        loaded()
        // A moment for the sections' own reads.
        compose.mainClock.advanceTimeBy(2_000)
        Thread.sleep(500)
        compose.waitForIdle()
        assertEquals(0, count(ReplayTags.CLOCK))
        assertEquals(0, count(ReplayTags.CALENDAR))
        assertEquals(0, count(ReplayTags.MILESTONES))
    }

    @Test fun noHistoryShowsTheEmptyPageOnly() {
        show()
        until("the empty page") { compose.onAllNodes(androidx.compose.ui.test.hasText("Nothing to replay yet", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(count(ReplayTags.CLOCK) + count(ReplayTags.CALENDAR) + count(ReplayTags.MILESTONES) > 0)
    }
}
