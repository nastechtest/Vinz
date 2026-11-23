package com.vinz.music.ui.screens

import com.vinz.music.R
import com.vinz.music.tr
import com.vinz.music.trp
import com.prism.music.data.db.mergeUploads
import com.vinz.music.ui.theme.VinzIcons
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.vinz.music.AppContainer
import com.vinz.music.data.Habits
import com.vinz.music.data.Personality
import com.vinz.music.data.ReplayStats
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.SongPlays
import com.prism.music.data.db.mainArtist
import com.vinz.music.data.model.Song
import com.vinz.music.data.model.hiRes
import com.vinz.music.playback.VinzMediaSourceFactory
import com.vinz.music.playback.QueueKind
import com.vinz.music.playback.QueueSource
import com.vinz.music.playback.toMediaItem
import com.vinz.music.ui.components.PlayingBars
import com.vinz.music.ui.components.recordInto
import com.vinz.music.ui.components.shareLayer
import com.vinz.music.ui.theme.ArtistType
import com.vinz.music.ui.theme.ArtistTypography
import com.vinz.music.ui.theme.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------- Data

/** Everything the Replay story shows, gathered once when it opens. */
internal class ReplayStory(
    val period: Period,
    /** "2026", "October", "Last 30 days", "All time". */
    val title: String,
    val totalMs: Long,
    val previousMs: Long?,
    val previousLabel: String?,
    val plays: Int,
    val activeDays: Int,
    val songCount: Int,
    val artistCount: Int,
    val songs: List<SongPlays>,
    val artists: List<ArtistPlays>,
    val genres: List<Pair<String, Long>>,
    val genreSongs: Map<String, SongPlays>,
    val habits: Habits,
    /** Artists heard for the first time in this period (null for all time, where everyone is new). */
    val newArtists: List<ArtistPlays>?,
    val personality: Personality,
    val topArtistImage: String?,
    val topArtistType: ArtistType,
    /** A year's story: its Rewind, with the parts only a year has. */
    val rewind: Boolean = false,
    val months: List<com.prism.music.data.db.MonthTotal> = emptyList(),
    val topAlbum: com.prism.music.data.db.AlbumPlays? = null,
    val firstSong: com.prism.music.data.db.FirstPlay? = null,
    val seasons: List<com.vinz.music.data.SeasonGenre> = emptyList(),
    val vinz: com.vinz.music.data.VinzYear = com.vinz.music.data.VinzYear(),
    val topLyric: String? = null,
    val previousTopArtist: String? = null,
    val previousSongCount: Int? = null,
    /** "Includes Spotify" when an imported history is counted (1.8.1): every card says so. */
    val includes: String? = null,
    // A year's 1.8.5 slides (see [com.vinz.music.data.RewindSnapshot]); empty for other stories and older kept Rewinds.
    val days: List<Long> = emptyList(),
    val weekHours: List<Long> = emptyList(),
    val hourSongs: List<SongPlays> = emptyList(),
    val milestones: List<com.vinz.music.data.RewindMilestone> = emptyList(),
    val finds: List<SongPlays> = emptyList(),
    val findCount: Int = finds.size,
) {
    /** "VINZ REWIND" for a year, "VINZ REPLAY" otherwise. */
    val brandName: String get() = if (rewind) tr(R.string.vinz_rewind) else tr(R.string.vinz_replay)
    /** [brandName] and what else it counts. */
    val brand: String get() = brandName + (includes?.let { " · " + it.uppercase() } ?: "")
}

/** The facts [StoryParts.pick] chooses this story's slides by. */
internal fun ReplayStory.facts() = com.vinz.music.data.StoryFacts(
    rewind = rewind, totalMs = totalMs, plays = plays, activeDays = activeDays, songs = songs.size, artists = artists.size,
    genres = genres.size, seasonsWithGenre = seasons.count { it.genre != null }, album = topAlbum,
    monthsWithListening = months.count { it.ms > 0 }, firstSong = firstSong != null, newArtists = newArtists?.size ?: 0,
    vinz = vinz.any, previous = previousMs != null,
    calendarDays = days.count { it > 0 }, clockHours = weekHours.count { it > 0 }, hourSong = hourSongs.isNotEmpty(),
    milestones = shownMilestones().size, finds = if (finds.isEmpty()) 0 else maxOf(findCount, finds.size),
)

/** The year's milestones this version can put into words (a kind added later is left out). */
internal fun ReplayStory.shownMilestones(): List<Pair<com.vinz.music.data.RewindMilestone, com.vinz.music.data.Milestone>> =
    milestones.mapNotNull { r -> r.milestone()?.let { r to it } }

/** A year's Rewind as its story: kept (or just worked out) by [com.vinz.music.data.Rewinds], plus the top artist's type. */
private suspend fun loadRewindStory(c: AppContainer, year: Int): ReplayStory = withContext(Dispatchers.IO) {
    val s = c.rewind.snapshot(year)
    val lead = s.artists.firstOrNull()
    val leadPage = lead?.artistId?.let { id -> withTimeoutOrNull(5_000) { runCatching { c.ytm.artist(id) }.getOrNull() } }
    val leadGenre = lead?.let { withTimeoutOrNull(3_000) { runCatching { c.meta.artistGenre(it.artistName) }.getOrNull() } }
    val leadType = if (lead?.artistId != null) ArtistTypography.forArtist(lead.artistId, leadGenre, ArtistTypography.parseAudience(leadPage?.subscribers)) else ArtistTypography.standard
    val photos = c.scope.launch {
        c.library.fetchArtistPhotos((s.artists.take(5) + (s.newArtists ?: emptyList()).take(6)).map { it.artistId to it.artistName })
    }
    withTimeoutOrNull(6_000) { photos.join() }
    val genreSongs = s.genreSongs.mapNotNull { (g, id) -> s.songs.firstOrNull { it.songId == id }?.let { g to it } }.toMap()
    ReplayStory(
        period = Period.year(year), title = "$year",
        totalMs = s.totalMs, previousMs = s.previousMs, previousLabel = if (s.previousMs != null) "${year - 1}" else null,
        plays = s.plays, activeDays = s.activeDays, songCount = s.songCount, artistCount = s.artistCount,
        songs = s.songs, artists = s.artists, genres = s.genres.map { it.genre to it.ms }, genreSongs = genreSongs,
        habits = s.habits(), newArtists = s.newArtists, personality = s.personality,
        topArtistImage = s.topArtistImage ?: leadPage?.thumbnail, topArtistType = leadType,
        rewind = true, months = s.months, topAlbum = s.topAlbum, firstSong = s.firstSong, seasons = s.seasons, vinz = s.vinz,
        topLyric = s.topLyric, previousTopArtist = s.previousTopArtist, previousSongCount = s.previousSongCount,
        includes = includesLine(c),
        days = s.days, weekHours = s.weekHours, hourSongs = s.hourSongs, milestones = s.milestones, finds = s.finds, findCount = s.findCount,
    )
}

/** "Includes Spotify" while an imported history is counted (1.8.1). */
private fun includesLine(c: AppContainer): String? =
    c.imports.counted.value.takeIf { it.isNotEmpty() }?.let { s -> tr(R.string.imp_includes, s.sortedBy { it.ordinal }.joinToString(", ") { it.label }) }

internal suspend fun loadReplayStory(c: AppContainer, period: Period): ReplayStory {
    if (period.isYear) return loadRewindStory(c, period.year)
    return loadPeriodStory(c, period)
}

private suspend fun loadPeriodStory(c: AppContainer, period: Period): ReplayStory = withContext(Dispatchers.IO) {
    val (from, to) = range(period)
    // Vinz's plays, plus the imported sources the listener counts (1.8.1).
    val dao = c.imports.replay()
    val songs = dao.topSongs(from, to, 150).mergeUploads().take(100)
    val artists = dao.topArtists(from, to, 20)
    val totalMs = dao.totalMs(from, to)
    val genreMap = c.meta.cachedGenres(songs.map { it.toSong() })
    val genres = songs.groupBy { genreMap[it.songId] ?: "" }.filterKeys { it.isNotEmpty() }
        .mapValues { (_, v) -> v.sumOf { it.ms } }.toList().sortedByDescending { it.second }.take(5)
    val genreSongs = genres.associate { (g, _) -> g to songs.first { genreMap[it.songId] == g } }

    // The same stretch of time one step back, for "up 24% on last year".
    val (prevFrom, prevTo, prevLabel) = Calendar.getInstance().let {
        fun shift(t: Long, field: Int, by: Int) = Calendar.getInstance().apply { timeInMillis = t; add(field, by) }.timeInMillis
        when (period.kind) {
            Period.Kind.MONTH -> Triple(shift(from, Calendar.MONTH, -1), shift(to, Calendar.MONTH, -1), tr(R.string.the_same_time_last_month))
            Period.Kind.MONTH_OF -> rangeBefore(period, from)!!.let { (a, b) -> Triple(a, b, tr(R.string.the_month_before)) }
            // 1.8.4: a whole day or week already over, against the whole one before it (midnight to midnight).
            Period.Kind.YESTERDAY -> rangeBefore(period, from)!!.let { (a, b) -> Triple(a, b, tr(R.string.the_day_before)) }
            Period.Kind.LAST_WEEK -> rangeBefore(period, from)!!.let { (a, b) -> Triple(a, b, tr(R.string.the_week_before)) }
            Period.Kind.DAYS30 -> Triple(from - 30L * 86_400_000, from - 1, tr(R.string.the_30_days_before))
            Period.Kind.TODAY -> Triple(from - 86_400_000, to - 86_400_000, tr(R.string.this_time_yesterday))
            Period.Kind.WEEK -> Triple(from - 7 * 86_400_000L, to - 7 * 86_400_000L, tr(R.string.the_same_time_last_week))
            // Any days: as many days just before them.
            Period.Kind.DAYS -> (to - from).let { span -> Triple(from - span - 1, from - 1, trp(R.plurals.the_n_days_before, (span / 86_400_000 + 1).toInt())) }
            Period.Kind.YEAR, Period.Kind.ALL -> Triple(0L, -1L, null)
        }
    }
    val previousMs = if (prevLabel != null) dao.totalMs(prevFrom, prevTo).takeIf { it > 60_000 } else null

    val habits = ReplayStats.habits(dao.events(from, to))
    val artistCount = dao.artistCount(from, to)
    val newArtists = if (period == Period.ALL || dao.firstEvent()?.let { it >= from } == true) null else dao.newArtists(from, to)
    val months = if (period == Period.ALL) emptyList() else dao.monthly(from, to)
    val activeDays = dao.activeDays(from, to)
    val plays = dao.count(from, to)
    val songCount = dao.songCount(from, to)
    val top = songs.firstOrNull()
    val genreTotal = genres.sumOf { it.second }.coerceAtLeast(1)

    // The top artist's photo and signature typeface, as on their artist page.
    val lead = artists.firstOrNull()
    val leadPage = lead?.artistId?.let { id -> withTimeoutOrNull(5_000) { runCatching { c.ytm.artist(id) }.getOrNull() } }
    val leadGenre = lead?.let { withTimeoutOrNull(3_000) { runCatching { c.meta.artistGenre(it.artistName) }.getOrNull() } }
    val leadType = if (lead?.artistId != null) ArtistTypography.forArtist(lead.artistId, leadGenre, ArtistTypography.parseAudience(leadPage?.subscribers)) else ArtistTypography.standard
    // Artist photos for the artist slides; the story waits a little for them rather than showing album covers.
    val photos = c.scope.launch {
        c.library.fetchArtistPhotos((artists.take(5) + (newArtists ?: emptyList()).take(6)).map { it.artistId to it.artistName })
    }
    withTimeoutOrNull(6_000) { photos.join() }

    ReplayStory(
        period = period,
        title = when (period.kind) {
            Period.Kind.YEAR -> "${period.year}"
            Period.Kind.MONTH -> monthName(Calendar.getInstance().get(Calendar.MONTH) + 1)
            Period.Kind.MONTH_OF -> monthName(period.month + 1)
            else -> period.label
        },
        totalMs = totalMs, previousMs = previousMs, previousLabel = prevLabel,
        plays = plays, activeDays = activeDays, songCount = songCount, artistCount = artistCount,
        songs = songs, artists = artists, genres = genres, genreSongs = genreSongs, habits = habits, newArtists = newArtists,
        personality = ReplayStats.personality(
            totalMs = totalMs, topFiveArtistsMs = artists.take(5).sumOf { it.ms }, artistCount = artistCount,
            newArtistCount = newArtists?.size, plays = plays, songCount = songCount,
            topSongPlays = top?.plays ?: 0, topSongTitle = top?.title, activeDays = activeDays,
            topGenreShare = genres.firstOrNull()?.let { it.second.toDouble() / genreTotal }, habits = habits,
        ),
        topArtistImage = leadPage?.thumbnail,
        topArtistType = leadType,
        months = months,
        includes = includesLine(c),
    )
}

/**
 * A month's name standing on its own, as a title ("Январь", not the "января" of "1 января" that the plain full name is in
 * Russian, Polish, Ukrainian, Czech…).
 */
internal fun monthName(month: Int, locale: java.util.Locale = java.util.Locale.getDefault()): String =
    java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, locale)
        // Some Java versions have no stand-alone names for a language and give the month's number instead.
        .takeUnless { it == "$month" } ?: java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, locale)

/** January to December in one letter each, in the language shown ("J F M…" in English, "Я Ф М…" in Russian). */
internal fun monthLetters(locale: java.util.Locale = java.util.Locale.getDefault()): List<String> = (1..12).map { m ->
    java.time.Month.of(m).getDisplayName(java.time.format.TextStyle.NARROW_STANDALONE, locale).takeUnless { it == "$m" || it.isBlank() }
        ?: monthName(m, locale).take(1).uppercase(locale)
}

/** Monday to Sunday in a letter or two each, in the language shown. */
internal fun weekdayLetters(locale: java.util.Locale = java.util.Locale.getDefault()): List<String> = java.time.DayOfWeek.entries.map { d ->
    d.getDisplayName(java.time.format.TextStyle.NARROW_STANDALONE, locale).takeUnless { it == "${d.value}" || it.isBlank() }
        ?: d.getDisplayName(java.time.format.TextStyle.SHORT, locale)
}

// ---------------------------------------------------------------- Slides

private typealias Kind = com.vinz.music.data.StoryPart

private class Slide(val kind: Kind, val song: SongPlays?, val colors: List<Color>)

private fun palette(vararg hex: Long) = hex.map { Color(it) }

/** The story's slides ([com.vinz.music.data.StoryParts.pick] says which), each with its colors and the song under it. */
private fun slidesFor(s: ReplayStory): List<Slide> {
    val songs = s.songs
    fun byArtist(name: String?) = songs.firstOrNull { it.mainArtist == name }
    fun byTitle(title: String?) = songs.firstOrNull { it.title == title }
    return com.vinz.music.data.StoryParts.pick(s.facts()).map { part ->
        when (part) {
            Kind.INTRO -> Slide(part, songs.getOrNull(2) ?: songs.firstOrNull(), palette(0xFF14092E, 0xFF7C5CFF, 0xFFFF3B5C, 0xFFFFB13B))
            Kind.MINUTES -> Slide(part, songs.getOrNull(3) ?: songs.firstOrNull(), palette(0xFF04241A, 0xFF1ED38A, 0xFFB8FF3B, 0xFF00B8FF))
            Kind.SEASONS -> Slide(part, byTitle(s.seasons.lastOrNull { it.genre != null }?.topSong) ?: songs.getOrNull(5) ?: songs.firstOrNull(), palette(0xFF1A1205, 0xFFFFB13B, 0xFF5BD6FF, 0xFF8BE36B))
            Kind.GENRES -> Slide(part, s.genreSongs[s.genres.first().first], palette(0xFF2A0820, 0xFFFF4FD8, 0xFFFF8A3D, 0xFF7C5CFF))
            Kind.TOP_ARTIST -> Slide(part, byArtist(s.artists.first().artistName), palette(0xFF0B0B12, 0xFF7C5CFF, 0xFF00C2FF, 0xFFFF3B5C))
            Kind.TOP_ARTISTS -> Slide(part, byArtist(s.artists[1].artistName), palette(0xFF0A1838, 0xFF3D7BFF, 0xFF00E5FF, 0xFFB44DFF))
            Kind.TOP_SONG -> Slide(part, songs.first(), palette(0xFF120A0A, 0xFFFF3B30, 0xFFFF9500, 0xFFFF2D87))
            Kind.TOP_SONGS -> Slide(part, songs.first(), palette(0xFF3B0A0A, 0xFFFF3B30, 0xFFFF9500, 0xFFFFD60A))
            Kind.TOP_ALBUM -> Slide(part, songs.firstOrNull { it.albumTitle == s.topAlbum?.albumTitle } ?: songs.firstOrNull(), palette(0xFF0E1A12, 0xFF2EC27E, 0xFFFFD60A, 0xFF00B4D8))
            Kind.MONTHS -> Slide(part, songs.getOrNull(6) ?: songs.firstOrNull(), palette(0xFF0B1030, 0xFF3A86FF, 0xFFE056FD, 0xFF00E5FF))
            Kind.CALENDAR -> Slide(part, songs.getOrNull(10) ?: songs.firstOrNull(), palette(0xFF061A14, 0xFF1ED38A, 0xFF00B8FF, 0xFFB8FF3B))
            Kind.CLOCK -> Slide(part, s.hourSongs.firstOrNull() ?: songs.getOrNull(11) ?: songs.firstOrNull(), palette(0xFF0A0A26, 0xFF7C5CFF, 0xFFFF7A9C, 0xFF00C2FF))
            Kind.FOUND -> Slide(part, s.finds.firstOrNull() ?: songs.firstOrNull(), palette(0xFF1A0E04, 0xFFFF9500, 0xFFFF3B5C, 0xFFFFD60A))
            Kind.MILESTONES -> Slide(part, songs.getOrNull(12) ?: songs.firstOrNull(), palette(0xFF12081F, 0xFFFFD36B, 0xFFB18CFF, 0xFFFF7A9C))
            Kind.FIRST_SONG -> Slide(part, songs.firstOrNull { it.songId == s.firstSong?.songId } ?: songs.getOrNull(7) ?: songs.firstOrNull(), palette(0xFF201033, 0xFFB18CFF, 0xFFFF7A9C, 0xFFFFD36B))
            Kind.HABITS -> Slide(part, songs.getOrNull(4) ?: songs.firstOrNull(), palette(0xFF050E26, 0xFF5B6CFF, 0xFF00D1FF, 0xFFFFC857))
            Kind.DISCOVERY -> Slide(part, byArtist(s.newArtists!!.first().artistName), palette(0xFF04201F, 0xFF00E0B8, 0xFF9BFF5B, 0xFF00A3FF))
            Kind.VINZ_YEAR -> Slide(part, songs.getOrNull(8) ?: songs.firstOrNull(), palette(0xFF100A24, 0xFFFF7A9C, 0xFF6BE3FF, 0xFFB18CFF))
            Kind.YEAR_COMPARE -> Slide(part, songs.getOrNull(9) ?: songs.firstOrNull(), palette(0xFF07161F, 0xFF00C2FF, 0xFF7C5CFF, 0xFF1ED38A))
            Kind.PERSONALITY -> Slide(part, songs.getOrNull(1) ?: songs.firstOrNull(), palette(0xFF240C4A, 0xFFD53369, 0xFFDAAE51, 0xFF7C5CFF))
            Kind.SUMMARY -> Slide(part, songs.firstOrNull(), palette(0xFF0C0C14, 0xFF7C5CFF, 0xFFFF3B5C, 0xFF00C2FF))
        }
    }
}

// ---------------------------------------------------------------- Background music

/**
 * Plays a stretch of each slide's song from around its chorus, crossfading
 * between them, on its own player so the main queue is left alone.
 */
@OptIn(UnstableApi::class)
private class StoryAudio(context: Context, c: AppContainer) {
    private val bestParts = c.bestParts
    private val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(VinzMediaSourceFactory(c.playbackDataSourceFactory(), c.videoDataSourceFactory()))
        .build().apply { volume = 0f; repeatMode = Player.REPEAT_MODE_ONE }
    var current by mutableStateOf<Song?>(null)
        private set
    var muted = false
        set(v) { field = v; player.volume = if (v) 0f else player.volume.coerceAtLeast(if (current != null) 1f else 0f) }
    private var job: Job? = null

    fun play(song: Song, scope: kotlinx.coroutines.CoroutineScope) {
        if (song.id == current?.id) return
        current = song
        job?.cancel()
        job = scope.launch {
            ramp(0f, 300)
            // Its best part (the most replayed stretch, or the chorus in the lyrics); else roughly where most choruses land.
            val best = withTimeoutOrNull(2_500) { runCatching { bestParts.startMs(song) }.getOrNull() }
            val start = best ?: if (song.durationSec > 90) (song.durationSec * 0.33).toLong().coerceIn(30, 80) * 1000 else 0L
            player.setMediaItem(song.toMediaItem(), start)
            player.prepare()
            player.play()
            withTimeoutOrNull(8_000) { while (player.playbackState != Player.STATE_READY) delay(40) }
            ramp(1f, 1_400)
        }
    }

    private suspend fun ramp(to: Float, ms: Long) {
        val from = player.volume
        val steps = 28
        for (i in 1..steps) {
            player.volume = if (muted) 0f else from + (to - from) * i / steps
            delay(ms / steps)
        }
    }

    fun release() {
        job?.cancel()
        player.release()
    }
}

// ---------------------------------------------------------------- The story

@Composable
internal fun ReplayStoryDialog(story: ReplayStory, onDismiss: () -> Unit) {
    StoryDialog(onDismiss) {
        // #287: in the app's font when "Stories in your font" is on.
        val own = if (com.vinz.music.ui.theme.LocalAppSettings.current.view.replayStoryFont) (androidx.compose.material3.MaterialTheme.typography.displayLarge.fontFamily ?: androidx.compose.ui.text.font.FontFamily.Default) else null
        androidx.compose.runtime.CompositionLocalProvider(LocalStoryFont provides own) { StoryContent(story, onDismiss) }
    }
}

/** The story's full-screen window. The screen stays on while it's open (#412): its slides run on a timer, untouched. */
@Composable
internal fun StoryDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The dialog's own view: its window is the one on top while the story plays.
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        // A story is a phone-shaped thing: on a wide window it stays 9:16 in the middle, on black.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val width = minOf(maxWidth, maxHeight * 9f / 16f)
            Box(Modifier.width(width).fillMaxHeight()) { content() }
        }
    }
}

@Composable
private fun StoryContent(story: ReplayStory, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val slides = remember(story) { slidesFor(story) }
    var index by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val audio = remember { StoryAudio(context, c) }
    val handedOff = remember { booleanArrayOf(false) }
    val slideLayer = rememberGraphicsLayer()

    // Like the big streaming apps: your music pauses for the story and picks up again after it.
    DisposableEffect(Unit) {
        val main = c.player.player
        val wasPlaying = main?.isPlaying == true
        main?.pause()
        onDispose {
            audio.release()
            if (wasPlaying && !handedOff[0]) c.player.player?.play()
        }
    }

    fun go(i: Int) {
        when {
            i in slides.indices -> { progress = 0f; index = i }
            i < 0 -> progress = 0f
        }
    }

    LaunchedEffect(index) { slides[index].song?.let { audio.play(it.toSong(), scope) } }
    LaunchedEffect(muted) { audio.muted = muted }
    LaunchedEffect(index, paused) {
        val ms = slides[index].kind.seconds * 1000f
        if (paused || ms == 0f) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (progress < 1f) {
            withFrameMillis { now -> progress = (progress + (now - last) / ms).coerceAtMost(1f); last = now }
        }
        go(index + 1)
    }

    val slide = slides[index]
    val base by animateColorAsState(slide.colors[0], tween(700), label = "base")
    val c1 by animateColorAsState(slide.colors[1], tween(700), label = "c1")
    val c2 by animateColorAsState(slide.colors[2], tween(700), label = "c2")
    val c3 by animateColorAsState(slide.colors[3], tween(700), label = "c3")

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                translationY = drag.coerceAtLeast(0f) * 0.6f
                val s = 1f - (drag.coerceAtLeast(0f) / 4000f).coerceAtMost(0.12f)
                scaleX = s; scaleY = s
            }
            .clip(RoundedCornerShape(if (drag > 0) 28.dp else 0.dp))
            .background(base),
    ) {
        // The slide (backdrop and all, not the controls over it) is also recorded, for sharing it as a picture.
        Box(Modifier.fillMaxSize().recordInto(slideLayer).background(base)) {
        Backdrop(base, c1, c2, c3)
        Box(
            Modifier.fillMaxSize()
                .pointerInput(slides) {
                    detectTapGestures(
                        onPress = { paused = true; tryAwaitRelease(); paused = false },
                        onTap = { o -> if (o.x < size.width * 0.3f) go(index - 1) else go(index + 1) },
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (drag > 260f) onDismiss() else drag = 0f },
                        onDragCancel = { drag = 0f },
                    ) { _, d -> drag = (drag + d).coerceAtLeast(0f) }
                },
        ) {
            val stillSlides = still()
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    if (stillSlides) fadeIn(tween(250)) togetherWith fadeOut(tween(200))
                    else (fadeIn(tween(450)) + scaleIn(tween(450), initialScale = 1.06f)) togetherWith fadeOut(tween(250))
                },
                label = "slide",
            ) { i ->
                SlideView(slides[i], story, onReplay = { go(0) }, onPlayAll = {
                    handedOff[0] = true
                    c.player.playQueue(story.songs.map { it.toSong() }, 0, QueueSource(QueueKind.REPLAY, (if (story.rewind) tr(R.string.rewind_2) else tr(R.string.replay_4)) + story.title))
                    onDismiss()
                })
            }
        }
        StoryBrand(story)
        }

        // Progress, title, mute and close.
        Column(Modifier.statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                slides.forEachIndexed { i, _ ->
                    val fill = when { i < index -> 1f; i == index -> progress; else -> 0f }
                    Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.28f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Color.White))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // The story's name is drawn with the slide, beside these (so it's in a shared slide too).
                Spacer(Modifier.weight(1f))
                // Any slide as a picture (the summary has its own card too).
                IconButton(onClick = {
                    paused = true
                    scope.launch {
                        val name = if (story.rewind) tr(R.string.rewind_3) else tr(R.string.replay_2)
                        if (!shareLayer(context, slideLayer, "vinz-${name.lowercase()}-${index + 1}.png", tr(R.string.my_vinz, name, story.title), tr(R.string.share_this_slide))) {
                            Toast.makeText(context, tr(R.string.couldn_t_share_this_slide), Toast.LENGTH_SHORT).show()
                        }
                        paused = false
                    }
                }) {
                    Icon(VinzIcons.Share, tr(R.string.share_this_slide), tint = Color.White)
                }
                IconButton(onClick = { muted = !muted }) {
                    Icon(if (muted) VinzIcons.VolumeOff else VinzIcons.VolumeUp, if (muted) tr(R.string.unmute) else tr(R.string.mute), tint = Color.White)
                }
                IconButton(onClick = onDismiss) { Icon(VinzIcons.Close, tr(R.string.close), tint = Color.White) }
            }
        }

        // What's playing, Spotify-style.
        audio.current?.let { song ->
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(hiRes(song.thumbnail, 120), null, Modifier.size(28.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(8.dp))
                // The story's own music, not the player's: it plays while the story shows.
                if (!muted) PlayingBars(Modifier.size(12.dp), playing = true)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${song.title} · ${song.primaryArtist}", color = Color.White, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(220.dp),
                )
            }
        }
    }
}

/** Slow-drifting color fields behind every slide (held still with Reduce motion or Performance mode). */
@Composable
private fun Backdrop(base: Color, c1: Color, c2: Color, c3: Color) {
    val t = drift(0f, 1f, 18_000, rest = 0f, reverse = false, linear = true)
    Canvas(Modifier.fillMaxSize()) {
        val a = t * 2 * PI.toFloat()
        val r = size.maxDimension * 0.62f
        fun blob(color: Color, cx: Float, cy: Float) =
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.75f), color.copy(alpha = 0f)), Offset(cx, cy), r), r, Offset(cx, cy))
        blob(c1, size.width * (0.2f + 0.25f * sin(a)), size.height * (0.18f + 0.12f * cos(a)))
        blob(c2, size.width * (0.85f + 0.15f * cos(a * 2)), size.height * (0.55f + 0.15f * sin(a)))
        blob(c3, size.width * (0.3f + 0.2f * cos(a)), size.height * (0.95f + 0.08f * sin(a * 2)))
        drawRect(Brush.verticalGradient(listOf(base.copy(alpha = 0.15f), base.copy(alpha = 0.55f))))
    }
}

// ---------------------------------------------------------------- Type and motion

/**
 * The app's font for the stories (#287, 1.8.1: "Stories in your font"), or null for Vinz's poster type (condensed and
 * extended display faces, as since 1.7). Provided by [StoryContent] from the settings.
 */
internal val LocalStoryFont = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.ui.text.font.FontFamily?> { null }

/**
 * A slide's big type. In the app's font (#287) it's bold and a touch tighter and smaller (an ordinary face runs wider
 * than the condensed poster one), with a little more line height than the poster faces need.
 */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun display(size: TextUnit, type: ArtistType = ArtistTypography.condensed): TextStyle {
    val own = LocalStoryFont.current ?: return TextStyle(
        fontFamily = type.family, fontWeight = type.weight, fontSize = size, lineHeight = size * 0.94f,
        letterSpacing = type.tracking.em, color = Color.White,
    )
    return TextStyle(fontFamily = own, fontWeight = FontWeight.Bold, fontSize = size * 0.9f, lineHeight = size, letterSpacing = (-0.02).em, color = Color.White)
}

/**
 * A slide's type at a glance, for picking it in Settings (#287): the Minutes slide's words on Replay's colors, in
 * Vinz's poster type or ([own]) the app's font.
 */
@Composable
internal fun StoryTypeSample(own: Boolean, modifier: Modifier = Modifier) {
    val family = if (own) (androidx.compose.material3.MaterialTheme.typography.displayLarge.fontFamily ?: androidx.compose.ui.text.font.FontFamily.Default) else null
    androidx.compose.runtime.CompositionLocalProvider(LocalStoryFont provides family) {
        Column(
            modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(replayGradient())).padding(12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(tr(R.string.you_listened_for), style = kicker(9.sp), maxLines = 1)
            Text("1,204", style = display(40.sp), maxLines = 1)
            Text(tr(R.string.of_music).uppercase(), style = display(16.sp, ArtistTypography.extended), maxLines = 1)
        }
    }
}

/** The small capitals over a slide's figure ("YOU LISTENED FOR"). */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun kicker(size: TextUnit = 14.sp): TextStyle {
    val own = LocalStoryFont.current
    return TextStyle(
        fontFamily = own ?: ArtistTypography.extended.family, fontWeight = if (own != null) FontWeight.SemiBold else FontWeight.Black,
        fontSize = size, letterSpacing = if (own != null) 0.1.em else 0.06.em, color = Color.White,
    )
}

private val body = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, color = Color.White)

/**
 * A slide's big type, at [style]'s size or smaller, so a word is never broken or cut off whatever the language, the
 * font or the phone's width (Reddit: the card's "TODAY" came out as "TODA"). [oneLine] keeps all of it on one line; else
 * it may wrap between words, and the longest word has to fit. [widest] is the text to fit when it changes as it's shown
 * (a figure counting up): the size stays put meanwhile.
 */
@Composable
private fun FitText(text: String, style: TextStyle, modifier: Modifier = Modifier, widest: String = text, oneLine: Boolean = true) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val room = constraints.maxWidth
        val size = remember(widest, style, room, oneLine) {
            fittedSize(style.fontSize.value, room) { s ->
                val sized = style.copy(fontSize = s.sp)
                (if (oneLine) listOf(widest) else widest.split(Regex("\\s+")).filter { it.isNotEmpty() })
                    .maxOfOrNull { measurer.measure(it, sized, maxLines = 1, softWrap = false).size.width } ?: 0
            }
        }
        val scale = size / style.fontSize.value
        Text(
            text, style = style.copy(fontSize = size.sp, lineHeight = style.lineHeight * scale),
            maxLines = if (oneLine) 1 else Int.MAX_VALUE, softWrap = !oneLine,
        )
    }
}

/** The biggest size up to [start] at which [width] fits in [room]. Type's width goes almost in step with its size. */
internal fun fittedSize(start: Float, room: Int, width: (Float) -> Int): Float {
    if (room == androidx.compose.ui.unit.Constraints.Infinity) return start
    var size = start
    repeat(4) {
        val w = width(size)
        if (w <= room) return size
        size = (size * room / w * 0.98f).coerceAtLeast(FIT_MIN_SP)
    }
    return size
}

/** Below this, a title stops being one. */
private const val FIT_MIN_SP = 12f

/** Whether the story draws still: Reduce motion, or Performance mode (which turns it on, [AppSettings.effective]). */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun still(): Boolean = com.vinz.music.ui.theme.LocalUi.current.reduceMotion

/**
 * A slow back-and-forth (or, [reverse] false, round-and-round) between [from] and [to] for a slide's ornament; held at
 * [rest] when the story draws still.
 */
@Composable
private fun drift(from: Float, to: Float, ms: Int, rest: Float = from, reverse: Boolean = true, linear: Boolean = false): Float {
    if (still()) return rest
    val v by rememberInfiniteTransition(label = "drift").animateFloat(
        from, to,
        infiniteRepeatable(tween(ms, easing = if (linear) LinearEasing else androidx.compose.animation.core.FastOutSlowInEasing), if (reverse) RepeatMode.Reverse else RepeatMode.Restart),
        label = "drift",
    )
    return v
}

/** A figure or bar growing to [target] after [delayMs]; there at once when the story draws still. */
@Composable
private fun grow(target: Float, delayMs: Long, ms: Int, easing: androidx.compose.animation.core.Easing = androidx.compose.animation.core.FastOutSlowInEasing): Float {
    val still = still()
    val a = remember { Animatable(if (still) target else 0f) }
    LaunchedEffect(target, still) {
        if (still) a.snapTo(target) else { delay(delayMs); a.animateTo(target, tween(ms, easing = easing)) }
    }
    return a.value
}

/** Rises and fades in after [delayMs] (at once, without moving, when the story draws still). */
@Composable
private fun Modifier.reveal(delayMs: Int): Modifier {
    if (still()) return this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        a.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow))
    }
    return graphicsLayer { alpha = a.value.coerceIn(0f, 1f); translationY = (1f - a.value) * 90f }
}

private fun minutesOf(ms: Long) = ms / 60_000
private fun fmt(n: Long) = "%,d".format(n)

@Composable
private fun SlideView(slide: Slide, s: ReplayStory, onReplay: () -> Unit, onPlayAll: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        val pad = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 26.dp, end = 26.dp, top = 84.dp, bottom = 64.dp)
        when (slide.kind) {
            Kind.INTRO -> Intro(s, pad)
            Kind.MINUTES -> Minutes(s, pad)
            Kind.SEASONS -> Seasons(s, pad)
            Kind.GENRES -> Genres(s, pad)
            Kind.TOP_ARTIST -> TopArtist(s, pad)
            Kind.TOP_ARTISTS -> TopArtists(s, pad)
            Kind.TOP_SONG -> TopSong(s, pad)
            Kind.TOP_SONGS -> TopSongs(s, pad)
            Kind.TOP_ALBUM -> TopAlbum(s, pad)
            Kind.MONTHS -> Months(s, pad)
            Kind.CALENDAR -> YearCalendar(s, pad)
            Kind.FIRST_SONG -> FirstSong(s, pad)
            Kind.HABITS -> HabitsSlide(s.habits, pad)
            Kind.CLOCK -> WeekClock(s, pad)
            Kind.DISCOVERY -> Discovery(s, pad)
            Kind.FOUND -> Found(s, pad)
            Kind.MILESTONES -> YearMilestones(s, pad)
            Kind.VINZ_YEAR -> VinzYearSlide(s, pad)
            Kind.YEAR_COMPARE -> YearCompare(s, pad)
            Kind.PERSONALITY -> PersonalitySlide(s.personality, pad)
            Kind.SUMMARY -> Summary(s, pad, onReplay, onPlayAll)
        }
    }
}

/**
 * The story's name over every slide, where the buttons' row has it on screen. Drawn with the slide, so a shared slide
 * carries it too ("VINZ REWIND · INCLUDES SPOTIFY · 2026"), not only the intro and the summary card (1.8.5).
 */
@Composable
private fun StoryBrand(story: ReplayStory) {
    Box(
        Modifier.statusBarsPadding().padding(start = 14.dp, end = BRAND_END, top = 15.dp).heightIn(min = 48.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The name and year first, what else is counted under it: a long "Includes …" can't push the year out of sight.
        Column {
            Text(
                "${story.brandName} · ${story.title.uppercase()}", style = kicker(12.sp), color = Color.White.copy(alpha = 0.85f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            story.includes?.let {
                Text(it.uppercase(), style = kicker(9.sp), color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One slide on its own, as a shared slide's picture has it: backdrop, slide and the story's name (for render tests). */
@Composable
internal fun StorySlide(kind: com.vinz.music.data.StoryPart, s: ReplayStory) {
    val slide = slidesFor(s).firstOrNull { it.kind == kind } ?: Slide(kind, null, palette(0xFF0C0C14, 0xFF7C5CFF, 0xFFFF3B5C, 0xFF00C2FF))
    Box(Modifier.fillMaxSize().background(slide.colors[0])) {
        Backdrop(slide.colors[0], slide.colors[1], slide.colors[2], slide.colors[3])
        SlideView(slide, s, onReplay = {}, onPlayAll = {})
        StoryBrand(s)
    }
}

/** Room for every row a slide has; at the largest text sizes (over 1.5×) the lists are cut short so nothing is cut off. */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun roomy(): Boolean = androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.5f

/** Where the buttons over the story start, from the end: the row's padding and its three buttons. */
private val BRAND_END = 10.dp + 48.dp * 3

/** An hour of the day as the phone writes it ("10 PM", "22 Uhr"…). */
private fun hourText(hour: Int): String =
    runCatching { java.time.LocalTime.of(hour, 0).format(com.vinz.music.localDates("j")) }.getOrNull() ?: ReplayStats.hourLabel(hour)

/** Five shades for the calendar: 0 for no music, then 1–4 by where the day stands among the year's days with music. */
internal fun calendarShades(days: List<Long>): IntArray {
    val lit = days.filter { it > 0 }.sorted()
    if (lit.isEmpty()) return IntArray(days.size)
    val cuts = listOf(0.25, 0.5, 0.75).map { lit[((lit.size - 1) * it).toInt()] }
    return IntArray(days.size) { i ->
        val ms = days[i]
        when {
            ms <= 0 -> 0
            ms <= cuts[0] -> 1
            ms <= cuts[1] -> 2
            ms <= cuts[2] -> 3
            else -> 4
        }
    }
}

private val shadeAlpha = floatArrayOf(0.10f, 0.32f, 0.52f, 0.76f, 1f)

/** The year as a calendar: a row for each month, a square for each day, lighter where there was more music. */
@Composable
private fun YearCalendar(s: ReplayStory, modifier: Modifier) {
    val year = s.period.year.takeIf { it > 0 } ?: s.title.toIntOrNull() ?: java.time.Year.now().value
    val lit = s.days.count { it > 0 }
    val shades = remember(s.days) { calendarShades(s.days) }
    val letters = monthLetters()
    val grown = grow(1f, 500, 1_200)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.rw_calendar_title), style = kicker(), modifier = Modifier.reveal(100), maxLines = 2, overflow = TextOverflow.Ellipsis)
        FitText(fmt(lit.toLong()), display(110.sp), Modifier.reveal(250))
        FitText(trp(R.plurals.rw_days_with_music, lit).uppercase(), display(36.sp, ArtistTypography.extended), Modifier.reveal(400), oneLine = false)
        Spacer(Modifier.height(26.dp))
        Canvas(Modifier.fillMaxWidth().aspectRatio(33f / 18f).reveal(500)) {
            val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
            val cw = size.width / 33f
            val label = cw * 2
            val ch = size.height / 12f
            val gap = minOf(cw, ch) * 0.2f
            // The month's letter is sized by its row, not the font size: it's part of the picture.
            val letterStyle = TextStyle(fontSize = (ch * 0.62f).toSp(), fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.7f))
            var i = 0
            for (m in 1..12) {
                val y = (m - 1) * ch
                val t = measurer.measure(letters[m - 1], letterStyle, maxLines = 1)
                val lx = (label - t.size.width) / 2
                drawText(t, topLeft = Offset(if (rtl) size.width - lx - t.size.width else lx, y + (ch - t.size.height) / 2))
                for (d in 0 until java.time.YearMonth.of(year, m).lengthOfMonth()) {
                    val level = shades.getOrElse(i++) { 0 }
                    val x = label + d * cw
                    val a = shadeAlpha[0] + (shadeAlpha[level] - shadeAlpha[0]) * grown
                    drawRoundRect(
                        Color.White.copy(alpha = a),
                        topLeft = Offset((if (rtl) size.width - x - cw else x) + gap / 2, y + gap / 2),
                        size = androidx.compose.ui.geometry.Size(cw - gap, ch - gap),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(gap),
                    )
                }
            }
        }
    }
}

/** The week as a clock: a row for each weekday, a square for each hour; and the song of the busiest hour. */
@Composable
private fun WeekClock(s: ReplayStory, modifier: Modifier) {
    val cells = s.weekHours
    val peak = s.habits.peakHour
    val days = weekdayLetters()
    val grown = grow(1f, 400, 1_200)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.rw_clock_title), style = kicker(), modifier = Modifier.reveal(100), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(22.dp))
        Canvas(Modifier.fillMaxWidth().aspectRatio(26f / (8 * 1.15f)).reveal(250)) {
            val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
            val cw = size.width / 26f
            val label = cw * 2
            val ch = size.height / 8f
            val gap = minOf(cw, ch) * 0.2f
            val max = (cells.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
            val labelStyle = TextStyle(fontSize = (ch * 0.55f).toSp(), fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.7f))
            fun xOf(left: Float, w: Float) = if (rtl) size.width - left - w else left
            for (wd in 0 until 7) {
                val y = wd * ch
                val t = measurer.measure(days[wd], labelStyle, maxLines = 1)
                drawText(t, topLeft = Offset(xOf((label - t.size.width) / 2, t.size.width.toFloat()), y + (ch - t.size.height) / 2))
                for (h in 0 until 24) {
                    val v = cells.getOrElse(wd * 24 + h) { 0L }
                    // Square root: the quiet hours still show against a few very busy ones.
                    val share = kotlin.math.sqrt(v / max)
                    val a = 0.08f + (if (v > 0) 0.14f + 0.78f * share else 0f) * grown
                    drawRoundRect(
                        if (h == peak && v > 0) Color.White.copy(alpha = maxOf(a, 0.5f * grown)) else Color.White.copy(alpha = a),
                        topLeft = Offset(xOf(label + h * cw, cw) + gap / 2, y + gap / 2),
                        size = androidx.compose.ui.geometry.Size(cw - gap, ch - gap),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(gap),
                    )
                }
            }
            // Hours along the bottom: midnight, 6, noon, 18.
            for (h in listOf(0, 6, 12, 18)) {
                val t = measurer.measure(hourText(h), labelStyle, maxLines = 1)
                drawText(t, topLeft = Offset(xOf(label + h * cw, t.size.width.toFloat()), 7 * ch + (ch - t.size.height) / 2))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(tr(R.string.prime_time), style = kicker(11.sp).copy(color = Color.White.copy(alpha = 0.75f)), modifier = Modifier.reveal(700))
        FitText(hourText(peak).uppercase(), display(84.sp), Modifier.reveal(800))
        s.hourSongs.firstOrNull()?.let { song ->
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp).reveal(1_200).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(hiRes(song.thumbnail, 226), null, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.rw_clock_song, hourText(peak)).uppercase(), style = kicker(11.sp).copy(color = Color.White.copy(alpha = 0.75f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.title, style = body.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artistName, style = body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = Color.White.copy(alpha = 0.75f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** What Vinz found for you: songs first heard on its radio, Smart Shuffle, DJ or a mix that you kept playing. */
@Composable
private fun Found(s: ReplayStory, modifier: Modifier) {
    val n = maxOf(s.findCount, s.finds.size)
    val roomy = roomy()
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.rw_found_title), style = kicker(), modifier = Modifier.reveal(100), maxLines = 2, overflow = TextOverflow.Ellipsis)
        FitText(fmt(n.toLong()), display(120.sp), Modifier.reveal(250))
        FitText(trp(R.plurals.rw_found_songs, n).uppercase(), display(36.sp, ArtistTypography.extended), Modifier.reveal(400), oneLine = false)
        Text(tr(R.string.rw_found_line), style = body.copy(fontSize = 16.sp, lineHeight = 22.sp), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.reveal(600).padding(top = 8.dp))
        Spacer(Modifier.height(18.dp))
        s.finds.take(if (roomy) 3 else 2).forEachIndexed { i, song ->
            Row(Modifier.padding(vertical = 6.dp).reveal(800 + i * 200), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(hiRes(song.thumbnail, 226), null, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = body.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${song.artistName} · ${trp(R.plurals.n_plays, song.plays, song.plays)}", style = body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = Color.White.copy(alpha = 0.75f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (s.vinz.radarFinds > 0 && roomy) {
            Spacer(Modifier.height(10.dp))
            Fact(tr(R.string.release_radar_finds), fmt(s.vinz.radarFinds.toLong()), 1_500)
        }
    }
}

/** The year's milestones: the biggest of each kind and the two songs played the most times over. */
@Composable
private fun YearMilestones(s: ReplayStory, modifier: Modifier) {
    val shown = s.shownMilestones().let { all -> if (roomy()) all else all.takeLast(3) }
    val fmtDay = com.vinz.music.localDates("MMMd")
    val zone = java.time.ZoneId.systemDefault()
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.rw_milestones_title), style = kicker(), modifier = Modifier.reveal(100), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            shown.forEachIndexed { i, (r, m) ->
                Row(
                    Modifier.fillMaxWidth().reveal(300 + i * 220).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (r.thumbnail != null) {
                        AsyncImage(hiRes(r.thumbnail, 226), null, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                            Icon(if (m.kind == com.vinz.music.data.MilestoneKind.STREAK) VinzIcons.FlameFilled else VinzIcons.StarFilled, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            com.vinz.music.data.MilestoneWatch.line(m, r.songTitle),
                            style = body.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            java.time.Instant.ofEpochMilli(r.at).atZone(zone).format(fmtDay),
                            style = body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = Color.White.copy(alpha = 0.7f)), maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Intro(s: ReplayStory, modifier: Modifier) {
    val spin = drift(-4f, 4f, 3_000, rest = 0f)
    Column(modifier, verticalArrangement = Arrangement.Center) {
        // A fanned stack of your top three covers.
        Box(Modifier.fillMaxWidth().height(250.dp).reveal(100), contentAlignment = Alignment.Center) {
            s.songs.take(3).reversed().forEachIndexed { i, song ->
                val angle = (i - 1) * 12f + spin * (if (i == 2) 1f else 0.4f)
                AsyncImage(
                    hiRes(song.thumbnail, 544), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(190.dp).offset(x = ((i - 1) * 46).dp).rotate(angle)
                        .shadow(24.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)),
                )
            }
        }
        Spacer(Modifier.height(36.dp))
        Text(s.brand, style = kicker(), modifier = Modifier.reveal(350))
        FitText(s.title.uppercase(), display(if (s.title.length > 6) 76.sp else 120.sp), Modifier.reveal(550))
        Text(
            when (s.period.kind) {
                Period.Kind.YEAR -> tr(R.string.your_year_in_music_2)
                Period.Kind.MONTH, Period.Kind.MONTH_OF -> tr(R.string.your_month_in_music)
                Period.Kind.DAYS30 -> tr(R.string.your_last_30_days_in_music)
                Period.Kind.TODAY -> tr(R.string.your_day_in_music)
                Period.Kind.YESTERDAY -> tr(R.string.your_yesterday_in_music)
                Period.Kind.WEEK, Period.Kind.LAST_WEEK -> tr(R.string.your_week_in_music)
                Period.Kind.DAYS -> tr(R.string.those_days_in_music)
                Period.Kind.ALL -> tr(R.string.everything_you_ve_played_in_one)
            },
            style = body.copy(fontSize = 24.sp), modifier = Modifier.reveal(800).padding(top = 8.dp),
        )
        Text(tr(R.string.tap_to_go_on_hold_to), style = body.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f)), modifier = Modifier.reveal(1_400).padding(top = 24.dp))
    }
}

@Composable
private fun Minutes(s: ReplayStory, modifier: Modifier) {
    val total = listenTotal(s.totalMs)
    val count = grow(total.value.toFloat(), 400, 2_200)
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.you_listened_for), style = kicker(), modifier = Modifier.reveal(100))
        // Counting up ends on the exact figure (a float can't hold every minute count exactly).
        val text = total.text(if (count >= total.value.toFloat()) total.value else count.toLong())
        val final = total.text(total.value)
        val longest = final.length
        FitText(text, display(if (longest > 8) 72.sp else if (longest > 6) 96.sp else 130.sp), Modifier.reveal(200), widest = final)
        FitText(total.unit.ifEmpty { tr(R.string.of_music) }.uppercase(), display(46.sp, ArtistTypography.extended), Modifier.reveal(400), oneLine = false)
        Spacer(Modifier.height(28.dp))
        val hours = s.totalMs / 3_600_000.0
        Text(
            if (hours >= 24) tr(R.string.that_s_1f_days_of_music).format(hours / 24) else tr(R.string.that_s_1f_hours_of_music).format(hours),
            style = body, modifier = Modifier.reveal(2_400),
        )
        if (s.previousMs != null && s.previousLabel != null) {
            val change = ((s.totalMs - s.previousMs) * 100.0 / s.previousMs).toInt()
            Row(Modifier.reveal(3_000).padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (change >= 0) VinzIcons.TrendingUp else VinzIcons.TrendingDown, null, tint = Color.White, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (change >= 0) tr(R.string.more_than, change, s.previousLabel) else tr(R.string.less_than, -change, s.previousLabel), style = body)
            }
        }
        Row(Modifier.reveal(3_600).padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Stat(fmt(s.songCount.toLong()), trp(R.plurals.songs_word, s.songCount))
            Stat(fmt(s.artistCount.toLong()), trp(R.plurals.artists_word, s.artistCount))
            Stat(fmt(s.activeDays.toLong()), trp(R.plurals.days_word, s.activeDays))
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = display(40.sp))
        Text(label, style = body.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)))
    }
}

@Composable
private fun Genres(s: ReplayStory, modifier: Modifier) {
    val total = s.genres.sumOf { it.second }.coerceAtLeast(1)
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.your_top_genre), style = kicker(), modifier = Modifier.reveal(100))
        FitText(com.vinz.music.data.meta.Genres.shown(s.genres.first().first).uppercase(), display(72.sp), Modifier.reveal(300), oneLine = false)
        Spacer(Modifier.height(30.dp))
        s.genres.forEachIndexed { i, (g, ms) ->
            val share = ms.toFloat() / total
            val w = grow(share, 700L + i * 160, 900)
            Column(Modifier.padding(vertical = 6.dp).reveal(600 + i * 160)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(g, style = body.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    Text("${(share * 100).toInt()}%", style = body.copy(fontSize = 17.sp))
                }
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(12.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(w).clip(CircleShape).background(Color.White.copy(alpha = if (i == 0) 1f else 0.7f)))
                }
            }
        }
    }
}

@Composable
private fun TopArtist(s: ReplayStory, modifier: Modifier) {
    val a = s.artists.first()
    val thumbs by LocalContainer.current.library.artistThumbs.collectAsState()
    val c = LocalContainer.current
    val image = s.topArtistImage ?: c.library.artistPhoto(thumbs, a.artistId, a.artistName)
    val zoom = drift(1.05f, 1.18f, 9_000)
    Box(Modifier.fillMaxSize()) {
        AsyncImage(hiRes(image, 2880), null, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom }, contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.35f to Color.Transparent, 0.6f to Color.Black.copy(alpha = 0.35f), 1f to Color.Black.copy(alpha = 0.9f))))
        Column(modifier, verticalArrangement = Arrangement.Bottom) {
            Text(tr(R.string.your_top_artist_2), style = kicker(), modifier = Modifier.reveal(300))
            val type = s.topArtistType
            // In your font (#287) the name is set in it too, at the artist's size, not in their signature's face.
            val own = LocalStoryFont.current
            Text(
                if (type.caps && own == null) a.artistName.uppercase() else a.artistName,
                style = TextStyle(
                    fontFamily = own ?: type.family, fontWeight = if (own != null) FontWeight.Bold else type.weight, fontSize = (type.size * 1.25f).coerceAtMost(72f).sp,
                    lineHeight = (type.size * 1.25f * type.lineHeight).coerceAtMost(72f).sp, letterSpacing = type.tracking.em, color = Color.White,
                    fontStyle = if (type.italic && own == null) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                ),
                maxLines = 3, modifier = Modifier.reveal(600),
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.reveal(1_200), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                listenTotal(a.ms).let { t -> Stat(t.text(t.value), t.unit.ifEmpty { tr(R.string.listened).lowercase() }) }
                Stat(fmt(a.plays.toLong()), trp(R.plurals.plays_word, a.plays))
                val theirs = s.songs.count { it.mainArtist == a.artistName }
                Stat(fmt(theirs.toLong()), trp(R.plurals.songs_word, theirs))
            }
            val share = (a.ms * 100.0 / s.totalMs.coerceAtLeast(1)).toInt()
            Text(tr(R.string.of_everything_you_played_was_them, share), style = body, modifier = Modifier.reveal(1_800).padding(top = 16.dp))
        }
    }
}

@Composable
private fun TopArtists(s: ReplayStory, modifier: Modifier) {
    val lib = LocalContainer.current.library
    val thumbs by lib.artistThumbs.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.your_top_artists), style = kicker(), modifier = Modifier.reveal(100))
        Spacer(Modifier.height(20.dp))
        s.artists.take(5).forEachIndexed { i, a ->
            Row(Modifier.padding(vertical = 8.dp).reveal(300 + i * 220), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = display(52.sp), modifier = Modifier.width(44.dp))
                AsyncImage(
                    hiRes(lib.artistPhoto(thumbs, a.artistId, a.artistName), 300), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(if (i == 0) 76.dp else 62.dp).clip(CircleShape).border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.artistName, style = body.copy(fontSize = if (i == 0) 24.sp else 20.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listenTime(a.ms), style = body.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)))
                }
            }
        }
    }
}

@Composable
private fun TopSong(s: ReplayStory, modifier: Modifier) {
    val song = s.songs.first()
    val pulse = drift(1f, 1.035f, 520)
    Box(Modifier.fillMaxSize()) {
        AsyncImage(hiRes(song.thumbnail, 300), null, Modifier.fillMaxSize().blur(60.dp).graphicsLayer { alpha = 0.55f }, contentScale = ContentScale.Crop)
        // Keeps white text readable over light covers.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
        Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr(R.string.your_1_song_2), style = kicker(), modifier = Modifier.reveal(100))
            Spacer(Modifier.height(22.dp))
            AsyncImage(
                hiRes(song.thumbnail, 900), null, contentScale = ContentScale.Crop,
                modifier = Modifier.reveal(250).size(270.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }
                    .shadow(40.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.height(26.dp))
            Text(song.title.uppercase(), style = display(48.sp), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.reveal(700))
            Text(song.artistName, style = body.copy(fontSize = 20.sp), textAlign = TextAlign.Center, modifier = Modifier.reveal(900).padding(top = 6.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                trp(R.plurals.played_it_n_times, song.plays, song.plays, listenTime(song.ms)),
                style = body, textAlign = TextAlign.Center, modifier = Modifier.reveal(1_500),
            )
            // The line it's playing from (its best part).
            s.topLyric?.let { line ->
                Text(
                    "“$line”", style = body.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                    textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.reveal(2_400).padding(top = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun TopSongs(s: ReplayStory, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.your_top_songs), style = kicker(), modifier = Modifier.reveal(100))
        Spacer(Modifier.height(20.dp))
        s.songs.take(5).forEachIndexed { i, song ->
            Row(Modifier.padding(vertical = 7.dp).reveal(300 + i * 220), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = display(52.sp), modifier = Modifier.width(44.dp))
                AsyncImage(hiRes(song.thumbnail, 226), null, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = body.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${song.artistName} · ${listenTime(song.ms)}", style = body.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun HabitsSlide(h: Habits, modifier: Modifier) {
    val grown = grow(1f, 300, 1_400)
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(tr(R.string.when_you_listen), style = kicker(), modifier = Modifier.reveal(100).fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        // A 24-hour clock: each spoke is an hour, its length how much you listened then.
        Box(Modifier.size(280.dp).reveal(200), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val max = h.byHour.max().coerceAtLeast(1)
                val inner = size.minDimension * 0.24f
                val outer = size.minDimension * 0.5f
                for (hr in 0 until 24) {
                    val ang = (hr / 24f) * 2 * PI.toFloat() - PI.toFloat() / 2
                    val len = (outer - inner) * (0.06f + 0.94f * h.byHour[hr] / max.toFloat()) * grown
                    val dir = Offset(cos(ang), sin(ang))
                    drawLine(
                        if (hr == h.peakHour) Color.White else Color.White.copy(alpha = 0.45f),
                        center + dir * inner, center + dir * (inner + len), strokeWidth = 11.dp.toPx() * 0.9f, cap = StrokeCap.Round,
                    )
                }
                drawCircle(Color.White.copy(alpha = 0.25f), inner - 8.dp.toPx(), style = Stroke(1.5f.dp.toPx()))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr(R.string.prime_time), style = kicker(10.sp))
                Text(ReplayStats.hourLabel(h.peakHour), style = display(34.sp))
            }
        }
        Spacer(Modifier.height(26.dp))
        val fmtDay = com.vinz.music.localDates("MMMd")
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact(tr(R.string.favorite_day), ReplayStats.weekdays[h.topWeekday], 1_200)
            if (h.longestStreak > 1) Fact(tr(R.string.longest_streak), trp(R.plurals.n_days_in_a_row, h.longestStreak), 1_450)
            h.biggestDay?.let { Fact(tr(R.string.biggest_day), "${it.format(fmtDay)} · ${listenTime(h.biggestDayMs)}", 1_700) }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, delayMs: Int) {
    Row(
        Modifier.fillMaxWidth().reveal(delayMs).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = kicker(11.sp).copy(color = Color.White.copy(alpha = 0.75f)), modifier = Modifier.weight(1f))
        Text(value, style = body.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun Discovery(s: ReplayStory, modifier: Modifier) {
    val found = s.newArtists.orEmpty()
    val lib = LocalContainer.current.library
    val thumbs by lib.artistThumbs.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.new_to_you), style = kicker(), modifier = Modifier.reveal(100))
        FitText(fmt(found.size.toLong()), display(140.sp), Modifier.reveal(250))
        FitText(trp(R.plurals.new_artists_title, found.size), display(44.sp, ArtistTypography.extended), Modifier.reveal(450), oneLine = false)
        Text(tr(R.string.found_their_way_into_your_rotation), style = body, modifier = Modifier.reveal(700).padding(top = 8.dp))
        Spacer(Modifier.height(26.dp))
        Row(Modifier.reveal(1_000)) {
            found.take(6).forEachIndexed { i, a ->
                AsyncImage(
                    hiRes(lib.artistPhoto(thumbs, a.artistId, a.artistName), 226), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.offset(x = (-14 * i).dp).size(62.dp).clip(CircleShape).border(3.dp, Color(0xFF04201F), CircleShape),
                )
            }
        }
        found.firstOrNull()?.let { a ->
            Text(
                tr(R.string.your_favorite_find_with, a.artistName, listenTime(a.ms)),
                style = body, modifier = Modifier.reveal(1_500).padding(top = 22.dp),
            )
        }
    }
}

@Composable
private fun PersonalitySlide(p: Personality, modifier: Modifier) {
    val turn = drift(0f, 360f, 16_000, reverse = false, linear = true)
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.your_listening_personality), style = kicker(), modifier = Modifier.reveal(100))
        Spacer(Modifier.height(24.dp))
        // A slowly turning vinz, Vinz's emblem.
        Canvas(Modifier.size(170.dp).reveal(300).rotate(turn)) {
            val w = size.width
            val tri = Path().apply {
                moveTo(w / 2, w * 0.08f); lineTo(w * 0.94f, w * 0.84f); lineTo(w * 0.06f, w * 0.84f); close()
            }
            drawPath(tri, Brush.sweepGradient(listOf(Color(0xFFFF7A9C), Color(0xFFFFD36B), Color(0xFF6BE3FF), Color(0xFFB18CFF), Color(0xFFFF7A9C))))
            drawPath(tri, Color.White.copy(alpha = 0.6f), style = Stroke(3.dp.toPx()))
        }
        Spacer(Modifier.height(28.dp))
        FitText(p.name.uppercase(), display(64.sp), Modifier.reveal(700), oneLine = false)
        Text(p.line, style = body, modifier = Modifier.reveal(1_200).padding(top = 14.dp))
    }
}

/** The year's sound, season by season (by months, as hemispheres disagree on the names). */
@Composable
private fun Seasons(s: ReplayStory, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.your_year_season_by_season), style = kicker(), modifier = Modifier.reveal(100))
        Spacer(Modifier.height(22.dp))
        s.seasons.forEachIndexed { i, q ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).reveal(300 + i * 260)
                    .clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text(q.label.uppercase(), style = kicker(11.sp).copy(color = Color.White.copy(alpha = 0.75f)))
                Text(q.genre?.let { com.vinz.music.data.meta.Genres.shown(it) } ?: tr(R.string.a_bit_of_everything), style = display(34.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                q.topSong?.let { Text(tr(R.string.on_repeat, it), style = body.copy(fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f)), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun TopAlbum(s: ReplayStory, modifier: Modifier) {
    val a = s.topAlbum ?: return
    val turn = drift(0f, 360f, 12_000, reverse = false, linear = true)
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(tr(R.string.your_top_album), style = kicker(), modifier = Modifier.reveal(100).fillMaxWidth())
        Spacer(Modifier.height(26.dp))
        // The cover, with its record sliding out from behind it.
        Box(Modifier.reveal(250).size(width = 300.dp, height = 240.dp)) {
            Box(Modifier.align(Alignment.CenterEnd).size(220.dp).rotate(turn).clip(CircleShape).background(Color(0xFF111111))) {
                Box(Modifier.align(Alignment.Center).size(80.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.85f)))
                AsyncImage(hiRes(a.thumbnail, 300), null, Modifier.align(Alignment.Center).size(70.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            }
            AsyncImage(
                hiRes(a.thumbnail, 900), null, contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.CenterStart).size(230.dp).shadow(30.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)),
            )
        }
        Spacer(Modifier.height(26.dp))
        Text(a.albumTitle.uppercase(), style = display(44.sp), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.reveal(700))
        Text(a.artistName, style = body.copy(fontSize = 20.sp), modifier = Modifier.reveal(900).padding(top = 6.dp))
        Text(
            tr(R.string.of_its_songs_plays, trp(R.plurals.n_of_its_songs, a.songs), trp(R.plurals.n_plays, a.plays), listenTime(a.ms)),
            style = body, textAlign = TextAlign.Center, modifier = Modifier.reveal(1_400).padding(top = 16.dp),
        )
    }
}

/** Month by month, the busiest one picked out. */
@Composable
private fun Months(s: ReplayStory, modifier: Modifier) {
    val grown = grow(1f, 300, 1_400)
    val byMonth = LongArray(12)
    s.months.forEach { m -> m.month.substringAfter('-').toIntOrNull()?.let { if (it in 1..12) byMonth[it - 1] += m.ms } }
    val peak = byMonth.indices.maxByOrNull { byMonth[it] } ?: 0
    // In the language shown (the letters were English's "J F M…" in every language), the name as a title.
    val letters = monthLetters()
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.your_biggest_month), style = kicker(), modifier = Modifier.reveal(100))
        FitText(monthName(peak + 1).uppercase(), display(72.sp), Modifier.reveal(300))
        Text(tr(R.string.that_month, listenTime(byMonth[peak])), style = body, modifier = Modifier.reveal(600).padding(top = 6.dp))
        Spacer(Modifier.height(30.dp))
        Canvas(Modifier.fillMaxWidth().height(200.dp).reveal(400)) {
            val max = byMonth.max().coerceAtLeast(1)
            val gap = 6.dp.toPx()
            val w = (size.width - gap * 11) / 12
            // Right to left in Arabic, Hebrew…, as the letters under the bars are.
            val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
            for (i in 0 until 12) {
                val h = (size.height * byMonth[i] / max.toFloat() * grown).coerceAtLeast(3.dp.toPx())
                val x = i * (w + gap)
                drawRoundRect(
                    if (i == peak) Color.White else Color.White.copy(alpha = 0.38f),
                    topLeft = Offset(if (rtl) size.width - x - w else x, size.height - h), size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            letters.forEach { Text(it, Modifier.weight(1f), style = body.copy(fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f)), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Clip) }
        }
    }
}

@Composable
private fun FirstSong(s: ReplayStory, modifier: Modifier) {
    val f = s.firstSong ?: return
    val at = java.time.Instant.ofEpochMilli(f.timestamp).atZone(java.time.ZoneId.systemDefault())
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(tr(R.string.where_it_started), style = kicker(), modifier = Modifier.reveal(100).fillMaxWidth())
        Spacer(Modifier.height(24.dp))
        AsyncImage(
            hiRes(f.thumbnail, 900), null, contentScale = ContentScale.Crop,
            modifier = Modifier.reveal(250).size(240.dp).shadow(30.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)),
        )
        Spacer(Modifier.height(24.dp))
        Text(tr(R.string.your_first_song_of, s.title), style = body, modifier = Modifier.reveal(600))
        Text(f.title.uppercase(), style = display(44.sp), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.reveal(800).padding(top = 6.dp))
        Text(f.artistName, style = body.copy(fontSize = 20.sp), modifier = Modifier.reveal(1_000).padding(top = 4.dp))
        Text(
            tr(R.string.date_at_time, at.format(com.vinz.music.localDates("MMMMd")), at.format(com.vinz.music.localDates("jmm"))),
            style = body.copy(fontSize = 15.sp, color = Color.White.copy(alpha = 0.75f)), modifier = Modifier.reveal(1_400).padding(top = 12.dp),
        )
    }
}

/** What Vinz's own features did this year. */
@Composable
private fun VinzYearSlide(s: ReplayStory, modifier: Modifier) {
    val p = s.vinz
    val rows = listOfNotNull(
        p.sung.takeIf { it > 0 }?.let { tr(R.string.songs_you_sang) to fmt(it.toLong()) },
        p.djSongs.takeIf { it > 0 }?.let { tr(R.string.songs_in_dj_sets) to fmt(it.toLong()) },
        p.smartPicks.takeIf { it > 0 }?.let { tr(R.string.smart_shuffle_finds_played) to fmt(it.toLong()) },
        p.recognized.takeIf { it > 0 }?.let { tr(R.string.songs_recognized) to fmt(it.toLong()) },
        p.blends.takeIf { it > 0 }?.let { trp(R.plurals.blends_made, p.blends) to fmt(it.toLong()) },
        p.radarFinds.takeIf { it > 0 }?.let { tr(R.string.release_radar_finds) to fmt(it.toLong()) },
    )
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.your_year_in_vinz), style = kicker(), modifier = Modifier.reveal(100))
        FitText(tr(R.string.more_than_listening), display(56.sp), Modifier.reveal(300).padding(top = 6.dp), oneLine = false)
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEachIndexed { i, (label, value) -> Fact(label, value, 700 + i * 220) }
        }
    }
}

/** This year against last. */
@Composable
private fun YearCompare(s: ReplayStory, modifier: Modifier) {
    val prev = s.previousMs ?: return
    val year = s.title.toIntOrNull()
    val change = ((s.totalMs - prev) * 100.0 / prev).toInt()
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text("${year?.minus(1) ?: "LAST YEAR"} → ${s.title}", style = kicker(), modifier = Modifier.reveal(100))
        FitText(if (change >= 0) "+$change%" else "$change%", display(130.sp), Modifier.reveal(300))
        FitText(if (change >= 0) tr(R.string.more_music) else tr(R.string.less_music), display(44.sp, ArtistTypography.extended), Modifier.reveal(500), oneLine = false)
        Spacer(Modifier.height(26.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact(tr(R.string.listening), "${listenTime(prev)} → ${listenTime(s.totalMs)}", 900)
            s.previousSongCount?.let { Fact(tr(R.string.different_songs), "${fmt(it.toLong())} → ${fmt(s.songCount.toLong())}", 1_150) }
            val now = s.artists.firstOrNull()?.artistName
            if (s.previousTopArtist != null && now != null) {
                Fact(tr(R.string.top_artist), if (s.previousTopArtist == now) tr(R.string.again_2, now) else "${s.previousTopArtist} → $now", 1_400)
            }
        }
    }
}

@Composable
private fun Summary(s: ReplayStory, modifier: Modifier, onReplay: () -> Unit, onPlayAll: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val card = rememberGraphicsLayer()
    val thumbs by c.library.artistThumbs.collectAsState()
    Column(modifier.padding(top = 0.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        // The card that gets shared, drawn into a layer so it can be saved as an image.
        val lead = s.artists.firstOrNull()
        SummaryCard(
            s, hiRes(s.topArtistImage ?: lead?.let { c.library.artistPhoto(thumbs, it.artistId, it.artistName) } ?: s.songs.firstOrNull()?.thumbnail, 600),
            Modifier.reveal(100).fillMaxWidth().recordInto(card),
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.reveal(500), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(tr(R.string.share), VinzIcons.Share, primary = true) {
                scope.launch {
                    val name = if (s.rewind) tr(R.string.rewind_3) else tr(R.string.replay_2)
                    if (!shareLayer(context, card, "vinz-${name.lowercase()}.png", tr(R.string.my_vinz, name, s.title), tr(R.string.share_your, name))) {
                        Toast.makeText(context, tr(R.string.couldn_t_share_your, name), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Pill(tr(R.string.widget_play), VinzIcons.Play, onClick = onPlayAll)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.reveal(700), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (c.settings.current.isLoggedIn) Pill(if (s.rewind) tr(R.string.save_top_100) else tr(R.string.save_playlist), VinzIcons.PlaylistAdd) {
                scope.launch {
                    val name = if (s.rewind) tr(R.string.top_100_of, s.title) else tr(R.string.replay, s.title)
                    val id = runCatching { c.ytm.createPlaylist(name, s.songs.take(100).map { it.songId }, if (s.rewind) tr(R.string.made_with_vinz_rewind) else tr(R.string.made_with_vinz_replay)) }.getOrNull()
                    Toast.makeText(context, if (id != null) tr(R.string.saved_to_youtube_music, name) else tr(R.string.couldn_t_create_playlist), Toast.LENGTH_SHORT).show()
                    if (id != null) c.library.created(id, name, minOf(s.songs.size, 100))
                }
            }
            Pill(tr(R.string.watch_again), VinzIcons.Refresh, onClick = onReplay)
        }
        // 1.8.7.6 (plan-1.8.7.6 §4.1 S5): once for a monthly Replay, once for the year's Rewind, then never.
        com.vinz.music.ui.support.SupportStoryLine(s.rewind, Modifier.reveal(900).padding(top = 14.dp))
    }
}

/** The summary slide's card: what gets shared. */
@Composable
internal fun SummaryCard(s: ReplayStory, image: Any?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1B0F3B), Color(0xFF5B3DD6), Color(0xFFE0335A))))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(image, null, Modifier.size(104.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(s.brandName, style = kicker(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                // What else it counts can run long ("Includes Spotify, Last.fm"): smaller, on its own lines, clear of the title.
                s.includes?.let {
                    Text(
                        it.uppercase(), style = kicker(9.sp).copy(lineHeight = 12.sp, color = Color.White.copy(alpha = 0.8f)),
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Shrinks to fit beside the cover (Reddit: "TODA").
                FitText(s.title.uppercase(), display(52.sp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Row {
            SummaryList(tr(R.string.top_artists), s.artists.take(5).map { it.artistName }, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            SummaryList(tr(R.string.top_songs_3), s.songs.take(5).map { it.title }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row {
            Column(Modifier.weight(1f)) {
                val total = listenTotal(s.totalMs)
                Text(tr(R.string.listened_2, total.unit).trim().uppercase(), style = kicker(10.sp).copy(color = Color.White.copy(alpha = 0.75f)))
                FitText(total.text(total.value), display(36.sp))
            }
            s.genres.firstOrNull()?.let { (g, _) ->
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(tr(R.string.top_genre), style = kicker(10.sp).copy(color = Color.White.copy(alpha = 0.75f)))
                    Text(g, style = body.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), maxLines = 2)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(s.personality.name, style = body.copy(fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f)))
    }
}

@Composable
private fun SummaryList(title: String, items: List<String>, modifier: Modifier) {
    Column(modifier) {
        Text(title.uppercase(), style = kicker(10.sp).copy(color = Color.White.copy(alpha = 0.75f)))
        Spacer(Modifier.height(4.dp))
        items.forEachIndexed { i, t ->
            Text("${i + 1}  $t", style = body.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Pill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(if (primary) Color.White else Color.White.copy(alpha = 0.16f)).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (primary) Color.Black else Color.White
        Icon(icon, null, Modifier.size(20.dp), tint = fg)
        Spacer(Modifier.width(8.dp))
        Text(label, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = fg))
    }
}
