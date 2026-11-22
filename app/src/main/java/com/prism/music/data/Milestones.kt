package com.vinz.music.data

import com.prism.music.data.db.PlayEventEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What a [Milestone] marks. */
enum class MilestoneKind {
    /** A song's Nth play ([Milestone.songId] is the song). */
    SONG_PLAYS,
    /** N hours listened in all. */
    HOURS,
    /** N different songs played. */
    SONGS,
    /** N days in a row with music, the first time a run got that long. */
    STREAK,
    /**
     * An artist is the most-listened one of [Milestone.value] (a year); [Milestone.subject] is the artist. The current
     * year's only from December on ([Milestones.of]).
     */
    TOP_ARTIST,
}

/**
 * A moment worth marking in the listener's history (1.8.5): [value] is the number reached (plays, hours, songs, days) or
 * the year for [MilestoneKind.TOP_ARTIST], [at] when it was reached.
 */
data class Milestone(val kind: MilestoneKind, val value: Int, val at: Long, val songId: String? = null, val subject: String? = null) {
    /** The same milestone however often the history is read again (what "seen" and "told about" are kept by). */
    val key: String get() = "${kind.name}:$value:${songId ?: subject ?: ""}"
}

/**
 * Milestones are read from the history each time, never stored: one pass over the plays in order. So turning a "Count
 * history from" source on or off, taking a play out of the history, or restoring a backup can't leave one that is no
 * longer true, and a first run after updating has nothing to backfill. Only what's been seen is kept ([Milestones.fresh]).
 */
object Milestones {
    val SONG_PLAYS = listOf(25, 50, 100, 250, 500, 1000, 2500)
    val HOURS = listOf(10, 50, 100, 250, 500, 1000, 2500, 5000, 10000)
    val SONGS = listOf(100, 500, 1000, 2500, 5000, 10000, 25000)
    val STREAKS = listOf(7, 30, 100, 365)
    /** An artist leads a year only with at least this much listened: the first song of January isn't a milestone. */
    const val TOP_ARTIST_MIN_MS = 60 * 60_000L

    /**
     * Every milestone in [events], oldest first. [events] in time order (as [com.vinz.music.data.imports.PlayStats.events]
     * gives them). [artistOf] names a song's main artist, for the year's top artist; without it there's none of those.
     * [now] is when they're read: it says which year is still going.
     */
    fun of(
        events: List<PlayEventEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis(),
        artistOf: (String) -> String? = { null },
    ): List<Milestone> {
        val out = ArrayList<Milestone>()
        val plays = HashMap<String, Int>()
        var totalMs = 0L
        var hoursAt = 0
        var songsAt = 0
        var streaksAt = 0
        var run = 0
        var lastDay: LocalDate? = null
        // The year's leader: who, and when they took the lead. One milestone a year, for whoever leads at its end (or now).
        var year = Int.MIN_VALUE
        val artistMs = HashMap<String, Long>()
        var leader: String? = null
        var leaderSince = 0L
        // This year's leader is only the year's top artist in its last month: before December it's no milestone (no
        // notice at the first hour, nor at each change of leader); in December it's reached on the 1st, or when they took
        // the lead after it. Past years are whoever led at the end, since they took the lead.
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val december = today.withMonth(12).withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        fun closeYear() {
            val who = leader ?: return
            when {
                year < today.year -> out += Milestone(MilestoneKind.TOP_ARTIST, year, leaderSince, subject = who)
                year == today.year && today.monthValue == 12 -> out += Milestone(MilestoneKind.TOP_ARTIST, year, maxOf(leaderSince, december), subject = who)
            }
        }

        for (e in events) {
            // An imported play not matched to a song yet ("") is listening time and a day with music, but no song.
            if (e.songId.isNotEmpty()) {
                val n = (plays[e.songId] ?: 0) + 1
                plays[e.songId] = n
                if (n in SONG_PLAYS) out += Milestone(MilestoneKind.SONG_PLAYS, n, e.timestamp, songId = e.songId)
            }

            totalMs += e.playedMs
            while (hoursAt < HOURS.size && totalMs >= HOURS[hoursAt] * 3_600_000L) out += Milestone(MilestoneKind.HOURS, HOURS[hoursAt++], e.timestamp)
            while (songsAt < SONGS.size && plays.size >= SONGS[songsAt]) out += Milestone(MilestoneKind.SONGS, SONGS[songsAt++], e.timestamp)

            val t = Instant.ofEpochMilli(e.timestamp).atZone(zone)
            val day = t.toLocalDate()
            if (day != lastDay) {
                run = if (lastDay != null && lastDay.plusDays(1) == day) run + 1 else 1
                lastDay = day
                while (streaksAt < STREAKS.size && run >= STREAKS[streaksAt]) out += Milestone(MilestoneKind.STREAK, STREAKS[streaksAt++], e.timestamp)
            }

            if (t.year != year) {
                closeYear()
                year = t.year; artistMs.clear(); leader = null
            }
            val artist = artistOf(e.songId)?.takeIf { it.isNotBlank() } ?: continue
            val ms = (artistMs[artist] ?: 0) + e.playedMs
            artistMs[artist] = ms
            if (artist != leader && ms >= TOP_ARTIST_MIN_MS && ms > (leader?.let { artistMs[it] } ?: 0)) {
                leader = artist; leaderSince = e.timestamp
            }
        }
        closeYear()
        return out.sortedBy { it.at }
    }

    /**
     * The ones to tell the listener about: reached after [since] (when milestones were first looked at on this phone, so
     * years of history don't arrive as news) and not among the [seen] keys.
     */
    fun fresh(all: List<Milestone>, since: Long, seen: Set<String>): List<Milestone> = all.filter { it.at > since && it.key !in seen }

    /**
     * Whether a play that brought a song to [songPlays] plays, the total to [totalMs] (from [beforeMs]) could have reached
     * a milestone: the cheap check after each play, so the whole history is only read when there may be news.
     * Streaks and the year's artist are looked at once a day instead ([com.vinz.music.data.MilestoneWatch]).
     */
    fun mayHaveReached(songPlays: Int, beforeMs: Long, totalMs: Long, songsBefore: Int, songs: Int): Boolean =
        songPlays in SONG_PLAYS ||
            HOURS.any { beforeMs < it * 3_600_000L && totalMs >= it * 3_600_000L } ||
            SONGS.any { songsBefore < it && songs >= it }
}
