package com.vinz.music

import androidx.room.Room
import com.vinz.music.data.MilestoneKind
import com.vinz.music.data.MilestoneWatch
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.SongPlays
import com.prism.music.data.db.toEntity
import com.vinz.music.data.imports.PlayStats
import com.vinz.music.data.imports.VinzPlays
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * 1.8.5: the milestone notice after a play reads the whole history only when the play may have reached something (a
 * song's 25th play, 10 hours, 100 songs: [com.vinz.music.data.Milestones.mayHaveReached]), and once a day for streaks
 * and the year's artist; never every half hour of listening.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MilestoneWatchTest {
    private lateinit var db: PrismDatabase
    private lateinit var stats: Counting
    private val notices = ArrayList<String>()
    private lateinit var watch: MilestoneWatch
    private val zone = ZoneId.systemDefault()
    private fun at(day: LocalDate, h: Int = 12, min: Int = 0) = day.atTime(h, min).atZone(zone).toInstant().toEpochMilli()
    private val day = LocalDate.of(2026, 3, 10)

    /** Counts the reads of the whole history: every play, and every song played. */
    private class Counting(val inner: PlayStats) : PlayStats by inner {
        var full = 0
        override suspend fun events(from: Long, to: Long): List<PlayEventEntity> = inner.events(from, to).also { if (from <= 0) full++ }
        override suspend fun topSongs(from: Long, to: Long, limit: Int): List<SongPlays> = inner.topSongs(from, to, limit).also { if (from <= 0) full++ }
    }

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, PrismDatabase::class.java).allowMainThreadQueries().build()
        stats = Counting(VinzPlays(db.plays()))
        val prefs = app.getSharedPreferences("milestones-test", 0).also { it.edit().clear().commit() }
        watch = MilestoneWatch({ stats }, { prefs }, { true }, { notices += it }, CoroutineScope(Dispatchers.Unconfined))
        runBlocking {
            for (id in listOf("a", "b")) db.songs().upsert(Song(id, "Song $id", listOf(ArtistRef("Artist $id", "UC$id")), null, 200).toEntity())
        }
    }

    @After fun tearDown() = db.close()

    private fun play(song: String, t: Long) = runBlocking { db.plays().insert(PlayEventEntity(songId = song, timestamp = t, playedMs = 180_000)) }

    @Test fun aPlayThatCantHaveReachedAnythingReadsNoHistory() = runBlocking {
        (0 until 10).forEach { play("a", at(day, 8) + it * 200_000L) }
        watch.tell(at(day, 11))                   // the first look
        stats.full = 0
        play("a", at(day, 11, 30))                // the 11th: no mark
        play("b", at(day, 11, 35))                // a second song: no mark
        assertTrue(watch.tell(at(day, 12)).isEmpty())
        play("a", at(day, 12, 30))
        assertTrue(watch.tell(at(day, 13)).isEmpty())
        assertEquals("the whole history wasn't read", 0, stats.full)
        assertTrue(notices.isEmpty())
    }

    @Test fun aPlayThatReachesAMarkStillNotifiesOnce() = runBlocking {
        (0 until 24).forEach { play("a", at(day, 8) + it * 200_000L) }
        watch.tell(at(day, 11))
        play("a", at(day, 11, 30))                // the 25th
        val told = watch.tell(at(day, 12))
        assertEquals(listOf(MilestoneKind.SONG_PLAYS to 25), told.map { it.milestone.kind to it.milestone.value })
        assertEquals(1, notices.size)
        play("a", at(day, 12, 30))                // the 26th: nothing new
        assertTrue(watch.tell(at(day, 13)).isEmpty())
        assertEquals(1, notices.size)
    }

    /** Streaks aren't in the cheap check: the first look of a day reads the history once, and finds a week in a row. */
    @Test fun aStreakIsFoundByTheDaysOneRead() = runBlocking {
        (0 until 6).forEach { play("a", at(day.plusDays(it.toLong()), 9)) }
        watch.tell(at(day.plusDays(5), 10))       // the first look, on the sixth day
        stats.full = 0
        play("a", at(day.plusDays(6), 9))          // the seventh day in a row
        val told = watch.tell(at(day.plusDays(6), 10))
        assertEquals(listOf(MilestoneKind.STREAK to 7), told.map { it.milestone.kind to it.milestone.value })
        assertEquals(1, notices.size)
        val reads = stats.full
        play("a", at(day.plusDays(6), 11))
        watch.tell(at(day.plusDays(6), 12))
        assertEquals("once that day", reads, stats.full)
    }
}
