package com.vinz.music

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vinz.music.data.model.AlbumRef
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import com.vinz.music.playback.PlaybackServices
import com.vinz.music.playback.QueueKind
import com.vinz.music.playback.QueueSource
import com.vinz.music.playback.VisualizerTap
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Plan item 10 (1.8.7), "Albums play gapless", on the phone and with no network: two WAV files written here that are one
 * 440 Hz sine cut in two (6 s each, 44.1 kHz stereo 16-bit) play as an album through Vinz's own player and service,
 * Crossfade off. Measured at the change of song: whether the audio output was made again (Media3's AudioTrack released
 * and made anew: a gap), how the player moved on (its discontinuity, and when), and the sound itself as it leaves Vinz's
 * chain (the visualizer's tap, [VisualizerTap.testTap]): every frame there, no silence and no step bigger than the
 * sine's own. One line with the tag "Gapless" has the numbers (the unit test GaplessChainTest checks the chain itself
 * with each effect on).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class GaplessAppTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val dir get() = java.io.File(Fixture.context.filesDir, "gapless-test")
    private var listener: AnalyticsListener? = null

    @After fun cleanUp() {
        VisualizerTap.testTap = null
        runCatching {
            instrumentation.runOnMainSync {
                Fixture.container.player.stop()
                listener?.let { PlaybackServices.playerForTest?.removeAnalyticsListener(it) }
            }
        }
        dir.deleteRecursively()
        Fixture.container.settings.setAutoplay(true)
    }

    /** [frames] of the sine from frame [from], as a 16-bit stereo WAV. */
    private fun wav(file: java.io.File, from: Int, frames: Int) {
        val b = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        b.putInt(16).putShort(1.toShort()).putShort(2.toShort()).putInt(RATE).putInt(RATE * 4).putShort(4.toShort()).putShort(16.toShort())
        b.put("data".toByteArray()).putInt(frames * 4)
        for (i in from until from + frames) {
            val v = (AMPLITUDE * 32767 * sin(2 * PI * HZ * i / RATE)).roundToInt().toShort()
            b.putShort(v).putShort(v)
        }
        file.writeBytes(b.array())
    }

    private fun <T> waitFor(ms: Long, what: () -> T?): T? {
        val until = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < until) { what()?.let { return it }; Thread.sleep(50) }
        return null
    }

    @Test fun twoHalvesOfOneSinePlayAsOne() {
        Fixture.ready()
        val c = Fixture.container
        // Crossfade off (an album doesn't blend anyway), and nothing else that would change the sound at the change or
        // stop it: skip silence, the fade on pause, Autoplay (it would look for more songs), pause when muted or face down.
        c.settings.setMix(false)
        c.settings.setSkipSilence(false)
        c.settings.setPauseFade(false)
        c.settings.setAutoplay(false)
        c.settings.setPauseOnMute(false)
        c.settings.setFaceDownPause(false)
        runBlocking {
            withTimeout(10_000) {
                while (c.settings.current.let { it.mix.on || it.skipSilence || it.sound.pauseFade || it.autoplay || it.behavior.pauseOnMute || it.behavior.faceDownPause }) delay(20)
            }
        }
        dir.mkdirs()
        val fileA = java.io.File(dir, "half-1.wav").also { wav(it, 0, SONG) }
        val fileB = java.io.File(dir, "half-2.wav").also { wav(it, SONG, SONG) }
        val album = AlbumRef("Gapless test album", "tstgaplessalbum")
        fun song(f: java.io.File, n: Int) = Song(
            id = Song.LOCAL_PREFIX + android.net.Uri.fromFile(f), title = "Gapless half $n",
            artists = listOf(ArtistRef("Test Artist", null)), album = album, durationSec = SONG / RATE,
        )
        val one = song(fileA, 1)
        val two = song(fileB, 2)
        Fixture.launch()
        val exo = waitFor(20_000) { PlaybackServices.playerForTest } ?: error("Vinz's playback service didn't start")

        // What the player reports.
        val made = AtomicInteger()
        val released = AtomicInteger()
        val underruns = AtomicInteger()
        val problems = CopyOnWriteArrayList<String>()
        val jumps = CopyOnWriteArrayList<String>()
        val changedAt = AtomicLong()
        val changeReason = AtomicInteger(-1)
        val changePosition = AtomicLong(-1)
        val l = object : AnalyticsListener {
            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) { made.incrementAndGet() }
            override fun onAudioTrackReleased(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) { released.incrementAndGet() }
            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) { underruns.incrementAndGet() }
            override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) { problems += "sink: $audioSinkError" }
            override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) { problems += "codec: $audioCodecError" }
            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) { problems += "player: ${error.errorCodeName}" }
            override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime, oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                jumps += "reason $reason ${oldPosition.mediaItemIndex}@${oldPosition.positionMs}→${newPosition.mediaItemIndex}@${newPosition.positionMs}"
            }
            override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) {
                if (mediaItem?.mediaId == two.id) {
                    changedAt.set(eventTime.realtimeMs)
                    changeReason.set(reason)
                    changePosition.set(eventTime.currentPlaybackPositionMs)
                }
            }
        }
        listener = l
        instrumentation.runOnMainSync { exo.addAnalyticsListener(l) }

        // The sound as it leaves Vinz's chain: the left channel, from the first song's first frame on.
        val left = FloatArray(RATE * 20)
        val filled = AtomicInteger()
        val nextSoundAt = AtomicLong()
        val encodings = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val rates = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        VisualizerTap.testTap = { buf, encoding, channels ->
            encodings += encoding
            rates += VisualizerTap.sampleRate
            var n = filled.get()
            val frameBytes = channels * if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
            while (buf.remaining() >= frameBytes && n < left.size) {
                left[n++] = if (encoding == C.ENCODING_PCM_FLOAT) buf.float else buf.short / 32768f
                buf.position(buf.position() + frameBytes - (if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2))
            }
            if (n > SONG && nextSoundAt.get() == 0L) nextSoundAt.set(SystemClock.elapsedRealtime())
            filled.set(n)
        }

        instrumentation.runOnMainSync { c.player.playQueue(listOf(one, two), 0, QueueSource(QueueKind.ALBUM, album.title, originId = album.id)) }
        // Repeat off: a copy left on "repeat one" by another test played the first tone again and again (A16, 2026-11-15).
        repeat(3) { if (c.player.repeatMode.value != androidx.media3.common.Player.REPEAT_MODE_OFF) { instrumentation.runOnMainSync { c.player.cycleRepeat() }; Thread.sleep(700) } }
        fun position(): Long { var at = 0L; instrumentation.runOnMainSync { at = c.player.position }; return at }
        // A second into the first song: the output is made by now. Only what happens after this counts.
        assertTrue("the first half plays", waitFor(20_000) { (c.player.currentSong.value?.id == one.id && position() > 1_000).takeIf { it } } == true)
        val madeBefore = made.get()
        val releasedBefore = released.get()
        val underrunsBefore = underruns.get()
        // Through the change, to 3 s into the second half.
        val through = waitFor(30_000) { (c.player.currentSong.value?.id == two.id && position() > 3_000).takeIf { it } } == true
        instrumentation.runOnMainSync { c.player.setPlaying(false) }
        VisualizerTap.testTap = null
        val madeAgain = made.get() - madeBefore
        val releasedAgain = released.get() - releasedBefore

        // The sound: each sample's step against the sine's own biggest step at the level it comes out at (a gain set
        // by normalization moves the level, not the shape), and runs of silence.
        val n = filled.get()
        val block = RATE / 100
        val peaks = FloatArray(n / block + 1).also { p -> for (i in 0 until n) p[i / block] = maxOf(p[i / block], abs(left[i])) }
        fun level(i: Int): Float { val b = i / block; return maxOf(peaks[b], peaks.getOrElse(b - 1) { 0f }, peaks.getOrElse(b + 1) { 0f }) }
        val lsb = 3f / 32768f
        var worstNear = 0.0; var worstNearAt = -1
        var worstElse = 0.0; var worstElseAt = -1
        for (i in 1 until n) {
            val own = level(i) * 2 * PI * HZ / RATE
            val ratio = abs(left[i] - left[i - 1]) / (own + lsb)
            if (abs(i - SONG) <= RATE / 10) { if (ratio > worstNear) { worstNear = ratio; worstNearAt = i } }
            else if (ratio > worstElse) { worstElse = ratio; worstElseAt = i }
        }
        var run = 0; var silence = 0; var silenceAt = -1
        for (i in 0 until n) { if (abs(left[i]) <= lsb) run++ else run = 0; if (run > silence) { silence = run; silenceAt = i } }
        val ahead = if (changedAt.get() > 0 && nextSoundAt.get() > 0) changedAt.get() - nextSoundAt.get() else null

        android.util.Log.i(
            "Gapless",
            "through the change: ${if (through) "yes" else "NO"}; audio output made again ${madeAgain}x, released ${releasedAgain}x " +
                "(made ${made.get()}x in all); underruns ${underruns.get() - underrunsBefore}; song change reason ${changeReason.get()} " +
                "at ${changePosition.get()} ms; jumps $jumps; problems $problems; out of the chain: $n frames " +
                "(encoding $encodings, $rates Hz), the change at frame $SONG; biggest step near the change ${"%.2f".format(worstNear)}x " +
                "the sine's own (frame $worstNearAt), elsewhere ${"%.2f".format(worstElse)}x (frame $worstElseAt); longest silence " +
                "$silence samples (ending at frame $silenceAt); the next half's sound left the chain ${ahead ?: "?"} ms before " +
                "the player said the song changed",
        )
        assertTrue("both halves played, through the change", through)
        assertTrue("no errors: $problems", problems.isEmpty())
        assertEquals("the song changed on its own (gapless), not by a seek", Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, changeReason.get())
        assertEquals("the audio output wasn't made again at the change (same format: no new AudioTrack)", 0, madeAgain)
        assertEquals("nor released", 0, releasedAgain)
        assertTrue("the sound came out at 44.1 kHz as written: $rates", rates == setOf(RATE))
        assertTrue("the chain gave the whole first half and more ($n frames)", n >= SONG + RATE)
        assertTrue("no silence in the sound ($silence samples at frame $silenceAt)", silence <= 3)
        assertTrue("no step at the change bigger than the sine's own (${"%.2f".format(worstNear)}x at frame $worstNearAt)", worstNear <= 1.15)
        assertTrue("nor anywhere else (${"%.2f".format(worstElse)}x at frame $worstElseAt)", worstElse <= 1.15)
    }

    private companion object {
        const val RATE = 44_100
        const val SONG = 6 * RATE
        const val HZ = 440.0
        const val AMPLITUDE = 0.5
    }
}
