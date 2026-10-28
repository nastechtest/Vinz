package com.prism.music.playback.sing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Deezer's Spleeter (2 stems), as sherpa-onnx exported it: the song's vocals, taken out of stereo 44.1 kHz audio
 * fed in a bit at a time ([feed], then [finish]), and handed back as soon as they're final.
 *
 * Each channel's spectrum (4096-point, hop 1024, periodic Hann) goes through both networks 512 frames (~11.9 s) at a
 * time, as Spleeter does it. Their outputs make a soft mask (vocals² / (vocals² + accompaniment²)) that's applied to
 * the mix's own spectrum, so the vocals keep the mix's phase; the networks only see up to ~11 kHz, and above it each
 * frame uses the mask's average over its top band, so sibilants go with the voice. The vocals come back out by
 * overlap-add, lined up sample for sample with what went in.
 */
class Spleeter(
    private val env: OrtEnvironment?,
    private val vocals: OrtSession?,
    private val accompaniment: OrtSession?,
    /** With false the mask is all ones: the whole mix comes back (for testing the transforms). */
    private val separate: Boolean = true,
) {
    companion object {
        const val RATE = 44_100
        const val N_FFT = 4096
        const val HOP = 1024
        const val BINS = 1024
        const val FRAMES = 512
        private const val SPEC = N_FFT / 2 + 1
        /** Leading zeros, so the song's first sample is covered by four frames like every other. */
        private const val PAD = N_FFT - HOP
        private const val EPS = 1e-10f
        /** Sum of the squared window over the overlapping frames (periodic Hann at 75 % overlap). */
        private const val NORM = 1.5
        private const val EXT_FROM = 896
    }

    /** Called after each chunk: how long the networks took, and the whole chunk (ms). */
    var onChunk: ((modelMs: Long, totalMs: Long) -> Unit)? = null

    private val fft = Fft(N_FFT)
    private val window = DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2 * PI * it / N_FFT) }

    // Input, in padded coordinates (index 0 is the first of PAD zeros), from [inStart] on.
    private var input = FloatArray(PAD * 2 + 2 * (FRAMES * HOP + N_FFT))
    private var inFrames = PAD
    private var inStart = 0L
    /** Song samples fed so far (without the padding). */
    private var fed = 0L

    // Output accumulator (overlap-add), padded coordinates, from [outStart] on.
    private var acc = DoubleArray(2 * (FRAMES * HOP + N_FFT))
    private var outStart = 0L
    /** Song samples handed back so far. */
    private var emitted = 0L

    private var chunk = 0L

    private val re = DoubleArray(N_FFT)
    private val im = DoubleArray(N_FFT)
    private val specRe = Array(2) { Array(FRAMES) { FloatArray(SPEC) } }
    private val specIm = Array(2) { Array(FRAMES) { FloatArray(SPEC) } }
    private val mag = FloatArray(2 * FRAMES * BINS)
    private val maskedRe = Array(2) { FloatArray(SPEC) }
    private val maskedIm = Array(2) { FloatArray(SPEC) }

    /** Feeds stereo 44.1 kHz samples (interleaved); returns the vocals now final (interleaved, same rate). */
    fun feed(samples: FloatArray): FloatArray {
        append(samples)
        fed += samples.size / 2
        val out = ArrayList<FloatArray>()
        while (inStart + inFrames >= chunkEnd(chunk)) out += runChunk()
        return out.flatten()
    }

    /** The rest of the vocals, once all the song is in (the last chunk padded with silence). */
    fun finish(): FloatArray {
        val total = fed
        val out = ArrayList<FloatArray>()
        while (emitted < total) {
            val need = (chunkEnd(chunk) - (inStart + inFrames)).coerceAtLeast(0)
            if (need > 0) append(FloatArray((need * 2).toInt()))
            out += runChunk()
        }
        val all = out.flatten()
        // Only as long as the song itself.
        return all
    }

    /** The padded sample past the last one chunk [c]'s frames need. */
    private fun chunkEnd(c: Long) = ((c + 1) * FRAMES - 1) * HOP + N_FFT

    private fun append(samples: FloatArray) {
        val frames = samples.size / 2
        if ((inFrames + frames) * 2 > input.size) input = input.copyOf(maxOf(input.size * 2, (inFrames + frames) * 2))
        System.arraycopy(samples, 0, input, inFrames * 2, samples.size)
        inFrames += frames
    }

    private fun runChunk(): FloatArray {
        val t0 = System.nanoTime()
        val first = chunk * FRAMES
        // Forward transforms: both channels in one FFT (left real, right imaginary), then pulled apart.
        for (f in 0 until FRAMES) {
            val start = ((first + f) * HOP - inStart).toInt()
            for (n in 0 until N_FFT) {
                val w = window[n]
                re[n] = input[(start + n) * 2] * w
                im[n] = input[(start + n) * 2 + 1] * w
            }
            fft.transform(re, im)
            for (k in 0 until SPEC) {
                val j = (N_FFT - k) % N_FFT
                // X_L = (Z[k] + conj Z[N-k]) / 2,  X_R = (Z[k] - conj Z[N-k]) / 2i
                specRe[0][f][k] = ((re[k] + re[j]) / 2).toFloat()
                specIm[0][f][k] = ((im[k] - im[j]) / 2).toFloat()
                specRe[1][f][k] = ((im[k] + im[j]) / 2).toFloat()
                specIm[1][f][k] = ((re[j] - re[k]) / 2).toFloat()
            }
            for (ch in 0 until 2) for (k in 0 until BINS) {
                val a = specRe[ch][f][k]; val b = specIm[ch][f][k]
                mag[(ch * FRAMES + f) * BINS + k] = sqrt(a * a + b * b)
            }
        }
        val tm = System.nanoTime()
        val mask = if (separate) masks() else null
        val modelNs = System.nanoTime() - tm

        // Masked spectra back to sound, overlap-added.
        val chunkStart = first * HOP
        ensureAcc(chunkEnd(chunk))
        for (f in 0 until FRAMES) {
            // Each channel's masked spectrum (bins 0..N/2).
            for (ch in 0 until 2) {
                val base = (ch * FRAMES + f) * BINS
                var ext = 1f
                if (mask != null) {
                    var s = 0f
                    for (k in EXT_FROM until BINS) s += mask[base + k]
                    ext = s / (BINS - EXT_FROM)
                }
                val mr = maskedRe[ch]; val mi = maskedIm[ch]
                for (k in 0 until SPEC) {
                    val m = when {
                        mask == null -> 1f
                        k < BINS -> mask[base + k]
                        else -> ext
                    }
                    mr[k] = specRe[ch][f][k] * m
                    mi[k] = specIm[ch][f][k] * m
                }
            }
            // Both channels back in one inverse FFT: Z[k] = L[k] + i·R[k], and Z[N-k] = conj(L[k]) + i·conj(R[k]).
            val lr = maskedRe[0]; val li = maskedIm[0]; val rr = maskedRe[1]; val ri = maskedIm[1]
            for (k in 0 until SPEC) {
                re[k] = (lr[k] - ri[k]).toDouble()
                im[k] = (li[k] + rr[k]).toDouble()
            }
            for (k in 1 until N_FFT / 2) {
                re[N_FFT - k] = (lr[k] + ri[k]).toDouble()
                im[N_FFT - k] = (rr[k] - li[k]).toDouble()
            }
            fft.transform(re, im, inverse = true)
            val at = ((first + f) * HOP - outStart).toInt()
            for (n in 0 until N_FFT) {
                val w = window[n]
                acc[(at + n) * 2] += re[n] * w
                acc[(at + n) * 2 + 1] += im[n] * w
            }
        }
        chunk++
        // Everything before the next chunk's first frame is final.
        val finalUpTo = chunk * FRAMES * HOP
        val ready = (finalUpTo - outStart).toInt()
        val emit = FloatArray(maxOf(0, ready) * 2)
        var n = 0
        for (i in 0 until ready) {
            val padded = outStart + i
            val song = padded - PAD
            if (song < 0 || song >= fed) continue
            emit[n * 2] = (acc[i * 2] / NORM).toFloat()
            emit[n * 2 + 1] = (acc[i * 2 + 1] / NORM).toFloat()
            n++
        }
        // Slide both buffers along.
        System.arraycopy(acc, ready * 2, acc, 0, acc.size - ready * 2)
        java.util.Arrays.fill(acc, acc.size - ready * 2, acc.size, 0.0)
        outStart = finalUpTo
        val keepFrom = (chunk * FRAMES * HOP) - inStart
        val drop = keepFrom.toInt().coerceIn(0, inFrames)
        System.arraycopy(input, drop * 2, input, 0, (inFrames - drop) * 2)
        inFrames -= drop
        inStart += drop
        emitted += n
        onChunk?.invoke(modelNs / 1_000_000, (System.nanoTime() - t0) / 1_000_000)
        return emit.copyOf(n * 2)
    }

    private fun ensureAcc(paddedEnd: Long) {
        val need = ((paddedEnd - outStart) * 2).toInt()
        if (need > acc.size) acc = acc.copyOf(need)
    }

    /** Both networks on this chunk's magnitudes: the vocals' share of each bin. */
    private fun masks(): FloatArray {
        val shape = longArrayOf(2, 1, FRAMES.toLong(), BINS.toLong())
        val v = run(vocals!!, shape)
        val a = run(accompaniment!!, shape)
        val m = FloatArray(v.size)
        for (i in m.indices) {
            val v2 = v[i] * v[i]
            val a2 = a[i] * a[i]
            m[i] = (v2 + EPS / 2) / (v2 + a2 + EPS)
        }
        return m
    }

    private fun run(session: OrtSession, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(env!!, FloatBuffer.wrap(mag), shape).use { x ->
            session.run(mapOf(session.inputNames.first() to x)).use { r ->
                val t = r[0] as OnnxTensor
                val buf = t.floatBuffer
                return FloatArray(buf.remaining()).also { buf.get(it) }
            }
        }
    }

    private fun List<FloatArray>.flatten(): FloatArray {
        val out = FloatArray(sumOf { it.size })
        var at = 0
        for (a in this) { System.arraycopy(a, 0, out, at, a.size); at += a.size }
        return out
    }
}
