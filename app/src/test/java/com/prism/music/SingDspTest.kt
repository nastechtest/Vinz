package com.prism.music

import com.prism.music.playback.sing.Fft
import com.prism.music.playback.sing.Resampler
import com.prism.music.playback.sing.Spleeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** Sing's signal path: everything it subtracts has to line up with the song to the sample. */
class SingDspTest {
    @Test
    fun fftRoundTrips() {
        val fft = Fft(4096)
        val rnd = Random(1)
        val re = DoubleArray(4096) { rnd.nextDouble() - 0.5 }
        val im = DoubleArray(4096) { rnd.nextDouble() - 0.5 }
        val r0 = re.copyOf(); val i0 = im.copyOf()
        fft.transform(re, im)
        fft.transform(re, im, inverse = true)
        for (k in 0 until 4096) { assertEquals(r0[k], re[k], 1e-9); assertEquals(i0[k], im[k], 1e-9) }
        // A pure tone lands in its bin.
        val tone = DoubleArray(4096) { sin(2 * PI * 10 * it / 4096) }
        val z = DoubleArray(4096)
        fft.transform(tone, z)
        assertEquals(2048.0, abs(z[10]), 1e-6)
    }

    @Test
    fun resamplingKeepsTimingExactly() {
        for ((from, to) in listOf(48_000 to 44_100, 44_100 to 32_000, 32_000 to 48_000)) {
            val hz = 997.0
            val frames = from * 2
            val input = FloatArray(frames * 2) { i -> (0.5 * sin(2 * PI * hz * (i / 2) / from)).toFloat() }
            // Fed in uneven pieces, as a decoder hands them over.
            val r = Resampler(2, from, to)
            val parts = ArrayList<FloatArray>()
            var at = 0
            val sizes = intArrayOf(1000, 7, 4096, 333, 20000)
            var s = 0
            while (at < input.size) {
                val n = minOf(sizes[s++ % sizes.size] * 2, input.size - at)
                parts += r.push(input.copyOfRange(at, at + n)); at += n
            }
            parts += r.finish()
            val out = FloatArray(parts.sumOf { it.size }).also { o -> var p = 0; parts.forEach { System.arraycopy(it, 0, o, p, it.size); p += it.size } }
            assertEquals("$from→$to length", (frames.toLong() * to / from).toInt(), out.size / 2)
            var worst = 0.0
            // Away from the very ends, each output sample is the tone at its own time.
            for (o in to / 10 until out.size / 2 - to / 10) {
                val expected = 0.5 * sin(2 * PI * hz * o / to)
                worst = maxOf(worst, abs(out[o * 2] - expected))
            }
            println("[dsp] $from→$to worst error $worst")
            assertTrue("$from→$to error $worst", worst < 2e-3)
        }
    }

    @Test
    fun spleeterTransformsGiveTheSongBack() {
        // With nothing masked, the "vocals" are the whole song, sample for sample, whatever the feeding.
        val sp = Spleeter(null, null, null, separate = false)
        val rnd = Random(7)
        val frames = Spleeter.FRAMES * Spleeter.HOP * 2 + 12_345
        val input = FloatArray(frames * 2) { (rnd.nextFloat() - 0.5f) * 0.8f }
        val parts = ArrayList<FloatArray>()
        var at = 0
        while (at < input.size) { val n = minOf(48_000 * 2 + 6, input.size - at); parts += sp.feed(input.copyOfRange(at, at + n)); at += n }
        parts += sp.finish()
        val out = FloatArray(parts.sumOf { it.size }).also { o -> var p = 0; parts.forEach { System.arraycopy(it, 0, o, p, it.size); p += it.size } }
        assertEquals(input.size, out.size)
        var worst = 0f
        for (i in input.indices) worst = maxOf(worst, abs(input[i] - out[i]))
        println("[dsp] spleeter round trip worst error $worst")
        assertTrue("error $worst", worst < 1e-4f)
    }
}
