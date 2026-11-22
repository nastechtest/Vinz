package com.vinz.music

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import com.vinz.music.playback.NormalizerProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/**
 * D62 (Reddit, 1.8.5): "static sounds at the start of songs, gone when restarting the song". The player's audio runs
 * ahead of the main thread: the next song's first ~0.65 s went through the normalizer at the last song's gain before
 * the service set the new one (Galaxy A16: a quiet song's +6 dB on a loud one, the limiter cutting up to 7 dB). A
 * restart had the right gain already, so it sounded clean. Now the processor asks for a song's gain when its sound
 * starts (the flush that comes with it).
 */
class NormalizerSongStartTest {
    private val rate = 48_000

    /** One window and period per id; "loud#video" is another item (the song swapped for its video) of the song "loud". */
    private class Songs(private val ids: List<String>) : Timeline() {
        override fun getWindowCount() = ids.size
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window = window.set(
            ids[windowIndex], MediaItem.Builder().setMediaId(ids[windowIndex].substringBefore('#')).build(), null, 0, 0, 0, true, false,
            null, 0, 200_000_000L, windowIndex, windowIndex, 0,
        )
        override fun getPeriodCount() = ids.size
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            period.set(ids[periodIndex], ids[periodIndex], periodIndex, 200_000_000L, 0)
        override fun getIndexOfPeriod(uid: Any) = ids.indexOf(uid).takeIf { it >= 0 } ?: C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = ids[periodIndex]
    }

    private val songs = Songs(listOf("quiet", "loud", "unknown", "loud#video"))
    /** What the service knows: the quiet song is lifted 6 dB, the loud one brought down 8; the third isn't known. */
    private val gains = mutableMapOf("quiet" to 6f, "loud" to -8f)

    private fun processor() = NormalizerProcessor().apply {
        songGainDb = { item, _ -> gains[item.mediaId] }
        configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
    }

    private fun startOf(p: NormalizerProcessor, id: String, positionUs: Long = 0) =
        p.flush(AudioProcessor.StreamMetadata.Builder().setTimeline(songs).setPeriodUid(id).setPositionOffsetUs(positionUs).build())

    /** [seconds] of a tone peaking at [peak] through [p], 20 ms at a time: the loudest sample out per 50 ms, in dBFS. */
    private fun play(p: NormalizerProcessor, seconds: Double, peak: Double = 0.98): List<Double> {
        val frames = (seconds * rate).toInt()
        val out = ArrayList<Float>()
        for (from in 0 until frames step 960) {
            val n = minOf(960, frames - from)
            val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            for (i in from until from + n) { val s = (peak * 32767 * sin(2 * PI * 220 * i / rate)).toInt().toShort(); b.putShort(s); b.putShort(s) }
            b.flip()
            p.queueInput(b)
            val o = p.output.order(ByteOrder.nativeOrder())
            while (o.remaining() >= 4) { out += o.short / 32768f; o.short }
        }
        return out.chunked(rate / 20).map { c -> 20 * log10(c.maxOf { abs(it) }.toDouble().coerceAtLeast(1e-6)) }
    }

    @Test fun aLoudSongAfterAQuietOneStartsAtItsOwnGain() {
        val p = processor()
        startOf(p, "quiet")
        p.setGainDb(6f) // the service, for the quiet song
        play(p, 1.0)
        // The next song's sound starts; the service only hears of it later.
        startOf(p, "loud")
        val first = play(p, 0.5)
        // 0.98 at -8 dB is -8.2 dBFS. At the quiet song's +6 dB it hit the limiter's -1 dBFS ceiling.
        assertTrue("the loud song's first 50 ms came out at ${first.first()} dBFS", first.first() < -7.5)
        assertTrue("never near the limiter: $first", first.all { it < -7.5 })
        // The service's own figure a moment later changes nothing.
        p.setGainDb(-8f)
        assertEquals(-8.2, play(p, 0.2).last(), 0.3)
    }

    @Test fun anUnknownSongStartsAsItIs() {
        val p = processor()
        startOf(p, "quiet")
        p.setGainDb(6f)
        play(p, 0.5)
        startOf(p, "unknown")
        // A tone at -10.5 dBFS comes out as it is, not 6 dB up.
        val first = play(p, 0.3, peak = 0.3)
        assertTrue("$first", first.all { abs(it + 10.5) < 0.3 })
    }

    @Test fun aSeekInTheSameSongKeepsItsGain() {
        val p = processor()
        startOf(p, "loud")
        // A better figure came while it played (the service eases it in).
        p.setGainDb(-4f, glideSec = 2f)
        play(p, 0.2)
        // Seeking within the song keeps the service's figure and lands on it at once, without a glide.
        startOf(p, "loud", positionUs = 60_000_000)
        assertEquals(-4.2, play(p, 0.1).first(), 0.3)
    }

    /**
     * Review (1.8.5): the song swapped for its video mid-song (or another form of it: the same song, but a new item, so a
     * new period) is the same listen and keeps the gain it had. A figure measured since is for its next play (past its
     * first seconds the service leaves the volume alone), not snapped to halfway through this one.
     */
    @Test fun theSameSongSwappedForItsVideoKeepsItsGain() {
        val p = processor()
        startOf(p, "loud")
        p.setGainDb(-8f)
        play(p, 1.0)
        // Measured 30 s in: -3 dB, kept for next time.
        gains["loud"] = -3f
        startOf(p, "loud#video", positionUs = 30_000_000)
        assertEquals("the video goes on at the song's -8 dB", -8.2, play(p, 0.2).first(), 0.3)
        // And back to the song.
        startOf(p, "loud", positionUs = 31_000_000)
        assertEquals(-8.2, play(p, 0.2).first(), 0.3)
        // Another song after it still starts at its own gain: a tone at -10.5 dBFS lifted 6 dB.
        startOf(p, "quiet")
        assertEquals(-4.5, play(p, 0.2, peak = 0.3).first(), 0.3)
    }

    /**
     * Review (1.8.5): a figure the service sets for a song lands only on that song. A short song's scan (or YouTube's
     * figure) arriving as it ends, after the audio already moved on to the next one and before the main thread hears of
     * it, put the last song's gain on the next one's start (D62's static, narrower).
     */
    @Test fun aLateFigureForTheLastSongStaysOffTheNext() {
        val p = processor()
        startOf(p, "quiet")
        p.setGainDb(6f, songId = "quiet")
        play(p, 1.0)
        startOf(p, "loud")
        // The quiet song's figure lands now: the service still thinks it plays.
        p.setGainDb(5f, glideSec = 2f, songId = "quiet")
        val first = play(p, 0.5)
        assertTrue("the loud song stays at its own -8 dB: $first", first.all { abs(it + 8.2) < 0.3 })
        // The service's own figure for the loud song is taken.
        p.setGainDb(-6f, songId = "loud")
        assertEquals(-6.2, play(p, 0.5).last(), 0.3)
    }

    @Test fun aFigureForTheNextSongSetBeforeItsSoundStartsWaitsForIt() {
        val p = processor()
        startOf(p, "quiet")
        p.setGainDb(6f, songId = "quiet")
        play(p, 0.5)
        // A song picked: the service has its figure before the player's sound gets to it. The quiet song plays on as it was.
        p.setGainDb(-5f, songId = "loud")
        assertEquals(-4.5, play(p, 0.2, peak = 0.3).last(), 0.3)
        startOf(p, "loud")
        assertEquals("the service's figure, not the stored one", -5.2, play(p, 0.2).first(), 0.3)
    }

    @Test fun withoutAnAskTheGainSetStays() {
        // A Mix blend's ending song: its own processor, given its gain once.
        val p = NormalizerProcessor().apply { configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)) }
        p.setGainDb(-8f)
        startOf(p, "quiet")
        assertEquals(-8.2, play(p, 0.1).first(), 0.3)
    }
}
