package com.vinz.music.data.imports

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.prism.music.data.db.AlbumPlays
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.CountedPlayDao
import com.prism.music.data.db.DayTotal
import com.prism.music.data.db.FirstPlay
import com.prism.music.data.db.HourTotal
import com.prism.music.data.db.MonthTotal
import com.prism.music.data.db.PlayEventDao
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.SongPlays
import com.prism.music.data.db.SourcePlays
import java.time.Instant
import java.time.ZoneId

/**
 * What Replay, Rewind and the Replay card count (1.8.1): Vinz's own plays, plus the imported sources the listener
 * counts ("Count history from"). With none of them, [VinzPlays] asks exactly what Replay always asked, so its numbers
 * can't change; with some, [CountedPlays] asks the same questions of [com.prism.music.data.db.CountedPlay].
 */
interface PlayStats {
    /** The imported sources counted besides Vinz's own plays (empty: Vinz only). */
    val sources: List<ImportSource>

    suspend fun totalMs(from: Long, to: Long): Long
    suspend fun count(from: Long, to: Long): Int
    suspend fun topSongs(from: Long, to: Long, limit: Int): List<SongPlays>
    suspend fun topArtists(from: Long, to: Long, limit: Int): List<ArtistPlays>
    suspend fun monthly(from: Long, to: Long): List<MonthTotal>
    /**
     * Time listened in each hour of each weekday of the listener's own time zone, for the clock. "Own" is the device's
     * current zone and its DST rules, applied to each play as it was then ([LocalClock]); the same for the two below.
     */
    suspend fun byHour(from: Long, to: Long): List<HourTotal>
    /** Time listened on each day (local date), for the calendar; days with nothing are left out. */
    suspend fun byDay(from: Long, to: Long): List<DayTotal>
    /** The most played songs in one hour of the day (0..23, local) within the period, for "tap an hour". */
    suspend fun topSongsInHour(from: Long, to: Long, hour: Int, limit: Int): List<SongPlays>
    suspend fun activeDays(from: Long, to: Long): Int
    suspend fun firstEvent(): Long?
    suspend fun events(from: Long, to: Long): List<PlayEventEntity>
    suspend fun songCount(from: Long, to: Long): Int
    /**
     * How many times [songId] was played up to [to]: the milestones' cheap check after a play (1.8.5). Both real
     * implementations ask SQLite; this fallback (for test fakes) counts [events].
     */
    suspend fun playsOf(songId: String, to: Long): Int = events(0, to).count { it.songId == songId }
    suspend fun artistCount(from: Long, to: Long): Int
    suspend fun newArtists(from: Long, to: Long): List<ArtistPlays>
    suspend fun topAlbums(from: Long, to: Long, limit: Int): List<AlbumPlays>
    suspend fun firstPlay(from: Long, to: Long): FirstPlay?
    suspend fun bySource(from: Long, to: Long): List<SourcePlays>
    suspend fun recognizedIn(from: Long, to: Long): Int
    suspend fun blendsIn(from: Long, to: Long): Int
    suspend fun releasesFoundIn(from: Long, to: Long): Int
}

/** Vinz's own plays only: [PlayEventDao] itself, call for call. */
class VinzPlays(private val d: PlayEventDao) : PlayStats {
    override val sources: List<ImportSource> get() = emptyList()
    override suspend fun totalMs(from: Long, to: Long) = d.totalMs(from, to)
    override suspend fun count(from: Long, to: Long) = d.count(from, to)
    override suspend fun topSongs(from: Long, to: Long, limit: Int) = d.topSongs(from, to, limit)
    override suspend fun topArtists(from: Long, to: Long, limit: Int) = d.topArtists(from, to, limit)
    override suspend fun monthly(from: Long, to: Long) = d.monthly(from, to)
    override suspend fun byHour(from: Long, to: Long) = d.clockHours(LocalClock.hours(LocalClock.VINZ, null, from, to))
    override suspend fun byDay(from: Long, to: Long) = d.clockDays(LocalClock.days(LocalClock.VINZ, null, from, to))
    override suspend fun topSongsInHour(from: Long, to: Long, hour: Int, limit: Int) =
        d.clockSongs(LocalClock.songs(LocalClock.VINZ, null, from, to, hour, limit))
    override suspend fun activeDays(from: Long, to: Long) = d.activeDays(from, to)
    override suspend fun firstEvent() = d.firstEvent()
    override suspend fun events(from: Long, to: Long) = d.events(from, to)
    override suspend fun songCount(from: Long, to: Long) = d.songCount(from, to)
    override suspend fun playsOf(songId: String, to: Long) = d.playsOf(songId, to)
    override suspend fun artistCount(from: Long, to: Long) = d.artistCount(from, to)
    override suspend fun newArtists(from: Long, to: Long) = d.newArtists(from, to)
    override suspend fun topAlbums(from: Long, to: Long, limit: Int) = d.topAlbums(from, to, limit)
    override suspend fun firstPlay(from: Long, to: Long) = d.firstPlay(from, to)
    override suspend fun bySource(from: Long, to: Long) = d.bySource(from, to)
    override suspend fun recognizedIn(from: Long, to: Long) = d.recognizedIn(from, to)
    override suspend fun blendsIn(from: Long, to: Long) = d.blendsIn(from, to)
    override suspend fun releasesFoundIn(from: Long, to: Long) = d.releasesFoundIn(from, to)
}

/** Vinz's plays and the imported [sources]. */
class CountedPlays(override val sources: List<ImportSource>, private val d: CountedPlayDao, private val vinz: PlayEventDao) : PlayStats {
    private val o = listOf(VINZ) + sources.map { it.key }
    override suspend fun totalMs(from: Long, to: Long) = d.totalMs(o, from, to)
    override suspend fun count(from: Long, to: Long) = d.count(o, from, to)
    override suspend fun topSongs(from: Long, to: Long, limit: Int) = d.topSongs(o, from, to, limit)
    override suspend fun topArtists(from: Long, to: Long, limit: Int) = d.topArtists(o, from, to, limit)
    override suspend fun monthly(from: Long, to: Long) = d.monthly(o, from, to)
    override suspend fun byHour(from: Long, to: Long) = d.clockHours(LocalClock.hours(LocalClock.COUNTED, o, from, to))
    override suspend fun byDay(from: Long, to: Long) = d.clockDays(LocalClock.days(LocalClock.COUNTED, o, from, to))
    override suspend fun topSongsInHour(from: Long, to: Long, hour: Int, limit: Int) =
        d.clockSongs(LocalClock.songs(LocalClock.COUNTED, o, from, to, hour, limit))
    override suspend fun activeDays(from: Long, to: Long) = d.activeDays(o, from, to)
    override suspend fun firstEvent() = d.firstEvent(o)
    override suspend fun events(from: Long, to: Long) = d.events(o, from, to)
    override suspend fun songCount(from: Long, to: Long) = d.songCount(o, from, to)
    override suspend fun playsOf(songId: String, to: Long) = d.playsOf(o, songId, to)
    override suspend fun artistCount(from: Long, to: Long) = d.artistCount(o, from, to)
    override suspend fun newArtists(from: Long, to: Long) = d.newArtists(o, from, to)
    override suspend fun topAlbums(from: Long, to: Long, limit: Int) = d.topAlbums(o, from, to, limit)
    override suspend fun firstPlay(from: Long, to: Long) = d.firstPlay(o, from, to)
    override suspend fun bySource(from: Long, to: Long) = d.bySource(o, from, to)
    // Not plays: Vinz's own, whatever's counted.
    override suspend fun recognizedIn(from: Long, to: Long) = vinz.recognizedIn(from, to)
    override suspend fun blendsIn(from: Long, to: Long) = vinz.blendsIn(from, to)
    override suspend fun releasesFoundIn(from: Long, to: Long) = vinz.releasesFoundIn(from, to)

    companion object { const val VINZ = "vinz" }
}

/**
 * The listening clock's and calendar's SQL (1.8.5): plays grouped by local weekday, hour and date in SQLite, in the
 * device's current time zone ([ZoneId.systemDefault], read on every call) with its daylight saving and history rules,
 * each play placed by the offset in force when it was played (a play at 02:30 before the autumn change and one at 02:30
 * after it are both 02:30).
 *
 * Not strftime's 'localtime': that follows the C library's zone (bionic's on a phone, the computer's own under
 * Robolectric, where no test can set it), not Java's, which the rest of Replay uses ([com.vinz.music.data.ReplayStats.habits]).
 * Instead each timestamp gets its offset from a CASE over the zone's offset changes within the period, newest first, so
 * most plays stop at the first test. Changes more than a year from now aren't listed: a play that far ahead keeps the
 * offset before them.
 */
internal object LocalClock {
    const val VINZ = "play_events"
    const val COUNTED = "counted_plays"
    private const val YEAR_MS = 366L * 86_400_000L

    /** The offset in seconds to add to `timestamp / 1000` for local time: a number, or a CASE on `timestamp`. */
    fun shift(zone: ZoneId, from: Long, to: Long, now: Long = System.currentTimeMillis()): String {
        val rules = zone.rules
        val lo = Instant.ofEpochMilli(from.coerceAtLeast(0))
        val hi = to.coerceAtMost(now + YEAR_MS)
        val first = rules.getOffset(lo).totalSeconds
        val changes = ArrayList<Pair<Long, Int>>()
        var t = rules.nextTransition(lo)
        while (t != null && t.instant.toEpochMilli() <= hi) {
            changes += t.instant.toEpochMilli() to t.offsetAfter.totalSeconds
            t = rules.nextTransition(t.instant)
        }
        if (changes.isEmpty()) return "$first"
        return changes.asReversed().joinToString(" ", prefix = "CASE ", postfix = " ELSE $first END") { (at, s) -> "WHEN timestamp >= $at THEN $s" }
    }

    /** The plays of [table] (origins [o] of `counted_plays`) between two times, with `t`, their local time in epoch seconds. */
    private fun plays(table: String, o: List<String>?, from: Long, to: Long, zone: ZoneId, cols: String, songsOnly: Boolean = false): Pair<String, List<Any>> {
        val origin = o?.let { "origin IN (${it.joinToString(",") { "?" }}) AND " }.orEmpty()
        val songs = if (songsOnly) " AND songId IS NOT NULL" else ""
        return "(SELECT $cols, timestamp / 1000 + (${shift(zone, from, to)}) AS t FROM $table WHERE ${origin}timestamp BETWEEN ? AND ?$songs)" to
            (o.orEmpty() + listOf(from, to))
    }

    fun hours(table: String, o: List<String>?, from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): SupportSQLiteQuery {
        val (src, args) = plays(table, o, from, to, zone, "playedMs")
        return SimpleSQLiteQuery(
            """SELECT (CAST(strftime('%w', t, 'unixepoch') AS INTEGER) + 6) % 7 AS weekday, CAST(strftime('%H', t, 'unixepoch') AS INTEGER) AS hour,
               SUM(playedMs) AS ms, COUNT(*) AS plays FROM $src GROUP BY weekday, hour ORDER BY weekday, hour""",
            args.toTypedArray(),
        )
    }

    fun days(table: String, o: List<String>?, from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): SupportSQLiteQuery {
        val (src, args) = plays(table, o, from, to, zone, "playedMs")
        return SimpleSQLiteQuery(
            "SELECT strftime('%Y-%m-%d', t, 'unixepoch') AS day, SUM(playedMs) AS ms, COUNT(*) AS plays FROM $src GROUP BY day ORDER BY day",
            args.toTypedArray(),
        )
    }

    /** As [PlayEventDao.topSongs] asks, for the plays in local [hour]; imported plays not matched to a song can't be listed. */
    fun songs(table: String, o: List<String>?, from: Long, to: Long, hour: Int, limit: Int, zone: ZoneId = ZoneId.systemDefault()): SupportSQLiteQuery {
        val (src, args) = plays(table, o, from, to, zone, "songId, playedMs", songsOnly = true)
        return SimpleSQLiteQuery(
            """SELECT e.songId AS songId, COUNT(*) AS plays, SUM(e.playedMs) AS ms, s.title AS title,
               s.artistName AS artistName, s.thumbnail AS thumbnail, s.albumTitle AS albumTitle,
               s.durationSec AS durationSec, s.artistId AS artistId, s.primaryArtist AS primaryArtist
               FROM $src e JOIN songs s ON s.id = e.songId
               WHERE CAST(strftime('%H', e.t, 'unixepoch') AS INTEGER) = ?
               GROUP BY e.songId ORDER BY ms DESC, plays DESC LIMIT ?""",
            (args + listOf(hour, limit)).toTypedArray(),
        )
    }
}

/**
 * What the mixes, radio and Quick picks learn from (1.8.1): Vinz's own plays, and the imported ones once they're
 * matched to songs, when "Imported plays shape your mixes" is on. One seam: every recommendation reads plays here.
 */
class TasteHistory(
    private val vinz: PlayEventDao,
    private val counted: CountedPlayDao,
    /** More to learn from that isn't plays (Spotify's top songs and artists, 1.8.1); null when there's none. */
    private val boost: () -> TasteBoost? = { null },
    private val imported: () -> List<ImportSource>,
) {
    private fun origins(): List<String>? = imported().takeIf { it.isNotEmpty() }?.let { listOf(CountedPlays.VINZ) + it.map { s -> s.key } }

    suspend fun tasteEventsSince(since: Long): List<PlayEventEntity> =
        origins()?.let { counted.tasteEventsSince(it, since) } ?: vinz.tasteEventsSince(since)

    suspend fun tasteTopSongs(from: Long, to: Long, limit: Int): List<SongPlays> {
        val plays = origins()?.let { counted.tasteTopSongs(it, from, to, limit) } ?: vinz.tasteTopSongs(from, to, limit)
        val more = boost()?.let { b -> runCatching { b.songs() }.getOrNull() }.orEmpty()
        return if (more.isEmpty()) plays else TasteBoost.mergeSongs(plays, more, limit)
    }

    suspend fun tasteTopArtists(from: Long, to: Long, limit: Int): List<ArtistPlays> {
        val plays = origins()?.let { counted.tasteTopArtists(it, from, to, limit) } ?: vinz.tasteTopArtists(from, to, limit)
        val more = boost()?.let { b -> runCatching { b.artists() }.getOrNull() }.orEmpty()
        return if (more.isEmpty()) plays else TasteBoost.mergeArtists(plays, more, limit)
    }

    /** Changes whenever there are new plays to learn from (a snapshot's "anything new?" check). */
    suspend fun changeCount(now: Long): Int =
        vinz.count(0, now) + (origins()?.let { o -> counted.matchedCount(o.drop(1), 0, now) } ?: 0) + (boost()?.version() ?: 0)
}

/**
 * Songs and artists the listener is into by another app's measure (Spotify's top items), counted as if they had been
 * played: added to the plays' own numbers, so a song both played here and in Spotify's top climbs, and one only in
 * the top joins the list.
 */
interface TasteBoost {
    suspend fun songs(): List<SongPlays>
    suspend fun artists(): List<ArtistPlays>
    /** Changes when what it says does. */
    fun version(): Int

    companion object {
        fun mergeSongs(plays: List<SongPlays>, more: List<SongPlays>, limit: Int): List<SongPlays> {
            val byId = LinkedHashMap<String, SongPlays>()
            plays.forEach { byId[it.songId] = it }
            more.forEach { m -> byId[m.songId] = byId[m.songId]?.let { it.copy(plays = it.plays + m.plays, ms = it.ms + m.ms) } ?: m }
            return byId.values.sortedWith(compareByDescending<SongPlays> { it.ms }.thenByDescending { it.plays }).take(limit)
        }

        fun mergeArtists(plays: List<ArtistPlays>, more: List<ArtistPlays>, limit: Int): List<ArtistPlays> {
            fun k(a: ArtistPlays) = ImportKeys.norm(a.artistName)
            val byName = LinkedHashMap<String, ArtistPlays>()
            plays.forEach { byName[k(it)] = it }
            more.forEach { m ->
                val key = k(m)
                byName[key] = byName[key]?.let { it.copy(plays = it.plays + m.plays, ms = it.ms + m.ms, thumbnail = it.thumbnail ?: m.thumbnail) } ?: m
            }
            return byName.values.sortedByDescending { it.ms }.take(limit)
        }
    }
}
