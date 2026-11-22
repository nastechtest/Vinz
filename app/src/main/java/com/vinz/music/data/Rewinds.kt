package com.vinz.music.data

import com.vinz.music.R
import com.vinz.music.tr
import com.prism.music.data.db.mergeUploads
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vinz.music.AppContainer
import com.vinz.music.BuildConfig
import com.vinz.music.container
import com.prism.music.data.db.SongPlays
import com.vinz.music.data.model.AlbumRef
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Rewind: the year in music, for every year Vinz has history for. The current year's opens on 1 December; a finished
 * year is worked out once and kept ([RewindStore]), so it opens at once and stays the way it was.
 */
class Rewinds(private val c: AppContainer) {
    /** Kept Rewinds, and the rule for when a year is kept or worked out again ([RewindKeeper]). */
    private val keeper = RewindKeeper(RewindStore(File(c.app.filesDir, "rewind")))
    val store: RewindStore get() = keeper.store

    /** Debug builds open the current year's Rewind early, for trying it out. Release builds: 1 December only. */
    val early: Boolean get() = BuildConfig.DEBUG

    fun isOpen(year: Int, now: Long = System.currentTimeMillis()) = RewindCalendar.isOpen(year, now, early = early)

    /** Years with listening, newest first. */
    suspend fun years(): List<Int> = RewindCalendar.years(c.imports.replay().firstEvent(), System.currentTimeMillis())

    /** [year]'s Rewind: as kept, or worked out now (and kept, once the year's over). */
    suspend fun snapshot(year: Int): RewindSnapshot = withContext(Dispatchers.IO) {
        keeper.snapshot(year, c.imports.replay()) { y, stats -> build(y, stats) }
    }

    /** The year that's just ended, kept on the first launch after it (in the background, after start-up settles). */
    suspend fun freezeLastYear() {
        val last = RewindCalendar.yearOf(System.currentTimeMillis()) - 1
        if (store.load(last) != null) return
        val (from, to) = RewindCalendar.bounds(last)
        if (c.db.plays().count(from, to) == 0) return
        runCatching { snapshot(last) }.onFailure { Log.w(TAG, "couldn't freeze Rewind $last", it) }
    }

    private fun SongPlays.song() = Song(
        id = songId, title = title,
        artists = artistName.split(", ").mapIndexed { i, n -> ArtistRef(n, if (i == 0) artistId else null) },
        album = albumTitle?.let { AlbumRef(it) }, durationSec = durationSec, thumbnail = thumbnail,
    )

    suspend fun build(year: Int, dao: com.vinz.music.data.imports.PlayStats = c.imports.replay()): RewindSnapshot = withContext(Dispatchers.IO) {
        // The slow, network-bound pieces (a lyric line, the top artist's photo) go first, alongside the counting.
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val (from0, end0) = RewindCalendar.bounds(year, zone)
        val topNow = dao.topSongs(from0, minOf(end0, now), 20).mergeUploads().firstOrNull()
        val lyricJob = async { topNow?.let { lyricLine(it) } }
        val imageJob = async {
            dao.topArtists(from0, minOf(end0, now), 1).firstOrNull()?.artistId?.let { id ->
                withTimeoutOrNull(5_000) { runCatching { c.ytm.artist(id).thumbnail }.getOrNull() }
            }
        }
        RewindBuild.build(
            year, dao, now, zone,
            genresOf = { songs -> c.meta.cachedGenres(songs.map { it.song() }) },
            lyric = { top -> if (top?.songId == topNow?.songId) lyricJob.await() else top?.let { lyricLine(it) } },
            image = { imageJob.await() },
        )
    }

    /** A line from [t], where its best part starts (its chorus, most likely); null without both. */
    private suspend fun lyricLine(t: SongPlays): String? = coroutineScope {
        val song = t.song()
        val start = async { withTimeoutOrNull(5_000) { runCatching { c.bestParts.startMs(song) }.getOrNull() } }
        val lyrics = async { withTimeoutOrNull(8_000) { runCatching { c.lyrics.auto(song) }.getOrNull() }?.takeIf { it.synced } }
        val s = start.await() ?: return@coroutineScope null
        val l = lyrics.await() ?: return@coroutineScope null
        l.lines.filter { !it.isGap && it.text.isNotBlank() }.firstOrNull { it.timeMs >= s - 1_500 }?.text?.trim()?.take(120)
    }

    companion object {
        private const val TAG = "VinzRewind"

        /** The "Your Rewind is ready" notification, 1 December at 10:00 this year (or next, once that's passed). */
        fun schedule(context: Context) {
            val now = System.currentTimeMillis()
            val (target, at) = RewindCalendar.nextNotice(now)
            WorkManager.getInstance(context).enqueueUniqueWork(
                "rewind_$target", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RewindWorker>().setInitialDelay(at - now, TimeUnit.MILLISECONDS).build(),
            )
        }

        /**
         * Whether [year]'s notice goes out: switched on, not told about [year] already ([toldYear], the last year told:
         * a clock or time zone moved back after the notice lines the same year up again), and a year that's more than a
         * light one in what Replay counts ([yearMs], imported history included when it's counted).
         */
        fun tells(year: Int, on: Boolean, toldYear: Int, yearMs: Long): Boolean = on && toldYear < year && yearMs > StoryParts.LIGHT_MS
    }
}

/** 1 December: tells the listener their Rewind is ready (if they listened this year), then lines up next year's. */
class RewindWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val year = RewindCalendar.yearOf(System.currentTimeMillis())
        val (from, to) = RewindCalendar.bounds(year)
        val prefs = applicationContext.getSharedPreferences("rewind", Context.MODE_PRIVATE)
        // What the story counts: Vinz's plays and any imported history counted (an imported-only year has a Rewind too).
        val yearMs = runCatching { c.imports.replay().totalMs(from, to) }.getOrElse { c.db.plays().totalMs(from, to) }
        if (Rewinds.tells(year, c.settings.current.rewindNotification, prefs.getInt(TOLD, 0), yearMs)) {
            prefs.edit().putInt(TOLD, year).apply()
            Notifications.post(
                applicationContext, Notifications.REWIND, 4800 + year % 100, tr(R.string.your_rewind_is_ready_2, year),
                tr(R.string.your_year_in_music_your_top), "vinz://rewind/$year",
            )
        }
        Rewinds.schedule(applicationContext)
        return Result.success()
    }

    private companion object { const val TOLD = "told_year" }
}
