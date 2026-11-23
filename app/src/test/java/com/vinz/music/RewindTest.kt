package com.vinz.music

import com.vinz.music.data.GenreShare
import com.vinz.music.data.Personality
import com.vinz.music.data.VinzYear
import com.vinz.music.data.RewindCalendar
import com.vinz.music.data.RewindSnapshot
import com.vinz.music.data.RewindStore
import com.vinz.music.data.SeasonGenre
import com.vinz.music.data.StoryFacts
import com.vinz.music.data.StoryPart
import com.vinz.music.data.StoryParts
import com.prism.music.data.db.AlbumPlays
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.FirstPlay
import com.prism.music.data.db.MonthTotal
import com.prism.music.data.db.SongPlays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Rewind: every year, on the phone's own clock, kept once it's over, and a story that fits how much was played. */
// Robolectric: the words come from strings.xml (1.7.9).
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class RewindTest {
    private val zones = listOf("America/New_York", "Europe/London", "Australia/Sydney", "Asia/Kolkata", "America/Sao_Paulo", "Pacific/Chatham", "UTC").map(ZoneId::of)
    private val day = 86_400_000L

    @Test
    fun yearsRunMidnightToMidnightWhateverDaylightSavingDoes() {
        for (zone in zones) for (year in listOf(2025, 2026, 2028)) {
            val (from, to) = RewindCalendar.bounds(year, zone)
            val start = Instant.ofEpochMilli(from).atZone(zone)
            assertEquals(LocalDate.of(year, 1, 1), start.toLocalDate())
            assertEquals(0, start.hour); assertEquals(0, start.minute)
            // The last millisecond is still 31 December there; the next one is next year.
            assertEquals(LocalDate.of(year, 12, 31), Instant.ofEpochMilli(to).atZone(zone).toLocalDate())
            assertEquals(year + 1, RewindCalendar.yearOf(to + 1, zone))
            assertEquals(year, RewindCalendar.yearOf(to, zone))
            // A clock change forward and one back cancel out over a year: exactly 365 (or 366) days.
            val days = if (year % 4 == 0) 366 else 365
            assertEquals("$zone $year", days * day, to - from + 1)
        }
    }

    @Test
    fun seasonsCoverTheYearExactly() {
        val zone = ZoneId.of("America/New_York")
        val (from, to) = RewindCalendar.bounds(2026, zone)
        var at = from
        for (q in 0..3) {
            val (a, b) = RewindCalendar.quarterBounds(2026, q, zone)
            assertEquals(at, a)
            at = b + 1
        }
        assertEquals(to + 1, at)
    }

    @Test
    fun theCurrentYearOpensOnTheFirstOfDecember() {
        val zone = ZoneId.of("Europe/London")
        fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()
        assertFalse(RewindCalendar.isOpen(2026, at(2026, 11, 30, 23, 59), zone))
        assertTrue(RewindCalendar.isOpen(2026, at(2026, 12, 1), zone))
        assertTrue(RewindCalendar.isOpen(2026, at(2026, 12, 31, 23, 59), zone))
        // Earlier years any time; later ones never; early (debug) opens this one now.
        assertTrue(RewindCalendar.isOpen(2025, at(2026, 3, 1), zone))
        assertFalse(RewindCalendar.isOpen(2027, at(2026, 12, 15), zone))
        assertTrue(RewindCalendar.isOpen(2026, at(2026, 3, 1), zone, early = true))
        assertEquals(at(2026, 12, 1), RewindCalendar.opensAt(2026, zone))
    }

    @Test
    fun everyYearWithListening() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2028, 6, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val first = ZonedDateTime.of(2025, 12, 31, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(listOf(2028, 2027, 2026, 2025), RewindCalendar.years(first, now, zone))
        assertEquals(listOf(2028), RewindCalendar.years(now - 1000, now, zone))
        assertTrue(RewindCalendar.years(null, now, zone).isEmpty())
        // The same moment is another year in another zone.
        assertEquals(2026, RewindCalendar.yearOf(first, ZoneId.of("Asia/Tokyo")))
    }

    private fun snapshot(year: Int) = RewindSnapshot(
        year = year, frozenAt = 1_800_000_000_000, totalMs = 9_000_000_000, previousMs = 7_000_000_000, previousTopArtist = "Old Fave", previousSongCount = 800,
        plays = 4321, activeDays = 300, songCount = 1200, artistCount = 400,
        songs = listOf(SongPlays("aaaaaaaaaaa", 90, 18_000_000, "Song A", "Artist A", "https://i.ytimg.com/vi/a/hq.jpg", "Album A", 200, "UC1")),
        artists = listOf(ArtistPlays("Artist A", 300, 60_000_000, null, "UC1")),
        genres = listOf(GenreShare("Pop", 1_000), GenreShare("Rock", 500)), genreSongs = mapOf("Pop" to "aaaaaaaaaaa"),
        byHour = (0 until 24).map { it * 1000L }, byWeekday = (0 until 7).map { it * 10L }, longestStreak = 41,
        biggestDay = "$year-07-04", biggestDayMs = 20_000_000, newArtists = listOf(ArtistPlays("New One", 20, 3_000_000, null, null)),
        personality = Personality("The Loyalist", "You know what you love."),
        months = listOf(MonthTotal("$year-01", 100), MonthTotal("$year-02", 200)),
        topAlbum = AlbumPlays("Album A", "Artist A", 120, 20_000_000, 9, null),
        firstSong = FirstPlay("aaaaaaaaaaa", "Song A", "Artist A", null, 1_767_225_600_000),
        seasons = listOf(SeasonGenre("January–March", "Pop", 100, "Song A")), vinz = VinzYear(sung = 12, recognized = 3), topLyric = "A line \"quoted\" — with dashes",
    )

    @Test
    fun keptYearsComeBackTheSame() {
        val dir = Files.createTempDirectory("rewind").toFile()
        try {
            val store = RewindStore(dir)
            assertNull(store.load(2025))
            val s = snapshot(2025)
            store.save(s)
            assertEquals(s, store.load(2025))
            assertEquals(listOf(2025), store.years())
            val h = store.load(2025)!!.habits()
            assertEquals(LocalDate.of(2025, 7, 4), h.biggestDay)
            assertEquals(23, h.peakHour)
            // A file from another version (or another year's under this name) isn't used.
            java.io.File(dir, "2024.json").writeText(java.io.File(dir, "2025.json").readText())
            assertNull(store.load(2024))
            java.io.File(dir, "2023.json").writeText(java.io.File(dir, "2025.json").readText().replace("\"version\":1", "\"version\":99"))
            assertNull(store.load(2023))
            java.io.File(dir, "2022.json").writeText("not json")
            assertNull(store.load(2022))
        } finally {
            dir.deleteRecursively()
        }
    }

    private val full = StoryFacts(
        rewind = true, totalMs = 400 * 3_600_000L, plays = 5000, activeDays = 280, songs = 100, artists = 20, genres = 5,
        seasonsWithGenre = 4, album = AlbumPlays("A", "B", 40, 1, 8, null), monthsWithListening = 12, firstSong = true,
        newArtists = 30, vinz = true, previous = true,
        calendarDays = 280, clockHours = 120, hourSong = true, milestones = 4, finds = 9,
    )

    @Test
    fun aFullYearGetsEveryPartInOrder() {
        assertEquals(StoryPart.entries.toList(), StoryParts.pick(full))
    }

    @Test
    fun partsOnlyWhenThereIsSomethingToShow() {
        // Replay of a month: the year-only parts never show.
        val month = StoryParts.pick(full.copy(rewind = false))
        assertTrue(listOf(StoryPart.SEASONS, StoryPart.TOP_ALBUM, StoryPart.MONTHS, StoryPart.FIRST_SONG, StoryPart.VINZ_YEAR, StoryPart.YEAR_COMPARE).none { it in month })
        assertEquals(StoryPart.SUMMARY, month.last())
        // A first year: no comparison; no features used: no Vinz slide; one album song: no album slide.
        val first = StoryParts.pick(full.copy(previous = false, vinz = false, album = AlbumPlays("A", "B", 40, 1, 1, null), newArtists = 0, seasonsWithGenre = 1))
        assertTrue(listOf(StoryPart.YEAR_COMPARE, StoryPart.VINZ_YEAR, StoryPart.TOP_ALBUM, StoryPart.DISCOVERY, StoryPart.SEASONS).none { it in first })
        assertTrue(StoryPart.TOP_ARTISTS in first && StoryPart.MONTHS in first)
    }

    @Test
    fun lightListenersGetAShortStory() {
        val light = StoryParts.pick(full.copy(totalMs = 20 * 60_000L, plays = 9, activeDays = 3, songs = 6, artists = 2))
        assertEquals(listOf(StoryPart.INTRO, StoryPart.MINUTES, StoryPart.TOP_ARTIST, StoryPart.TOP_SONG, StoryPart.SUMMARY), light)
        // Barely anything: still a story, never a slide about nothing.
        val nothing = StoryParts.pick(StoryFacts(rewind = true, totalMs = 30_000, plays = 1, activeDays = 1, songs = 0, artists = 0, genres = 0))
        assertEquals(listOf(StoryPart.INTRO, StoryPart.MINUTES, StoryPart.SUMMARY), nothing)
        // Few artists or songs: no top-five lists.
        val few = StoryParts.pick(full.copy(artists = 2, songs = 2))
        assertTrue(StoryPart.TOP_ARTISTS !in few && StoryPart.TOP_SONGS !in few && StoryPart.TOP_ARTIST in few && StoryPart.TOP_SONG in few)
    }

    // ---------------------------------------------------------------- 1.8.5: the new slides

    private val newParts = listOf(StoryPart.CALENDAR, StoryPart.CLOCK, StoryPart.FOUND, StoryPart.MILESTONES)

    @Test
    fun theNewSlidesComeInTheirPlaces() {
        val all = StoryParts.pick(full)
        fun after(a: StoryPart, b: StoryPart) = assertEquals("$b right after $a", all.indexOf(a) + 1, all.indexOf(b))
        after(StoryPart.MONTHS, StoryPart.CALENDAR)
        after(StoryPart.HABITS, StoryPart.CLOCK)
        after(StoryPart.DISCOVERY, StoryPart.FOUND)
        after(StoryPart.FOUND, StoryPart.MILESTONES)
        assertEquals(StoryPart.SUMMARY, all.last())
    }

    @Test
    fun theNewSlidesHideWhenThereIsTooLittle() {
        // Each just under its line, the rest of the year full.
        assertTrue(StoryPart.CALENDAR !in StoryParts.pick(full.copy(calendarDays = StoryParts.CALENDAR_DAYS - 1)))
        assertTrue(StoryPart.CALENDAR in StoryParts.pick(full.copy(calendarDays = StoryParts.CALENDAR_DAYS)))
        assertTrue(StoryPart.CLOCK !in StoryParts.pick(full.copy(clockHours = StoryParts.CLOCK_HOURS - 1)))
        assertTrue(StoryPart.CLOCK !in StoryParts.pick(full.copy(activeDays = StoryParts.CLOCK_DAYS - 1)))
        assertTrue(StoryPart.CLOCK !in StoryParts.pick(full.copy(hourSong = false)))
        assertTrue(StoryPart.FOUND !in StoryParts.pick(full.copy(finds = StoryParts.FINDS - 1)))
        assertTrue(StoryPart.MILESTONES !in StoryParts.pick(full.copy(milestones = StoryParts.MILESTONES - 1)))
        // A Replay of a month, a light year, and a Rewind kept before 1.8.5 (none of the new data): none of them.
        assertTrue(newParts.none { it in StoryParts.pick(full.copy(rewind = false)) })
        assertTrue(newParts.none { it in StoryParts.pick(full.copy(totalMs = 20 * 60_000L)) })
        assertTrue(newParts.none { it in StoryParts.pick(full.copy(calendarDays = 0, clockHours = 0, hourSong = false, milestones = 0, finds = 0)) })
    }

    @Test
    fun aRewindKeptByTheOldVersionStillOpensAsItWas() {
        // Written by 1.8.4.1's RewindStore (the shape on main before 1.8.5); RewindSnapshot.VERSION is still 1.
        val old = javaClass.getResource("/rewind/2025-from-1.8.4.1.json")!!.readText()
        assertTrue(old.contains("\"version\":1"))
        val dir = Files.createTempDirectory("rewind").toFile()
        try {
            java.io.File(dir, "2025.json").writeText(old)
            val s = RewindStore(dir).load(2025)!!
            assertEquals(1, RewindSnapshot.VERSION)
            assertEquals(9_000_000_000, s.totalMs)
            assertEquals(listOf("Song A", "Song B (Live)"), s.songs.map { it.title })
            assertEquals("Artist B", s.songs[1].primaryArtist)
            assertEquals(7, s.vinz.radarFinds)
            assertEquals("January–March", s.seasons.first().label)
            assertEquals("A line \"quoted\" — with dashes", s.topLyric)
            // The 1.8.5 parts are simply absent: its story goes without those slides.
            assertTrue(s.days.isEmpty() && s.weekHours.isEmpty() && s.hourSongs.isEmpty() && s.milestones.isEmpty() && s.finds.isEmpty())
            assertEquals(0, s.findCount)
            // And kept again it stays readable.
            RewindStore(dir).save(s)
            assertEquals(s, RewindStore(dir).load(2025))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theNewPartsAreKeptToo() {
        val dir = Files.createTempDirectory("rewind").toFile()
        try {
            val s = snapshot(2026).copy(
                days = List(365) { (it % 7) * 60_000L }, weekHours = List(168) { it.toLong() },
                hourSongs = snapshot(2026).songs, findCount = 12, finds = snapshot(2026).songs,
                milestones = listOf(com.vinz.music.data.RewindMilestone("HOURS", 500, 1_780_000_000_000), com.vinz.music.data.RewindMilestone("SONG_PLAYS", 100, 1_781_000_000_000, "aaaaaaaaaaa", "Song A", null)),
            )
            RewindStore(dir).save(s)
            assertEquals(s, RewindStore(dir).load(2026))
            // A milestone kind a later version adds is left out, not an error.
            assertNull(com.vinz.music.data.RewindMilestone("SOMETHING_NEW", 1, 0).milestone())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- 1 December

    private val farWest = ZoneId.of("Etc/GMT+12") // UTC−12
    private val farEast = ZoneId.of("Pacific/Kiritimati") // UTC+14

    @Test
    fun itOpensAtMidnightOnTheFirstOfDecemberWhereverThePhoneIs() {
        for (zone in zones + farWest + farEast) {
            fun at(m: Int, d: Int, h: Int, min: Int, s: Int = 0) = ZonedDateTime.of(2026, m, d, h, min, s, 0, zone).toInstant().toEpochMilli()
            assertFalse("$zone", RewindCalendar.isOpen(2026, at(11, 30, 23, 59, 59), zone))
            assertFalse("$zone", RewindCalendar.isOpen(2026, at(12, 1, 0, 0) - 1, zone))
            assertTrue("$zone", RewindCalendar.isOpen(2026, at(12, 1, 0, 0), zone))
            assertTrue("$zone", RewindCalendar.isOpen(2026, at(12, 31, 23, 59), zone))
        }
        // The same instant: open in the far east, not yet in the far west (a day and two hours apart).
        val kiritimatiMidnight = ZonedDateTime.of(2026, 12, 1, 0, 0, 0, 0, farEast).toInstant().toEpochMilli()
        assertTrue(RewindCalendar.isOpen(2026, kiritimatiMidnight, farEast))
        assertFalse(RewindCalendar.isOpen(2026, kiritimatiMidnight, farWest))
    }

    @Test
    fun theNoticeIsDueAtTenOnTheFirstOfDecemberLocalTime() {
        for (zone in zones + farWest + farEast) {
            val at = Instant.ofEpochMilli(RewindCalendar.noticeAt(2026, zone)).atZone(zone)
            assertEquals("$zone", LocalDate.of(2026, 12, 1), at.toLocalDate())
            assertEquals("$zone", 10, at.hour); assertEquals(0, at.minute)
            fun ms(m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(2026, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()
            // Before it: this year's. At it and after: next year's (so the worker's own re-scheduling can't line it up twice).
            assertEquals(2026 to RewindCalendar.noticeAt(2026, zone), RewindCalendar.nextNotice(ms(11, 12, 15), zone))
            assertEquals(2026, RewindCalendar.nextNotice(ms(12, 1, 9, 59), zone).first)
            assertEquals(2027, RewindCalendar.nextNotice(RewindCalendar.noticeAt(2026, zone), zone).first)
            assertEquals(2027, RewindCalendar.nextNotice(ms(12, 31, 23, 59), zone).first)
            assertEquals(2027 to RewindCalendar.noticeAt(2027, zone), RewindCalendar.nextNotice(ms(12, 2, 8), zone))
        }
    }

    @Test
    fun theNoticeGoesOutOnceAndOnlyForAYearWorthTelling() {
        val enough = StoryParts.LIGHT_MS + 1
        assertTrue(com.vinz.music.data.Rewinds.tells(2026, on = true, toldYear = 2025, yearMs = enough))
        assertTrue(com.vinz.music.data.Rewinds.tells(2026, on = true, toldYear = 0, yearMs = enough))
        // Already told (a clock or zone moved back lines the same year up again): not twice.
        assertFalse(com.vinz.music.data.Rewinds.tells(2026, on = true, toldYear = 2026, yearMs = enough))
        // A light year, or switched off: nothing.
        assertFalse(com.vinz.music.data.Rewinds.tells(2026, on = true, toldYear = 2025, yearMs = StoryParts.LIGHT_MS))
        assertFalse(com.vinz.music.data.Rewinds.tells(2026, on = false, toldYear = 2025, yearMs = enough))
    }

    // ---------------------------------------------------------------- Kept or worked out again

    private class Built(var plays: Int = 50) {
        var builds = 0
        suspend fun build(year: Int, @Suppress("UNUSED_PARAMETER") stats: com.vinz.music.data.imports.PlayStats, now: Long): RewindSnapshot {
            builds++
            return snapshot0(year, now).copy(plays = plays, totalMs = builds * 1_000L)
        }
    }

    private fun stats(vararg sources: com.vinz.music.data.imports.ImportSource): com.vinz.music.data.imports.PlayStats =
        java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(com.vinz.music.data.imports.PlayStats::class.java)) { _, m, _ ->
            if (m.name == "getSources") sources.toList() else error("not asked in this test: ${m.name}")
        } as com.vinz.music.data.imports.PlayStats

    @Test
    fun theYearUnderWayIsWorkedOutAfreshAndAFinishedOneIsKept() = kotlinx.coroutines.runBlocking {
        val zone = ZoneId.of("Europe/Berlin")
        val dir = Files.createTempDirectory("rewind").toFile()
        try {
            var now = ZonedDateTime.of(2026, 12, 15, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
            val keeper = com.vinz.music.data.RewindKeeper(RewindStore(dir), { now }, { zone })
            val b = Built()
            val vinz = stats()
            suspend fun open(stats: com.vinz.music.data.imports.PlayStats = vinz) = keeper.snapshot(2026, stats) { y, s -> b.build(y, s, now) }

            // December, the year still running: worked out, not kept; opened again within ten minutes it's the same one.
            val first = open()
            assertEquals(1, b.builds)
            assertTrue(RewindStore(dir).years().isEmpty())
            now += 5 * 60_000L
            assertEquals(first, open())
            assertEquals(1, b.builds)
            // Later on, with more listening: worked out again.
            now += 6 * 60_000L
            open()
            assertEquals(2, b.builds)
            // "Count history from" switched on mid-December: at once, not after ten minutes; and off again: at once too.
            open(stats(com.vinz.music.data.imports.ImportSource.SPOTIFY))
            assertEquals(3, b.builds)
            open()
            assertEquals(4, b.builds)

            // 1 January, 05:00: the year's over but its last plays may still be on their way: not kept yet.
            now = ZonedDateTime.of(2027, 1, 1, 5, 0, 0, 0, zone).toInstant().toEpochMilli()
            open()
            assertTrue(RewindStore(dir).years().isEmpty())
            // 07:00: kept, and from then on that one, however the history changes.
            now = ZonedDateTime.of(2027, 1, 1, 7, 0, 0, 0, zone).toInstant().toEpochMilli()
            val kept = open()
            assertEquals(listOf(2026), RewindStore(dir).years())
            val builds = b.builds
            now += 30L * 86_400_000L
            assertEquals(kept, open())
            assertEquals(builds, b.builds)
            // With an imported history counted, the kept one (Vinz's plays only) isn't the story: worked out afresh, not kept.
            open(stats(com.vinz.music.data.imports.ImportSource.SPOTIFY))
            assertEquals(builds + 1, b.builds)
            assertEquals(kept, RewindStore(dir).load(2026))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aFinishedYearWithoutPlaysIsNeverKept() = kotlinx.coroutines.runBlocking {
        val zone = ZoneId.of("UTC")
        val dir = Files.createTempDirectory("rewind").toFile()
        try {
            val now = ZonedDateTime.of(2027, 3, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
            val keeper = com.vinz.music.data.RewindKeeper(RewindStore(dir), { now }, { zone })
            val b = Built(plays = 0)
            keeper.snapshot(2026, stats()) { y, s -> b.build(y, s, now) }
            assertTrue(RewindStore(dir).years().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    companion object {
        fun snapshot0(year: Int, frozenAt: Long) = RewindSnapshot(
            year = year, frozenAt = frozenAt, totalMs = 0, plays = 0, activeDays = 0, songCount = 0, artistCount = 0,
            songs = emptyList(), artists = emptyList(), genres = emptyList(), byHour = List(24) { 0L }, byWeekday = List(7) { 0L },
            longestStreak = 0, personality = Personality("", ""),
        )
    }
}
