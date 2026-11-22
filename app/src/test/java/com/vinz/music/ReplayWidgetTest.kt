package com.vinz.music

import androidx.room.Room
import com.prism.music.data.db.AlbumPlays
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.FirstPlay
import com.prism.music.data.db.MonthTotal
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.SongPlays
import com.prism.music.data.db.SourcePlays
import com.prism.music.data.db.toEntity
import com.vinz.music.data.imports.CountedPlays
import com.vinz.music.data.imports.HistoryImporter
import com.vinz.music.data.imports.ImportKeys
import com.vinz.music.data.imports.ImportSource
import com.vinz.music.data.imports.MatchResult
import com.vinz.music.data.imports.MatchStatus
import com.vinz.music.data.imports.ParsedPlay
import com.vinz.music.data.imports.PlayStats
import com.vinz.music.data.imports.VinzPlays
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import com.vinz.music.widget.ReplayWeek
import com.vinz.music.widget.ReplayWidgets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

/**
 * The Replay widgets' week (1.8.5): Replay's own "This week" stretch, the top song (uploads of one song together, as
 * Replay lists them) and artist, from whatever Replay counts (Vinz's plays, or with imported histories), nothing played
 * → the empty state, and history paused → the last week shown, never an error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ReplayWidgetTest {
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    /** Wednesday 11 November 2026, 15:00 UTC. */
    private val now = at("2026-11-11T15:00:00Z")

    private fun plays(id: String, title: String, artist: String, plays: Int, min: Int, primary: String = artist) =
        SongPlays(id, plays, min * 60_000L, title, artist, "https://i/$id.jpg", null, 200, "UC$artist", primary)

    /** Answers the two questions the widget asks, and notes the stretch it was asked for. */
    private class FakeStats(val songs: List<SongPlays>, val artists: List<ArtistPlays>) : PlayStats {
        var asked: Pair<Long, Long>? = null
        override val sources: List<ImportSource> = emptyList()
        override suspend fun topSongs(from: Long, to: Long, limit: Int): List<SongPlays> { asked = from to to; return songs.take(limit) }
        override suspend fun topArtists(from: Long, to: Long, limit: Int): List<ArtistPlays> = artists.take(limit)
        override suspend fun totalMs(from: Long, to: Long): Long = error("not asked")
        override suspend fun count(from: Long, to: Long): Int = error("not asked")
        override suspend fun monthly(from: Long, to: Long): List<MonthTotal> = error("not asked")
        override suspend fun byHour(from: Long, to: Long): List<com.prism.music.data.db.HourTotal> = error("not asked")
        override suspend fun byDay(from: Long, to: Long): List<com.prism.music.data.db.DayTotal> = error("not asked")
        override suspend fun topSongsInHour(from: Long, to: Long, hour: Int, limit: Int): List<SongPlays> = error("not asked")
        override suspend fun activeDays(from: Long, to: Long): Int = error("not asked")
        override suspend fun firstEvent(): Long? = error("not asked")
        override suspend fun events(from: Long, to: Long): List<PlayEventEntity> = error("not asked")
        override suspend fun songCount(from: Long, to: Long): Int = error("not asked")
        override suspend fun artistCount(from: Long, to: Long): Int = error("not asked")
        override suspend fun newArtists(from: Long, to: Long): List<ArtistPlays> = error("not asked")
        override suspend fun topAlbums(from: Long, to: Long, limit: Int): List<AlbumPlays> = error("not asked")
        override suspend fun firstPlay(from: Long, to: Long): FirstPlay? = error("not asked")
        override suspend fun bySource(from: Long, to: Long): List<SourcePlays> = error("not asked")
        override suspend fun recognizedIn(from: Long, to: Long): Int = error("not asked")
        override suspend fun blendsIn(from: Long, to: Long): Int = error("not asked")
        override suspend fun releasesFoundIn(from: Long, to: Long): Int = error("not asked")
    }

    @Test fun weekIsReplaysThisWeekChip() = runBlocking {
        val s = FakeStats(listOf(plays("a", "A", "X", 1, 3)), emptyList())
        ReplayWidgets.week(s, now, ZoneOffset.UTC, DayOfWeek.MONDAY)
        assertEquals("Monday's midnight to now", at("2026-11-09T00:00:00Z") to now, s.asked)
        // The same as Replay's own chip (its range()), however the region starts the week.
        assertEquals(com.vinz.music.ui.screens.range(com.vinz.music.ui.screens.Period.WEEK, now, ZoneOffset.UTC, DayOfWeek.MONDAY), s.asked)
        ReplayWidgets.week(s, now, ZoneOffset.UTC, DayOfWeek.SUNDAY)
        assertEquals("a week that starts on Sunday", at("2026-11-08T00:00:00Z") to now, s.asked)
    }

    @Test fun picksTheTopSongAndArtist() = runBlocking {
        val s = FakeStats(
            listOf(
                plays("b1", "Beat It", "Michael Jackson", 3, 12),
                plays("x", "Starboy", "The Weeknd", 4, 15),
                // A second upload of Beat It: together they're the week's top song, as in Replay's list.
                plays("b2", "Beat It (Single Version)", "Michael Jackson", 2, 8),
            ),
            listOf(ArtistPlays("Michael Jackson", 5, 20 * 60_000L, "https://i/b1.jpg", "UCmj"), ArtistPlays("The Weeknd", 4, 15 * 60_000L, null, null)),
        )
        val w = ReplayWidgets.week(s, now, ZoneOffset.UTC, DayOfWeek.MONDAY) { id, name -> if (id == "UCmj") "https://photo/$name" else null }
        assertEquals("b1", w.song?.songId)
        assertEquals(5, w.song?.plays)
        assertEquals(20 * 60_000L, w.song?.ms)
        assertEquals("Michael Jackson", w.artist?.artistName)
        assertEquals("the stored photo, not a song's cover", "https://photo/Michael Jackson", w.artistPhoto)
        assertFalse(w.isEmpty)
    }

    @Test fun nothingPlayedIsTheEmptyState() = runBlocking {
        val w = ReplayWidgets.week(FakeStats(emptyList(), emptyList()), now, ZoneOffset.UTC, DayOfWeek.MONDAY)
        assertTrue(w.isEmpty)
        assertNull(w.artist)
        assertEquals("never last week's song when history is on", w, ReplayWidgets.shown(w, ReplayWeek(plays("old", "Old", "Y", 1, 4)), paused = false))
    }

    @Test fun historyPausedOrUnreadableShowsTheLastWeek() {
        val last = ReplayWeek(plays("a", "A", "X", 2, 6))
        val empty = ReplayWeek()
        assertEquals("paused: nothing is kept, so the last result stays", last, ReplayWidgets.shown(empty, last, paused = true))
        assertEquals("paused with nothing shown before: the empty state", empty, ReplayWidgets.shown(empty, null, paused = true))
        assertEquals("the plays couldn't be read: the last result, no error", last, ReplayWidgets.shown(null, last, paused = false))
        assertEquals(empty, ReplayWidgets.shown(null, null, paused = false))
        val fresh = ReplayWeek(plays("b", "B", "Z", 1, 3))
        assertEquals("paused, but this week has plays: those", fresh, ReplayWidgets.shown(fresh, last, paused = true))
    }

    @Test fun songLineSaysPlaysAndTime() {
        assertEquals("12 plays · 40 min", ReplayWidgets.songStat(plays("a", "A", "X", 12, 40), hours = false))
        assertEquals("3 plays · 1h 30m", ReplayWidgets.songStat(plays("a", "A", "X", 3, 90), hours = true))
    }

    /** In the database: Vinz's own plays only, or with Spotify's counted ("Count history from"), as Replay asks. */
    @Test fun followsTheHistoriesReplayCounts() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PrismDatabase::class.java).allowMainThreadQueries().build()
        try {
            fun song(id: String, title: String, artist: String) = Song(id, title, listOf(ArtistRef(artist, "UC$id")), null, 200, thumbnail = "https://i/$id.jpg")
            db.songs().upsert(song("p1", "Starboy", "The Weeknd").toEntity())
            // This week (from Monday 9 November): Starboy, 4 minutes in Vinz. Last week: an older song, longer.
            db.plays().insert(PlayEventEntity(songId = "p1", timestamp = at("2026-11-10T10:00:00Z"), playedMs = 240_000))
            db.songs().upsert(song("p0", "Creep", "Radiohead").toEntity())
            db.plays().insert(PlayEventEntity(songId = "p0", timestamp = at("2026-11-05T10:00:00Z"), playedMs = 900_000))
            // Spotify's data download: Anti-Hero three times this week.
            val importer = HistoryImporter(db)
            val session = importer.session(ImportSource.SPOTIFY)
            listOf("2026-11-09T08:00:00Z", "2026-11-10T08:00:00Z", "2026-11-11T08:00:00Z").forEach {
                session.add(ParsedPlay(timestamp = at(it), playedMs = 200_000, title = "Anti-Hero", artist = "Taylor Swift"))
            }
            session.finish()
            val ah = song("ah", "Anti-Hero", "Taylor Swift")
            importer.applyMatch(db.imports().match(ImportKeys.songKey("Anti-Hero", "Taylor Swift"))!!, MatchResult(MatchStatus.SURE, ah, listOf(ah)))

            val vinzOnly = ReplayWidgets.week(VinzPlays(db.plays()), now, ZoneOffset.UTC, DayOfWeek.MONDAY)
            assertEquals("Vinz's own plays: Starboy (last week's Creep isn't this week)", "p1", vinzOnly.song?.songId)
            assertEquals("The Weeknd", vinzOnly.artist?.artistName)
            val withSpotify = ReplayWidgets.week(CountedPlays(listOf(ImportSource.SPOTIFY), db.counted(), db.plays()), now, ZoneOffset.UTC, DayOfWeek.MONDAY)
            assertEquals("counting Spotify: Anti-Hero", "ah", withSpotify.song?.songId)
            assertEquals("Taylor Swift", withSpotify.artist?.artistName)
        } finally {
            db.close()
        }
    }

    /**
     * Android 11 and older: the layout comes from the size the launcher says. One that hasn't said (0) gets the widget's
     * own: before, the wide widget then drew the small layout. A short song widget gets the compact one.
     */
    @Test fun olderLaunchersGetTheLayoutForTheirSize() {
        val wide = R.layout.widget_replay
        val small = R.layout.widget_replay_small
        val compact = R.layout.widget_replay_compact
        assertEquals("wide widget, no size said", wide, ReplayWidgets.layoutFor(wideWidget = true, minWidth = 0, height = 0))
        assertEquals("song widget, no size said", small, ReplayWidgets.layoutFor(wideWidget = false, minWidth = 0, height = 0))
        assertEquals(wide, ReplayWidgets.layoutFor(wideWidget = false, minWidth = 300, height = 120))
        assertEquals("the wide one made narrow", small, ReplayWidgets.layoutFor(wideWidget = true, minWidth = 180, height = 180))
        assertEquals(compact, ReplayWidgets.layoutFor(wideWidget = false, minWidth = 120, height = 110))
        assertEquals(small, ReplayWidgets.layoutFor(wideWidget = false, minWidth = 120, height = ReplayWidgets.TALL_DP))
    }

    /** Home screen only (never on the lock screen), no update period of Android's own, and in the manifest. */
    @Test fun providersAreHomeScreenOnly() {
        for (name in listOf("widget_replay_info.xml", "widget_replay_small_info.xml")) {
            val xml = File("src/main/res/xml/$name").readText()
            assertTrue(name, xml.contains("android:widgetCategory=\"home_screen\""))
            assertFalse(name, xml.contains("keyguard"))
            assertTrue(name, xml.contains("android:updatePeriodMillis=\"0\""))
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains(".widget.ReplayWidget\"") && manifest.contains(".widget.ReplayWideWidget\""))
    }
}
