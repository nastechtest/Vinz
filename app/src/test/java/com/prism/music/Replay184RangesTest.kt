package com.vinz.music

import com.vinz.music.ui.screens.Period
import com.vinz.music.ui.screens.chipLabel
import com.vinz.music.ui.screens.range
import com.vinz.music.ui.screens.rangeBefore
import com.vinz.music.ui.screens.replayPresets
import com.vinz.music.ui.screens.timelineEnd
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 1.8.4 (D58, D59): Replay's Yesterday, Last week and Last month. */
class Replay184RangesTest {
    // Berlin: clocks go forward on 29 March 2026 (a 23-hour day) and back on 25 October 2026 (a 25-hour day).
    private val zone = ZoneId.of("Europe/Berlin")
    private val hour = 3_600_000L
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun at(y: Int, m: Int, d: Int, h: Int = 10, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
    private fun yesterday(now: Long) = range(Period.YESTERDAY, now, zone, DayOfWeek.MONDAY)
    private fun lastWeek(now: Long, first: DayOfWeek) = range(Period.LAST_WEEK, now, zone, first)

    @Test fun yesterdayIsMidnightToMidnight() {
        val (from, to) = yesterday(at(2026, 11, 9, 0, 5))
        assertEquals(day(2026, 11, 8), from)
        assertEquals(day(2026, 11, 9) - 1, to)
        assertEquals(24 * hour, to - from + 1)
        // Whatever the time of day, it is the same day.
        assertEquals(from to to, yesterday(at(2026, 11, 9, 23, 59)))
    }

    @Test fun yesterdayIs23HoursWhenTheClocksWentForward() {
        val (from, to) = yesterday(at(2026, 3, 30))
        assertEquals(day(2026, 3, 29), from)
        assertEquals(day(2026, 3, 30) - 1, to)
        assertEquals(23 * hour, to - from + 1)
    }

    @Test fun yesterdayIs25HoursWhenTheClocksWentBack() {
        val (from, to) = yesterday(at(2026, 10, 26))
        assertEquals(day(2026, 10, 25), from)
        assertEquals(day(2026, 10, 26) - 1, to)
        assertEquals(25 * hour, to - from + 1)
    }

    @Test fun yesterdayOnNewYearsDayIsTheOldYearsLastDay() {
        assertEquals(day(2026, 12, 31) to day(2027, 1, 1) - 1, yesterday(at(2027, 1, 1)))
    }

    @Test fun yesterdayOnTheFirstOfMarchIsFebruarysLastDay() {
        assertEquals(day(2027, 2, 28) to day(2027, 3, 1) - 1, yesterday(at(2027, 3, 1)))
        assertEquals("a leap year", day(2028, 2, 29) to day(2028, 3, 1) - 1, yesterday(at(2028, 3, 1)))
    }

    @Test fun lastWeekAcrossAMonthEnd() {
        // Tuesday 3 November 2026. Monday first: this week starts 2 November, so last week is 26 October to 1 November.
        assertEquals(day(2026, 10, 26) to day(2026, 11, 2) - 1, lastWeek(at(2026, 11, 3), DayOfWeek.MONDAY))
        // Sunday first: this week starts 1 November, so last week is 25 to 31 October (with the 25-hour day in it).
        val (from, to) = lastWeek(at(2026, 11, 3), DayOfWeek.SUNDAY)
        assertEquals(day(2026, 10, 25) to day(2026, 11, 1) - 1, from to to)
        assertEquals((7 * 24 + 1) * hour, to - from + 1)
    }

    @Test fun lastWeekAcrossAYearEnd() {
        // Wednesday 6 January 2027.
        assertEquals(day(2026, 12, 28) to day(2027, 1, 4) - 1, lastWeek(at(2027, 1, 6), DayOfWeek.MONDAY))
        assertEquals(day(2026, 12, 27) to day(2027, 1, 3) - 1, lastWeek(at(2027, 1, 6), DayOfWeek.SUNDAY))
    }

    @Test fun lastWeekOnTheWeeksFirstDayIsTheSevenDaysBefore() {
        // Monday 9 November 2026, a few minutes in.
        assertEquals(day(2026, 11, 2) to day(2026, 11, 9) - 1, lastWeek(at(2026, 11, 9, 0, 5), DayOfWeek.MONDAY))
        // Sunday 8 November 2026 where the week starts on Sunday.
        assertEquals(day(2026, 11, 1) to day(2026, 11, 8) - 1, lastWeek(at(2026, 11, 8, 0, 5), DayOfWeek.SUNDAY))
    }

    @Test fun lastMonthInJanuaryIsDecemberOfTheYearBefore() {
        val p = Period.lastMonth(LocalDate.of(2027, 1, 15))
        assertEquals(Period.monthOf(2026, 11), p)
        assertEquals(day(2026, 12, 1) to day(2027, 1, 1) - 1, range(p, at(2027, 1, 15), zone, DayOfWeek.MONDAY))
        assertEquals("from a 31st", Period.monthOf(2027, 1), Period.lastMonth(LocalDate.of(2027, 3, 31)))
        assertEquals(Period.monthOf(2026, 9), Period.lastMonth(LocalDate.of(2026, 11, 9)))
    }

    @Test fun yesterdayIsComparedWithTheDayBefore() {
        val (from, _) = yesterday(at(2026, 11, 9))
        assertEquals(day(2026, 11, 7) to day(2026, 11, 8) - 1, rangeBefore(Period.YESTERDAY, from, zone))
        // The day before is the 23-hour one: still midnight to midnight.
        val (spring, _) = yesterday(at(2026, 3, 31))
        val before = rangeBefore(Period.YESTERDAY, spring, zone)!!
        assertEquals(day(2026, 3, 29) to day(2026, 3, 30) - 1, before)
        assertEquals(23 * hour, before.second - before.first + 1)
    }

    @Test fun lastWeekIsComparedWithTheWeekBefore() {
        val (from, _) = lastWeek(at(2027, 1, 6), DayOfWeek.MONDAY)
        assertEquals(day(2026, 12, 21) to day(2026, 12, 28) - 1, rangeBefore(Period.LAST_WEEK, from, zone))
        // With the clocks' change in the week before: 1 to 7 November's week before is 25 to 31 October (Sunday first).
        val (nov, _) = lastWeek(at(2026, 11, 10), DayOfWeek.SUNDAY)
        assertEquals(day(2026, 10, 25) to day(2026, 11, 1) - 1, rangeBefore(Period.LAST_WEEK, nov, zone))
    }

    @Test fun lastMonthIsComparedWithTheMonthBefore() {
        val p = Period.lastMonth(LocalDate.of(2027, 1, 15))
        val (from, _) = range(p, at(2027, 1, 15), zone, DayOfWeek.MONDAY)
        assertEquals(day(2026, 11, 1) to day(2026, 12, 1) - 1, rangeBefore(p, from, zone))
    }

    @Test fun theOtherStretchesKeepTheirOwnComparison() {
        for (p in listOf(Period.TODAY, Period.WEEK, Period.MONTH, Period.DAYS30, Period.ALL, Period.year(2026), Period.days(20_000, 20_003))) {
            assertEquals(p.key, null, rangeBefore(p, day(2026, 11, 1), zone))
        }
    }

    @Test fun theGraphOfYesterdayAndOfLastWeekShowsThemWhole() {
        val (_, y) = yesterday(at(2026, 10, 26))
        assertEquals(day(2026, 10, 26), timelineEnd(Period.YESTERDAY, y, zone, DayOfWeek.MONDAY))
        val (_, mon) = lastWeek(at(2027, 1, 6), DayOfWeek.MONDAY)
        assertEquals(day(2027, 1, 4), timelineEnd(Period.LAST_WEEK, mon, zone, DayOfWeek.MONDAY))
        val (_, sun) = lastWeek(at(2027, 1, 6), DayOfWeek.SUNDAY)
        assertEquals(day(2027, 1, 3), timelineEnd(Period.LAST_WEEK, sun, zone, DayOfWeek.SUNDAY))
    }

    @Test fun chipsInOrder() {
        val today = LocalDate.of(2026, 11, 9)
        assertEquals(
            listOf(
                Period.TODAY, Period.YESTERDAY, Period.WEEK, Period.LAST_WEEK, Period.MONTH, Period.monthOf(2026, 9), Period.DAYS30,
                Period.year(2026), Period.year(2025), Period.ALL,
            ),
            replayPresets(listOf(2026, 2025), today),
        )
    }

    @Test fun savedPicksStillRead() {
        assertEquals(Period.YESTERDAY, Period.of(Period.YESTERDAY.key))
        assertEquals(Period.LAST_WEEK, Period.of(Period.LAST_WEEK.key))
        assertEquals("YESTERDAY", Period.YESTERDAY.key)
        assertEquals("LAST_WEEK", Period.LAST_WEEK.key)
        // What 1.8.3 saved.
        assertEquals(Period.TODAY, Period.of("TODAY"))
        assertEquals(Period.WEEK, Period.of("WEEK"))
        assertEquals(Period.MONTH, Period.of("MONTH"))
        assertEquals(Period.DAYS30, Period.of("DAYS30"))
        assertEquals(Period.ALL, Period.of("ALL"))
        assertEquals(Period.year(2024), Period.of("y2024"))
        assertEquals(Period.monthOf(2026, 9), Period.of("m2026-9"))
        assertEquals(Period.days(20_380, 20_390), Period.of("d20380-20390"))
    }
}

/** The new chips' words (from the app's resources: the new strings are in their own file until they are folded in). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class Replay184LabelsTest {
    @Test fun theNewChipsSayWhatTheyAre() {
        val today = LocalDate.of(2026, 11, 9)
        val lastMonth = Period.lastMonth(today)
        assertEquals(
            listOf("Today", "Yesterday", "This week", "Last week", "This month", "Last month", "Last 30 days", "2026", "All time"),
            replayPresets(listOf(2026), today).map { chipLabel(it, lastMonth) },
        )
        assertEquals("Yesterday", Period.YESTERDAY.label)
        assertEquals("Last week", Period.LAST_WEEK.label)
        // A month picked under Other dates keeps its name; the page's own headings name last month too.
        assertEquals(Period.monthOf(2026, 8).label, chipLabel(Period.monthOf(2026, 8), lastMonth))
    }
}
