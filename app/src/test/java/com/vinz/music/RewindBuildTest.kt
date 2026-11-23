package com.vinz.music

import androidx.room.Room
import com.vinz.music.data.RewindBuild
import com.vinz.music.data.RewindCalendar
import com.vinz.music.data.StoryPart
import com.vinz.music.data.StoryParts
import com.prism.music.data.db.ImportedPlayEntity
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.SongEntity
import com.vinz.music.data.imports.CountedPlays
import com.vinz.music.data.imports.ImportSource
import com.vinz.music.data.imports.VinzPlays
import com.vinz.music.ui.screens.Period
import com.vinz.music.ui.screens.ReplayStory
import com.vinz.music.ui.screens.facts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * A year's Rewind worked out from a real database (1.8.5): a huge history ([BigHistory], 60,000 plays over three years plus
 * 10,000 imported), an imported-only year, ten plays, and nothing at all. The huge one has to stay quick.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.NATIVE)
class RewindBuildTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PrismDatabase::class.java).allowMainThreadQueries().build()
    private val oldZone: TimeZone = TimeZone.getDefault()
    private val zone = ZoneId.of("Europe/Berlin")

    @After fun close() {
        TimeZone.setDefault(oldZone)
        db.close()
    }

    /** The story the snapshot makes, as [com.vinz.music.ui.screens.ReplayStory] would get it. */
    private fun story(s: com.vinz.music.data.RewindSnapshot) = ReplayStory(
        period = Period.year(s.year), title = "${s.year}", totalMs = s.totalMs, previousMs = s.previousMs, previousLabel = null,
        plays = s.plays, activeDays = s.activeDays, songCount = s.songCount, artistCount = s.artistCount, songs = s.songs, artists = s.artists,
        genres = s.genres.map { it.genre to it.ms }, genreSongs = emptyMap(), habits = s.habits(), newArtists = s.newArtists, personality = s.personality,
        topArtistImage = null, topArtistType = com.vinz.music.ui.theme.ArtistTypography.standard, rewind = true, months = s.months, topAlbum = s.topAlbum,
        firstSong = s.firstSong, seasons = s.seasons, vinz = s.vinz, days = s.days, weekHours = s.weekHours, hourSongs = s.hourSongs,
        milestones = s.milestones, finds = s.finds, findCount = s.findCount,
    )

    @Test fun aHugeYearIsQuickAndAddsUp() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        val fillStart = System.nanoTime()
        BigHistory.fill(db)
        val fillMs = (System.nanoTime() - fillStart) / 1_000_000
        val now = BigHistory.END
        // The fixture's songs were all first heard in 2023; three new ones came in 2026 on a radio and stayed.
        db.songs().upsertAll((0 until 3).map { SongEntity(id = "new$it", title = "New $it", artistName = "Newcomer", artistId = null, albumTitle = null, albumId = null, durationSec = 200, thumbnail = null, primaryArtist = "Newcomer") })
        val june = ZonedDateTime.of(2026, 6, 1, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        for (i in 0 until 3) for (p in 0 until 4 + i) db.plays().insert(PlayEventEntity(songId = "new$i", timestamp = june + i * 3_600_000L + p * 86_400_000L, playedMs = 190_000, source = if (p == 0) "radio" else "library"))
        val report = StringBuilder("fill $fillMs ms\n")
        for ((name, stats) in listOf("Vinz" to VinzPlays(db.plays()), "Vinz+Spotify" to CountedPlays(listOf(ImportSource.SPOTIFY), db.counted(), db.plays()))) {
            // Twice: the first run pays for the JVM warming up.
            var s = RewindBuild.build(2026, stats, now, zone)
            val t = System.nanoTime()
            s = RewindBuild.build(2026, stats, now, zone)
            val ms = (System.nanoTime() - t) / 1_000_000
            val (from, to) = RewindCalendar.bounds(2026, zone).let { (a, b) -> a to minOf(b, now) }
            report.append("$name 2026: $ms ms, ${s.plays} plays, ${s.milestones.size} milestones, ${s.findCount} finds, ${s.days.count { it > 0 }} days, json ${kotlinx.serialization.json.Json.encodeToString(com.vinz.music.data.RewindSnapshot.serializer(), s).length / 1024} KB\n")
            assertTrue("$name took $ms ms\n$report", ms < MAX_MS)

            assertEquals(stats.count(from, to), s.plays)
            assertEquals(stats.totalMs(from, to), s.totalMs)
            // The calendar and the clock hold every millisecond of the year, no more.
            assertEquals(s.totalMs, s.days.sum())
            assertEquals(s.totalMs, s.weekHours.sum())
            assertEquals(s.byHour.sum(), s.weekHours.sum())
            assertEquals(365, s.days.size)
            // The clock's busiest hour is the habits' one, and its songs are that hour's (same zone, same rules).
            val peak = s.habits().peakHour
            assertEquals(stats.topSongsInHour(from, to, peak, 1).first().songId, s.hourSongs.first().songId)
            assertTrue(s.milestones.isNotEmpty() && s.milestones.all { it.at in from..to })
            assertEquals(listOf("new2", "new1", "new0"), s.finds.map { it.songId })
            assertEquals(3, s.findCount)
            val parts = StoryParts.pick(story(s).facts())
            assertTrue("$name: $parts", listOf(StoryPart.CALENDAR, StoryPart.CLOCK, StoryPart.FOUND, StoryPart.MILESTONES).all { it in parts })
        }
        println("[rewind-build] $report")
    }

    private fun songRow(i: Int) = SongEntity(id = "s$i", title = "Song $i", artistName = "Artist $i", artistId = "UC$i", albumTitle = null, albumId = null, durationSec = 200, thumbnail = null, primaryArtist = "Artist $i")

    @Test fun aYearWithOnlyImportedHistory() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        db.songs().upsertAll((0 until 20).map(::songRow))
        // Spotify plays every day of 2026 up to November, no Vinz play at all.
        val start = ZonedDateTime.of(2026, 1, 1, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
        db.imports().insertPlays((0 until 300).map { d ->
            ImportedPlayEntity(origin = ImportSource.SPOTIFY.key, timestamp = start + d * 86_400_000L + (d % 5) * 3_600_000L, playedMs = 200_000, title = "Song ${d % 20}", artist = "Artist ${d % 20}", songKey = "k${d % 20}", matchedId = "s${d % 20}")
        })
        val now = ZonedDateTime.of(2026, 12, 2, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val vinzOnly = RewindBuild.build(2026, VinzPlays(db.plays()), now, zone)
        assertEquals(0, vinzOnly.plays)
        assertEquals(listOf(StoryPart.INTRO, StoryPart.MINUTES, StoryPart.SUMMARY), StoryParts.pick(story(vinzOnly).facts()))

        val s = RewindBuild.build(2026, CountedPlays(listOf(ImportSource.SPOTIFY), db.counted(), db.plays()), now, zone)
        assertEquals(300, s.plays)
        assertEquals(300 * 200_000L, s.days.sum())
        assertEquals(300, s.days.count { it > 0 })
        // Imported plays carry their source's name, not a Vinz feature: nothing was "found for you".
        assertTrue(s.finds.isEmpty())
        val parts = StoryParts.pick(story(s).facts())
        assertTrue(parts.toString(), StoryPart.CALENDAR in parts && StoryPart.CLOCK in parts && StoryPart.FOUND !in parts)
    }

    @Test fun aYearWithTenPlaysOrNone() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        db.songs().upsertAll((0 until 10).map(::songRow))
        val start = ZonedDateTime.of(2026, 3, 1, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
        (0 until 10).forEach { db.plays().insert(PlayEventEntity(songId = "s$it", timestamp = start + it * 86_400_000L, playedMs = 150_000, source = "radio")) }
        val now = ZonedDateTime.of(2026, 12, 2, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val s = RewindBuild.build(2026, VinzPlays(db.plays()), now, zone)
        assertEquals(10, s.plays)
        val parts = StoryParts.pick(story(s).facts())
        // 25 minutes: the short story, none of the new slides.
        assertEquals(listOf(StoryPart.INTRO, StoryPart.MINUTES, StoryPart.TOP_ARTIST, StoryPart.TOP_SONG, StoryPart.SUMMARY), parts)

        // A year nobody listened in (2025 here) still makes a snapshot, empty.
        val empty = RewindBuild.build(2025, VinzPlays(db.plays()), now, zone)
        assertEquals(0, empty.plays)
        assertTrue(empty.hourSongs.isEmpty() && empty.milestones.isEmpty() && empty.finds.isEmpty() && empty.days.all { it == 0L })
        assertFalse(StoryPart.CLOCK in StoryParts.pick(story(empty).facts()))
    }

    companion object { const val MAX_MS = 3_000L }
}
