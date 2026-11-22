package com.vinz.music

import com.prism.music.data.db.HourTotal
import com.vinz.music.ui.screens.CalendarGrid
import com.vinz.music.ui.screens.ClockGrid
import com.vinz.music.ui.screens.Period
import com.vinz.music.ui.screens.ReplaySections
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Replay's listening clock and calendar (1.8.5), the arithmetic: the busiest hour and weekday, rows in the locale's week,
 * hours in 12 or 24 h, a month's blank cells and weeks, a tap's day, how dark a day is, and which periods get the
 * sections at all.
 */
class ReplayGridsTest {
    private val hours = listOf(
        HourTotal(weekday = 2, hour = 21, ms = 2_400_000, plays = 4), // Wednesday 21:00
        HourTotal(weekday = 2, hour = 10, ms = 180_000, plays = 3),
        HourTotal(weekday = 0, hour = 8, ms = 300_000, plays = 3),    // Monday 08:00
        HourTotal(weekday = 0, hour = 21, ms = 400_000, plays = 2),
        HourTotal(weekday = 5, hour = 10, ms = 1_000_000, plays = 10), // Saturday 10:00
    )

    @Test fun theBusiestHourAndDayAddUpAcrossTheWeek() {
        // 21:00 has 2,800 s over two weekdays; 10:00 has 1,180 s; Wednesday 2,580 s beats Saturday's 1,000 s.
        assertEquals(21, ClockGrid.busiest(ClockGrid.byHour(hours)))
        assertEquals(2, ClockGrid.busiest(ClockGrid.byWeekday(hours)))
        assertEquals(2_800_000L, ClockGrid.byHour(hours)[21])
        val cells = ClockGrid.cells(hours)
        assertEquals(168, cells.size)
        assertEquals(2_400_000L, cells[2 * 24 + 21])
        assertEquals(0L, cells[6 * 24 + 23])
    }

    @Test fun nothingPlayedHasNoBusiestHour() {
        assertNull(ClockGrid.busiest(ClockGrid.byHour(emptyList())))
        assertNull(ClockGrid.busiest(LongArray(7)))
    }

    @Test fun aTieGoesToTheEarlierHour() {
        assertEquals(9, ClockGrid.busiest(ClockGrid.byHour(listOf(HourTotal(1, 9, 100, 1), HourTotal(3, 17, 100, 1)))))
    }

    @Test fun rowsStartOnTheLocalesFirstDayOfTheWeek() {
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5, 6), ClockGrid.rows(WeekFields.of(Locale.GERMANY).firstDayOfWeek))
        assertArrayEquals(intArrayOf(6, 0, 1, 2, 3, 4, 5), ClockGrid.rows(WeekFields.of(Locale.US).firstDayOfWeek))
        assertArrayEquals(intArrayOf(5, 6, 0, 1, 2, 3, 4), ClockGrid.rows(DayOfWeek.SATURDAY))
    }

    @Test fun aTapFindsItsHour() {
        assertEquals(0, ClockGrid.hourAt(0f, 240f))
        assertEquals(21, ClockGrid.hourAt(215f, 240f))
        assertEquals(23, ClockGrid.hourAt(239.9f, 240f))
        assertNull(ClockGrid.hourAt(240f, 240f))
        assertNull(ClockGrid.hourAt(-1f, 240f))
    }

    @Test fun hoursFollowThePhonesClock() {
        assertEquals("21:00", ClockGrid.hourLabel(21, is24 = true, Locale.UK))
        assertEquals("09:00", ClockGrid.hourLabel(9, is24 = true, Locale.UK))
        assertTrue(ClockGrid.hourLabel(21, is24 = false, Locale.US).let { it.startsWith("9") && it.endsWith("PM") })
        assertTrue(ClockGrid.hourLabel(0, is24 = false, Locale.US).let { it.startsWith("12") && it.endsWith("AM") })
        assertEquals("Wednesday", ClockGrid.dayName(2, Locale.US))
    }

    @Test fun monthsHaveTheirBlanksAndWeeks() {
        // 1 November 2026 is a Sunday.
        val nov = YearMonth.of(2026, 11)
        assertEquals(6, CalendarGrid.lead(nov, DayOfWeek.MONDAY))
        assertEquals(6, CalendarGrid.weeks(nov, DayOfWeek.MONDAY)) // 6 blanks + 30 days: a six-week month
        assertEquals(0, CalendarGrid.lead(nov, DayOfWeek.SUNDAY))
        assertEquals(5, CalendarGrid.weeks(nov, DayOfWeek.SUNDAY))
        // February 2026 starts on a Sunday and has 28 days: exactly four weeks from Sunday.
        assertEquals(4, CalendarGrid.weeks(YearMonth.of(2026, 2), DayOfWeek.SUNDAY))
    }

    @Test fun aCellAndItsDayAgree() {
        val first = DayOfWeek.MONDAY
        var d = LocalDate.of(2025, 12, 1)
        while (d.year < 2027) {
            val (col, row) = CalendarGrid.cellOf(d, first)
            assertTrue("$d col", col in 0..6)
            assertTrue("$d row", row in 0..5)
            assertEquals(d, CalendarGrid.dayAt(YearMonth.from(d), first, col, row))
            // The column is the weekday counted from the first day of the week.
            assertEquals("$d", (d.dayOfWeek.value - first.value + 7) % 7, col)
            d = d.plusDays(1)
        }
        // Blanks before the 1st and after the last are no day.
        assertNull(CalendarGrid.dayAt(YearMonth.of(2026, 11), first, 0, 0))
        assertNull(CalendarGrid.dayAt(YearMonth.of(2026, 11), first, 6, 5))
    }

    @Test fun monthsCrossTheYear() {
        val m = CalendarGrid.months(LocalDate.of(2025, 11, 15), LocalDate.of(2026, 2, 3))
        assertEquals(listOf(YearMonth.of(2025, 11), YearMonth.of(2025, 12), YearMonth.of(2026, 1), YearMonth.of(2026, 2)), m)
        assertEquals(listOf(YearMonth.of(2026, 3)), CalendarGrid.months(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
    }

    /** All time shows its last twelve months (three years of months were many screens tall); a shorter history shows all of it. */
    @Test fun allTimeShowsItsLastTwelveMonths() {
        val last = LocalDate.of(2026, 11, 12)
        assertEquals(LocalDate.of(2025, 12, 1), CalendarGrid.allTimeFrom(LocalDate.of(2023, 10, 4), last))
        assertEquals(12, CalendarGrid.months(CalendarGrid.allTimeFrom(LocalDate.of(2023, 10, 4), last), last).size)
        assertEquals(LocalDate.of(2026, 8, 20), CalendarGrid.allTimeFrom(LocalDate.of(2026, 8, 20), last))
    }

    @Test fun daysGetDarkerWithTime() {
        assertEquals(0, CalendarGrid.level(0, 1000))
        assertEquals(1, CalendarGrid.level(1, 1000))
        assertEquals(1, CalendarGrid.level(250, 1000))
        assertEquals(2, CalendarGrid.level(251, 1000))
        assertEquals(4, CalendarGrid.level(1000, 1000))
        assertEquals(0, CalendarGrid.level(500, 0))
    }

    @Test fun theLayoutFindsTheDayUnderATap() {
        // 37 months (three years) across 320 dp at 2 px a dp: three months a row, every day back from its own square.
        val l = CalendarGrid.Layout(640f, 37, 2f)
        assertEquals(3, l.perRow)
        assertEquals(13, l.rows)
        for (i in listOf(0, 1, 2, 3, 20, 36)) for (col in 0..6) for (row in 0..5) {
            val o = l.cellOrigin(i, col, row)
            assertEquals(Triple(i, col, row), l.hit(o.x + l.cell / 2, o.y + l.cell / 2))
        }
        // The month's name isn't a day, nor is the space past the last month.
        assertNull(l.hit(l.monthOrigin(0).x + 4f, l.monthOrigin(0).y + 4f))
        assertNull(l.hit(l.monthOrigin(36).x + l.monthW + l.monthGap + 4f, l.monthOrigin(36).y + l.label + 4f))
        // One month isn't stretched over the width: its days stay small.
        val one = CalendarGrid.Layout(640f, 1, 2f)
        assertEquals(1, one.perRow)
        assertEquals(CalendarGrid.CELL_MAX_DP * 2f, one.cell, 0.01f)
    }

    @Test fun shortOrLightPeriodsHaveNoClockOrCalendar() {
        val month = Period.monthOf(2026, 9)
        assertTrue(ReplaySections.showsClock(month, 10))
        assertFalse(ReplaySections.showsClock(month, 9))
        assertFalse(ReplaySections.showsClock(Period.TODAY, 500))
        assertFalse(ReplaySections.showsClock(Period.YESTERDAY, 500))
        assertTrue(ReplaySections.showsClock(Period.WEEK, 500))
        assertFalse(ReplaySections.showsClock(Period.days(20000, 20000), 500))

        assertTrue(ReplaySections.showsCalendar(month, 2))
        assertFalse(ReplaySections.showsCalendar(month, 1))
        assertTrue(ReplaySections.showsCalendar(Period.ALL, 2))
        assertTrue(ReplaySections.showsCalendar(Period.year(2026), 2))
        assertFalse(ReplaySections.showsCalendar(Period.WEEK, 7))
        assertFalse(ReplaySections.showsCalendar(Period.days(20000, 20005), 6))
        assertTrue(ReplaySections.showsCalendar(Period.days(20000, 20013), 6))
    }
}
