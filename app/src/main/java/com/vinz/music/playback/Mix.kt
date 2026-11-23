package com.vinz.music.playback

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/**
 * Crossfade (after Spotify's): when a song runs into the next one, the ending song fades out while the next one fades
 * in, both at their own speed, instead of one stopping and the next starting. Its length is the listener's, or with
 * [auto] it's worked out for each pair of songs ([autoLengthMs]): over the ending song's own fade-out when it has
 * one, over the next song's quiet intro when the ending is abrupt, and briefly when neither gives room.
 *
 * How a blend goes: a few seconds before it, a second player ([buildTail]) gets the ending song ready at the same
 * spot, muted. Just before the blend it starts playing along, and its audio is shifted until it's in step with the
 * main player (it's still silent). At the blend the main player hands over: the tail carries on the ending song,
 * and the main player moves to the next song, starting where its music starts (or on its first beat), silent, then
 * fading in while the tail fades out (equal power, so the loudness holds). With beat matching (a DJ-style option,
 * off unless chosen), the next song plays at the ending song's tempo while they overlap (pitch kept), its beats lined
 * up with the ending song's, and drifts back to its own tempo once the ending song is gone.
 *
 * The main player moves on to the next song as the blend starts (as Spotify's does), so the player, lyrics,
 * history and the car's screen are all about the incoming song from then on.
 */
@OptIn(UnstableApi::class)
class MixEngine(
    private val main: ExoPlayer,
    private val scope: CoroutineScope,
    private val tempo: MixTempo,
    /** Blend length in ms, or null when crossfade is off. */
    private val mixMs: () -> Long?,
    /** The length is worked out for each pair of songs ([autoLengthMs]) instead of [mixMs]'s. */
    private val auto: () -> Boolean,
    private val beatMatch: () -> Boolean,
    /**
     * DJ mode: how much two songs' keys clash (0 the same key … 1 a clash, [MusicKey.clash]), or null when that's not
     * in play. Keys that sit together get a long blend; clashing ones a short one, so they're heard together briefly.
     */
    private val harmonic: (from: String, to: String) -> Double? = { _, _ -> null },
    /** Whether this transition may blend at all (video, live, Listen Together, an album's next track…). */
    private val allowed: (from: MediaItem, to: MediaItem) -> Boolean,
    /** Scans a song from what's on the phone (no network), if it hasn't been ([MixTempo.measure]). */
    private val scanFromPhone: (String) -> Unit,
    /** A second player for the ending song: same sources and effects as the main one (plus [ShiftProcessor] first), no audio focus. */
    private val buildTail: (ShiftProcessor) -> ExoPlayer,
    /** A blend is over (done or stopped) and the main player is back to normal: the listener's own speed can return. */
    private val onSettled: () -> Unit = {},
    /** The pitch the music plays at (Sing's key, Reddit R6): blends change the speed only, never the key. */
    private val pitch: () -> Float = { 1f },
    /** How the two volumes cross (1.7.8, A1). */
    private val curve: () -> com.vinz.music.data.prefs.CrossfadeCurve = { com.vinz.music.data.prefs.CrossfadeCurve.EQUAL_POWER },
    /** Where the next blend starts and how one under way is going, for the player (A6). */
    private val state: MixState = MixState(),
    /** #493: why [allowed] said no for these two, for the listener ([MixState.why]); read from the songs themselves by default. */
    private val whyNot: (from: MediaItem, to: MediaItem) -> MixWhy = ::mixWhyNotFromSongs,
) {
    /** The main player at [speed], in the music's key. */
    private fun at(speed: Float) = PlaybackParameters(speed, pitch())
    private class Plan(
        val fromId: String,
        val toId: String,
        /** Its place in the queue, kept up to date as the queue changes ([following]). */
        var toIndex: Int,
        /** Where in the ending song the blend starts (later, and the blend shorter, when it's put off: [postponeMs]). */
        var startMs: Long,
        /** Where in the next song it starts playing. */
        val inMs: Long,
        var lengthMs: Long,
        /** The next song's speed while they overlap (its tempo matched to the ending song's); 1 without beat matching. */
        val ratio: Float,
        val beats: Boolean,
        /** A beat of the ending song in the next song's time (ms), with beat matching. */
        val beatMs: Double = 0.0,
    ) {
        /** How many times the blend has been put off to get the tail in step. */
        var postponed = 0
    }

    /** [SKIP_FADE]: skipped mid-blend, the ending song fades out (and the song skipped to up) over [SKIP_FADE_MS]. */
    private enum class Phase { IDLE, PREPARED, SYNCING, BLENDING, SETTLING, SKIP_FADE }

    private var phase = Phase.IDLE
    private var plan: Plan? = null
    private var tail: ExoPlayer? = null
    private var shifter: ShiftProcessor? = null
    private var blendStartedAt = 0L
    private var settleFrom = 1f
    private var settleStartedAt = 0L
    /**
     * No blend after [songId], as things stood: [atMs] into it, with [nextId] next. #432: not for the rest of the song
     * whatever happens (it was, so a seek that ended one blend, or came too near the end for one, left the song without a
     * crossfade and without its mark even once it was taken back, and so did a next song that joined the queue late).
     */
    private class Refusal(val songId: String, val nextId: String?, val atMs: Long)
    private var refusal: Refusal? = null
    private var lastSyncLog = 0L
    private var syncActionAt = 0L
    /** While the next song plays a little off tempo to bring its beats in line: when to stop (0 when it isn't). */
    private var catchUpUntil = 0L
    /** Each player's position less the clock, at its freshest in the current window of readings. */
    private var mainLead = Long.MIN_VALUE
    private var tailLead = Long.MIN_VALUE
    private var windowFrom = 0L
    /** How far the tail is ahead of the main player (ms) by the last full window since it was shifted; null until measured. */
    private var offset: Long? = null
    /** The main player's position less the clock, by the last window (for timing the cross-over between readings). */
    private var clockLead = Long.MIN_VALUE
    /** How long the cross-over to the tail takes, settled as the blend comes up (0 until then). */
    private var handoverMs = 0L
    private var loop: Job? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                val wait = runCatching { tick() }.getOrElse { e ->
                    Log.w(TAG, "mix step failed", e)
                    note("mix step failed: $e")
                    abort("error", MixWhy.GLITCH)
                    500L
                }
                delay(wait)
            }
        }
    }

    fun release() {
        loop?.cancel()
        abort("released", null)
    }

    /** A blend is under way (the next song's tempo is the blend's until it settles): the Speed button waits for it. */
    val blending: Boolean get() = phase == Phase.BLENDING || phase == Phase.SETTLING || phase == Phase.SKIP_FADE

    /** Ends a blend under way at once (casting starts: the music goes elsewhere, and no blend may leave it at half volume). */
    fun stopBlend() {
        // (Said even with no blend under way: while the music plays elsewhere, none is made, #493.)
        abort("stopped", MixWhy.CASTING, always = true)
    }

    /** The song a scan from the phone was last asked for (once per song as its end comes up). */
    private var scanAskedFor: String? = null
    /** When the ending song went quiet mid-blend and the next was brought in early (0 when it hasn't). */
    private var rushedAt = 0L
    private var rushFrom = 0f
    private var rushMs = RUSH_MS
    /** Since when the ending song has been stopped to load mid-blend (0 when it's playing). */
    private var tailStalledAt = 0L
    /**
     * #364, for a blend heard as "the first song pauses and lags": how each blend went, said at its end. Since when the
     * tail has stopped while the main player plays, in the lead-in (0: it hasn't), and how long that came to.
     */
    private var tailStoppedAt = 0L
    private var tailStoppedMs = 0L
    private var tailStops = 0
    /** The next song's wait to start, from the hand-over (0 until it's playing). */
    private var nextWaitedMs = 0L
    /** The ending song's time against the clock in the lead-in ([watchMain]): what it's lost, and the last stretch's reading. */
    private val mainLost = LostTime()

    /** The fade-in [MixState.into] last looked at, and the furthest the song has played since ([wentBack]). */
    private var intoSeen: BlendIn? = null
    private var intoFurthest = 0L

    /** Mid-blend: the furthest the next song has played (a seek back from it is the listener's), and the blend's last progress. */
    private var blendFurthest = 0L
    private var lastProgress = 0f
    /** A skip mid-blend ([Phase.SKIP_FADE]): the volumes the fade starts from, and when. */
    private var fadeMainFrom = 1f
    private var fadeTailFrom = 0f
    private var fadeStartedAt = 0L

    /** One step; returns how long to wait before the next. */
    private fun tick(): Long {
        val length = mixMs()
        if (length == null) {
            if (phase != Phase.IDLE) abort("Mix turned off", null)
            state.mark.value = null
            state.into.value = null
            return 1000
        }
        return when (phase) {
            Phase.IDLE -> idle(length)
            Phase.PREPARED -> prepared()
            Phase.SYNCING -> syncing()
            Phase.BLENDING -> blending()
            Phase.SETTLING -> settling()
            Phase.SKIP_FADE -> skipFading()
        }
    }

    private fun idle(chosen: Long): Long {
        // With Auto, plans are made as early as the longest blend could need.
        val length = if (auto()) AUTO_MAX_MS else chosen
        if (!main.isPlaying) return 500
        val item = main.currentMediaItem ?: return 500
        // How the song playing came in is only its own: another song (skipped to, or the next without a blend) has none.
        if (state.into.value?.let { it.songId != item.mediaId } == true) state.into.value = null
        // Nor once the same song starts over (repeat one, Previous) or is taken back into its fade-in: that's not heard again.
        state.into.value?.let { into ->
            if (intoSeen !== into) { intoSeen = into; intoFurthest = main.currentPosition }
            intoFurthest = maxOf(intoFurthest, main.currentPosition)
            if (wentBack(into, main.currentPosition, intoFurthest)) state.into.value = null
        }
        val dur = main.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return 500
        // Counted to where the music ends, not the file: a song with a long silent tail blends that much earlier (counted
        // from the file's end, the blend's moment had passed before it was even planned, and the song just stopped).
        val musicEnd = tempo.cached(item.mediaId)?.lastSoundMs?.takeIf { it in dur / 2..dur } ?: dur
        val left = musicEnd - main.currentPosition
        val planAhead = length + LEAD_MS + PREPARE_MS + 5_000
        // The player's crossfade mark: where the blend should start, as far as it's known yet (once it's being planned,
        // the plan's own, below: set once a step, so the bar doesn't flick between the two).
        val refused = refused(item)
        if (left > planAhead || refused) state.mark.value = if (refused) null else predict(item, dur, chosen)
        // Not scanned yet (on mobile data, say): by now the rest of the song is usually buffered, so it's scanned from
        // the song cache, to know where its music really ends (a fade-out or a silent tail would otherwise be blended
        // over, and sound like the song stopping before the next one came in).
        if (dur - main.currentPosition < length + 60_000 && scanAskedFor != item.mediaId && tempo.cached(item.mediaId)?.edgesUnknown != false) {
            scanAskedFor = item.mediaId
            scanFromPhone(item.mediaId)
        }
        if (left > planAhead) return minOf(2000, left - planAhead).coerceAtLeast(250)
        if (refused) return 1000
        val p = makePlan(item, dur, length) ?: run { refuse(item.mediaId); return 1000 }
        // (The tail can still load while it waits to play along, see [prepared], so only the lead-in itself is needed.)
        if (main.currentPosition > p.startMs - LEAD_MS - 1000) {
            // Too late to get the tail ready by then (a seek to just before the blend, or a scan that came late and moved
            // it earlier). #432: the blend starts as soon as the tail can be ready and is that much shorter, ending where
            // it would have; only with no room left for that does the song end on its own.
            val pos = main.currentPosition
            val late = lateStartMs(p.startMs, p.lengthMs, pos, if (p.beats) p.beatMs / p.ratio else 0.0)
            if (late == null) {
                noBlend(item, MixWhy.TOO_LATE, "too close to the end to get ready (at $pos ms, blend at ${p.startMs} ms)")
                refuse(item.mediaId)
                return 1000
            }
            note("too close to the blend after ${item.mediaId} to get ready (at $pos ms, blend at ${p.startMs} ms): it starts at $late ms instead")
            p.lengthMs -= late - p.startMs
            p.startMs = late
        }
        // Planned: the mark with the blend's length too (an automatic one is only known from here on).
        state.mark.value = BlendMark(p.fromId, p.startMs, p.lengthMs)
        if (main.currentPosition < p.startMs - LEAD_MS - PREPARE_MS - 3000) return 250
        val shift = ShiftProcessor()
        val t = buildTail(shift)
        shifter = shift
        t.volume = 0f
        t.setMediaItem(item.buildUpon().build())
        t.seekTo(p.startMs - LEAD_MS)
        t.playWhenReady = false
        t.prepare()
        tail = t
        plan = p
        mainLost.reset()
        state.mark.value = BlendMark(p.fromId, p.startMs, p.lengthMs)
        phase = Phase.PREPARED
        note("planned ${p.fromId} -> ${p.toId}: blend at ${p.startMs} ms for ${p.lengthMs} ms, next from ${p.inMs} ms" +
            if (p.beats) ", tempo ×%.3f, on the beat".format(p.ratio) else "")
        return 50
    }

    /** The song up next, as the queue is now. */
    private fun nextId(): String? = main.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { main.getMediaItemAt(it).mediaId }

    /** No blend after [songId] as things stand ([Refusal]); its mark goes. */
    private fun refuse(songId: String) {
        refusal = Refusal(songId, nextId(), main.currentPosition)
        state.mark.value = null
    }

    /** Whether the blend after [item] is still refused: until the song is taken back, or another song is next ([stillRefused]). */
    private fun refused(item: MediaItem): Boolean {
        val r = refusal ?: return false
        if (r.songId == item.mediaId && stillRefused(r.atMs, r.nextId, main.currentPosition, nextId())) return true
        refusal = null
        return false
    }

    /**
     * A step worth knowing about later: logcat (tag VinzMix) and Playback diagnostics' own log, which keeps it however
     * long ago it was (GitHub #106's diagnostics had lost the crossfade's lines from logcat's short window).
     */
    private fun note(message: String) {
        Log.i(TAG, message)
        AudioDiagnostics.log("mix", message)
    }

    /**
     * Why a song isn't blended into the next, said once per song (in Share diagnostics, [detail]) and kept for the
     * listener ([MixState.why], #493: it used to go only to diagnostics), then null.
     */
    private fun noBlend(item: MediaItem, why: MixWhy, detail: String): Plan? {
        note("no blend after ${item.mediaId}: $detail")
        state.why.value = MixRefusal(why, item.mediaId, nextId())
        return null
    }

    /**
     * Where a blend after [item] would start, from what's known now ([makePlan]'s reckoning without the beat, which moves
     * it by under a beat), or null when there won't be one. Quiet: nothing is logged. Its length is given only when it's
     * the listener's: an automatic one (and DJ mode's) is only known once the blend is planned.
     */
    private fun predict(item: MediaItem, dur: Long, length: Long): BlendMark? {
        val toIndex = main.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return null
        if (main.repeatMode == Player.REPEAT_MODE_ONE) return null
        val next = main.getMediaItemAt(toIndex)
        if (next.mediaId.isBlank() || next.mediaId == item.mediaId || !allowed(item, next)) return null
        val a = tempo.cached(item.mediaId)
        val nextDur = (next.mediaMetadata.durationMs ?: 0L).takeIf { it > 0 } ?: Long.MAX_VALUE
        val automatic = auto()
        val len = minOf(if (automatic) autoLengthMs(a, tempo.cached(next.mediaId)) else length, dur / 3, nextDur / 3)
        if (len < 1000 || dur < 30_000) return null
        val start = (a?.lastSoundMs?.takeIf { it in dur / 2..dur } ?: dur) - len
        return start.takeIf { it >= dur / 2 }?.let { BlendMark(item.mediaId, it, if (automatic) 0L else len) }
    }

    /** The blend's length before the songs' own lengths cap it: the listener's, or worked out for the pair; DJ mode's keys stretch or cut it. */
    private fun chosenLength(item: MediaItem, next: MediaItem, a: Tempo?, b: Tempo?, length: Long): Long {
        var chosen = if (auto()) autoLengthMs(a, b) else length
        harmonic(item.mediaId, next.mediaId)?.let { clash ->
            chosen = when {
                clash <= 0.25 -> maxOf(chosen, HARMONIC_LONG_MS)
                clash >= 1.0 -> minOf(chosen, HARMONIC_SHORT_MS)
                else -> chosen
            }
        }
        return chosen
    }

    private fun makePlan(item: MediaItem, dur: Long, length: Long): Plan? {
        val toIndex = main.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return noBlend(item, MixWhy.NOTHING_NEXT, "nothing next in the queue")
        if (main.repeatMode == Player.REPEAT_MODE_ONE) return noBlend(item, MixWhy.REPEAT_ONE, "repeat one")
        val next = main.getMediaItemAt(toIndex)
        if (next.mediaId.isBlank() || next.mediaId == item.mediaId) return noBlend(item, MixWhy.SAME_SONG, "the same song next")
        if (!allowed(item, next)) {
            val why = whyNot(item, next)
            return noBlend(item, why, "not blended here (${why.name})")
        }
        val a = tempo.cached(item.mediaId)
        val b = tempo.cached(next.mediaId)
        val nextDur = (next.mediaMetadata.durationMs ?: 0L).takeIf { it > 0 } ?: Long.MAX_VALUE
        val len = minOf(chosenLength(item, next, a, b, length), dur / 3, nextDur / 3)
        if (len < 1000 || dur < 30_000) return noBlend(
            item, if (dur < 30_000) MixWhy.SHORT else MixWhy.NEXT_SHORT,
            "a song too short to blend (${dur / 1000} s, next ${nextDur.takeIf { it != Long.MAX_VALUE }?.let { it / 1000 } ?: "?"} s)",
        )
        val end = (a?.lastSoundMs?.takeIf { it in dur / 2..dur } ?: dur)
        var start = end - len
        // Silence before the next song's music is skipped (up to 10 s; a quiet intro is music, and stays).
        var inMs = (b?.firstSoundMs ?: 0L).coerceIn(0, 10_000)
        var ratio = 1f
        var beats = false
        var beatMs = 0.0
        if (beatMatch() && a != null && b != null && a.steady && b.steady) {
            // Tempo compared octave-free: a 170 BPM song against an 85 BPM one already shares a beat.
            val bBpm = listOf(b.bpm, b.bpm * 2, b.bpm / 2).minBy { abs(ln(it / a.bpm)) }
            val r = a.bpm / bBpm
            if (abs(r - 1) <= MAX_STRETCH && b.firstBeatMs <= 4000) {
                // The blend starts on one of the ending song's beats, with the next song's first beat.
                val beat = a.periodMs
                val k = floor((start - a.outroBeatMs) / beat)
                start = (a.outroBeatMs + k * beat).toLong()
                inMs = b.firstBeatMs
                ratio = r.toFloat()
                beats = true
                beatMs = beat * r
            }
        }
        if (start < dur / 2) return noBlend(item, MixWhy.TOO_LONG, "the blend would start before the song's middle")
        return Plan(item.mediaId, next.mediaId, toIndex, start, inMs, len, ratio, beats, beatMs)
    }

    /**
     * #368: the plan as the queue is now. The queue can change after a blend is planned (Play next, a song taken out,
     * Add to queue while shuffled): the plan's place in the queue is kept up to date, as the blend seeked to a place that
     * by then held another song, and a new next song takes over the blend, at the same moment and for as long (without
     * the old one's tempo and beats). It used to run into the song it planned for, or be cut off when that wasn't the one
     * that came. Null when there's nothing now to blend into.
     */
    private fun following(p: Plan): Plan? {
        followingWhy = MixWhy.NOTHING_NEXT
        val at = main.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return null
        val next = main.getMediaItemAt(at)
        val from = main.currentMediaItem ?: return null
        // 1.8.7: still allowed as things are now (video mode switched on for either song since it was planned, say).
        if (next.mediaId == p.toId) { if (!allowed(from, next)) { followingWhy = whyNot(from, next); return null }; p.toIndex = at; return p }
        if (next.mediaId.isBlank() || next.mediaId == from.mediaId) { followingWhy = MixWhy.SAME_SONG; return null }
        if (!allowed(from, next)) { followingWhy = whyNot(from, next); return null }
        val nextDur = (next.mediaMetadata.durationMs ?: 0L).takeIf { it > 0 } ?: Long.MAX_VALUE
        val length = minOf(p.lengthMs, nextDur / 3)
        if (length < 1000) { followingWhy = MixWhy.NEXT_SHORT; return null }
        note("the next song changed to ${next.mediaId} (was ${p.toId}): the blend goes into it")
        val inMs = (tempo.cached(next.mediaId)?.firstSoundMs ?: 0L).coerceIn(0, 10_000)
        state.mark.value = BlendMark(p.fromId, p.startMs, length)
        return Plan(p.fromId, next.mediaId, at, p.startMs, inMs, length, 1f, false).also { it.postponed = p.postponed; plan = it }
    }

    /** Why [following] found nothing to blend into, the last time it didn't. */
    private var followingWhy = MixWhy.NOTHING_NEXT

    /** The tail is getting ready; it starts playing along (muted) just before the blend. */
    private fun prepared(): Long {
        val planned = plan ?: return abort("no plan", MixWhy.GLITCH)
        val t = tail ?: return abort("no tail", MixWhy.GLITCH)
        if (main.currentMediaItem?.mediaId != planned.fromId) return abort("song changed", MixWhy.SKIPPED)
        // 1.8.7: it can't go on as things are now. Repeat one switched on since it was planned (it moved on all the same),
        // or the song stopped (a playback error: the second player played on, muted, meanwhile).
        if (main.repeatMode == Player.REPEAT_MODE_ONE) return abort("repeat one", MixWhy.REPEAT_ONE, again = true)
        if (main.playbackState == Player.STATE_IDLE) return abort("the song stopped (an error)", MixWhy.GLITCH, again = true)
        val p = following(planned) ?: return abort("nothing next to blend into (or no longer blended here)", followingWhy)
        val pos = main.currentPosition
        if (pos > p.startMs + 200 || pos < p.startMs - LEAD_MS - PREPARE_MS - 15_000) return abort("seeked away", null, again = true)
        watchMain(pos, "while the tail got ready")
        if (pos < p.startMs - LEAD_MS) return if (p.startMs - LEAD_MS - pos > 400) 100 else 10
        if (t.playbackState != Player.STATE_READY) {
            // Still loading the ending (slow network): it can wait as long as there's time left to get in step.
            if (pos < p.startMs - MIN_SYNC_MS) return 50
            // Not ready in time: no blend, the song ends on its own.
            return abort("tail not ready (the song's ending didn't load in time)", MixWhy.SLOW)
        }
        // Where the main player is now, if it's well past where the tail waits (a reload costs a moment; [syncing]
        // shifts away whatever difference is left). Still muted.
        if (abs(pos - t.currentPosition) > 1000) t.seekTo(pos)
        t.playWhenReady = main.playWhenReady
        mainLead = Long.MIN_VALUE; tailLead = Long.MIN_VALUE; windowFrom = 0L
        offset = null
        clockLead = Long.MIN_VALUE
        handoverMs = 0
        syncActionAt = SystemClock.elapsedRealtime()
        tailStoppedAt = 0L; tailStoppedMs = 0L; tailStops = 0; nextWaitedMs = 0L
        phase = Phase.SYNCING
        note("the tail starts playing along, ${p.startMs - pos} ms before the blend")
        return 20
    }

    /**
     * #364 "the first song pauses and lags before eventually crossfading": whether the song being listened to really loses
     * time as the tail is made and starts (a second player loading, its audio track joining the same session). Said at once,
     * with how far from the blend.
     */
    private fun watchMain(pos: Long, doing: String) {
        if (!main.isPlaying) { mainLost.reset(); return }
        if (main.playbackParameters.speed != 1f) return
        val lost = mainLost.read(pos, SystemClock.elapsedRealtime())
        if (lost > 0) note("the ending song lost $lost ms $doing, at $pos ms")
    }

    /** Counts the tail stopping to load while it should be playing along (its data late, or a slow phone), said at the blend's end. */
    private fun watchTail(t: ExoPlayer, now: Long) {
        val stopped = main.isPlaying && !t.isPlaying && t.playbackState != Player.STATE_ENDED
        if (stopped && tailStoppedAt == 0L) { tailStoppedAt = now; tailStops++ }
        if (!stopped && tailStoppedAt != 0L) { tailStoppedMs += now - tailStoppedAt; tailStoppedAt = 0L }
    }

    /** The tail's place in the song: its reported position plus however far [ShiftProcessor] has moved it. */
    private fun tailPosition(t: ExoPlayer): Long = t.currentPosition + (shifter?.shiftedMs ?: 0L)

    /**
     * Both play the ending song, the tail muted, until the tail is in step. The two are compared over a stretch of
     * readings (a median, as a player's reported position wobbles, most of all just after it starts), then the
     * tail's audio is shifted by exactly that much ([ShiftProcessor]: frames skipped or silence added), and checked
     * again once the shifted audio has reached the speaker.
     */
    private fun syncing(): Long {
        val planned = plan ?: return abort("no plan", MixWhy.GLITCH)
        val t = tail ?: return abort("no tail", MixWhy.GLITCH)
        if (main.currentMediaItem?.mediaId != planned.fromId) return abort("song changed", MixWhy.SKIPPED)
        // 1.8.7: it can't go on as things are now. Repeat one switched on since it was planned (it moved on all the same),
        // or the song stopped (a playback error: the second player played on, muted, meanwhile).
        if (main.repeatMode == Player.REPEAT_MODE_ONE) return abort("repeat one", MixWhy.REPEAT_ONE, again = true)
        if (main.playbackState == Player.STATE_IDLE) return abort("the song stopped (an error)", MixWhy.GLITCH, again = true)
        if (t.playbackState == Player.STATE_IDLE) return abort("the ending song's second player stopped (an error)", MixWhy.GLITCH)
        val p = following(planned) ?: return abort("nothing next to blend into (or no longer blended here)", followingWhy)
        t.playWhenReady = main.playWhenReady
        val pos = main.currentPosition
        if (pos > p.startMs + 500 || pos < p.startMs - LEAD_MS - 2000) return abort("seeked away", null, again = true)
        val now = SystemClock.elapsedRealtime()
        if (!main.isPlaying) {
            // 1.8.7: paused (or loading) while the two are lined up. The clock goes on and the positions don't, so what was
            // read before doesn't hold after: the main player's lead over the clock put it seconds on from where it is
            // (the hand-over came early), and the two may start again apart. Read again from scratch once it plays.
            mainLead = Long.MIN_VALUE; tailLead = Long.MIN_VALUE; windowFrom = 0L
            offset = null
            clockLead = Long.MIN_VALUE
            handoverMs = 0L
            syncActionAt = now
            mainLost.reset()
            // Looked at ten times a second, not a hundred: a pause can last.
            return 100
        }
        if (now - syncActionAt > SETTLE_READINGS_MS) watchTail(t, now)
        watchMain(pos, "as the tail played along")
        val drift = tailPosition(t) - pos
        // A player's reported position isn't worked out afresh on every read: it's the position as of its last update,
        // which comes every few hundred ms while it's busy loading, so a reading can be that stale (never early). Each
        // player's lead over the clock is therefore taken at its freshest over a window longer than that.
        if (t.isPlaying && main.isPlaying && now - syncActionAt > SETTLE_READINGS_MS) {
            mainLead = maxOf(mainLead, pos - now)
            tailLead = maxOf(tailLead, tailPosition(t) - now)
            if (windowFrom == 0L) windowFrom = now
            if (now - windowFrom >= WINDOW_MS) {
                val off = tailLead - mainLead
                offset = off
                clockLead = mainLead
                mainLead = Long.MIN_VALUE; tailLead = Long.MIN_VALUE; windowFrom = 0L
                Log.d(TAG, "tail $off ms off (shifted ${shifter?.shiftedMs} ms), ${p.startMs - pos} ms to the blend")
                // A shift is only made while there's time to check it before the blend.
                if (abs(off) > IN_STEP_MS && p.startMs - pos > SETTLE_READINGS_MS + WINDOW_MS + LONG_HANDOVER_MS + 100) {
                    shifter?.shift(-off)
                    offset = null
                    syncActionAt = now
                }
            }
        }
        if (com.vinz.music.BuildConfig.DEBUG && now - lastSyncLog > 100) {
            lastSyncLog = now
            Log.v(TAG, "in step? tail $drift ms off as read, ${p.startMs - pos} ms to the blend")
        }
        // Where the main player really is: its last reading may be a few hundred ms old (see above).
        val at = if (main.isPlaying && clockLead != Long.MIN_VALUE) maxOf(pos, now + clockLead) else pos
        if (at < p.startMs - LONG_HANDOVER_MS) return 10
        val d = offset
        // How the two cross over is settled once, as the blend comes up: in step, quickly; a little apart, over a
        // longer stretch, which sounds like a brief doubling rather than a jump; further apart than that, or not
        // measured, not at all (the song ends as usual).
        if (handoverMs == 0L) {
            handoverMs = when {
                outOfStep(d, t.isPlaying, p.beats) -> {
                    // Usually the tail stopped for a moment to load (a slow connection) and fell behind after it was put in
                    // step. Rather than no crossfade at all, the blend is put off a little (it ends where it would have),
                    // the tail shifted, and measured again.
                    val by = postponeMs(p.lengthMs, p.postponed, if (p.beats) p.beatMs / p.ratio else 0.0)
                    if (by > 0 && t.isPlaying) {
                        d?.let { shifter?.shift(-it) }
                        offset = null
                        syncActionAt = now
                        mainLead = Long.MIN_VALUE; tailLead = Long.MIN_VALUE; windowFrom = 0L
                        t.volume = 0f
                        main.volume = 1f
                        p.startMs += by
                        p.lengthMs -= by
                        p.postponed++
                        state.mark.value = BlendMark(p.fromId, p.startMs, p.lengthMs)
                        note("tail ${d ?: "?"} ms off at the blend: put off by $by ms (now ${p.lengthMs} ms long)")
                        return 10
                    }
                    // Put off as often as it may be and still apart: a plain blend (not on the beat) goes ahead anyway when
                    // it's within [LAST_CHANCE_MS], crossed over slowly, so it sounds like a brief doubling (Reddit 1.7.1:
                    // "no crossfade happens at all"; a slower phone's readings can stay a few hundred ms apart).
                    val apart = d ?: drift
                    if (!p.beats && t.isPlaying && abs(apart) <= LAST_CHANCE_MS) {
                        note("tail $apart ms off after ${p.postponed} tries: blending anyway, crossed over slowly")
                        LONG_HANDOVER_MS
                    } else {
                        note("tail out of step by ${d ?: "an unknown amount"} ms at the blend")
                        return abort("out of step", MixWhy.SLOW)
                    }
                }
                // (Measured: [outOfStep] says so otherwise.)
                abs(d ?: 0L) > IN_STEP_DOUBLING_MS -> LONG_HANDOVER_MS
                else -> HANDOVER_MS
            }
        }
        if (at < p.startMs) {
            // The same audio from both: cross over to the tail in a few steps, so nothing clicks.
            val x = ((at - (p.startMs - handoverMs)).toFloat() / handoverMs).coerceIn(0f, 1f)
            t.volume = x
            main.volume = 1f - x
            return 10
        }
        // Hand over: the tail carries the ending song on; the main player moves to the next one, silent.
        t.volume = 1f
        main.volume = 0f
        main.seekTo(p.toIndex, p.inMs)
        main.playbackParameters = at(p.ratio)
        // The next song's bar grows in over the blend, in its own time (faster or slower by the tempo match).
        state.into.value = BlendIn(p.toId, p.inMs, (p.lengthMs * p.ratio).toLong())
        // A blend happens: the last time one didn't is no longer the news (#493).
        state.why.value = null
        blendStartedAt = now
        syncActionAt = now
        windowFrom = 0L
        catchUpUntil = 0L
        rushedAt = 0L
        tailStalledAt = 0L
        blendFurthest = p.inMs
        lastProgress = 0f
        phase = Phase.BLENDING
        note("blend ${p.fromId} -> ${p.toId} started (tail $d ms off, crossed over in $handoverMs ms, put off ${p.postponed} times; tail stopped $tailStops times in the lead-in, $tailStoppedMs ms)")
        return 20
    }
    private fun blending(): Long {
        val p = plan ?: return abort("no plan", MixWhy.GLITCH)
        val t = tail ?: return abort("no tail", MixWhy.GLITCH)
        if (main.currentMediaItem?.mediaId != p.toId) { state.why.value = MixRefusal(MixWhy.SKIPPED, p.fromId, p.toId); return skipped(t) }
        t.playWhenReady = main.playWhenReady
        // The blend's clock is whichever song is behind: a pause can't rush it, and if the next song takes a moment to
        // start, the ending one isn't faded out into a gap before it's there.
        val tailProgress = (tailPosition(t) - p.startMs).toFloat() / p.lengthMs
        val mainProgress = if (main.isPlaying) (main.currentPosition - p.inMs) / p.ratio / p.lengthMs else 0f
        var progress = minOf(tailProgress, mainProgress).coerceIn(0f, 1f)
        // The ending song has gone quiet (its music is over: a fade-out's end, a silent tail) while the next one is
        // still fading in: it comes up the rest of the way now, instead of the blend fading out over silence.
        val now = SystemClock.elapsedRealtime()
        if (nextWaitedMs == 0L && main.isPlaying) {
            nextWaitedMs = now - blendStartedAt
            // Long enough to hear: the ending song carries on alone at full volume meanwhile, past where its fade should start.
            if (nextWaitedMs > NEXT_WAIT_WORTH_SAYING_MS) note("${p.toId} took $nextWaitedMs ms to start after the hand-over")
        }
        if (rushedAt == 0L && main.isPlaying && (shifter?.quietMs ?: 0L) >= QUIET_HANDOFF_MS && progress < 0.9f) {
            rushedAt = now
            rushFrom = progress
            rushMs = RUSH_MS
            note("${p.fromId} went quiet ${(tailPosition(t) - p.startMs)} ms into the blend: bringing ${p.toId} in now")
        }
        // The ending song stopped to load (its data didn't come in time) while the next one plays: the blend's clock
        // would stand still with the next song held near silent, so the listener would hear nothing until the ending
        // song came back. The ending song is let go instead, and the next one comes up.
        if (t.playbackState == Player.STATE_BUFFERING && main.isPlaying) {
            if (tailStalledAt == 0L) tailStalledAt = now
        } else tailStalledAt = 0L
        if (rushedAt == 0L && tailStalledAt != 0L && now - tailStalledAt >= TAIL_STALL_MS) {
            rushedAt = now
            rushFrom = progress
            rushMs = STALL_RUSH_MS
            note("${p.fromId} stalled loading ${(tailPosition(t) - p.startMs)} ms into the blend: bringing ${p.toId} in now")
        }
        // 1.8.7: the ending song's player stopped (an error): its clock stood still, and with it the blend, the next song
        // held below full for good. It comes up the rest of the way now (the ending song is already silent).
        if (rushedAt == 0L && t.playbackState == Player.STATE_IDLE) {
            rushedAt = now
            rushFrom = lastProgress
            rushMs = RUSH_MS
            note("${p.fromId}'s second player stopped ${(tailPosition(t) - p.startMs)} ms into the blend: bringing ${p.toId} in now")
        }
        // 1.8.7: the next song taken back (a seek) during the blend. Its clock went back with it, so it was muted again,
        // then jumped to full when the ending song ran out. The listener wants the next song: it comes up the rest of the
        // way from where it was, and the ending song goes.
        val mainAt = main.currentPosition
        if (rushedAt == 0L && mainAt < blendFurthest - SEEK_BACK_MS) {
            rushedAt = now
            rushFrom = lastProgress
            rushMs = RUSH_MS
            // Its bar no longer grows in from where the blend began.
            state.into.value = null
            note("${p.toId} taken back to $mainAt ms during the blend (it had played to $blendFurthest ms): bringing it in now")
        }
        blendFurthest = maxOf(blendFurthest, mainAt)
        if (rushedAt != 0L) progress = maxOf(progress, (rushFrom + (now - rushedAt).toFloat() / rushMs * (1 - rushFrom)).coerceAtMost(1f))
        val ended = t.playbackState == Player.STATE_ENDED
        if (now - lastSyncLog >= 1000) {
            lastSyncLog = now
            note("blending %.2f: ending at %d ms (%s, quiet %d ms), next at %d ms (%s)".format(
                progress, tailPosition(t), stateName(t), shifter?.quietMs ?: 0L, main.currentPosition, stateName(main),
            ))
        }
        val (incoming, outgoing) = curve().gains(progress)
        main.volume = incoming
        t.volume = outgoing
        if (main.isPlaying) lastProgress = progress
        state.blend.value = BlendNow(p.fromId, p.toId, progress)
        if (p.beats && t.isPlaying) alignBeats(p, t, progress)
        if (progress >= 1f || ended) {
            t.release()
            tail = null
            shifter = null
            main.volume = 1f
            note("blend ${p.fromId} -> ${p.toId} done in ${SystemClock.elapsedRealtime() - blendStartedAt} ms")
            if (main.playbackParameters.speed != 1f) {
                settleFrom = main.playbackParameters.speed
                settleStartedAt = SystemClock.elapsedRealtime()
                phase = Phase.SETTLING
            } else finish()
        }
        return 30
    }

    /**
     * Beats: the next song started a little late (it had to load), so its beats are off the ending song's. Only the
     * beat matters, not which one, so the gap is taken modulo a beat. It's measured over a window (each player at its
     * freshest reading, as in [syncing]); a big gap is closed by a seek on ([beatSeekMs]) while the next song is still barely audible,
     * a small one by playing it a few percent faster or slower for exactly as long as that takes, without steering by
     * the reported position meanwhile (it glides for a while after a speed change).
     */
    private fun alignBeats(p: Plan, t: ExoPlayer, progress: Float) {
        val now = SystemClock.elapsedRealtime()
        if (!main.isPlaying) { syncActionAt = now; windowFrom = 0L; return }
        if (catchUpUntil != 0L) {
            if (now < catchUpUntil) return
            main.playbackParameters = at(p.ratio)
            catchUpUntil = 0L
            syncActionAt = now
            return
        }
        if (now - syncActionAt < SETTLE_READINGS_MS) return
        if (windowFrom == 0L) { windowFrom = now; mainLead = Long.MIN_VALUE; tailLead = Long.MIN_VALUE }
        val dt = now - windowFrom
        // Each player's position as of the window's start, at its freshest.
        mainLead = maxOf(mainLead, main.currentPosition - (dt * p.ratio).toLong())
        tailLead = maxOf(tailLead, tailPosition(t) - dt)
        if (dt < WINDOW_MS) return
        windowFrom = 0L
        val raw = mainLead - (p.inMs + (tailLead - p.startMs) * p.ratio).toLong()
        val err = raw - (Math.round(raw / p.beatMs) * p.beatMs).toLong()
        Log.d(TAG, "beats ${err} ms apart (${raw} ms in all)")
        when {
            abs(err) <= BEATS_IN_STEP_MS -> syncActionAt = now
            abs(err) > 60 && progress < 0.2f -> {
                main.seekTo(beatSeekMs(main.currentPosition, mainLead + (dt * p.ratio).toLong(), err, p.beatMs))
                syncActionAt = now
            }
            else -> {
                val faster = err < 0
                main.playbackParameters = at(p.ratio * (if (faster) 1 + CATCH_UP else 1 - CATCH_UP))
                catchUpUntil = now + (abs(err) / (CATCH_UP * p.ratio)).toLong()
            }
        }
    }

    /** After a tempo-matched blend, the song eases back to its own speed. */
    private fun settling(): Long {
        val p = plan
        if (p != null && main.currentMediaItem?.mediaId != p.toId) {
            main.playbackParameters = at(1f)
            finish(); return 250
        }
        val f = ((SystemClock.elapsedRealtime() - settleStartedAt) / SETTLE_MS.toFloat()).coerceIn(0f, 1f)
        main.playbackParameters = at(settleFrom + (1f - settleFrom) * f)
        if (f >= 1f) { main.playbackParameters = at(1f); finish() }
        return 100
    }

    /**
     * 1.8.7: skipped (or taken to another song) mid-blend. It was a hard cut: the ending song stopped dead wherever its
     * fade was. Now it fades out over [SKIP_FADE_MS], and the song skipped to comes up from the next song's volume to full
     * over the same time. (Crossfading on skips itself is a later release.)
     */
    private fun skipped(t: ExoPlayer): Long {
        note("blend stopped: skipped during the blend (the ending song fades out over $SKIP_FADE_MS ms)")
        state.blend.value = null
        state.mark.value = null
        state.into.value = null
        if (main.playbackParameters.speed != 1f) main.playbackParameters = at(1f)
        catchUpUntil = 0L
        plan = null
        fadeMainFrom = main.volume.coerceIn(0f, 1f)
        fadeTailFrom = t.volume.coerceIn(0f, 1f)
        fadeStartedAt = SystemClock.elapsedRealtime()
        phase = Phase.SKIP_FADE
        return skipFading()
    }

    /** [Phase.SKIP_FADE]'s steps: both volumes move a step; at the end the second player goes and the main one is at full. */
    private fun skipFading(): Long {
        val t = tail ?: return abort("no tail", MixWhy.GLITCH)
        val f = ((SystemClock.elapsedRealtime() - fadeStartedAt).toFloat() / SKIP_FADE_MS).coerceIn(0f, 1f)
        if (f < 1f) {
            t.volume = fadeTailFrom * (1f - f)
            main.volume = fadeMainFrom + (1f - fadeMainFrom) * f
            return 10
        }
        t.volume = 0f
        runCatching { t.release() }
        tail = null
        shifter = null
        main.volume = 1f
        catchUpUntil = 0L
        phase = Phase.IDLE
        plan = null
        refusal = null
        onSettled()
        return 100
    }

    private fun finish() {
        state.blend.value = null
        state.mark.value = null
        catchUpUntil = 0L
        phase = Phase.IDLE
        plan = null
        refusal = null
        onSettled()
    }

    private fun stateName(p: ExoPlayer): String = when {
        p.isPlaying -> "playing"
        p.playbackState == Player.STATE_BUFFERING -> "loading"
        p.playbackState == Player.STATE_ENDED -> "ended"
        !p.playWhenReady -> "paused"
        else -> "idle"
    }

    /**
     * Stops whatever is under way. [again]: the listener moved in the song (#432), so the blend is planned afresh from
     * where the song is now, rather than refused after this song. [reason]: what the listener is told of it ([MixState.why],
     * #493), null for a stop that's no refusal (Mix turned off, a seek, the app closing); kept only when a blend was under
     * way, or [always].
     */
    private fun abort(why: String, reason: MixWhy?, again: Boolean = false, always: Boolean = false): Long {
        val wasOn = phase != Phase.IDLE
        if (wasOn) note("blend stopped: $why")
        if (reason != null && (wasOn || always)) {
            val from = plan?.fromId ?: main.currentMediaItem?.mediaId
            if (from != null) state.why.value = MixRefusal(reason, from, plan?.toId ?: nextId())
        }
        state.blend.value = null
        state.mark.value = null
        // Stopped mid-blend, the next song jumps to full volume: its bar no longer grows in.
        if (phase == Phase.BLENDING) state.into.value = null
        tail?.let { runCatching { it.release() } }
        tail = null
        shifter = null
        if (main.volume != 1f) main.volume = 1f
        if (main.playbackParameters.speed != 1f) main.playbackParameters = at(1f)
        if (!again && (phase == Phase.PREPARED || phase == Phase.SYNCING)) plan?.let { refuse(it.fromId) }
        catchUpUntil = 0L
        phase = Phase.IDLE
        plan = null
        if (wasOn) onSettled()
        return if (again) 100 else 500
    }

    companion object {
        private const val TAG = "VinzMix"
        /** How far back a reading may be from the furthest seen before it counts as the song going back (readings lag). */
        private const val WENT_BACK_SLACK_MS = 1_000L
        /** The longest [idle] waits between looks: a song started over may have played this far on by the next one. */
        private const val IDLE_LOOK_MS = 2_500L

        /**
         * Whether the song that came in from a crossfade ([into]) is at [positionMs] from before it: behind where its
         * fade-in began, or taken back (from [furthestMs], the furthest it's played) to about its fade-in's end or before,
         * as repeat one or Previous starting it over does. A blend brought in early (its ending song went quiet) ends
         * short of that end without going back, so it keeps its shape.
         */
        fun wentBack(into: BlendIn, positionMs: Long, furthestMs: Long): Boolean =
            positionMs < into.inMs ||
                (positionMs < furthestMs - WENT_BACK_SLACK_MS && positionMs < into.inMs + into.lengthMs + IDLE_LOOK_MS)

        /**
         * Auto crossfade's length for [ending] running into [next] (their scans; null or [Tempo.edgesUnknown] when not
         * known yet). A song that fades out is crossfaded over its fade (so it's heard dying away under the next one,
         * not cut off); an abrupt ending runs into a quiet intro over that intro; two hard edges just touch briefly.
         */
        fun autoLengthMs(ending: Tempo?, next: Tempo?): Long {
            val fade = ending?.fadeOutMs?.takeIf { it >= 0 } ?: return AUTO_UNKNOWN_MS
            val intro = next?.quietIntroMs?.takeIf { it >= 0 } ?: 0L
            return when {
                fade >= AUTO_SHORT_MS -> fade
                intro >= AUTO_SHORT_MS -> minOf(intro, AUTO_INTRO_MAX_MS)
                else -> AUTO_SHORT_MS
            }.coerceIn(AUTO_SHORT_MS, AUTO_MAX_MS)
        }

        /**
         * #432: whether a blend refused [atMs] into its song, with [wasNext] up next, is still refused at [positionMs] with
         * [next] up next. Not once the song has been taken back (there may be time to get ready now), nor once another
         * song is next (one that joined the queue late, or one that may be blended into where the last couldn't).
         */
        fun stillRefused(atMs: Long, wasNext: String?, positionMs: Long, next: String?): Boolean =
            next == wasNext && positionMs >= atMs - WENT_BACK_SLACK_MS

        /**
         * #432: where a blend planned at [startMs] for [lengthMs] starts instead when the song is already at [positionMs],
         * too near it for the tail to be made and brought in step: the lead-in and a moment to load on from here (in whole
         * beats of the ending song with beat matching, [beatMs] apart, so it still starts on a beat), ending where it
         * would have. [startMs] itself when there's time as it is; null when under [LATE_MIN_MS] of blend would be left.
         */
        fun lateStartMs(startMs: Long, lengthMs: Long, positionMs: Long, beatMs: Double): Long? {
            val least = positionMs + LEAD_MS + LATE_SPARE_MS - startMs
            if (least <= 0) return startMs
            val by = if (beatMs > 0) (kotlin.math.ceil(least / beatMs) * beatMs).toLong() else least
            return if (lengthMs - by >= LATE_MIN_MS) startMs + by else null
        }

        /** Beyond the lead-in, what a blend started late leaves for the tail to be made and to load. */
        private const val LATE_SPARE_MS = 1_500L
        /** The shortest blend worth starting late (Auto's own shortest). */
        private const val LATE_MIN_MS = 1_500L

        /**
         * How far to put a blend off when the tail isn't in step as it comes up ([POSTPONE_MS], in whole beats of the
         * ending song with beat matching, so the blend still starts on a beat), or 0 when it can't be: put off
         * [MAX_POSTPONES] times already, or the blend would get shorter than [MIN_POSTPONED_MS].
         */
        fun postponeMs(lengthMs: Long, postponed: Int, beatMs: Double): Long {
            if (postponed >= MAX_POSTPONES) return 0
            val by = if (beatMs > 0) (kotlin.math.ceil(POSTPONE_MS / beatMs) * beatMs).toLong() else POSTPONE_MS
            return if (lengthMs - by >= MIN_POSTPONED_MS) by else 0
        }

        /**
         * Where the next song is moved to bring its beats [err] ms off the ending song's back in line, [beatMs] apart:
         * [reportedMs] is its reported position, [heardMs] where it really is by the freshest readings (neither is ever
         * ahead of the sound). #210: only ever on, never back. Back by [err] lines the beats up, and so does on by a beat
         * less [err]; the move back played again what had just been heard (half a beat, and more from a stale reported
         * position), heard as a repeat in a tempo-matched crossfade.
         */
        fun beatSeekMs(reportedMs: Long, heardMs: Long, err: Long, beatMs: Double): Long {
            val on = ((-err % beatMs) + beatMs) % beatMs
            return maxOf(reportedMs, heardMs) + Math.round(on)
        }

        /**
         * At the hand-over, the tail [d] ms ahead of the main player (null: not measured) is too far out of step to cross
         * over to: the blend is put off and the tail set right, or there's no blend. #210: with the beats matched, a tail
         * more than [IN_STEP_DOUBLING_MS] behind counts too: the slow cross-over to it played that much of the ending
         * song again, heard as a repeat. (Ahead, the cross-over skips a moment instead; a plain crossfade keeps it.)
         */
        fun outOfStep(d: Long?, tailPlaying: Boolean, beats: Boolean): Boolean =
            d == null || !tailPlaying || abs(d) > 250 || (beats && d < -IN_STEP_DOUBLING_MS)

        /** Two copies of the song this close are heard as one (the quick cross-over's limit). */
        private const val IN_STEP_DOUBLING_MS = 45L

        private const val POSTPONE_MS = 2000L
        private const val MAX_POSTPONES = 2
        private const val MIN_POSTPONED_MS = 3000L
        /** The next song starting later than this after the hand-over goes in the log. */
        private const val NEXT_WAIT_WORTH_SAYING_MS = 300L
        /** A skip mid-blend: how long the ending song takes to fade out (and the song skipped to to come up). */
        const val SKIP_FADE_MS = 150L
        /** The next song this far behind the furthest it's played mid-blend has been taken back (readings lag, never lead). */
        private const val SEEK_BACK_MS = 500L
        /** The ending song stopped to load this long mid-blend: it's let go ([STALL_RUSH_MS]). */
        private const val TAIL_STALL_MS = 500L
        /** How quickly the next song comes up when the ending song is let go. */
        private const val STALL_RUSH_MS = 1500L

        /** How much of the ending song the tail holds loaded: a whole blend and its lead-in, however long ([tailNeedsMs]). */
        const val TAIL_BUFFER_MS = 60_000L

        /** What the tail plays of the ending song for a blend of [lengthMs]: the lead-in, then the blend. */
        fun tailNeedsMs(lengthMs: Long): Long = LEAD_MS + lengthMs

        /** Auto's longest crossfade, as Spotify's slider's. (A fixed length may be longer: MAX_CROSSFADE_SECONDS, #351.) */
        const val AUTO_MAX_MS = 12_000L
        /** DJ mode: songs in keys that sit together blend at least this long (within [AUTO_MAX_MS], which DJ mode plans by). */
        private const val HARMONIC_LONG_MS = 10_000L
        /** DJ mode: songs in clashing keys blend no longer than this. */
        private const val HARMONIC_SHORT_MS = 3_000L
        /** Auto's shortest: two hard edges barely overlap. */
        private const val AUTO_SHORT_MS = 1_500L
        /** The most of an abrupt ending that's faded over a quiet intro (more would cut off the song's last bars). */
        private const val AUTO_INTRO_MAX_MS = 4_000L
        /** Auto's length while the ending song hasn't been scanned yet. */
        private const val AUTO_UNKNOWN_MS = 6_000L
        /** The tail starts playing along this long before the blend, to get in step while still silent. */
        private const val LEAD_MS = 5000L
        /** The last moments before the blend, when the two players (same song, in step) cross over. */
        private const val HANDOVER_MS = 120L
        /** The cross-over when the two are a little apart. */
        private const val LONG_HANDOVER_MS = 350L
        /** How far apart a plain blend may still start, once putting it off hasn't brought the two in step. */
        private const val LAST_CHANCE_MS = 1200L
        /**
         * A player's reported position is unsteady for a moment after it starts, and a shift takes the length of the
         * audio buffer to reach the speaker; readings wait this long.
         */
        private const val SETTLE_READINGS_MS = 800L
        /** Beats this close count as together. */
        private const val BEATS_IN_STEP_MS = 15L
        /** How much faster or slower the next song plays while its beats catch up. */
        private const val CATCH_UP = 0.05f
        /** How long each window of readings runs: longer than the gap between a busy player's position updates. */
        private const val WINDOW_MS = 450L
        /** Closer than this, the tail counts as in step (the ear doesn't hear a doubling this short). */
        private const val IN_STEP_MS = 8L
        /** Time given to the tail to load the ending (it's in the song cache, so usually far less; mobile data can take longer). */
        private const val PREPARE_MS = 6000L
        /** The least time before the blend a late tail may still start in: enough to measure and settle once. */
        private const val MIN_SYNC_MS = 2500L
        /** The ending song quiet this long mid-blend means its music is over. */
        private const val QUIET_HANDOFF_MS = 400L
        /** How quickly the next song comes up the rest of the way then. */
        private const val RUSH_MS = 700L
        /** The furthest the next song's tempo is stretched to match (±6%, a DJ's usual range); beyond that it blends without beats. */
        private const val MAX_STRETCH = 0.06
        /** How long the next song takes to ease back to its own tempo after a tempo-matched blend. */
        private const val SETTLE_MS = 4000L
    }
}

/**
 * How much time a playing song has lost against the clock (#364's diagnostics). A player's position is as of its last
 * update, so a reading can be a few hundred ms stale, never early: each stretch counts at its freshest reading, and a later
 * stretch behind an earlier one has really lost that much.
 */
internal class LostTime(private val stretchMs: Long = 600, private val worthSayingMs: Long = 60) {
    private var last = Long.MIN_VALUE
    private var best = Long.MIN_VALUE
    private var from = 0L

    fun reset() { last = Long.MIN_VALUE; best = Long.MIN_VALUE; from = 0L }

    /** A reading: [pos] at [now]. The time lost, when a stretch has just shown some, else 0. */
    fun read(pos: Long, now: Long): Long {
        if (from == 0L) from = now
        best = maxOf(best, pos - now)
        if (now - from < stretchMs) return 0
        val lost = if (last == Long.MIN_VALUE) 0 else last - best
        // (Ahead of the last stretch is only that one's readings having been stale: it's the new mark.)
        last = best
        best = Long.MIN_VALUE
        from = now
        return lost.takeIf { it >= worthSayingMs } ?: 0
    }
}

/**
 * Where the crossfade after [songId] starts, [startMs] into it (A6's mark on the progress bar), and how long the song
 * fades out over ([lengthMs], 1.8.1's shaped bar): 0 while that isn't known yet (an automatic length is worked out when
 * the blend is planned, a few seconds before it).
 */
data class BlendMark(val songId: String, val startMs: Long, val lengthMs: Long = 0L)

/**
 * How [songId] came in from a crossfade (1.8.1's shaped bar): its volume rose from [inMs] into it over [lengthMs] of its
 * own time (a tempo-matched blend plays it a little faster or slower meanwhile).
 */
data class BlendIn(val songId: String, val inMs: Long, val lengthMs: Long)

/** A crossfade under way: [fromId] fading out into [toId], [progress] (0 … 1) of the way (A6's blended covers). */
data class BlendNow(val fromId: String, val toId: String, val progress: Float)

/** What [MixEngine] tells the player about crossfades (one per app, [com.vinz.music.AppContainer.mixState]). */
class MixState {
    val mark = kotlinx.coroutines.flow.MutableStateFlow<BlendMark?>(null)
    val blend = kotlinx.coroutines.flow.MutableStateFlow<BlendNow?>(null)
    /** The song playing came in from a crossfade: kept while it plays (its bar's start keeps the fade-in's shape). */
    val into = kotlinx.coroutines.flow.MutableStateFlow<BlendIn?>(null)
    /** #493: the last time two songs weren't blended (or a blend was dropped), and why; null again once a blend happens. */
    val why = kotlinx.coroutines.flow.MutableStateFlow<MixRefusal?>(null)
}

/** #493 "DJ is not working": the last blend refused or dropped, [why], after [fromId] with [toId] up next (null: nothing was). */
data class MixRefusal(val why: MixWhy, val fromId: String, val toId: String?)

/**
 * Why two songs weren't blended, as a listener would put it (#493): each of [MixEngine]'s refusals is one of these, and
 * the engine's reasons that mean the same to a listener share one. [text] is its sentence (Crossfade's ⓘ, the DJ chip's).
 */
enum class MixWhy(@androidx.annotation.StringRes val text: Int) {
    NOTHING_NEXT(com.vinz.music.R.string.mix_why_nothing_next),
    REPEAT_ONE(com.vinz.music.R.string.mix_why_repeat_one),
    SAME_SONG(com.vinz.music.R.string.mix_why_same_song),
    VIDEO(com.vinz.music.R.string.mix_why_video),
    LIVE(com.vinz.music.R.string.mix_why_live),
    PODCAST(com.vinz.music.R.string.mix_why_podcast),
    /** An album playing through (or Keep an album's songs whole). */
    ALBUM(com.vinz.music.R.string.mix_why_album),
    /** Sing is on: each song plays whole, its vocals taken out (the player says so: [mixWhyHeld]). */
    SING(com.vinz.music.R.string.mix_why_sing),
    /** Listen together: songs change as the host's do (the player says so: [mixWhyHeld]). */
    TOGETHER(com.vinz.music.R.string.mix_why_together),
    CASTING(com.vinz.music.R.string.mix_why_casting),
    /** The ending song is under 30 s. */
    SHORT(com.vinz.music.R.string.mix_why_short),
    /** The next song is too short for even a second's blend (under 3 s). */
    NEXT_SHORT(com.vinz.music.R.string.mix_why_next_short),
    /** The blend would start before the song's middle (its music ends early for the length chosen). */
    TOO_LONG(com.vinz.music.R.string.mix_why_too_long),
    /** Too close to the blend to get ready (a seek, or a scan that moved it). */
    TOO_LATE(com.vinz.music.R.string.mix_why_too_late),
    /** The ending song's second player didn't load or get in step in time. */
    SLOW(com.vinz.music.R.string.mix_why_slow),
    /** Another song was picked before or during the blend. */
    SKIPPED(com.vinz.music.R.string.mix_why_skipped),
    /** Something inside the engine went wrong (no plan, no tail, a step that failed). */
    GLITCH(com.vinz.music.R.string.mix_why_glitch),
}

/**
 * Why [MixEngine]'s `allowed` said no for [from] into [to], from what the songs themselves say (PlaybackService's
 * `mayBlend`, in its order): a video, a live stream, an episode, an album's own next track; otherwise what the songs
 * can't say: [held], the player's own reason (Sing, casting or Listen together: [mixWhyHeld]), which PlaybackService
 * passes in. Without it (no player told the engine) it can't say, and calls it a [MixWhy.GLITCH]. (Casting rarely gets
 * here: Mix is off while casting, [MixEngine.stopBlend].)
 */
fun mixWhyNot(from: MediaItem, to: MediaItem, held: MixWhy? = null): MixWhy {
    val a = from.toSong()
    val b = to.toSong()
    return when {
        a == null || b == null -> MixWhy.GLITCH
        a.isLive || b.isLive -> MixWhy.LIVE
        from.videoModeId != null || to.videoModeId != null -> MixWhy.VIDEO
        a.isEpisode || b.isEpisode -> MixWhy.PODCAST
        a.album?.id != null && a.album?.id == b.album?.id -> MixWhy.ALBUM
        else -> held ?: MixWhy.GLITCH
    }
}

/** [mixWhyNot]'s two-song form, for the engine's default (a reference to the three-argument one wouldn't fit it). */
fun mixWhyNotFromSongs(from: MediaItem, to: MediaItem): MixWhy = mixWhyNot(from, to)

/**
 * Which of the player's own states keeps two songs from blending, in `mayBlend`'s order: Sing, then casting, then Listen
 * together (following a host, or in a session); null when none is on.
 */
fun mixWhyHeld(sing: Boolean, casting: Boolean, together: Boolean): MixWhy? = when {
    sing -> MixWhy.SING
    casting -> MixWhy.CASTING
    together -> MixWhy.TOGETHER
    else -> null
}