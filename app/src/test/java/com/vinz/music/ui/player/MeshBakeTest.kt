package com.vinz.music.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * 1.8.7: the player's still mesh is made once into a small blurred picture, rather than blurred by the system each time
 * anything on the player redraws. It must look as the blurred mesh did, and be made again only for new colors or a new size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
class MeshBakeTest {
    @get:Rule val compose = createComposeRule()

    /** A mesh like the cover-made ones: a 6 × 6 grid of strong colors smoothly scaled to 32 × 32. */
    private fun mesh(seed: Int, smooth: Boolean = true): ArtworkMesh {
        val rnd = kotlin.random.Random(seed)
        val grid = IntArray(36) { (0xFF shl 24) or (rnd.nextInt(30, 200) shl 16) or (rnd.nextInt(30, 200) shl 8) or rnd.nextInt(30, 200) }
        val small = Bitmap.createBitmap(grid, 6, 6, Bitmap.Config.ARGB_8888)
        return ArtworkMesh(Bitmap.createScaledBitmap(small, 32, 32, smooth).asImageBitmap())
    }

    /** Lets the clock run on (past the 900 ms cross-fade), or until [until] holds (at most 10 s). */
    private fun settle(until: (() -> Boolean)? = null) {
        val end = System.currentTimeMillis() + 10_000
        var n = 0
        do { compose.mainClock.advanceTimeBy(120); Thread.sleep(20); compose.waitForIdle(); n++ }
        while (if (until == null) n < 12 else !until() && System.currentTimeMillis() < end)
    }

    private fun shot(name: String): Bitmap {
        val b = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/meshbake").apply { mkdirs() }.let { File(it, "$name.png").outputStream().use { o -> b.compress(Bitmap.CompressFormat.PNG, 100, o) } }
        return b
    }

    /** An exact full-size Gaussian blur of [src] with Skia's sigma for [radius], edges clamped: what Modifier.blur draws. */
    private fun reference(src: Bitmap, radius: Float): Bitmap {
        val w = src.width; val h = src.height
        val sigma = 0.57735f * radius + 0.5f
        val r = ceil(3 * sigma).toInt()
        val k = DoubleArray(2 * r + 1) { val d = (it - r).toDouble(); exp(-d * d / (2.0 * sigma * sigma)) }.let { a -> val s = a.sum(); DoubleArray(a.size) { a[it] / s } }
        val a = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val ch = Array(3) { c -> DoubleArray(w * h) { ((a[it] shr (16 - 8 * c)) and 0xFF).toDouble() } }
        for (c in 0 until 3) {
            val t = DoubleArray(w * h)
            for (y in 0 until h) for (x in 0 until w) { var s = 0.0; for (i in k.indices) s += k[i] * ch[c][y * w + (x + i - r).coerceIn(0, w - 1)]; t[y * w + x] = s }
            for (y in 0 until h) for (x in 0 until w) { var s = 0.0; for (i in k.indices) s += k[i] * t[(y + i - r).coerceIn(0, h - 1) * w + x]; ch[c][y * w + x] = s }
        }
        val out = IntArray(w * h) { (0xFF shl 24) or (ch[0][it].roundToInt().coerceIn(0, 255) shl 16) or (ch[1][it].roundToInt().coerceIn(0, 255) shl 8) or ch[2][it].roundToInt().coerceIn(0, 255) }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Mean and largest difference per channel (0-255) of [a] and [b]. */
    private fun diff(a: Bitmap, b: Bitmap): Pair<Double, Int> {
        var sum = 0.0; var n = 0; var most = 0
        for (y in 0 until a.height step 3) for (x in 0 until a.width step 3) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            for (sh in intArrayOf(16, 8, 0)) { val d = abs(((p shr sh) and 0xFF) - ((q shr sh) and 0xFF)); sum += d; n++; most = maxOf(most, d) }
        }
        return sum / n to most
    }

    private var baked by mutableStateOf(false)
    private var blurred by mutableStateOf(true)

    private fun looksLikeTheBlurredMesh(seam: Int, intensity: Float, smooth: Boolean = true) {
        val m = mesh(7 + seam, smooth)
        compose.setContent {
            ArtworkMeshBackdrop(m, Modifier.fillMaxSize(), seam = seam.dp, intensity = intensity, liveBlur = blurred, baked = baked)
        }
        // The old way, under the system's blur.
        settle()
        val live = shot("live-$seam")
        // The mesh unblurred: Robolectric draws no RenderEffect, so the blur it would have had is worked out here.
        blurred = false; settle()
        val plain = shot("plain-$seam")
        // What the system's blur draws (Modifier.blur: edges clamped), worked out exactly at full size.
        val want = reference(plain, 32f)
        // The made picture.
        blurred = true; baked = true
        val before = meshBakeCount.get()
        settle { meshBakeCount.get() > before }
        settle()
        val got = shot("baked-$seam")
        assertTrue("the mesh's picture was made", meshBakeCount.get() > before)
        val (mean, most) = diff(want, got)
        // Robolectric's own drawing of the system blur, away from the edges (it lets them fade to the black behind, where
        // a phone clamps them).
        val m3 = 3 * 19
        fun inner(b: Bitmap) = Bitmap.createBitmap(b, m3, m3, b.width - 2 * m3, b.height - 2 * m3)
        val (liveMean, liveMost) = diff(inner(live), inner(got))
        println("seam $seam: baked vs exact blur: mean $mean, largest $most; vs Robolectric's blur inside: mean $liveMean, largest $liveMost; " +
            "plain vs exact blur: ${diff(want, plain)}")
        assertTrue("baked differs from the blurred mesh by $mean per channel on average", mean <= 1.0)
        assertTrue("baked differs from the blurred mesh by up to $most", most <= 4)
        assertTrue("baked differs from the system's blur by $liveMean per channel on average", liveMean <= 1.5)
        assertTrue("baked differs from the system's blur by up to $liveMost", liveMost <= 6)
    }

    @Test fun theBakedMeshLooksLikeTheBlurredOne() = looksLikeTheBlurredMesh(seam = 0, intensity = 1f)

    @Test fun theBakedMeshUnderAFullBleedCoverLooksLikeTheBlurredOne() = looksLikeTheBlurredMesh(seam = 240, intensity = 1.4f)

    /** Hard-edged colors, which the blur has much more to do on: still the same. */
    @Test fun aHardEdgedMeshBlursTheSame() = looksLikeTheBlurredMesh(seam = 0, intensity = 1f, smooth = false)

    @Test fun itIsMadeOnceAndAgainOnlyForNewColorsOrSize() {
        var tick by mutableStateOf(0)
        var m by mutableStateOf(mesh(1))
        var big by mutableStateOf(true)
        val start = meshBakeCount.get()
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                // Whatever else redraws reaches it too: a new modifier each tick recomposes and redraws it.
                val t = tick
                ArtworkMeshBackdrop(m, (if (big) Modifier.fillMaxSize() else Modifier.size(300.dp, 500.dp)).graphicsLayer { alpha = 1f - t * 0f })
            }
        }
        settle { meshBakeCount.get() > start }
        settle()
        assertEquals("made once", start + 1, meshBakeCount.get())
        repeat(12) { tick++; compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
        settle()
        assertEquals("not made again as other things change", start + 1, meshBakeCount.get())
        // The next song's colors: made once (the cross-fade draws the two pictures, the new one taking over after it).
        m = mesh(2)
        settle { meshBakeCount.get() > start + 1 }
        settle()
        repeat(6) { tick++; compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
        assertEquals("made again for new colors", start + 2, meshBakeCount.get())
        // A new size (a turn of the phone).
        big = false
        settle { meshBakeCount.get() > start + 2 }
        settle()
        assertEquals("made again for a new size", start + 3, meshBakeCount.get())
        // Back to the size before: still kept.
        big = true
        settle()
        assertEquals("the last few are kept", start + 3, meshBakeCount.get())
    }

    /** A 1080 × 2340 phone at 2.625 (the A16): 135 × 293 pixels, about 160 KB, rather than a full-screen layer. */
    @Test fun thePictureIsSmall() {
        val img = bakeMeshBlur(mesh(3), 1080, 2340, 0, 32 * 2.625f, 1f)
        assertEquals(135, img.width)
        assertEquals(293, img.height)
        assertEquals(8, meshBakeStep(blurSigma(32 * 2.625f)))
        // The softest blur the slider allows still keeps it about 4 pixels or more where it's made.
        val soft = blurSigma(32 * 0.3f)
        assertTrue(soft / meshBakeStep(soft) >= 4f || meshBakeStep(soft) == 1)
    }
}
