package com.vinz.music

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.exoplayer.audio.ChannelMappingAudioProcessor
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.audio.TrimmingAudioProcessor
import com.google.common.collect.ImmutableList
import com.vinz.music.playback.EdgeSilenceProcessor
import com.vinz.music.playback.EqFilter
import com.vinz.music.playback.EqProfile
import com.vinz.music.playback.NormalizerProcessor
import com.vinz.music.playback.ParametricEqProcessor
import com.vinz.music.playback.SpatialAudioProcessor
import com.vinz.music.playback.VisualizerTap
import com.vinz.music.playback.sing.StemStore
import com.vinz.music.playback.sing.VocalStemProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Plan item 10 (1.8.7), "Albums play gapless": Vinz's own audio chain, built as the service builds it (the sink's own
 * trimming, channel mapping and 16-bit stages first, then Sing, skip silence, headphone EQ, Spatial, the normalizer and
 * the visualizer's tap, with Media3's silence skipper and Sonic after them), driven the way Media3's DefaultAudioSink
 * (1.11.1) drives it across a gapless move to the next song in the same format: the sink is configured for the next song
 * as its first sound comes out of the decoder, then the chain is drained to the end of the last song (queueEndOfStream)
 * and flushed with the next song's period (StreamMetadata), and the service sets the next song's gain a moment later.
 *
 * One 440 Hz sine split across two songs of an album has to come out exactly as it does played as one song: nothing left
 * out, nothing put in, no step at the change. Both with the sink keeping up and with the end of the first song still in
 * the chain when the next one's configuration comes (the usual case: the output is full, so the chain holds its last
 * buffers until it has room).
 */
class GaplessChainTest {
    private val rate = 44_100
    private val songFrames = 2 * rate
    private val chunk = 1024

    /** One window and period per id, each [durationUs] long. */
    private class Songs(private val ids: List<String>, private val durationUs: Long) : Timeline() {
        override fun getWindowCount() = ids.size
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window = window.set(
            ids[windowIndex], MediaItem.Builder().setMediaId(ids[windowIndex]).build(), null, 0, 0, 0, true, false,
            null, 0, durationUs, windowIndex, windowIndex, 0,
        )
        override fun getPeriodCount() = ids.size
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            period.set(ids[periodIndex], ids[periodIndex], periodIndex, durationUs, 0)
        override fun getIndexOfPeriod(uid: Any) = ids.indexOf(uid).takeIf { it >= 0 } ?: C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = ids[periodIndex]
    }

    /** The settings that touch the chain. [gainDb]: the gain "Albums as they are" gives every song of the album. */
    private data class Setup(
        val normalize: Boolean = false,
        val gainDb: Float = 0f,
        val eq: EqProfile? = null,
        val spatial: Boolean = false,
        val amplitude: Double = 0.5,
    )

    /** The sine's [n] frames from frame [from] (both channels), 16-bit. */
    private fun sine(s: Setup, from: Int, n: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        for (i in from until from + n) {
            val v = (s.amplitude * 32767 * sin(2 * PI * 440 * i / rate)).roundToInt().toShort()
            b.putShort(v); b.putShort(v)
        }
        b.flip()
        return b
    }

    /** Vinz's chain for [s], and what came out of it. */
    private inner class Sink(private val s: Setup) {
        val normalizer = NormalizerProcessor().apply {
            // As the service's startingGain: the album's one gain with normalization on, none with it off.
            songGainDb = { _, _ -> if (s.normalize) s.gainDb else 0f }
        }
        private val edges = EdgeSilenceProcessor()
        private val eq = ParametricEqProcessor().apply { configure(s.eq, 1f) }
        private val spatial = SpatialAudioProcessor().apply { configure(s.spatial, 1f) }
        private val vocals = VocalStemProcessor(object : VocalStemProcessor.Source {
            override val onNow = false
            override val levelNow = 1f
            override val store = StemStore(kotlin.io.path.createTempDirectory("gapless").toFile())
        })
        private val pipeline = AudioProcessingPipeline(
            ImmutableList.builder<AudioProcessor>()
                .add(TrimmingAudioProcessor(), ChannelMappingAudioProcessor(), ToInt16PcmAudioProcessor())
                .add(*EdgeSilenceProcessor.Chain(edges, arrayOf(vocals, edges, eq, spatial, normalizer, TeeAudioProcessor(VisualizerTap))).audioProcessors)
                .build(),
        )
        private val format = AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)
        private val out = java.io.ByteArrayOutputStream()

        private fun meta(tl: Timeline, uid: String) =
            AudioProcessor.StreamMetadata.Builder().setTimeline(tl).setPeriodUid(uid).setPositionOffsetUs(0).build()

        /** The first song: the sink's configure, then its first flush (setupAudioProcessors). */
        fun begin(tl: Timeline, uid: String) {
            pipeline.configure(format)
            pipeline.flush(meta(tl, uid))
        }

        /** What the chain has ready goes out (to the AudioTrack, which here always has room). */
        fun take() {
            while (true) {
                val o = pipeline.output
                if (!o.hasRemaining()) return
                val bytes = ByteArray(o.remaining())
                o.get(bytes)
                out.write(bytes)
            }
        }

        fun write(b: ByteBuffer) {
            take()
            while (b.hasRemaining()) {
                pipeline.queueInput(b)
                take()
            }
        }

        /** Handed to the chain while the output is full: nothing is taken out. True when the chain took all of it. */
        fun writeWhileFull(b: ByteBuffer): Boolean {
            pipeline.queueInput(b)
            return !b.hasRemaining()
        }

        /** The next song, as DefaultAudioSink.handleBuffer moves to a reusable pending configuration. */
        fun nextSong(tl: Timeline, uid: String) {
            // MediaCodecAudioRenderer.onOutputFormatChanged → DefaultAudioSink.configure: the same processors, so the
            // same pipeline, configured again (each processor's onConfigure runs now).
            pipeline.configure(format)
            // The next song's first buffer: drainToEndOfStream, the same audio output, then setupAudioProcessors.
            drain()
            pipeline.flush(meta(tl, uid))
        }

        fun drain() {
            pipeline.queueEndOfStream()
            var guard = 0
            while (!pipeline.isEnded) {
                take()
                check(++guard < 10_000) { "the chain never drained" }
            }
        }

        fun samples(): ShortArray {
            val b = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.nativeOrder()).asShortBuffer()
            return ShortArray(b.remaining()).also { b.get(it) }
        }
    }

    /**
     * Two songs of [songFrames] each (or, [gapless] false, the same sound as one song of twice the length), 1024 frames at
     * a time as a decoder hands them. [full]: the first song's last four buffers go in while the output is full, so they
     * are still in the chain when the next song's configuration comes.
     */
    private fun play(s: Setup, gapless: Boolean, full: Boolean = false): ShortArray {
        val sink = Sink(s)
        val tl = if (gapless) Songs(listOf("a1", "a2"), songFrames * 1_000_000L / rate) else Songs(listOf("whole"), 2L * songFrames * 1_000_000L / rate)
        sink.begin(tl, if (gapless) "a1" else "whole")
        for (song in 0..1) {
            if (song == 1 && gapless) sink.nextSong(tl, "a2")
            for (from in 0 until songFrames step chunk) {
                val n = minOf(chunk, songFrames - from)
                // The service sets the song's gain once the main thread hears it started (loadExtras): 0.1 s in for the
                // first, 0.3 s in for the next (the player's audio runs ahead of the main thread).
                if (s.normalize && from == (if (song == 0) 4 else 13) * chunk) {
                    sink.normalizer.setGainDb(s.gainDb, 0.1f, if (gapless) "a${song + 1}" else null)
                }
                val b = sine(s, song * songFrames + from, n)
                if (full && song == 0 && from >= songFrames - 4 * chunk) {
                    if (!sink.writeWhileFull(b)) sink.write(b)
                } else sink.write(b)
            }
        }
        sink.drain()
        return sink.samples()
    }

    /** Where (frame) and how far (16-bit steps) [a] and [b] differ most, comparing the left channel. */
    private fun worst(a: ShortArray, b: ShortArray): Pair<Int, Int> {
        var at = -1; var most = 0
        for (i in 0 until minOf(a.size, b.size) step 2) {
            val d = abs(a[i] - b[i])
            if (d > most) { most = d; at = i / 2 }
        }
        return at to most
    }

    /** The biggest step from one sample to the next (left channel) within [radius] frames of [frame]. */
    private fun biggestStep(x: ShortArray, frame: Int, radius: Int): Int {
        var most = 0
        for (f in maxOf(1, frame - radius) until minOf(x.size / 2, frame + radius)) most = maxOf(most, abs(x[2 * f] - x[2 * f - 2]))
        return most
    }

    /** The longest run of (near) silent samples, left channel: a gap would show as one. */
    private fun longestSilence(x: ShortArray): Int {
        var run = 0; var most = 0
        for (i in x.indices step 2) { if (abs(x[i].toInt()) <= 2) run++ else run = 0; most = maxOf(most, run) }
        return most
    }

    /** Plays [s] across the change and as one song, and checks the two came out the same. */
    private fun check(name: String, s: Setup, full: Boolean = false) {
        val one = play(s, gapless = false)
        val two = play(s, gapless = true, full = full)
        val (at, most) = worst(one, two)
        val step = biggestStep(two, songFrames, 2048)
        val ownStep = biggestStep(one, songFrames, 2048)
        val line = "$name${if (full) " (output full at the change)" else ""}: ${two.size / 2} frames out of ${2 * songFrames} " +
            "(as one song ${one.size / 2}); most apart $most at frame $at (the change at $songFrames); biggest step " +
            "at the change $step (as one song $ownStep); longest silence ${longestSilence(two)} samples"
        println("Gapless: $line")
        assertEquals("nothing left out or put in: $line", one.size, two.size)
        assertEquals("every sample of the sine: $line", 2 * 2 * songFrames, two.size)
        assertTrue("no silence: $line", longestSilence(two) <= 2)
        assertTrue("no step at the change bigger than the sound's own: $line", step <= ownStep + 3)
        assertTrue("the same as one song (within 3 of 32768): $line", most <= 3)
    }

    @Test fun asItComesTheSoundGoesThroughUntouched() {
        val s = Setup()
        val two = play(s, gapless = true)
        val input = ShortArray(2 * 2 * songFrames).also { a ->
            val b = sine(s, 0, 2 * songFrames).asShortBuffer(); b.get(a)
        }
        assertArrayEquals("bit for bit, the change included", input, two)
        check("defaults", s)
        check("defaults", s, full = true)
    }

    /** "Albums as they are": every song of the album at one gain, the limiter not reached. */
    @Test fun anAlbumAtOneGainStaysContinuous() {
        val s = Setup(normalize = true, gainDb = -4f)
        check("normalize, album at -4 dB", s)
        check("normalize, album at -4 dB", s, full = true)
    }

    /** A quiet album lifted into the limiter: its hold on the peaks goes on across the change. */
    @Test fun aLiftedAlbumKeepsTheLimitersHoldAcrossTheChange() {
        val s = Setup(normalize = true, gainDb = 6f, amplitude = 0.6)
        check("normalize, album lifted 6 dB into the limiter", s)
        check("normalize, album lifted 6 dB into the limiter", s, full = true)
    }

    /** Headphone correction on: its filters (and preamp) go on across the change. */
    @Test fun headphoneCorrectionGoesOnAcrossTheChange() {
        val s = Setup(eq = EqProfile("Test", -3.0, listOf(EqFilter(EqFilter.Type.PEAK, 1000.0, 4.0, 1.0), EqFilter(EqFilter.Type.LOW_SHELF, 105.0, 5.0, 0.7))))
        check("headphone EQ", s)
        check("headphone EQ", s, full = true)
    }

    /** Vinz Spatial on: its room and crossfeed go on across the change. */
    @Test fun spatialGoesOnAcrossTheChange() {
        val s = Setup(spatial = true)
        check("spatial", s)
        check("spatial", s, full = true)
    }

    /**
     * A seek is still a seek: the limiter, the EQ and Spatial start afresh there (nothing to carry across a jump in the
     * sound). What comes after a seek from a loud passage is what comes after one from silence, sample for sample.
     */
    @Test fun aSeekStillStartsTheEffectsAfresh() {
        val tl = Songs(listOf("a1"), 10_000_000L)
        fun at(us: Long) = AudioProcessor.StreamMetadata.Builder().setTimeline(tl).setPeriodUid("a1").setPositionOffsetUs(us).build()
        val makers: Map<String, () -> AudioProcessor> = mapOf(
            "normalizer lifted 6 dB" to { NormalizerProcessor().apply { songGainDb = { _, _ -> 6f } } },
            "headphone EQ" to { ParametricEqProcessor().apply { configure(EqProfile("Test", -3.0, listOf(EqFilter(EqFilter.Type.PEAK, 1000.0, 4.0, 1.0))), 1f) } },
            "spatial" to { SpatialAudioProcessor().apply { configure(true, 1f) } },
        )
        for ((name, make) in makers) {
            fun afterSeekFrom(before: Setup): ShortArray {
                val p = make()
                p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
                p.flush(at(0))
                p.queueInput(sine(before, 0, rate / 2)); p.output
                p.flush(at(5_000_000))
                p.queueInput(sine(Setup(amplitude = 0.1), 0, rate / 10))
                val o = p.output.order(ByteOrder.nativeOrder()).asShortBuffer()
                return ShortArray(o.remaining()).also { o.get(it) }
            }
            val fromLoud = afterSeekFrom(Setup(amplitude = 0.9))
            val fromSilence = afterSeekFrom(Setup(amplitude = 0.0))
            val (where, most) = worst(fromLoud, fromSilence)
            assertTrue("$name: after a seek nothing of the sound before is left (most apart $most at frame $where)", most <= 1)
        }
    }
}
