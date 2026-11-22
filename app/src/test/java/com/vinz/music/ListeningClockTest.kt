package com.vinz.music

import androidx.room.Room
import com.prism.music.data.db.DayTotal
import com.prism.music.data.db.HourTotal
import com.prism.music.data.db.ImportedPlayEntity
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.SongEntity
import com.vinz.music.data.imports.CountedPlays
import com.vinz.music.data.imports.ImportSource
import com.vinz.music.data.imports.PlayStats
import com.vinz.music.data.imports.VinzPlays
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.random.Random

/**
 * Replay's listening clock and calendar (1.8.5): time per weekday and hour, per day, and the top songs of one hour,
 * grouped in SQL in the listener's own time zone. Checked against the same sums done in Kotlin with java.time, for
 * Vinz's plays alone and with imported Spotify plays, and at the edges: midnight, Sunday to Monday, both DST change
 * days, a half-hour zone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ListeningClockTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PrismDatabase::class.java).allowMainThreadQueries().build()
    private val oldZone: TimeZone = TimeZone.getDefault()

    @After fun close() {
        TimeZone.setDefault(oldZone)
        db.close()
    }

    private fun zone(id: String): ZoneId { TimeZone.setDefault(TimeZone.getTimeZone(id)); return ZoneId.of(id) }

    private val vinz get() = VinzPlays(db.plays())
    private val withSpotify get() = CountedPlays(listOf(ImportSource.SPOTIFY), db.counted(), db.plays())

    /** A play as Replay counts it: song (null = an imported play not matched), time, length. */
    private data class P(val songId: String?, val at: Long, val ms: Long)

    private fun expectedHours(plays: List<P>, z: ZoneId): List<HourTotal> =
        plays.groupBy { Instant.ofEpochMilli(it.at).atZone(z).let { t -> (t.dayOfWeek.value - 1) to t.hour } }
            .map { (k, v) -> HourTotal(k.first, k.second, v.sumOf { it.ms }, v.size) }
            .sortedWith(compareBy({ it.weekday }, { it.hour }))

    private fun expectedDays(plays: List<P>, z: ZoneId): List<DayTotal> =
        plays.groupBy { Instant.ofEpochMilli(it.at).atZone(z).toLocalDate().toString() }
            .map { (d, v) -> DayTotal(d, v.sumOf { it.ms }, v.size) }.sortedBy { it.day }

    /** songId to (plays, ms), most listened first. */
    private fun expectedTop(plays: List<P>, z: ZoneId, hour: Int): List<Triple<String, Int, Long>> =
        plays.filter { it.songId != null && Instant.ofEpochMilli(it.at).atZone(z).hour == hour }.groupBy { it.songId!! }
            .map { (id, v) -> Triple(id, v.size, v.sumOf { it.ms }) }
            .sortedWith(compareByDescending<Triple<String, Int, Long>> { it.third }.thenByDescending { it.second })

    private fun hours(s: PlayStats, from: Long, to: Long) = runBlocking { s.byHour(from, to) }.sortedWith(compareBy({ it.weekday }, { it.hour }))
    private fun days(s: PlayStats, from: Long, to: Long) = runBlocking { s.byDay(from, to) }

    /** The query's top songs agree with Kotlin's: the same times in the same order, each song's numbers its own. */
    private fun assertTop(expected: List<Triple<String, Int, Long>>, s: PlayStats, from: Long, to: Long, hour: Int, limit: Int) {
        val got = runBlocking { s.topSongsInHour(from, to, hour, limit) }
        assertEquals("hour $hour: how many", minOf(limit, expected.size), got.size)
        assertEquals("hour $hour: time, most first", expected.take(limit).map { it.third }, got.map { it.ms })
        val byId = expected.associateBy { it.first }
        got.forEach { g ->
            val e = byId[g.songId] ?: error("hour $hour: ${g.songId} isn't played then")
            assertEquals("hour $hour: ${g.songId}", e.second to e.third, g.plays to g.ms)
            assertTrue("hour $hour: ${g.songId} has its song", g.title.isNotEmpty())
        }
    }

    private suspend fun songs(n: Int) =
        db.songs().upsertAll((0 until n).map { SongEntity("s$it", "Title $it", "Artist ${it % 7}", "UC${it % 7}", "Album", null, 200, null, primaryArtist = "Artist ${it % 7}") })

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    // --- Random histories against plain Kotlin ---

    /** ~3,000 Vinz plays and ~1,200 imported over two years, a DST zone; and the same asked for a part of it. */
    @Test fun matchesKotlinForVinzAndWithSpotify() = runBlocking {
        val z = zone("America/New_York")
        songs(60)
        val r = Random(7)
        val from = at("2025-01-01T00:00:00Z"); val to = at("2026-12-31T00:00:00Z")
        val vinzPlays = (0 until 3_000).map { P("s${r.nextInt(60)}", from + r.nextLong(to - from), 10_000 + r.nextLong(300_000)) }
        vinzPlays.forEach { db.plays().insert(PlayEventEntity(songId = it.songId!!, timestamp = it.at, playedMs = it.ms, excluded = r.nextInt(9) == 0)) }
        val imported = (0 until 1_200).map { i ->
            ImportedPlayEntity(
                origin = "spotify", timestamp = from + i * ((to - from) / 1_200) + r.nextLong(1_000), playedMs = if (i % 13 == 0) 4_000 else 20_000 + r.nextLong(200_000),
                estimated = i % 17 == 0, title = "Title $i", artist = "A", songKey = "k$i", matchedId = if (i % 5 == 0) null else "s${r.nextInt(60)}",
            )
        }
        db.imports().insertPlays(imported)
        // What counted_plays counts: 10 s and over, and an estimated length only once the song is known.
        val importedCounted = imported.filter { it.playedMs >= 10_000 && (it.matchedId != null || !it.estimated) }.map { P(it.matchedId, it.timestamp, it.playedMs) }
        assertTrue(importedCounted.any { it.songId == null })
        val all = vinzPlays + importedCounted

        for ((a, b) in listOf(from to to, at("2025-03-01T00:00:00Z") to at("2025-11-15T12:00:00Z"), 0L to Long.MAX_VALUE)) {
            fun inRange(l: List<P>) = l.filter { it.at in a..b }
            assertEquals("Vinz byHour $a..$b", expectedHours(inRange(vinzPlays), z), hours(vinz, a, b))
            assertEquals("Vinz byDay $a..$b", expectedDays(inRange(vinzPlays), z), days(vinz, a, b))
            assertEquals("the view with no source counted = Vinz", hours(vinz, a, b), hours(CountedPlays(emptyList(), db.counted(), db.plays()), a, b))
            assertEquals("…and its days", days(vinz, a, b), days(CountedPlays(emptyList(), db.counted(), db.plays()), a, b))
            assertEquals("with Spotify byHour $a..$b", expectedHours(inRange(all), z), hours(withSpotify, a, b))
            assertEquals("with Spotify byDay $a..$b", expectedDays(inRange(all), z), days(withSpotify, a, b))
            for (h in listOf(0, 7, 13, 23)) {
                assertTop(expectedTop(inRange(vinzPlays), z, h), vinz, a, b, h, 10)
                assertTop(expectedTop(inRange(all), z, h), withSpotify, a, b, h, 10)
            }
        }
        // Every play lands in exactly one hour and one day.
        assertEquals(all.size, hours(withSpotify, from, to).sumOf { it.plays })
        assertEquals(all.sumOf { it.ms }, days(withSpotify, from, to).sumOf { it.ms })
    }

    // --- Edges ---

    /** One second before midnight is Sunday 23:00; midnight is Monday 00:00, the next week's first hour. */
    @Test fun midnightAndSundayToMonday() = runBlocking {
        val z = zone("Europe/Berlin")
        songs(2)
        val sunday = ZonedDateTime.of(2026, 6, 14, 23, 59, 59, 0, z)
        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)
        val monday = sunday.plusSeconds(1)
        db.plays().insert(PlayEventEntity(songId = "s0", timestamp = sunday.toInstant().toEpochMilli(), playedMs = 60_000))
        db.plays().insert(PlayEventEntity(songId = "s1", timestamp = monday.toInstant().toEpochMilli(), playedMs = 90_000))
        for (s in listOf(vinz, withSpotify)) {
            assertEquals(listOf(HourTotal(0, 0, 90_000, 1), HourTotal(6, 23, 60_000, 1)), hours(s, 0, Long.MAX_VALUE))
            assertEquals(listOf(DayTotal("2026-06-14", 60_000, 1), DayTotal("2026-06-15", 90_000, 1)), days(s, 0, Long.MAX_VALUE))
            assertEquals(listOf("s0"), s.topSongsInHour(0, Long.MAX_VALUE, 23, 5).map { it.songId })
            assertEquals(listOf("s1"), s.topSongsInHour(0, Long.MAX_VALUE, 0, 5).map { it.songId })
        }
    }

    /** Europe/Berlin's spring and autumn change days: the skipped hour holds nothing, the repeated one holds both plays. */
    @Test fun daylightSavingChangeDays() = runBlocking {
        zone("Europe/Berlin")
        songs(3)
        listOf(
            "2026-03-29T00:30:00Z" to "s0", // 01:30 CET
            "2026-03-29T01:30:00Z" to "s1", // 03:30 CEST (02:00 never happened)
            "2026-10-24T22:30:00Z" to "s0", // 00:30 CEST, Sunday the 25th
            "2026-10-25T00:30:00Z" to "s1", // 02:30 CEST
            "2026-10-25T01:30:00Z" to "s2", // 02:30 CET, the same hour again
            "2026-10-25T22:59:59Z" to "s2", // 23:59:59 CET
            "2026-10-25T23:00:00Z" to "s2", // 00:00 CET, Monday the 26th
        ).forEach { (t, s) -> db.plays().insert(PlayEventEntity(songId = s, timestamp = at(t), playedMs = 30_000)) }
        for (s in listOf(vinz, withSpotify)) {
            assertEquals(
                listOf(HourTotal(0, 0, 30_000, 1), HourTotal(6, 0, 30_000, 1), HourTotal(6, 1, 30_000, 1), HourTotal(6, 2, 60_000, 2), HourTotal(6, 3, 30_000, 1), HourTotal(6, 23, 30_000, 1)),
                hours(s, 0, Long.MAX_VALUE),
            )
            assertEquals(
                listOf(DayTotal("2026-03-29", 60_000, 2), DayTotal("2026-10-25", 120_000, 4), DayTotal("2026-10-26", 30_000, 1)),
                days(s, 0, Long.MAX_VALUE),
            )
            assertEquals(setOf("s1", "s2"), s.topSongsInHour(0, Long.MAX_VALUE, 2, 5).map { it.songId }.toSet())
            // A range starting inside summer time still knows when it ends.
            assertEquals(listOf(DayTotal("2026-10-25", 120_000, 4)), days(s, at("2026-10-24T22:00:00Z"), at("2026-10-25T22:59:59Z")))
        }
    }

    /** India is five and a half hours ahead: 18:29:59 UTC is 23:59:59, 18:30 UTC is the next day's midnight. */
    @Test fun halfHourZone() = runBlocking {
        zone("Asia/Kolkata")
        songs(2)
        db.plays().insert(PlayEventEntity(songId = "s0", timestamp = at("2026-06-14T18:29:59Z"), playedMs = 40_000))
        db.plays().insert(PlayEventEntity(songId = "s1", timestamp = at("2026-06-14T18:30:00Z"), playedMs = 50_000))
        db.plays().insert(PlayEventEntity(songId = "s1", timestamp = at("2026-06-15T03:45:00Z"), playedMs = 70_000)) // 09:15
        for (s in listOf(vinz, withSpotify)) {
            assertEquals(listOf(HourTotal(0, 0, 50_000, 1), HourTotal(0, 9, 70_000, 1), HourTotal(6, 23, 40_000, 1)), hours(s, 0, Long.MAX_VALUE))
            assertEquals(listOf(DayTotal("2026-06-14", 40_000, 1), DayTotal("2026-06-15", 120_000, 2)), days(s, 0, Long.MAX_VALUE))
            assertEquals(listOf("s1"), s.topSongsInHour(0, Long.MAX_VALUE, 9, 5).map { it.songId })
        }
    }

    /** Unmatched imported plays count for time but have no song to list; too-short ones don't count at all. */
    @Test fun unmatchedImportsCountForTimeOnly() = runBlocking {
        zone("UTC")
        songs(1)
        db.imports().insertPlays(listOf(
            ImportedPlayEntity(origin = "spotify", timestamp = at("2026-05-01T08:10:00Z"), playedMs = 100_000, title = "X", artist = "Y", songKey = "x|y"),
            ImportedPlayEntity(origin = "spotify", timestamp = at("2026-05-01T08:20:00Z"), playedMs = 60_000, title = "T", artist = "A", songKey = "t|a", matchedId = "s0"),
            ImportedPlayEntity(origin = "spotify", timestamp = at("2026-05-01T08:30:00Z"), playedMs = 5_000, title = "T", artist = "A", songKey = "t|a", matchedId = "s0"),
        ))
        assertEquals(listOf(HourTotal(4, 8, 160_000, 2)), hours(withSpotify, 0, Long.MAX_VALUE))
        assertEquals(listOf(DayTotal("2026-05-01", 160_000, 2)), days(withSpotify, 0, Long.MAX_VALUE))
        val top = withSpotify.topSongsInHour(0, Long.MAX_VALUE, 8, 5)
        assertEquals(listOf(Triple("s0", 1, 60_000L)), top.map { Triple(it.songId, it.plays, it.ms) })
        assertEquals("Vinz alone: nothing", emptyList<HourTotal>(), hours(vinz, 0, Long.MAX_VALUE))
    }
}
