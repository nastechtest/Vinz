package com.vinz.music

import com.vinz.music.data.RewindParts
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.SongPlays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Rewind 2026's new pieces (1.8.5), worked out from the plays: the calendar and the week's clock on the phone's own
 * clock (31 December / 1 January, daylight saving), the year's milestones from the whole history, and the songs Vinz
 * found that stuck.
 */
class RewindPartsTest {
    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()
    private fun play(song: String, t: Long, ms: Long = 180_000, source: String? = null) = PlayEventEntity(songId = song, timestamp = t, playedMs = ms, source = source)
    private fun song(id: String, title: String = "Song $id") = SongPlays(id, 1, 1, title, "Artist $id", "https://i/$id.jpg", null, 200, null, "Artist $id")

    @Test
    fun theCalendarRunsFromFirstOfJanuaryToNewYearsEveLocally() {
        val zone = ZoneId.of("America/New_York")
        val events = listOf(
            play("a", at(zone, 2025, 12, 31, 23, 59), 1_000), // last year
            play("a", at(zone, 2026, 1, 1, 0, 0), 2_000), // its first minute
            play("a", at(zone, 2026, 12, 31, 23, 59), 3_000), // its last
            play("a", at(zone, 2027, 1, 1, 0, 0), 4_000), // next year
            // The night the clocks go back: 01:30 twice, both on 1 November.
            play("b", ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli(), 10),
            play("b", ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, zone).withLaterOffsetAtOverlap().toInstant().toEpochMilli(), 20),
        )
        val days = RewindParts.days(events, 2026, zone)
        assertEquals(365, days.size)
        assertEquals(2_000L, days.first())
        assertEquals(3_000L, days.last())
        assertEquals(30L, days[java.time.LocalDate.of(2026, 11, 1).dayOfYear - 1])
        assertEquals(2_000L + 3_000 + 30, days.sum())
        // A leap year has its 29 February; the same plays in Tokyo fall on other days.
        assertEquals(366, RewindParts.days(emptyList(), 2028, zone).size)
        val tokyo = RewindParts.days(events, 2026, ZoneId.of("Asia/Tokyo"))
        // New York's last minute of 2025 and first of 2026 are both Tokyo's 1 January afternoon; its New Year's Eve 2026 is Tokyo's 2027.
        assertEquals(3_000L, tokyo.first())
        assertEquals(3_030L, tokyo.sum())
    }

    @Test
    fun theWeekClockPutsEachPlayInItsLocalHour() {
        val zone = ZoneId.of("Europe/Berlin")
        // Monday 9 March 2026, 22:15; Sunday 29 March, the night the clocks go forward: 03:30 (02:xx doesn't exist).
        val events = listOf(play("a", at(zone, 2026, 3, 9, 22, 15), 100), play("a", at(zone, 2026, 3, 29, 3, 30), 7))
        val week = RewindParts.weekHours(events, zone)
        assertEquals(168, week.size)
        assertEquals(100L, week[0 * 24 + 22])
        assertEquals(7L, week[6 * 24 + 3])
        assertEquals(107L, week.sum())
    }

    @Test
    fun theYearsMilestonesComeFromTheWholeHistory() {
        val zone = ZoneId.of("UTC")
        val (from, to) = at(zone, 2026, 1, 1, 0) to at(zone, 2026, 12, 31, 23, 59)
        val all = ArrayList<PlayEventEntity>()
        // 30 plays of "a" in 2025 and 30 in 2026: its 50th play is 2026's, its 25th 2025's.
        repeat(30) { all += play("a", at(zone, 2025, 3, 1, 10) + it * 60_000L) }
        repeat(30) { all += play("a", at(zone, 2026, 2, 1, 10) + it * 60_000L) }
        // A song nobody can name (an imported play not matched): its milestone isn't shown.
        repeat(26) { all += play("x", at(zone, 2026, 4, 1, 10) + it * 60_000L) }
        // One long play takes the total past 10 and 50 hours at once: only the bigger shows.
        all += play("b", at(zone, 2026, 6, 1, 10), 47 * 3_600_000L)
        // A week in a row in May 2026.
        for (d in 1..7) all += play("c", at(zone, 2026, 5, d, 9))
        all.sortBy { it.timestamp }
        val ms = RewindParts.milestones(all, from, to, mapOf("a" to song("a", "Alpha"), "b" to song("b"), "c" to song("c")), zone)
        val kinds = ms.map { it.kind to it.value }
        assertTrue(kinds.toString(), ("SONG_PLAYS" to 50) in kinds)
        assertTrue(kinds.toString(), ("SONG_PLAYS" to 25) !in kinds) // 2025's
        assertTrue(kinds.toString(), ("STREAK" to 7) in kinds)
        assertTrue(kinds.toString(), ("HOURS" to 50) in kinds && ("HOURS" to 10) !in kinds) // the biggest of the year only
        assertTrue(ms.none { it.songId == "x" })
        assertTrue(ms.none { it.kind == "TOP_ARTIST" })
        assertEquals("Alpha", ms.first { it.kind == "SONG_PLAYS" }.songTitle)
        assertEquals(ms.sortedBy { it.at }, ms)
        assertTrue(ms.size <= RewindParts.MAX_MILESTONES)
        assertTrue(ms.all { it.at in from..to })
    }

    @Test
    fun foundForYouIsWhatVinzBroughtThatStuck() {
        val zone = ZoneId.of("UTC")
        val (from, to) = at(zone, 2026, 1, 1, 0) to at(zone, 2026, 12, 31, 23, 59)
        val t = at(zone, 2026, 3, 1, 12)
        val all = listOf(
            play("old", at(zone, 2025, 6, 1, 12), source = "radio"), // first heard last year: not this year's find
            *Array(5) { play("old", t + it) },
            play("radio", t + 10, source = "radio"), play("radio", t + 11), play("radio", t + 12, source = "album"),
            play("mix", t + 20, source = "mix:daily:1"), play("mix", t + 21), play("mix", t + 22), play("mix", t + 23),
            play("smart", t + 30, source = "smart"), play("smart", t + 31), // only twice: didn't stick
            play("album", t + 40, source = "album"), play("album", t + 41), play("album", t + 42), // the listener's own pick
            play("", t + 50, source = "radio"), play("", t + 51), play("", t + 52), // unmatched imported plays
            play("unknown", t + 60, source = "dj"), play("unknown", t + 61), play("unknown", t + 62), // a song without a name
        ).sortedBy { it.timestamp }
        val known = listOf("old", "radio", "mix", "smart", "album").associateWith { song(it) }
        val found = RewindParts.finds(all, from, to, known)
        assertEquals(listOf("mix", "radio"), found.map { it.songId })
        assertEquals(4, found.first().plays)
        assertEquals(4 * 180_000L, found.first().ms)
        assertTrue(RewindParts.foundBy("RADIO") && RewindParts.foundBy("dj") && RewindParts.foundBy("blend") && !RewindParts.foundBy("spotify") && !RewindParts.foundBy(null))
    }
}
