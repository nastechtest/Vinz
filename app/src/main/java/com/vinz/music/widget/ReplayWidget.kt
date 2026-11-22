package com.vinz.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vinz.music.R
import com.vinz.music.container
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.SongPlays
import com.prism.music.data.db.mergeUploads
import com.vinz.music.data.imports.PlayStats
import com.vinz.music.tr
import com.vinz.music.trp
import com.vinz.music.ui.screens.Period
import com.vinz.music.ui.screens.listenTime
import com.vinz.music.ui.screens.range
import com.vinz.music.ui.screens.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * The Replay widgets (1.8.5): this week's top song (2×2), and beside it the top artist (4×2), counted as Replay counts
 * them (its "This week" chip, and the histories counted under "Count history from"). Read on the phone; nothing leaves it.
 * Drawn when one is added, when Replay opens, and once a day (a periodic job that only exists while a Replay widget does).
 */
abstract class ReplayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ReplayWidgets.schedule(context)
        ReplayWidgets.refresh(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = ReplayWidgets.refresh(context)
    override fun onEnabled(context: Context) = ReplayWidgets.schedule(context)
    override fun onDisabled(context: Context) = ReplayWidgets.unscheduleIfNone(context)
}

/** "Vinz · Top song": the top song alone (2×2; made 4 wide, it shows the artist too). */
class ReplayWidget : ReplayWidgetProvider()

/** "Vinz · Replay": the top song and the top artist side by side (4×2). */
class ReplayWideWidget : ReplayWidgetProvider()

/** This week's top song and artist (none: nothing played), and the artist's photo when one is stored. */
@Serializable
data class ReplayWeek(val song: SongPlays? = null, val artist: ArtistPlays? = null, val artistPhoto: String? = null) {
    val isEmpty: Boolean get() = song == null
}

/** The daily redraw of the Replay widgets ([ReplayWidgets.schedule]). */
class ReplayWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { ReplayWidgets.draw(applicationContext) }
        return Result.success()
    }
}

object ReplayWidgets {
    private const val WORK = "replay_widget"
    private const val PREFS = "replay_widget"
    /** From this width (dp) the artist sits beside the song. */
    const val WIDE_DP = 250

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val providers = listOf(ReplayWidget::class.java, ReplayWideWidget::class.java)

    /** Every placed Replay widget, by its provider. */
    private fun placed(context: Context): Map<Class<*>, IntArray> {
        val manager = AppWidgetManager.getInstance(context) ?: return emptyMap()
        return providers.associateWith { runCatching { manager.getAppWidgetIds(ComponentName(context, it)) }.getOrNull() ?: IntArray(0) }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * This week (Replay's "This week" chip: from the first day of the week as the phone's region starts it, to [now])
     * in [stats]: its most listened song (uploads of one song counted together, as Replay lists them) and artist.
     * [photo] is the stored picture of an artist (by id and name), if there is one.
     */
    suspend fun week(
        stats: PlayStats,
        now: Long = System.currentTimeMillis(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        firstDay: java.time.DayOfWeek = java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek,
        photo: (String?, String) -> String? = { _, _ -> null },
    ): ReplayWeek {
        val (from, to) = range(Period.WEEK, now, zone, firstDay)
        val song = stats.topSongs(from, to, 20).mergeUploads().firstOrNull() ?: return ReplayWeek()
        val artist = stats.topArtists(from, to, 1).firstOrNull()
        return ReplayWeek(song, artist, artist?.let { photo(it.artistId, it.artistName) })
    }

    /**
     * What the widget shows: this week's [fresh] answer; while history is [paused] and the week has nothing (no plays
     * are kept), the [last] one shown; when the plays couldn't be read (null), the last one too, never an error.
     */
    fun shown(fresh: ReplayWeek?, last: ReplayWeek?, paused: Boolean): ReplayWeek = when {
        fresh == null -> last ?: ReplayWeek()
        fresh.isEmpty && paused -> last ?: fresh
        else -> fresh
    }

    /** The song's line under its title: plays and time, as Replay says them. */
    fun songStat(song: SongPlays, hours: Boolean): String = trp(R.plurals.n_plays, song.plays, song.plays) + " · " + listenTime(song.ms, hours)

    /** Lines the daily redraw up (once; a second call keeps the first). */
    fun schedule(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<ReplayWidgetWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }

    /** The last Replay widget of one kind went: the daily redraw stops unless one of the other kind is still placed. */
    fun unscheduleIfNone(context: Context) {
        if (placed(context.applicationContext).isNotEmpty()) return
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK) }
    }

    /** Draws every placed Replay widget again (off the main thread). Cheap when none is placed. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        if (placed(app).isEmpty()) return
        scope.launch { runCatching { draw(app) } }
    }

    /** Reads the week and draws every placed Replay widget. */
    suspend fun draw(context: Context) = lock.withLock {
        val app = context.applicationContext
        val placed = placed(app)
        if (placed.isEmpty()) return@withLock
        val manager = AppWidgetManager.getInstance(app) ?: return@withLock
        val c = app.container
        val fresh = runCatching {
            week(c.imports.replay(), photo = { id, name -> c.library.artistPhoto(c.library.artistThumbs.value, id, name) })
        }.getOrNull()
        val paused = runCatching { com.vinz.music.data.HistoryPause.paused(c.settings.current.behavior) }.getOrDefault(false)
        val w = shown(fresh, last(app), paused)
        if (fresh != null && !fresh.isEmpty) save(app, fresh)
        val hours = runCatching { c.settings.current.replayHours }.getOrDefault(false)
        val white = Widgets.whiteLight(app)
        val cover = w.song?.let { s -> (com.vinz.music.data.PlaylistCovers.artOf(s.toSong()) ?: s.thumbnail)?.let { Widgets.squareCover(app, it, 168, 0.14f) } }
        val photo = w.artistPhoto?.let { Widgets.squareCover(app, it, 144, 0.5f) }
        for ((provider, ids) in placed) for (id in ids) {
            runCatching { manager.updateAppWidget(id, views(app, manager, id, provider == ReplayWideWidget::class.java, w, cover, photo, hours, white)) }
        }
    }

    /** From this height (dp) the song widget has its cover above the title; shorter, the compact one (cover beside its label). */
    const val TALL_DP = 160

    /**
     * The layout an Android 11 or older launcher gets, from the size it says in the options ([minWidth], and the
     * [height] it is drawn at, both dp). A launcher that hasn't said (0) gets the widget's own: the wide one wide.
     */
    fun layoutFor(wideWidget: Boolean, minWidth: Int, height: Int): Int = when {
        (if (minWidth <= 0) wideWidget else minWidth >= WIDE_DP) -> R.layout.widget_replay
        height in 1 until TALL_DP -> R.layout.widget_replay_compact
        else -> R.layout.widget_replay_small
    }

    private fun views(
        context: Context, manager: AppWidgetManager, id: Int, wideWidget: Boolean, w: ReplayWeek, cover: Bitmap?, photo: Bitmap?, hours: Boolean, white: Boolean,
    ): RemoteViews {
        // Android 12+ picks the layout by the size it's drawn at; older launchers say the size in the options.
        if (Build.VERSION.SDK_INT >= 31) return RemoteViews(
            mapOf(
                SizeF(40f, 40f) to views(context, R.layout.widget_replay_compact, w, cover, photo, hours, white),
                SizeF(40f, TALL_DP.toFloat()) to views(context, R.layout.widget_replay_small, w, cover, photo, hours, white),
                SizeF(WIDE_DP.toFloat(), 40f) to views(context, R.layout.widget_replay, w, cover, photo, hours, white),
            ),
        )
        val options = manager.getAppWidgetOptions(id)
        // Upright, a widget is its min width wide and its max height tall.
        val layout = layoutFor(wideWidget, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
        return views(context, layout, w, cover, photo, hours, white)
    }

    /** One widget: [wide] with the artist beside the song. Its words come from [w]; [white]: White light. */
    fun views(context: Context, wide: Boolean, w: ReplayWeek, cover: Bitmap?, photo: Bitmap?, hours: Boolean, white: Boolean): RemoteViews =
        views(context, if (wide) R.layout.widget_replay else R.layout.widget_replay_small, w, cover, photo, hours, white)

    /** One widget in [layout] (wide, small or compact). */
    fun views(context: Context, layout: Int, w: ReplayWeek, cover: Bitmap?, photo: Bitmap?, hours: Boolean, white: Boolean): RemoteViews =
        RemoteViews(context.packageName, layout).apply {
            val wide = layout == R.layout.widget_replay
            val open = PendingIntent.getActivity(
                context, 30,
                // Replay (MainActivity: vinz://replay).
                Intent(context, com.vinz.music.VinzActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("vinz://replay"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            setOnClickPendingIntent(R.id.rp_open, open)
            val song = w.song
            setViewVisibility(R.id.rp_content, if (song == null) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.rp_empty, if (song == null) View.VISIBLE else View.GONE)
            // White light: the spectrum dash in white, like the other widgets' rings.
            if (white) setInt(R.id.rp_dash, "setColorFilter", 0xFFFFFFFF.toInt())
            if (song == null) {
                setTextViewText(R.id.rp_empty_text, tr(R.string.widget_replay_empty))
                return@apply
            }
            setTextViewText(R.id.rp_song_label, tr(R.string.widget_replay_song_label))
            setTextViewText(R.id.rp_title, song.title)
            setTextViewText(R.id.rp_artist, song.artistName)
            setContentDescription(R.id.rp_open, tr(R.string.widget_replay_open) + ". " + tr(R.string.widget_replay_song_label) + ": " + song.title + ", " + song.artistName)
            val pad = ((if (layout == R.layout.widget_replay_compact) 10 else 14) * context.resources.displayMetrics.density).toInt()
            if (cover != null) {
                setImageViewBitmap(R.id.rp_song_art, cover)
                setViewPadding(R.id.rp_song_art, 0, 0, 0, 0)
            } else {
                setImageViewResource(R.id.rp_song_art, R.drawable.ic_notification)
                setViewPadding(R.id.rp_song_art, pad, pad, pad, pad)
            }
            if (!wide) return@apply
            setTextViewText(R.id.rp_stat, songStat(song, hours))
            val artist = w.artist
            setViewVisibility(R.id.rp_artist_box, if (artist == null) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.rp_divider, if (artist == null) View.GONE else View.VISIBLE)
            if (artist == null) return@apply
            setTextViewText(R.id.rp_artist_label, tr(R.string.widget_replay_artist_label))
            setTextViewText(R.id.rp_artist_name, artist.artistName)
            setTextViewText(R.id.rp_artist_time, listenTime(artist.ms, hours))
            setContentDescription(
                R.id.rp_open,
                tr(R.string.widget_replay_open) + ". " + tr(R.string.widget_replay_song_label) + ": " + song.title + ", " + song.artistName +
                    ". " + tr(R.string.widget_replay_artist_label) + ": " + artist.artistName,
            )
            val small = (7 * context.resources.displayMetrics.density).toInt()
            if (photo != null) {
                setImageViewBitmap(R.id.rp_artist_photo, photo)
                setViewPadding(R.id.rp_artist_photo, 0, 0, 0, 0)
            } else {
                setImageViewResource(R.id.rp_artist_photo, R.drawable.ic_notification)
                setViewPadding(R.id.rp_artist_photo, small, small, small, small)
            }
        }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun last(context: Context): ReplayWeek? = prefs(context).getString("last", null)?.let {
        runCatching { json.decodeFromString(ReplayWeek.serializer(), it) }.getOrNull()
    }

    private fun save(context: Context, w: ReplayWeek) {
        prefs(context).edit().putString("last", json.encodeToString(ReplayWeek.serializer(), w)).apply()
    }
}
