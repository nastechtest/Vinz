package com.vinz.music.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Volume normalization inside Vinz's own audio chain, so it works on every
 * output (Bluetooth included) and offline. Each song gets a gain from its
 * loudness; quiet songs are lifted as well as loud ones lowered, and a peak
 * limiter keeps the lifted ones from clipping.
 */
@UnstableApi
class NormalizerProcessor : BaseAudioProcessor() {
    /** The gain gone to (audio thread). */
    private var targetGain = 1f
    /** How long a change takes (s): quick before a song's sound starts, slow once it's playing. */
    private var glideSec = 0.1f
    private var gain = 1f
    /** [appVolume] as it glides (~30 ms), apart from the song's gain, which can take seconds. */
    private var vol = 1f
    private var env = 0f
    private var rate = 48_000
    private var float = false
    private var attack = 0f
    private var release = 0f

    /**
     * Sets the song's gain in dB; it glides there over ~100 ms, or over [glideSec] seconds. A song already playing changes
     * slowly (Reddit 1.7: "a hiccup near the start, like it switches to another version": its measured loudness landing a
     * few seconds in moved the volume by several dB in a tenth of a second). [songId]: the song it's for (null: whichever
     * plays); taken from the next sound on, while that song is the one playing.
     */
    fun setGainDb(db: Float, glideSec: Float = 0.1f, songId: String? = null) {
        ask = Ask(songId, 10f.pow(db / 20f), glideSec.coerceAtLeast(0.01f))
    }

    /**
     * A gain asked for by [setGainDb] (any thread), for the song [songId] (null: whatever plays). The audio thread takes it
     * ([takeAsk]) only while that song's sound is what it plays: a figure for the last song (a short one's scan landing as
     * it ended, the main thread not yet told the next one started) isn't put on the next song's start (D62 again).
     */
    private class Ask(val songId: String?, val gain: Float, val glideSec: Float)
    @Volatile private var ask: Ask? = null
    /** The [ask] already taken, or passed over at a song change (audio thread). */
    private var taken: Ask? = null
    /** The song (its media id) the audio now comes from, as of the last flush; null when not known. */
    private var songId: String? = null

    private fun takeAsk() {
        val a = ask ?: return
        if (a === taken) return
        if (a.songId != null && songId != null && a.songId != songId) return
        taken = a
        targetGain = a.gain
        glideSec = a.glideSec
    }

    /**
     * D62 (1.8.5): the gain (dB) a song gets from what's known of it now, asked here on the audio thread when its sound
     * starts (null: nothing known, it plays as it is). The song's own [setGainDb] comes from the main thread after the
     * player says the song changed, and the player's audio runs ahead of that: on the Galaxy A16 the next song's first
     * ~0.65 s went through at the last song's gain (a quiet song's +6 dB on a loud one, the limiter cutting up to 7 dB:
     * static at the start of a song, gone when it was restarted).
     */
    @Volatile var songGainDb: ((MediaItem, Timeline) -> Float?)? = null
    /** The song (its period) the audio now comes from, as of the last flush. */
    private var periodUid: Any? = null

    companion object {
        /**
         * Vinz's own volume (GitHub #116: "Vinz's own volume" under the player), on top of each song's gain in every
         * player (the crossfade's ending song too), so the phone's media volume isn't touched. 1 is full, as before.
         */
        @Volatile var appVolume = 1f

        /** The sleep timer's fade-out (1.7.8, A5), on top of [appVolume]: 1 except while it fades. */
        @Volatile var fadeVolume = 1f

        /** A slider's place (0 … 1) as a volume: squared, so the slider's middle sounds like half as loud. */
        fun volumeFor(slider: Float): Float = slider.coerceIn(0f, 1f).let { it * it }

        /**
         * The gain (dB) that brings a song measured at [lufs] to the [target] level; [peakDb]: its loudest sample (dBFS).
         * #210 (1.8.0): a quiet song is lifted as far as its peaks have room (up to the limiter's -1 dB ceiling) plus the
         * [LIMITED_DB] the limiter may take off them, so a quiet recording ends up as loud as a loud master and a soft
         * passage with loud hits isn't flattened; without a peak figure, [LIMITED_DB] at most, as before 1.8.0 (when every
         * song was lifted 6 dB at most and lowered 12 at most, which left big gaps).
         */
        fun gainFor(target: Float, lufs: Float, peakDb: Float? = null): Float {
            val want = target - lufs
            if (want <= 0f) return want.coerceAtLeast(-MAX_CUT_DB)
            val room = peakDb?.let { CEILING_DB - it + LIMITED_DB } ?: LIMITED_DB
            return minOf(want, room, MAX_LIFT_DB).coerceAtLeast(0f)
        }

        /** The limiter's ceiling (dBFS): 0.89, as [queueInput] has it. */
        private const val CEILING_DB = -1f
        /** How much the limiter may take off a lifted song's peaks. */
        private const val LIMITED_DB = 6f
        /** At most this much up (a very quiet recording's hiss stays down) or down. */
        private const val MAX_LIFT_DB = 20f
        private const val MAX_CUT_DB = 24f
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        rate = inputAudioFormat.sampleRate
        float = enc == C.ENCODING_PCM_FLOAT
        attack = 1f - exp(-1f / (0.0008f * rate))
        release = 1f - exp(-1f / (0.15f * rate))
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        takeAsk()
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        val target = targetGain
        val volTarget = appVolume * fadeVolume
        if (target == 1f && volTarget == 1f && abs(gain - 1f) < 1e-4f && abs(vol - 1f) < 1e-4f && env < 0.89f) {
            gain = 1f
            vol = 1f
            out.put(inputBuffer)
            out.flip()
            return
        }
        val t0 = if (AudioDiagnostics.enabled) System.nanoTime() else 0L
        // Per stereo frame (the gain moves every other sample).
        val glide = 1f - exp(-1f / (glideSec * rate))
        val volGlide = 1f - exp(-1f / (0.03f * rate))
        val ceiling = 0.89f // -1 dBFS
        val samples = bytes / (if (float) 4 else 2)
        for (s in 0 until samples) {
            if (s % 2 == 0) {
                gain += (target - gain) * glide
                vol += (volTarget - vol) * volGlide
            }
            val x = (if (float) inputBuffer.float else inputBuffer.short / 32768f) * gain * vol
            // Peak limiter: fast attack, slow release, never above the ceiling.
            val a = abs(x)
            // Instant attack: the gain is cut on the very sample that would go over.
            env = if (a > env) a else env + (a - env) * release
            var y = if (env > ceiling) x * (ceiling / env) else x
            if (y > 0.99f) y = 0.99f else if (y < -0.99f) y = -0.99f
            if (float) out.putFloat(y) else out.putShort((y * 32767f).roundToInt().toShort())
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
        if (t0 != 0L) AudioDiagnostics.processed("normalizer", t0, samples / inputAudioFormat.channelCount.coerceAtLeast(1), rate)
    }

    /**
     * A new song, or a seek in the same one. Another song gets its own gain from its first sample ([songGainDb]; as it
     * is when nothing's known), not the last song's. Either way the gain is where it should be at once: there's
     * nothing to glide from across a jump in the sound.
     *
     * The same song in another form (swapped for its video, or back: a new item, so a new period, but the same media id)
     * is the same listen and keeps the gain it had: past its first seconds the service leaves a song's volume alone, and
     * a figure measured since is for its next play.
     */
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val uid = streamMetadata.periodUid
        if (uid != null && uid != periodUid) {
            val tl = streamMetadata.timeline
            val item = tl.getIndexOfPeriod(uid).takeIf { it != C.INDEX_UNSET }
                ?.let { tl.getWindow(tl.getPeriod(it, Timeline.Period()).windowIndex, Timeline.Window()).mediaItem }
            val id = item?.mediaId?.takeIf { it.isNotEmpty() }
            // Without [songGainDb] (a Mix blend's ending song, given its gain once), the gain set stays.
            val gainOf = songGainDb
            if (gainOf != null && (id == null || id != songId)) {
                val db = item?.let { runCatching { gainOf(it, tl) }.getOrNull() } ?: 0f
                targetGain = 10f.pow(db / 20f)
                glideSec = 0.1f
                // A gain asked for before this song's sound started was for the last one, unless it names this one.
                ask?.let { if (id == null || it.songId != id) taken = it }
            }
            songId = id
        }
        periodUid = uid
        takeAsk()
        gain = targetGain
        // Nor does the limiter hold the last sound's peaks down on the next (its instant attack still catches the new ones).
        // Across a gapless move to the next song the sound goes on without a break (the player plays the chain out to the
        // last song's end, then flushes it for the next one): there the limiter's hold goes on too, or a lifted album
        // clicked at every change of song (plan item 10, GaplessChainTest).
        if (!drained) env = 0f
        playedOut.clear()
    }

    /** The sound before the next flush was played out to its end ([onQueueEndOfStream]): what follows goes on from it. */
    private val playedOut = PlayedOut()
    private val drained get() = playedOut.goesOn()

    override fun onQueueEndOfStream() {
        playedOut.mark()
    }

    override fun onReset() {
        env = 0f
        playedOut.clear()
        periodUid = null
        songId = null
    }
}
