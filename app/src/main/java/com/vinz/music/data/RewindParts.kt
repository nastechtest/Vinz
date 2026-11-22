package com.vinz.music.data

import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.SongPlays
import com.prism.music.data.db.mergeUploads
import com.vinz.music.data.imports.ImportSource
import com.vinz.music.data.imports.PlayStats
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * A milestone reached in a Rewind's year, as the Rewind keeps it (1.8.5). The words are made when it's shown
 * ([MilestoneWatch.line]), so they follow the app's language; a kind this version doesn't know is left out.
 */
@Serializable
data class RewindMilestone(
    val kind: String,
    val value: Int,
    val at: Long,
    val songId: String? = null,
    val songTitle: String? = null,
    val thumbnail: String? = null,
    val subject: String? = null,
) {
    fun milestone(): Milestone? = runCatching { MilestoneKind.valueOf(kind) }.getOrNull()?.let { Milestone(it, value, at, songId, subject) }
}

/** The pieces of a Rewind added in 1.8.5, worked out from the plays themselves (no network, no database). */
object RewindParts {
    /** Sources ([PlayEventEntity.source]) that mean Vinz brought the song: its radio, Smart Shuffle, DJ, a blend, a mix. */
    fun foundBy(source: String?): Boolean {
        val s = source?.lowercase() ?: return false
        return s == "radio" || s == "smart" || s == "dj" || s == "blend" || s.startsWith("mix:")
    }

    /** Time listened on each day of [year] in [zone] (index 0 = 1 January); [events] outside the year are ignored. */
    fun days(events: List<PlayEventEntity>, year: Int, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
        val out = LongArray(java.time.Year.of(year).length())
        for (e in events) {
            val d = Instant.ofEpochMilli(e.timestamp).atZone(zone)
            if (d.year == year) out[d.dayOfYear - 1] += e.playedMs
        }
        return out.toList()
    }

    /** Time listened in each hour of each weekday, local: index weekday × 24 + hour (0 = Monday, midnight). */
    fun weekHours(events: List<PlayEventEntity>, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
        val out = LongArray(7 * 24)
        for (e in events) {
            val t = Instant.ofEpochMilli(e.timestamp).atZone(zone)
            out[(t.dayOfWeek.value - 1) * 24 + t.hour] += e.playedMs
        }
        return out.toList()
    }

    /** At most this many milestones on the slide. */
    const val MAX_MILESTONES = 5

    /**
     * The milestones reached between [from] and [to], from the whole history [all] (in time order: a song's 100th play
     * counts every play before it): the biggest of each kind (1,000 hours, not 500 as well), the two biggest songs'
     * plays, oldest first. A song's plays only when [songs] knows the song (a title to show); the year's top artist is
     * left out (its own slide says so).
     */
    fun milestones(all: List<PlayEventEntity>, from: Long, to: Long, songs: Map<String, SongPlays>, zone: ZoneId = ZoneId.systemDefault()): List<RewindMilestone> {
        val inYear = Milestones.of(all, zone).filter { it.at in from..to && it.kind != MilestoneKind.TOP_ARTIST }
        val totals = inYear.filter { it.kind != MilestoneKind.SONG_PLAYS }.groupBy { it.kind }.values.map { same -> same.maxBy { it.value } }
        val songMs = inYear.filter { it.kind == MilestoneKind.SONG_PLAYS && it.songId != null && songs[it.songId] != null }
            .groupBy { it.songId }.values.map { same -> same.maxBy { it.value } }
            .sortedWith(compareByDescending<Milestone> { it.value }.thenBy { it.at }).take(2)
        return (totals + songMs).sortedBy { it.at }.take(MAX_MILESTONES).map { m ->
            val song = m.songId?.let { songs[it] }
            RewindMilestone(m.kind.name, m.value, m.at, m.songId, song?.title, song?.thumbnail, m.subject)
        }
    }

    /** A find needs this many plays in the year: it stuck. */
    const val FIND_PLAYS = 3
    const val MAX_FINDS = 5

    /**
     * Songs first ever played between [from] and [to] through something Vinz brought ([foundBy]), then played at least
     * [FIND_PLAYS] times in that stretch; most listened first. From the whole history [all] in time order (a song
     * heard before is no find); [songs] gives each its title and cover, and one it doesn't know is left out.
     */
    fun finds(all: List<PlayEventEntity>, from: Long, to: Long, songs: Map<String, SongPlays>): List<SongPlays> {
        val first = HashMap<String, PlayEventEntity>()
        val plays = HashMap<String, Int>()
        val ms = HashMap<String, Long>()
        for (e in all) {
            if (e.songId.isEmpty()) continue
            first.putIfAbsent(e.songId, e)
            if (e.timestamp in from..to) {
                plays.merge(e.songId, 1, Int::plus)
                ms.merge(e.songId, e.playedMs, Long::plus)
            }
        }
        return first.values.asSequence()
            .filter { it.timestamp in from..to && foundBy(it.source) && (plays[it.songId] ?: 0) >= FIND_PLAYS }
            .mapNotNull { e -> songs[e.songId]?.copy(plays = plays[e.songId] ?: 0, ms = ms[e.songId] ?: 0) }
            .sortedWith(compareByDescending<SongPlays> { it.ms }.thenByDescending { it.plays })
            .toList()
    }
}

/**
 * Works a year's Rewind out from what Replay counts ([PlayStats]). Everything slow or online comes in from outside:
 * [genresOf] (the songs' genres, from what's cached), [lyric] (a line of the top song) and [image] (the top artist's
 * photo), so this runs the same in a test.
 */
object RewindBuild {
    private const val EVERY_SONG = 100_000

    suspend fun build(
        year: Int,
        dao: PlayStats,
        now: Long,
        zone: ZoneId,
        genresOf: suspend (List<SongPlays>) -> Map<String, String> = { emptyMap() },
        lyric: suspend (top: SongPlays?) -> String? = { null },
        image: suspend () -> String? = { null },
    ): RewindSnapshot {
        val (from, end) = RewindCalendar.bounds(year, zone)
        val to = minOf(end, now)
        val raw = dao.topSongs(from, to, 150)
        val songs = raw.mergeUploads().take(100)
        val artists = dao.topArtists(from, to, 20)
        val totalMs = dao.totalMs(from, to)
        val genreMap = genresOf(songs)
        val genres = songs.groupBy { genreMap[it.songId] ?: "" }.filterKeys { it.isNotEmpty() }
            .mapValues { (_, v) -> v.sumOf { it.ms } }.toList().sortedByDescending { it.second }.take(5)
        val genreSongs = genres.associate { (g, _) -> g to songs.first { genreMap[it.songId] == g }.songId }

        // Last year over the same stretch (all of it, once this one's over).
        val prevFrom = RewindCalendar.bounds(year - 1, zone).first
        val prevTo = minOf(RewindCalendar.bounds(year - 1, zone).second, Instant.ofEpochMilli(to).atZone(zone).minusYears(1).toInstant().toEpochMilli())
        val previousMs = dao.totalMs(prevFrom, prevTo).takeIf { it > 60 * 60_000L }

        // The whole history up to the year's end, once: milestones and finds need what came before; the year's own
        // plays are its tail (in time order).
        val all = dao.events(0, to)
        val events = all.indexOfFirst { it.timestamp >= from }.let { if (it < 0) emptyList() else all.subList(it, all.size) }
        val habits = ReplayStats.habits(events, zone)
        val artistCount = dao.artistCount(from, to)
        val firstEvent = all.firstOrNull()?.timestamp ?: dao.firstEvent()
        val newArtists = if (firstEvent?.let { it >= from } == true) null else dao.newArtists(from, to)
        val activeDays = dao.activeDays(from, to)
        val plays = dao.count(from, to)
        val songCount = dao.songCount(from, to)
        val top = songs.firstOrNull()
        val genreTotal = genres.sumOf { it.second }.coerceAtLeast(1)

        val seasons = RewindCalendar.quarters.indices.mapNotNull { q ->
            val (qa, qb) = RewindCalendar.quarterBounds(year, q, zone)
            if (qa > to) return@mapNotNull null
            val qs = dao.topSongs(qa, minOf(qb, to), 60).mergeUploads().take(40)
            if (qs.isEmpty()) return@mapNotNull null
            val g = genresOf(qs)
            val genre = qs.groupBy { g[it.songId] ?: "" }.filterKeys { it.isNotEmpty() }.mapValues { (_, v) -> v.sumOf { it.ms } }.maxByOrNull { it.value }?.key
            SeasonGenre(RewindCalendar.quarters[q].first, genre, qs.sumOf { it.ms }, qs.first().title)
        }

        val bySource = dao.bySource(from, to).associate { (it.source ?: "") to it.plays }
        val vinz = VinzYear(
            sung = bySource["sing"] ?: 0,
            djSongs = bySource["dj"] ?: 0,
            smartPicks = bySource["smart"] ?: 0,
            recognized = dao.recognizedIn(from, to),
            blends = dao.blendsIn(from, to),
            radarFinds = dao.releasesFoundIn(from, to),
        )

        // Every song played in the year, for the milestones' and finds' titles (a find is rarely among the top 150).
        val known = dao.topSongs(from, to, EVERY_SONG).associateBy { it.songId } + songs.associateBy { it.songId }
        val hourSongs = if (events.isEmpty()) emptyList() else dao.topSongsInHour(from, to, habits.peakHour, 6).mergeUploads().take(3)
        val found = RewindParts.finds(all, from, to, known)

        val topLyric = lyric(top)
        val leadImage = image()

        return RewindSnapshot(
            year = year, frozenAt = now, totalMs = totalMs, previousMs = previousMs,
            previousTopArtist = if (previousMs != null) dao.topArtists(prevFrom, prevTo, 1).firstOrNull()?.artistName else null,
            previousSongCount = if (previousMs != null) dao.songCount(prevFrom, prevTo) else null,
            plays = plays, activeDays = activeDays, songCount = songCount, artistCount = artistCount,
            songs = songs, artists = artists, genres = genres.map { GenreShare(it.first, it.second) }, genreSongs = genreSongs,
            byHour = habits.byHour.toList(), byWeekday = habits.byWeekday.toList(), longestStreak = habits.longestStreak,
            biggestDay = habits.biggestDay?.toString(), biggestDayMs = habits.biggestDayMs,
            newArtists = newArtists,
            personality = ReplayStats.personality(
                totalMs = totalMs, topFiveArtistsMs = artists.take(5).sumOf { it.ms }, artistCount = artistCount,
                newArtistCount = newArtists?.size, plays = plays, songCount = songCount,
                topSongPlays = top?.plays ?: 0, topSongTitle = top?.title, activeDays = activeDays,
                topGenreShare = genres.firstOrNull()?.let { it.second.toDouble() / genreTotal }, habits = habits,
            ),
            topArtistImage = leadImage,
            months = dao.monthly(from, to),
            topAlbum = dao.topAlbums(from, to, 1).firstOrNull(),
            firstSong = dao.firstPlay(from, to),
            seasons = seasons,
            vinz = vinz,
            topLyric = topLyric,
            days = RewindParts.days(events, year, zone),
            weekHours = RewindParts.weekHours(events, zone),
            hourSongs = hourSongs,
            milestones = RewindParts.milestones(all, from, to, known, zone),
            finds = found.take(RewindParts.MAX_FINDS),
            findCount = found.size,
        )
    }
}

/**
 * Which Rewind a year gets, kept or worked out (the rule since 1.7.x, here so it can be tested):
 * - With only Vinz's own plays counted, a finished year (from 06:00 on 1 January, [FINISHED_AFTER_MS]) is worked out
 *   once and kept ([RewindStore]); from then on it's always that one, whatever happens to the history.
 * - The year still running, or any year while an imported history is counted, is worked out afresh each time it's
 *   opened, except that the same year with the same sources within [RECENT_MS] is the one just made.
 */
class RewindKeeper(
    val store: RewindStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    @Volatile private var recent: RewindSnapshot? = null
    @Volatile private var recentSources: Set<ImportSource> = emptySet()

    fun finished(year: Int, now: Long = clock()) = now > RewindCalendar.bounds(year, zone()).second + FINISHED_AFTER_MS

    suspend fun snapshot(year: Int, stats: PlayStats, build: suspend (Int, PlayStats) -> RewindSnapshot): RewindSnapshot {
        // Kept Rewinds are Vinz's own plays: with an imported history counted (1.8.1) the year is worked out afresh.
        val vinzOnly = stats.sources.isEmpty()
        if (vinzOnly) store.load(year)?.let { return it }
        val sources = stats.sources.toSet()
        recent?.takeIf { it.year == year && clock() - it.frozenAt in 0 until RECENT_MS && recentSources == sources }?.let { return it }
        val s = build(year, stats)
        if (vinzOnly && finished(year) && s.plays > 0) runCatching { store.save(s) }.onFailure { android.util.Log.w("VinzRewind", "couldn't keep Rewind $year", it) }
        else { recent = s; recentSources = sources }
        return s
    }

    companion object {
        const val RECENT_MS = 10 * 60_000L
        /** A little past midnight on 1 January (06:00), so the year's last plays have been counted before it's kept. */
        const val FINISHED_AFTER_MS = 6 * 60 * 60_000L
    }
}
