package com.vinz.music

import com.vinz.music.data.RecapCalendar
import com.vinz.music.data.RewindCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/** 1.8.5: the monthly recap's dates, on the phone's own clock. */
class RecapCalendarTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test fun theCardIsLastMonthsForTheFirstWeek() {
        assertEquals(YearMonth.of(2026, 10), RecapCalendar.cardMonth(at(berlin, 2026, 11, 1, 0, 0), berlin))
        assertEquals(YearMonth.of(2026, 10), RecapCalendar.cardMonth(at(berlin, 2026, 11, 7, 23, 59), berlin))
        assertNull(RecapCalendar.cardMonth(at(berlin, 2026, 11, 8, 0, 0), berlin))
        assertNull(RecapCalendar.cardMonth(at(berlin, 2026, 10, 31, 23, 59), berlin))
    }

    /** January's card is December's recap (of last year), whatever Rewind shows beside it. */
    @Test fun januaryShowsDecemberOfTheYearBefore() {
        assertEquals(YearMonth.of(2026, 12), RecapCalendar.cardMonth(at(berlin, 2027, 1, 3), berlin))
    }

    /** The same moment is the 1st in one place and still the last day of the month in another. */
    @Test fun theMonthTurnsByThePhonesOwnClock() {
        val moment = at(ZoneId.of("Pacific/Kiritimati"), 2026, 12, 1, 0, 30)   // UTC+14
        assertEquals(YearMonth.of(2026, 11), RecapCalendar.cardMonth(moment, ZoneId.of("Pacific/Kiritimati")))
        assertNull("still 30 November there", RecapCalendar.cardMonth(moment, ZoneId.of("Etc/GMT+12")))
    }

    @Test fun aMonthsBoundsAreMidnightToMidnightThroughDaylightSaving() {
        // March 2026 in Berlin is an hour short (the clocks go forward on the 29th), October an hour long.
        val (m0, m1) = RecapCalendar.bounds(YearMonth.of(2026, 3), berlin)
        assertEquals(at(berlin, 2026, 3, 1, 0, 0), m0)
        assertEquals(at(berlin, 2026, 4, 1, 0, 0) - 1, m1)
        assertEquals(31L * 24 * 3_600_000 - 3_600_000, m1 - m0 + 1)
        val (o0, o1) = RecapCalendar.bounds(YearMonth.of(2026, 10), berlin)
        assertEquals(31L * 24 * 3_600_000 + 3_600_000, o1 - o0 + 1)
        // Months meet with nothing between them and nothing counted twice.
        assertEquals(m1 + 1, RecapCalendar.bounds(YearMonth.of(2026, 4), berlin).first)
        // December ends where the year does.
        assertEquals(RewindCalendar.bounds(2026, berlin).second, RecapCalendar.bounds(YearMonth.of(2026, 12), berlin).second)
    }

    @Test fun theNoticeIsTheFirstAtTen() {
        assertEquals(at(berlin, 2026, 11, 1, 10, 0), RecapCalendar.noticeAt(YearMonth.of(2026, 10), berlin))
        assertEquals(at(berlin, 2027, 1, 1, 10, 0), RecapCalendar.noticeAt(YearMonth.of(2026, 12), berlin))
    }

    /** 1 December at 10:00 is Rewind's: November's recap is told about a day later. */
    @Test fun novembersNoticeWaitsADayForRewind() {
        val rewind = RewindCalendar.opensAt(2026, berlin) + 10 * 3_600_000L
        val recap = RecapCalendar.noticeAt(YearMonth.of(2026, 11), berlin)
        assertEquals(at(berlin, 2026, 12, 2, 10, 0), recap)
        assertTrue(recap - rewind == 24 * 3_600_000L)
    }

    @Test fun theNextNotice() {
        // 12 November: October's has passed, November's is next (on 2 December).
        assertEquals(YearMonth.of(2026, 11) to at(berlin, 2026, 12, 2, 10, 0), RecapCalendar.nextNotice(at(berlin, 2026, 11, 12), berlin))
        // 1 November, 09:00: October's is in an hour.
        assertEquals(YearMonth.of(2026, 10) to at(berlin, 2026, 11, 1, 10, 0), RecapCalendar.nextNotice(at(berlin, 2026, 11, 1, 9, 0), berlin))
        // 1 November, 10:00 exactly: that one is due now, so the next is November's.
        assertEquals(YearMonth.of(2026, 11), RecapCalendar.nextNotice(at(berlin, 2026, 11, 1, 10, 0), berlin).first)
        // 1 December, 12:00: November's is tomorrow, not skipped.
        assertEquals(YearMonth.of(2026, 11) to at(berlin, 2026, 12, 2, 10, 0), RecapCalendar.nextNotice(at(berlin, 2026, 12, 1, 12, 0), berlin))
        // New Year's Eve: December's, on 1 January.
        assertEquals(YearMonth.of(2026, 12) to at(berlin, 2027, 1, 1, 10, 0), RecapCalendar.nextNotice(at(berlin, 2026, 12, 31, 23, 0), berlin))
    }

    @Test fun aMonthsName() {
        assertEquals("October", RecapCalendar.name(YearMonth.of(2026, 10), java.util.Locale.ENGLISH))
        assertEquals("Oktober", RecapCalendar.name(YearMonth.of(2026, 10), java.util.Locale.GERMAN))
    }
}
