package com.vinz.music.data

import android.content.Context
import android.content.SharedPreferences
import com.vinz.music.AppContainer
import com.vinz.music.R
import com.vinz.music.data.imports.PlayStats
import com.vinz.music.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** A milestone with what it's about, ready to show: the song's title and cover for a song's plays. */
data class ShownMilestone(val milestone: Milestone, val text: String, val thumbnail: String?, val fresh: Boolean)

/**
 * Milestones for Replay's list and for the notice (1.8.5). They're read from the history ([Milestones.of]); kept here
 * is only when they were first looked at on this phone (earlier ones aren't news) and which have been seen.
 */
class MilestoneWatch internal constructor(
    /** What Replay counts, asked anew each time ("Count history from" can change). */
    private val stats: () -> PlayStats,
    prefsOf: () -> SharedPreferences,
    /** Whether the notice is turned on. */
    private val on: () -> Boolean,
    /** Posts the notice with this text. */
    private val notify: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    constructor(c: AppContainer) : this(
        stats = { c.imports.replay() },
        prefsOf = { c.app.getSharedPreferences("milestones", Context.MODE_PRIVATE) },
        on = { c.settings.current.behavior.milestoneNotify },
        notify = { text -> Notifications.post(c.app, Notifications.REPLAY_NEWS, 5300, tr(R.string.ms_new), text, "vinz://replay") },
        scope = c.scope,
    )

    private val prefs by lazy(prefsOf)

    /** When milestones were first looked at here: set on the first look, so years of history don't arrive as news. */
    private fun since(now: Long): Long = prefs.getLong(SINCE, 0L).takeIf { it > 0 } ?: now.also { prefs.edit().putLong(SINCE, it).apply() }
    private fun seen(): Set<String> = prefs.getStringSet(SEEN, emptySet()).orEmpty()

    /** Every milestone in what Replay counts, newest first, with the words to show. */
    suspend fun all(now: Long = System.currentTimeMillis()): List<ShownMilestone> = withContext(Dispatchers.IO) {
        val stats = stats()
        val songs = stats.topSongs(0, now, MAX_SONGS).associateBy { it.songId }
        val events = stats.events(0, now)
        val since = since(now)
        val seen = seen()
        Milestones.of(events, now = now) { songs[it]?.artistName?.substringBefore(", ") }
            .asReversed()
            .mapNotNull { m ->
                val song = m.songId?.let { songs[it] }
                // A song's plays without the song (an imported play not matched yet) has nothing to show.
                if (m.kind == MilestoneKind.SONG_PLAYS && song == null) null
                else ShownMilestone(m, line(m, song?.title), song?.thumbnail, fresh = m.at > since && m.key !in seen)
            }
    }

    /** The list was opened: what's in it now is seen. */
    fun markSeen(shown: List<ShownMilestone>) {
        val fresh = shown.filter { it.fresh }.map { it.milestone.key }
        if (fresh.isNotEmpty()) prefs.edit().putStringSet(SEEN, seen() + fresh).apply()
    }

    @Volatile private var lastCheck = 0L

    /**
     * After a play is saved: with the notice turned on, and not more than once in [CHECK_EVERY_MS], looks for a
     * milestone reached since the last look and tells about it. The whole history is read only when a play may have
     * reached one, or once a day ([mayHaveNews]). Off (as it comes), nothing is read at all.
     */
    fun afterPlay(now: Long = System.currentTimeMillis()) {
        if (!on()) return
        if (now - lastCheck < CHECK_EVERY_MS) return
        lastCheck = now
        scope.launch(Dispatchers.IO) { runCatching { tell(now) } }
    }

    internal suspend fun tell(now: Long, zone: ZoneId = ZoneId.systemDefault()): List<ShownMilestone> {
        if (!mayHaveNews(now, zone)) return emptyList()
        val fresh = all(now).filter { it.fresh }
        if (fresh.isEmpty()) return fresh
        markSeen(fresh)
        // One notice: the newest, and how many more.
        val text = fresh.first().text + if (fresh.size > 1) " " + tr(R.string.ms_and_more, fresh.size - 1) else ""
        notify(text)
        return fresh
    }

    /**
     * Whether the whole history is worth reading now: the plays since the last look could have reached a song's, the
     * hours' or the songs' next mark ([Milestones.mayHaveReached], a few counts in SQLite), or it's the day's first look
     * (streaks and the year's artist, which no cheap count shows, are looked at once a day). The very first look only
     * notes the time: nothing before it is news.
     */
    private suspend fun mayHaveNews(now: Long, zone: ZoneId): Boolean {
        val looked = prefs.getLong(LOOKED, 0L)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        val daily = prefs.getLong(DAY, Long.MIN_VALUE) != today
        prefs.edit().putLong(LOOKED, now).putLong(DAY, today).apply()
        if (looked <= 0L) { since(now); return false }
        if (daily) return true
        val s = stats()
        val new = s.events(looked + 1, now)
        if (new.isEmpty()) return false
        val totalMs = s.totalMs(0, now)
        val beforeMs = totalMs - new.sumOf { it.playedMs }
        val songs = s.songCount(0, now)
        val songsBefore = s.songCount(0, looked)
        if (Milestones.mayHaveReached(0, beforeMs, totalMs, songsBefore, songs)) return true
        // Each song played since: any of its plays since then could be a mark (a song played twice may pass one between).
        return new.filter { it.songId.isNotEmpty() }.groupingBy { it.songId }.eachCount().any { (id, k) ->
            val n = s.playsOf(id, now)
            (n - k + 1..n).any { it in Milestones.SONG_PLAYS }
        }
    }

    companion object {
        private const val SINCE = "since"
        private const val SEEN = "seen"
        /** When the notice last looked (the plays after it are the ones the cheap check asks about), and on which day. */
        private const val LOOKED = "looked"
        private const val DAY = "looked_day"
        private const val MAX_SONGS = 100_000
        const val CHECK_EVERY_MS = 30 * 60_000L

        /** What a milestone says: "You've played Song 100 times", "500 hours of music". */
        fun line(m: Milestone, songTitle: String?): String = when (m.kind) {
            MilestoneKind.SONG_PLAYS -> tr(R.string.ms_song_plays, songTitle ?: "", m.value)
            MilestoneKind.HOURS -> tr(R.string.ms_hours, m.value)
            MilestoneKind.SONGS -> tr(R.string.ms_songs, m.value)
            MilestoneKind.STREAK -> tr(R.string.ms_streak, m.value)
            MilestoneKind.TOP_ARTIST -> tr(R.string.ms_top_artist, m.subject ?: "", m.value)
        }
    }
}
