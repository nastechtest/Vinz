package com.vinz.music

import com.vinz.music.data.Milestone
import com.vinz.music.data.MilestoneKind
import com.vinz.music.data.Milestones
import com.prism.music.data.db.PlayEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 1.8.5 milestones: read from the history in one pass, each reached once, at the play that reached it. */
class MilestonesTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
    private fun play(song: String, t: Long, minutes: Int = 3) = PlayEventEntity(songId = song, timestamp = t, playedMs = minutes * 60_000L)
    private fun List<Milestone>.of(kind: MilestoneKind) = filter { it.kind == kind }

    @Test fun aSongsPlaysAreMarkedOnceEachAtThePlayThatReachedThem() {
        val start = at(2026, 1, 1)
        val events = (0 until 120).map { play("a", start + it * 60_000L * 10) } + play("b", start + 5_000_000_000L)
        val m = Milestones.of(events, zone).of(MilestoneKind.SONG_PLAYS)
        assertEquals(listOf(25, 50, 100), m.map { it.value })
        assertTrue(m.all { it.songId == "a" })
        assertEquals("the 25th play's own time", events[24].timestamp, m[0].at)
        assertEquals("the 100th play's own time", events[99].timestamp, m[2].at)
    }

    @Test fun hoursAndDifferentSongs() {
        val start = at(2026, 3, 1)
        // 600 different songs of 6 minutes: 60 hours.
        val events = (0 until 600).map { play("s$it", start + it * 400_000L, minutes = 6) }
        val all = Milestones.of(events, zone)
        assertEquals(listOf(10, 50), all.of(MilestoneKind.HOURS).map { it.value })
        assertEquals("10 hours is the 100th six-minute play", events[99].timestamp, all.of(MilestoneKind.HOURS)[0].at)
        assertEquals(listOf(100, 500), all.of(MilestoneKind.SONGS).map { it.value })
        assertEquals(events[499].timestamp, all.of(MilestoneKind.SONGS)[1].at)
    }

    /** One very long play can pass two marks at once: both are given, neither twice. */
    @Test fun oneLongPlayPassingTwoMarks() {
        val all = Milestones.of(listOf(play("podcast", at(2026, 1, 1), minutes = 60 * 51)), zone)
        assertEquals(listOf(10, 50), all.of(MilestoneKind.HOURS).map { it.value })
    }

    @Test fun aStreakCountsDaysInThisTimeZoneAndIsMarkedTheFirstTimeOnly() {
        val days = (1..8).map { play("a", at(2026, 5, it, h = 23, min = 30)) } +          // 8 days in a row
            (12..20).map { play("a", at(2026, 5, it, h = 0, min = 10)) }                    // a gap, then 9 more
        val streaks = Milestones.of(days, zone).of(MilestoneKind.STREAK)
        assertEquals("7 days, once, though two runs got there", listOf(7), streaks.map { it.value })
        assertEquals(at(2026, 5, 7, h = 23, min = 30), streaks[0].at)
        // Read in a zone seven hours behind, 00:10 on the 12th..20th falls on the evenings before: still days in a row.
        assertEquals(listOf(7), Milestones.of(days, ZoneId.of("America/Denver")).of(MilestoneKind.STREAK).map { it.value })
    }

    /** 23:30 and 00:10 the next morning are two days in a row; two plays on one day are one. */
    @Test fun aStreakAcrossMidnight() {
        val events = listOf(play("a", at(2026, 5, 1, 23, 30)), play("a", at(2026, 5, 2, 0, 10)), play("a", at(2026, 5, 2, 9)), play("a", at(2026, 5, 4, 9))) +
            (5..9).map { play("a", at(2026, 5, it)) }
        assertEquals("1-2, then 4-9 is six days: no week", emptyList<Int>(), Milestones.of(events, zone).of(MilestoneKind.STREAK).map { it.value })
        assertEquals(listOf(7), Milestones.of(events + play("a", at(2026, 5, 10)), zone).of(MilestoneKind.STREAK).map { it.value })
    }

    @Test fun theYearsTopArtistIsOneAYearWhoeverLeadsAtItsEndSinceTheyTookTheLead() {
        val artist = mapOf("x1" to "Xena", "x2" to "Xena", "y1" to "Yuri")
        val events = buildList {
            // 2025: Yuri only, two hours.
            repeat(40) { add(play("y1", at(2025, 6, 1) + it * 200_000L)) }
            // 2026: Xena leads from her 20th play (an hour), Yuri overtakes in March, Xena takes it back in May.
            repeat(25) { add(play("x1", at(2026, 1, 5) + it * 200_000L)) }
            repeat(30) { add(play("y1", at(2026, 3, 1) + it * 200_000L)) }
            repeat(10) { add(play("x2", at(2026, 5, 1) + it * 200_000L)) }
        }
        // Read in 2027, both years are over.
        val top = Milestones.of(events, zone, now = at(2027, 1, 2)) { artist[it] }.of(MilestoneKind.TOP_ARTIST)
        assertEquals(listOf(2025 to "Yuri", 2026 to "Xena"), top.map { it.value to it.subject })
        assertEquals("Yuri led 2025 from the play that made an hour", at(2025, 6, 1) + 19 * 200_000L, top[0].at)
        // 25 + 6 Xena plays pass Yuri's 30: the sixth play in May.
        assertEquals(at(2026, 5, 1) + 5 * 200_000L, top[1].at)
    }

    /**
     * This year's leader isn't "top artist of the year" while the year has months to go: not from the first hour, not
     * at each change of leader. In December it is (reached on the 1st, or when they took the lead after it).
     */
    @Test fun thisYearsTopArtistWaitsForDecember() {
        val artist = mapOf("x1" to "Xena", "y1" to "Yuri")
        val events = buildList {
            repeat(40) { add(play("y1", at(2025, 6, 1) + it * 200_000L)) }
            repeat(25) { add(play("x1", at(2026, 1, 5) + it * 200_000L)) }
            repeat(30) { add(play("y1", at(2026, 3, 1) + it * 200_000L)) }
        }
        val inNovember = Milestones.of(events, zone, now = at(2026, 11, 12)) { artist[it] }.of(MilestoneKind.TOP_ARTIST)
        assertEquals("last year's only", listOf(2025 to "Yuri"), inNovember.map { it.value to it.subject })
        assertEquals("a past year is as it was", at(2025, 6, 1) + 19 * 200_000L, inNovember[0].at)

        val inDecember = Milestones.of(events, zone, now = at(2026, 12, 3)) { artist[it] }.of(MilestoneKind.TOP_ARTIST)
        assertEquals(listOf(2025 to "Yuri", 2026 to "Yuri"), inDecember.map { it.value to it.subject })
        assertEquals("reached on 1 December", at(2026, 12, 1, 0, 0), inDecember[1].at)

        // Taking the lead in December: reached then.
        val late = events + (0 until 40).map { play("x1", at(2026, 12, 2) + it * 200_000L) }
        val lead = Milestones.of(late, zone, now = at(2026, 12, 5)) { artist[it] }.of(MilestoneKind.TOP_ARTIST).last()
        assertEquals("her 31st play passes his 30", "Xena" to at(2026, 12, 2) + 5 * 200_000L, lead.subject to lead.at)
    }

    /**
     * Imported plays not matched to a song come with no song (""): they count as listening time and days, but aren't a
     * song of their own, so neither "different songs" nor a song's plays.
     */
    @Test fun unmatchedImportedPlaysAreNoSong() {
        val start = at(2026, 2, 1)
        val events = (0 until 99).map { play("s$it", start + it * 400_000L) } + (0 until 30).map { play("", start + 50_000_000L + it * 400_000L) }
        val all = Milestones.of(events, zone)
        assertTrue("99 songs and the unmatched plays aren't 100", all.of(MilestoneKind.SONGS).isEmpty())
        assertTrue(all.of(MilestoneKind.SONG_PLAYS).isEmpty())
        assertEquals("129 three-minute plays are 6.45 hours: no mark yet", 0, all.of(MilestoneKind.HOURS).size)
        val more = events + (0 until 80).map { play("", start + 90_000_000L + it * 400_000L) }
        assertEquals("but 209 plays of 3 minutes are 10 hours", listOf(10), Milestones.of(more, zone).of(MilestoneKind.HOURS).map { it.value })
    }

    @Test fun noArtistNamesNoTopArtist() {
        val events = (0 until 50).map { play("a", at(2026, 1, 1) + it * 200_000L) }
        assertTrue(Milestones.of(events, zone).of(MilestoneKind.TOP_ARTIST).isEmpty())
    }

    /** Years of history aren't news: only what was reached after milestones were first looked at, and not seen yet. */
    @Test fun freshIsWhatCameAfterTheFirstLookAndWasntSeen() {
        val start = at(2024, 1, 1)
        val events = (0 until 110).map { play("a", start + it * 86_400_000L / 4) }
        val all = Milestones.of(events, zone)
        val firstLook = events[60].timestamp
        val fresh = Milestones.fresh(all, since = firstLook, seen = emptySet())
        assertTrue("25 and 50 plays were before the first look", fresh.none { it.kind == MilestoneKind.SONG_PLAYS && it.value < 100 })
        assertTrue(fresh.any { it.kind == MilestoneKind.SONG_PLAYS && it.value == 100 })
        val seen = fresh.map { it.key }.toSet()
        assertTrue("seen ones aren't told again", Milestones.fresh(all, firstLook, seen).isEmpty())
        // Read again from the same history, a milestone has the same key.
        assertEquals(all.map { it.key }, Milestones.of(events, zone).map { it.key })
    }

    /** A play taken out of the history (or a source switched off) takes its milestone with it: nothing is stored. */
    @Test fun aMilestoneGoesWhenItsPlaysDo() {
        val events = (0 until 25).map { play("a", at(2026, 1, 1) + it * 200_000L) }
        assertEquals(1, Milestones.of(events, zone).of(MilestoneKind.SONG_PLAYS).size)
        assertEquals(0, Milestones.of(events.drop(1), zone).of(MilestoneKind.SONG_PLAYS).size)
    }

    @Test fun theCheapCheckAfterAPlay() {
        assertTrue(Milestones.mayHaveReached(songPlays = 100, beforeMs = 0, totalMs = 1, songsBefore = 3, songs = 3))
        assertTrue(Milestones.mayHaveReached(songPlays = 3, beforeMs = 10 * 3_600_000L - 1, totalMs = 10 * 3_600_000L + 5, songsBefore = 3, songs = 3))
        assertTrue(Milestones.mayHaveReached(songPlays = 1, beforeMs = 0, totalMs = 1, songsBefore = 99, songs = 100))
        assertFalse(Milestones.mayHaveReached(songPlays = 26, beforeMs = 5, totalMs = 9, songsBefore = 100, songs = 100))
    }

    /** 60,000 plays over three years in well under a second: it runs when Replay opens. */
    @Test fun aBigHistoryIsQuick() {
        val start = at(2024, 1, 1)
        val events = (0 until 60_000).map { play("s${(it * 7919) % 4000}", start + it * 1_500_000L) }
        Milestones.of(events.take(5_000), zone) { "artist${it.hashCode() % 300}" }   // warm up
        val t0 = System.nanoTime()
        val all = Milestones.of(events, zone) { "artist${it.hashCode() % 300}" }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 1_000)
        assertTrue(all.isNotEmpty())
    }
}
