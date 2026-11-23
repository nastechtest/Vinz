package com.vinz.music.playback

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.vinz.music.AppContainer
import com.vinz.music.data.lyrics.LyricLine
import com.vinz.music.data.lyrics.Lyrics
import com.vinz.music.data.model.Song
import com.vinz.music.data.model.hiRes
import com.vinz.music.data.prefs.CarLyricsLines
import com.vinz.music.data.prefs.CarLyricsStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.BreakIterator
import java.util.Locale

/**
 * What the media session (and so Android Auto, the notification, a watch) sees of the player: the real player,
 * except that
 * - while lyrics are showing, the current song's title and cover are swapped for the lyric;
 * - while shuffle is on, the queue is listed in the order it will play ([PlayOrderTimeline]). The session's queue is
 *   the playlist in its stored order, so the car listed the songs unshuffled while the phone showed them shuffled, and
 *   a song picked there by its place landed on another. Seeks by place are turned back into the real ones.
 * The app itself talks to the real player and never sees either.
 */
@OptIn(UnstableApi::class)
class LyricsOverlayPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    /** [subtitle] is only set when a lyric may wrap onto the second line (PR #400): otherwise the song's own stays. */
    class Overlay(val mediaId: String, val title: String, val artist: String, val artwork: ByteArray?, val subtitle: String? = null)

    private var overlay: Overlay? = null
    private val window = Timeline.Window()
    /** The play order last shown (shown place -> real place), while shuffled; null when shown as stored. */
    @Volatile private var shown: IntArray? = null

    fun show(o: Overlay?) {
        if (o == null && overlay == null) return
        overlay = o
        invalidateState()
    }

    /** A speaker or TV the music is cast to (Vinz Connect): its volume is the session's, so the volume keys work it. */
    interface Remote {
        /** 0–100, or null when not casting. */
        val volume: Int?
        fun setVolume(v: Int)
    }

    var remote: Remote? = null

    /** The cast volume (or casting itself) changed. Call on the main thread. */
    fun remoteChanged() = invalidateState()

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): com.google.common.util.concurrent.ListenableFuture<*> {
        val r = remote
        if (r?.volume == null) return super.handleSetDeviceVolume(deviceVolume, flags)
        r.setVolume(RemoteVolume.level(deviceVolume))
        return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): com.google.common.util.concurrent.ListenableFuture<*> {
        val r = remote
        if (r?.volume == null) return super.handleIncreaseDeviceVolume(flags)
        r.setVolume(RemoteVolume.stepped(r.volume ?: 0, 1))
        return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): com.google.common.util.concurrent.ListenableFuture<*> {
        val r = remote
        if (r?.volume == null) return super.handleDecreaseDeviceVolume(flags)
        r.setVolume(RemoteVolume.stepped(r.volume ?: 0, -1))
        return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): com.google.common.util.concurrent.ListenableFuture<*> {
        val r = remote
        if (r?.volume == null) return super.handleSetDeviceMuted(muted, flags)
        if (muted) r.setVolume(0)
        return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }

    /** Fades a pause or play in (1.7.8, A5): true when it took the change over, false to make it at once. */
    var gentle: ((play: Boolean) -> Boolean)? = null

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): com.google.common.util.concurrent.ListenableFuture<*> {
        if (gentle?.invoke(playWhenReady) == true) return com.google.common.util.concurrent.Futures.immediateVoidFuture()
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): com.google.common.util.concurrent.ListenableFuture<*> {
        val order = shown
        val real = if (order != null && mediaItemIndex in order.indices) order[mediaItemIndex] else mediaItemIndex
        return super.handleSeek(real, positionMs, seekCommand)
    }

    override fun getState(): State {
        var s = super.getState()
        shown = null
        remote?.volume?.let { v ->
            s = s.buildUpon()
                .setDeviceInfo(androidx.media3.common.DeviceInfo.Builder(androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(RemoteVolume.STEPS).build())
                .setDeviceVolume(RemoteVolume.shown(v))
                .setIsDeviceMuted(v == 0)
                .setAvailableCommands(
                    s.availableCommands.buildUpon().addAll(
                        Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                    ).build()
                )
                .build()
        }
        if (s.shuffleModeEnabled && s.timeline.windowCount > 1 && s.currentMediaItemIndex in 0 until s.timeline.windowCount) {
            val t = PlayOrderTimeline.of(s.timeline)
            if (t != null) {
                shown = t.order
                s = s.buildUpon()
                    .setPlaylist(t, s.currentTracks, s.currentMetadata)
                    .setCurrentMediaItemIndex(t.shownIndexOf(s.currentMediaItemIndex))
                    .build()
            }
        }
        val o = overlay ?: return s
        val index = s.currentMediaItemIndex
        if (s.timeline.isEmpty || index !in 0 until s.timeline.windowCount) return s
        if (s.timeline.getWindow(index, window).mediaItem.mediaId != o.mediaId) return s
        val meta = s.currentMetadata.buildUpon()
            .setTitle(o.title)
            .setDisplayTitle(o.title)
            .setArtist(o.artist)
            .apply { if (o.subtitle != null) setSubtitle(o.subtitle) }
            .apply { if (o.artwork != null) { setArtworkData(o.artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER); setArtworkUri(null) } }
            .build()
        return s.buildUpon().setPlaylist(s.timeline, s.currentTracks, meta).build()
    }

}

/**
 * A cast speaker's volume (0–100) as the phone's volume keys and Android's volume panel see it (#534, 1.8.7.6): in
 * [STEPS] steps, one per key press. Before, the session said 100 steps and a press moved 4 of them: Android's panel
 * showed one step up, then jumped to where the speaker really was, and a held key ran to the end.
 */
object RemoteVolume {
    const val STEPS = 25
    private const val PER_STEP = 100 / STEPS
    /** A held key's repeats come every 50 ms; one step is taken per this long, about 2.5 s from silent to full. */
    const val HELD_GAP_MS = 100L

    /** The step shown for a speaker at [level] (0–100). */
    fun shown(level: Int): Int = (level.coerceIn(0, 100) + PER_STEP / 2) / PER_STEP

    /** The speaker's level (0–100) for the step [shown]. */
    fun level(shown: Int): Int = shown.coerceIn(0, STEPS) * PER_STEP

    /** The level one key press up ([by] 1) or down (-1) from [level]: the next step, so the panel and speaker agree. */
    fun stepped(level: Int, by: Int): Int = level(shown(level) + by)

    /** Whether a volume key event is taken: every first press, and a held key's repeats [HELD_GAP_MS] apart. */
    fun takesKey(repeat: Int, now: Long, lastAt: Long): Boolean = repeat == 0 || now - lastAt >= HELD_GAP_MS
}

/**
 * Whether the playing line is put in the session's title and cover (#392, 1.8.4). Two switches, each on its own:
 * "Lyrics in the car" ([inCar]) while a car is connected and the phone is really in one, and "Lyrics in the
 * notification" ([onPhone]) always. Before 1.8.4 the phone's switch only worked with the car's on.
 */
internal fun carLyricsActive(inCar: Boolean, onPhone: Boolean, carConnected: Boolean, driving: Boolean): Boolean =
    (inCar && carConnected && driving) || onPhone

/**
 * The session's lyrics button (notification, watch, car): whether lyrics are [showing] where the listener is now, and
 * what both switches are after a tap ([car] = "Lyrics in the car", [phone] = "Lyrics in the notification").
 */
internal data class LyricsButton(val showing: Boolean, val car: Boolean, val phone: Boolean)

/**
 * What the lyrics button shows and does (#392, 1.8.4), now that the phone's switch stands apart from the car's.
 * It works the lyrics of the place the listener is in ([driving] = a car is connected and the phone is really in one):
 * - in a car it turns "Lyrics in the car" on and off, as it always did. Hiding there also turns the notification's
 *   switch off when that one is on, or the line would stay on the car's screen (one session feeds both);
 * - anywhere else it turns "Lyrics in the notification" on and off, and leaves the car's switch alone.
 * Before, it only ever worked the car's switch: with the notification's alone it said "Show lyrics" over lyrics that
 * were showing, and a tap changed nothing to see.
 */
internal fun lyricsButton(inCar: Boolean, onPhone: Boolean, driving: Boolean): LyricsButton {
    val showing = carLyricsActive(inCar, onPhone, carConnected = driving, driving = driving)
    return when {
        driving -> LyricsButton(showing, car = !showing, phone = if (showing) false else onPhone)
        else -> LyricsButton(showing, car = inCar, phone = !showing)
    }
}

/** What the session is given for a lyric line: its title, its artist line, and a subtitle when the line may wrap. */
internal data class CarLyricText(val title: String, val artist: String, val subtitle: String?)

/**
 * The title and second line for the line being sung. [keepTitle] (lyrics on the cover only) leaves the song's own;
 * one line puts the lyric in the title over "Title · Artist"; wrap to two (PR #400) carries a long lyric on into the
 * second line in place of the song's name.
 */
internal fun carLyricText(line: String, songTitle: String, songArtist: String, keepTitle: Boolean, lines: CarLyricsLines): CarLyricText = when {
    keepTitle -> CarLyricText(songTitle, songArtist, null)
    lines == CarLyricsLines.WRAP_TWO -> {
        val (first, second) = CarLyrics.splitLyric(line, "$songTitle · $songArtist")
        CarLyricText(first, second, second)
    }
    else -> CarLyricText(line, "$songTitle · $songArtist", null)
}

/**
 * Lyrics on Android Auto's player. The car only shows a title, an artist and a cover, so the line
 * being sung becomes the title and the cover becomes a lyric card — in karaoke style, each word
 * lights up as it's sung (word-timed lyrics are looked for first).
 */
class CarLyrics(
    private val context: Context,
    private val c: AppContainer,
    private val player: Player,
    private val overlay: LyricsOverlayPlayer,
    private val scope: CoroutineScope,
) {
    /** Android Auto is connected to Vinz's session. */
    var carConnected = false
        set(v) { if (field != v) { field = v; if (v) car.update(); refresh() } }

    /**
     * The phone is actually in a car. Android Auto's phone app stays connected to the session in
     * the background (for its resume card), so a connection alone doesn't mean anyone is driving.
     */
    private val car = CarState(context, scope) { refresh() }

    val active: Boolean get() = c.settings.current.let { carLyricsActive(it.carLyrics, it.carLyricsOnPhone, carConnected, car.driving) }

    /** The session's lyrics button as it stands now ([lyricsButton]). */
    internal val button: LyricsButton get() = c.settings.current.let { lyricsButton(it.carLyrics, it.carLyricsOnPhone, carConnected && car.driving) }

    /** A tap on the session's lyrics button: the switch of the place the listener is in ([lyricsButton]). */
    fun toggle() {
        val now = c.settings.current
        val after = button
        if (after.car != now.carLyrics) c.settings.setCarLyrics(after.car)
        if (after.phone != now.carLyricsOnPhone) c.settings.setCarLyricsOnPhone(after.phone)
    }

    /** The button's answer may have changed (a car came or went): the service redraws its buttons. */
    var onPlaceChange: (() -> Unit)? = null
    private var place: Boolean? = null

    fun start() = car.start()
    fun stop() = car.stop()

    private var songId: String? = null
    private var song: Song? = null
    private var lyrics: Lyrics? = null
    private var background: Bitmap? = null
    private var loadJob: Job? = null
    private var tickJob: Job? = null
    /** What the car was last sent: the line, words sung, style, and whether the song's own title was kept. */
    private var shown: Pair<Triple<Int, Int, CarLyricsStyle>, Boolean>? = null
    private var lastArtAt = 0L

    /** Call when the song, the settings, the car connection or play/pause changes. */
    fun refresh() {
        val inACar = carConnected && car.driving
        if (place != inACar) { val first = place == null; place = inACar; if (!first) onPlaceChange?.invoke() }
        if (!active) {
            tickJob?.cancel(); tickJob = null
            overlay.show(null); shown = null
            return
        }
        val current = player.currentMediaItem?.toSong()
        if (current == null) { overlay.show(null); shown = null; return }
        if (current.id != songId) load(current)
        shown = null
        if (tickJob?.isActive != true) tickJob = scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(if (player.isPlaying) 90 else 600)
            }
        }
    }

    private fun load(s: Song) {
        songId = s.id; song = s; lyrics = null; background = null; shown = null
        overlay.show(null)
        loadJob?.cancel()
        loadJob = scope.launch {
            val first = withContext(Dispatchers.IO) { runCatching { c.lyrics.auto(s) }.getOrNull() }
            if (songId != s.id) return@launch
            lyrics = first?.takeIf { it.synced }
            background = withContext(Dispatchers.IO) { CarLyricsCard.background(context, s.thumbnail) }
            shown = null
            // Karaoke first: if this song's lyrics are only line by line, look for word-timed ones elsewhere.
            if (first?.wordSynced != true && c.settings.current.carLyricsStyle == CarLyricsStyle.KARAOKE) {
                val better = withContext(Dispatchers.IO) { runCatching { c.lyrics.download(s) }.getOrNull() }
                if (songId == s.id && better != null && (better.wordSynced || lyrics == null) && better.synced) { lyrics = better; shown = null }
            }
        }
    }

    private suspend fun tick() {
        val s = song ?: return
        val l = lyrics
        val style = c.settings.current.carLyricsStyle
        if (l == null || player.currentMediaItem?.mediaId != s.id) { clear(); return }
        val pos = c.player.lyricPosition
        val lines = l.lines
        val idx = CarLyricsCard.lineAt(lines, pos)
        val line = lines.getOrNull(idx)
        if (line == null || line.isGap || line.text.isBlank()) { clear(); return }
        // Long pause after a line: back to the song's own title and cover until the next one.
        val next = lines.drop(idx + 1).firstOrNull { !it.isGap && it.text.isNotBlank() }
        if (line.endMs > 0 && pos > line.endMs + 1_500 && (next == null || next.timeMs - pos > 2_500)) { clear(); return }
        val sung = if (style == CarLyricsStyle.KARAOKE && line.isWordSynced) line.words.count { it.startMs <= pos } else -1
        // Lyrics on the cover only: the title stays the song's (with "Title only" there'd be nothing to show).
        val keepTitle = c.settings.current.carLyricsKeepTitle && style != CarLyricsStyle.TEXT
        val key = Triple(idx, sung, style) to keepTitle
        if (key == shown) return
        val now = System.currentTimeMillis()
        val sameLine = shown?.first?.first == idx && shown?.first?.third == style && shown?.second == keepTitle
        // Word steps are spaced out a little: each one sends a new picture to the car.
        if (sameLine && now - lastArtAt < 280) return
        val art = if (style == CarLyricsStyle.TEXT) null else withContext(Dispatchers.Default) {
            CarLyricsCard.jpeg(CarLyricsCard.render(background, lines, idx, sung))
        }
        if (songId != s.id) return
        lastArtAt = now
        shown = key
        val text = carLyricText(line.text, s.title, s.artistText, keepTitle, c.settings.current.behavior.carLyricsLines)
        overlay.show(LyricsOverlayPlayer.Overlay(s.id, text.title, text.artist, art, text.subtitle))
    }

    private fun clear() {
        if (shown == null) return
        shown = null
        overlay.show(null)
    }

    companion object {
        /** How much of a lyric the car's title takes before it wraps (PR #400's length), in cells: see [cells]. */
        const val TITLE_CELLS = 24

        /**
         * A long lyric over the title and the second line (PR #400 by henrynvn09): the first line is as much as fits in
         * [maxTitle] cells, the second is all the rest (the car shortens it itself). A line that fits is left with
         * [defaultSubtitle] under it.
         *
         * The cut is at the end of a word when one ends in the last two thirds of the line, and otherwise, or for a word
         * longer than a line, wherever the line is full. Chinese and Japanese have no spaces and may be cut between any
         * two characters, though not just before closing punctuation or after an opening bracket. It is never inside what
         * reads as one character: an emoji (two UTF-16 chars), a family or flag made of several, a letter and its accents.
         * Right-to-left text is cut in reading order like any other, so the first line is the start of the sentence.
         */
        fun splitLyric(text: String, defaultSubtitle: String, maxTitle: Int = TITLE_CELLS): Pair<String, String> {
            val max = maxTitle.coerceAtLeast(1)
            val t = text.trim()
            val bounds = graphemeBounds(t)
            var width = 0
            var word = -1
            var wordWidth = 0
            var hard = -1
            var over = false
            for (k in 0 until bounds.size - 1) {
                val at = bounds[k]
                if (k > 0) {
                    hard = at
                    if (wordEnds(t, bounds[k - 1], at)) { word = at; wordWidth = width }
                }
                width += cells(t.codePointAt(at))
                if (width > max) { over = true; break }
            }
            if (!over) return text to defaultSubtitle
            val cut = when {
                word > 0 && wordWidth >= max / 3 -> word
                hard > 0 -> hard
                // Not even one character fits: it goes on the first line all the same.
                bounds.size > 2 -> bounds[1]
                else -> return text to defaultSubtitle
            }
            val first = t.substring(0, cut).trimEnd()
            val second = t.substring(cut).trimStart()
            return if (first.isEmpty() || second.isEmpty()) text to defaultSubtitle else first to second
        }

        /**
         * Where [text] may be cut, from 0 to its length: between whole characters as they're read. Android's
         * BreakIterator knows emoji sequences; an older one (the JVM's, in tests) cuts a family at each joiner and a
         * flag in two, so those places are taken out here whichever is running.
         */
        private fun graphemeBounds(text: String): IntArray {
            val out = ArrayList<Int>()
            val it = BreakIterator.getCharacterInstance(Locale.ROOT)
            it.setText(text)
            var i = it.first()
            while (i != BreakIterator.DONE) {
                if (i == 0 || i == text.length || !joined(text, i)) out += i
                i = it.next()
            }
            if (out.isEmpty() || out.last() != text.length) out += text.length
            return out.toIntArray()
        }

        /** The characters on either side of [i] belong together. */
        private fun joined(text: String, i: Int): Boolean {
            if (Character.isLowSurrogate(text[i]) && Character.isHighSurrogate(text[i - 1])) return true
            val before = text.codePointBefore(i)
            val after = text.codePointAt(i)
            if (before == ZWJ || after == ZWJ) return true
            // Variation selectors, skin tones, tag letters (subdivision flags), the keycap.
            if (after in 0xFE00..0xFE0F || after in 0x1F3FB..0x1F3FF || after in 0xE0020..0xE007F || after == 0x20E3) return true
            when (Character.getType(after)) {
                Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> return true
            }
            if (flagLetter(before) && flagLetter(after)) {
                // Flags are pairs: inside one when an odd number of flag letters comes before.
                var n = 0
                var j = i
                while (j > 0 && flagLetter(text.codePointBefore(j))) { n++; j -= 2 }
                return n % 2 == 1
            }
            return false
        }

        private const val ZWJ = 0x200D
        private fun flagLetter(cp: Int) = cp in 0x1F1E6..0x1F1FF

        /** A word ends between the character at [prev] and the one at [at]: before a space, or between Chinese/Japanese characters. */
        private fun wordEnds(text: String, prev: Int, at: Int): Boolean {
            val b = text.codePointAt(at)
            if (Character.isWhitespace(b)) return true
            val a = text.codePointAt(prev)
            if (Character.isWhitespace(a)) return false
            if (!cutsAnywhere(a) && !cutsAnywhere(b)) return false
            return !(b < 0x10000 && b.toChar() in CLOSERS) && !(a < 0x10000 && a.toChar() in OPENERS)
        }

        /** Wide and written without spaces (Korean is wide too, but has spaces: its words stay whole). */
        private fun cutsAnywhere(cp: Int) = wide(cp) && !(cp in 0x1100..0x115F || cp in 0x3130..0x318F || cp in 0xAC00..0xD7A3)

        /** A Chinese, Japanese or Korean character or an emoji is about two Latin letters wide on the car's screen. */
        private fun cells(cp: Int) = if (wide(cp)) 2 else 1

        private fun wide(cp: Int) = cp in 0x1100..0x115F || cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF ||
            cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x1F000..0x1FAFF || cp in 0x20000..0x3FFFD

        private const val CLOSERS = "、。，．・：；？！）］｝」』】〕〉》〗〙ー々ゝゞヽヾ…‥’”,.!?:;)]}"
        private const val OPENERS = "（［｛「『【〔〈《〖〘‘“([{"
    }
}

/**
 * Whether the phone is in a car right now: projecting to Android Auto, in car mode, or running
 * on the car itself (Android Automotive). Follows Android Auto's own connection broadcasts.
 */
class CarState(private val context: Context, private val scope: CoroutineScope, private val onChange: () -> Unit) {
    @Volatile var driving = false
        private set
    private val automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = update()
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
            addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
            addAction(CONNECTION_UPDATED)
        }
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED); registered = true }
        update()
    }

    fun stop() {
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    fun update() {
        scope.launch {
            val now = automotive || withContext(Dispatchers.IO) { carMode() || projecting() }
            if (now != driving) { driving = now; onChange() }
        }
    }

    private fun carMode() = context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_CAR

    /** Android Auto's connection provider (what androidx.car.app's CarConnection reads): 2 = projecting. */
    private fun projecting(): Boolean = runCatching {
        context.contentResolver.query(Uri.parse("content://androidx.car.app.connection"), arrayOf("CarConnectionState"), null, null, null)?.use { cur ->
            cur.moveToNext() && cur.getInt(cur.getColumnIndexOrThrow("CarConnectionState")) != 0
        } ?: false
    }.getOrDefault(false)

    private companion object {
        const val CONNECTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
    }
}

/** Draws the lyric card the car shows in place of the cover. */
object CarLyricsCard {
    const val SIZE = 720

    fun lineAt(lines: List<LyricLine>, ms: Long): Int {
        var idx = -1
        for (i in lines.indices) if (lines[i].timeMs in 0..ms) idx = i else if (lines[i].timeMs > ms) break
        return idx
    }

    /** The song's cover, blurred and darkened, for the card to sit on. Null falls back to a gradient. */
    suspend fun background(context: Context, thumbnail: String?): Bitmap? {
        val url = hiRes(thumbnail, 226) ?: return null
        val req = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        val bmp = (context.imageLoader.execute(req) as? SuccessResult)?.image?.toBitmap() ?: return null
        // A cheap, smooth blur: down to a few pixels, then back up with filtering.
        val tiny = Bitmap.createScaledBitmap(bmp, 12, 12, true)
        val mid = Bitmap.createScaledBitmap(tiny, 90, 90, true)
        return Bitmap.createScaledBitmap(mid, SIZE, SIZE, true)
    }

    private val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun layout(text: CharSequence, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.04f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    /**
     * The card: the previous line faint above, the current line large (sung words bright, the rest dim
     * when [sung] ≥ 0), and the next line below.
     */
    fun render(background: Bitmap?, lines: List<LyricLine>, index: Int, sung: Int): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (background != null) canvas.drawBitmap(background, 0f, 0f, null)
        else canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), 0xFF2A1B5C.toInt(), 0xFF0E0E14.toInt(), Shader.TileMode.CLAMP) })
        canvas.drawColor(Color.argb(120, 0, 0, 0))
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, SIZE.toFloat(), Color.argb(40, 0, 0, 0), Color.argb(150, 0, 0, 0), Shader.TileMode.CLAMP) })

        val margin = 56
        val width = SIZE - margin * 2
        val line = lines[index]
        val prev = lines.take(index).lastOrNull { !it.isGap && it.text.isNotBlank() }?.text
        val next = lines.drop(index + 1).firstOrNull { !it.isGap && it.text.isNotBlank() }?.text

        // Current line, as large as fits in four lines.
        val text: CharSequence = if (sung >= 0 && line.words.isNotEmpty()) {
            val full = line.words.joinToString("") { it.text }.trimEnd()
            val sungLen = line.words.take(sung).joinToString("") { it.text }.length.coerceAtMost(full.length)
            SpannableString(full).apply {
                setSpan(ForegroundColorSpan(Color.WHITE), 0, sungLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(Color.argb(105, 255, 255, 255)), sungLen, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else line.text
        val main = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; color = Color.WHITE }
        var size = 74f
        var current: StaticLayout
        while (true) {
            main.textSize = size
            current = layout(text, main, width, 4)
            if ((current.lineCount <= 3 && current.height <= SIZE * 0.5f) || size <= 40f) break
            size -= 4f
        }

        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = 34f }
        val prevLayout = prev?.let { layout(it, small.apply { color = Color.argb(95, 255, 255, 255) }, width, 2) }
        val nextPaint = TextPaint(small).apply { color = Color.argb(150, 255, 255, 255); textSize = 38f; typeface = bold }
        val nextLayout = next?.let { layout(it, nextPaint, width, 2) }

        val gap = 30
        val total = (prevLayout?.height?.plus(gap) ?: 0) + current.height + (nextLayout?.height?.plus(gap) ?: 0)
        var y = ((SIZE - total) / 2f).coerceAtLeast(40f)
        fun draw(l: StaticLayout) {
            canvas.save(); canvas.translate(margin.toFloat(), y); l.draw(canvas); canvas.restore()
            y += l.height + gap
        }
        prevLayout?.let(::draw)
        draw(current)
        nextLayout?.let(::draw)
        return out
    }

    fun jpeg(bmp: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.JPEG, 86, out)
        out.toByteArray()
    }
}
