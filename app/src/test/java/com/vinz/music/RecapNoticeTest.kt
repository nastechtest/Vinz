package com.vinz.music

import com.vinz.music.data.RecapCalendar
import com.vinz.music.data.Recaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/** 1.8.5: the monthly recap's notice is told once a month, and each one lines up the next. */
class RecapNoticeTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val newYork = ZoneId.of("America/New_York")
    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    /** A time zone or clock change lines the same month up again: it's told about once, as Rewind's year is. */
    @Test fun toldTwiceIsOneNotice() {
        val october = YearMonth.of(2026, 10)
        var told: YearMonth? = null
        var notices = 0
        repeat(2) {
            if (Recaps.tells(october, on = true, toldMonth = told, hasRecap = true)) { notices++; told = october }
        }
        assertEquals(1, notices)
        assertFalse("an earlier month isn't told after a later one", Recaps.tells(YearMonth.of(2026, 9), true, told, true))
        assertTrue("next month is", Recaps.tells(YearMonth.of(2026, 11), true, told, true))
        assertFalse("switched off", Recaps.tells(YearMonth.of(2026, 11), false, told, true))
        assertFalse("a month too quiet for a recap", Recaps.tells(YearMonth.of(2026, 11), true, told, false))
    }

    /**
     * October's notice ran at 10:00 in Berlin on 1 November, and the phone is now in New York, where it's 05:00: the
     * calendar alone still says October's notice is next (the work that's running), so the chain would stop there.
     * After a month is handled, the next one is always the month after it.
     */
    @Test fun theChainGoesOnToTheNextMonth() {
        val october = YearMonth.of(2026, 10)
        val now = at(berlin, 2026, 11, 1, 10, 0)
        assertEquals("the calendar alone lines October up again", october, RecapCalendar.nextNotice(now, newYork).first)
        val (month, whenAt) = Recaps.next(now, handled = october, zone = newYork)
        assertEquals(YearMonth.of(2026, 11), month)
        assertEquals("November's waits for 2 December (Rewind has the 1st)", at(newYork, 2026, 12, 2, 10, 0), whenAt)
        // December's, handled in January, lines up January's on 1 February.
        assertEquals(YearMonth.of(2027, 1) to at(berlin, 2027, 2, 1, 10, 0), Recaps.next(at(berlin, 2027, 1, 1, 10, 0), YearMonth.of(2026, 12), berlin))
        // At app start (nothing handled) it's the calendar's next notice.
        assertEquals(RecapCalendar.nextNotice(now, berlin), Recaps.next(now, handled = null, zone = berlin))
    }
}
