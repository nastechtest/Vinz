package com.vinz.music

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ColorSource
import com.vinz.music.data.prefs.LightStyle
import com.vinz.music.data.prefs.SurfaceTint
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.theme.frostedBlurDp
import com.vinz.music.ui.theme.frostedWash
import com.vinz.music.ui.theme.glassSource
import com.vinz.music.ui.theme.seedFor
import com.vinz.music.ui.theme.themeColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 1.8.7, the Minimal Look on a Galaxy A16: on Home the rows' text read sharp through the mini player and the tab bar, with
 * only the glass's veil over it. Glass lays a blurred copy of the page ([glassSource], Haze's hazeSource) over the page
 * itself; with Pure black (no Ambient frost) a page with the Default background draws no background of its own, so the
 * copy was see-through around every letter and the sharp page showed through it. The copy now carries the page's
 * background.
 *
 * Robolectric draws in software, where Haze leaves its blur out, so the glass is put together here as Haze does it on a
 * phone: the page, then the copy blurred by Frosted Glass's own radius over it, then the glass's veil. The picture
 * (before above, after below) goes to app/build/glass-previews/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h220dp-xhdpi")
class GlassSourceTest {
    @get:Rule val compose = createComposeRule()

    /** The Minimal Look's colors (Looks.kt, "minimal"): dark, Pure black, White light, a white accent, plain surfaces. */
    private val minimal = AppSettings(
        themeMode = ThemeMode.DARK, pureBlack = true, lightStyle = LightStyle.WHITE, colorSource = ColorSource.CUSTOM,
        accent = 0xFFFFFFFF.toInt(), surfaceTint = SurfaceTint.NEUTRAL,
    )
    private val scheme = themeColors(minimal, true, seedFor(minimal, true, null), null).scheme

    private var behind by mutableStateOf(Color.Black)
    private var fixed by mutableStateOf(true)

    /** Home's rows, white text on the page with no background of its own (Pure black: the Ambient frost steps aside). */
    private fun show() = compose.setContent {
        // What's on screen behind the page (MainActivity's own background); changed to read the copy's see-through.
        Box(Modifier.fillMaxSize().background(behind)) {
            val source = if (fixed) Modifier.glassSource(HazeState(), true, scheme.background) else Modifier.hazeSource(HazeState())
            Column(Modifier.fillMaxSize().then(source).padding(horizontal = 16.dp, vertical = 8.dp)) {
                listOf(
                    "It's So Nice (To See Old Friends)" to "Minnie Riperton · Perfect Angel",
                    "Can't Feel My Face" to "The Weeknd · Beauty Behind the Madness",
                    "Make Love To Me" to "Lake Street Dive",
                ).forEach { (title, artist) ->
                    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).background(Color(0xFF8C8C8C)))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(title, color = Color.White, fontSize = 17.sp, maxLines = 1)
                            Text(artist, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    private fun capture(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)

    /** The copy glass samples, as premultiplied channels (alpha, red, green, blue), read from it over black and over white. */
    private class Copy(val w: Int, val h: Int, val ch: Array<FloatArray>) {
        fun alpha(i: Int) = ch[0][i]
    }

    private var shown = false

    private fun copy(fix: Boolean): Copy {
        fixed = fix
        behind = Color.Black
        if (!shown) { show(); shown = true }
        compose.waitForIdle()
        val black = capture()
        behind = Color.White
        compose.waitForIdle()
        val white = capture()
        val w = black.width; val h = black.height
        val b = IntArray(w * h).also { black.getPixels(it, 0, w, 0, 0, w, h) }
        val wh = IntArray(w * h).also { white.getPixels(it, 0, w, 0, 0, w, h) }
        val ch = Array(4) { FloatArray(w * h) }
        for (i in b.indices) {
            fun c(p: Int, s: Int) = (p shr s and 0xFF) / 255f
            // Over black a pixel is its own (premultiplied) color; over white, that plus white times what lets through.
            val through = ((c(wh[i], 16) - c(b[i], 16)) + (c(wh[i], 8) - c(b[i], 8)) + (c(wh[i], 0) - c(b[i], 0))) / 3f
            ch[0][i] = (1f - through).coerceIn(0f, 1f)
            ch[1][i] = c(b[i], 16); ch[2][i] = c(b[i], 8); ch[3][i] = c(b[i], 0)
        }
        return Copy(w, h, ch)
    }

    @Test fun theCopyGlassSamplesIsOpaque() {
        val after = copy(fix = true)
        assertEquals("see-through pixels in the copy", 0, (0 until after.w * after.h).count { after.alpha(it) < 0.99f })
    }

    /** What the test measures against: the page as it was copied, see-through around its letters. */
    @Test fun thePageAloneLeftTheCopySeeThrough() {
        val before = copy(fix = false)
        val n = before.w * before.h
        assertTrue((0 until n).count { before.alpha(it) < 0.5f } > n / 2)
    }

    @Test fun noTextBehindTheMiniPlayerReadsThroughIt() {
        val before = glass(copy(fix = false))
        val after = glass(copy(fix = true))
        // Sharp page under the veil: a letter's edge is a step of about half the range from one pixel to the next.
        assertTrue("before: ${before.step}", before.step > 0.25f)
        // Blurred: a gentle slope, no letters.
        assertTrue("after: ${after.step}", after.step < 0.05f)
        // Its own title reads on it (white on the bar, 4.5:1 or better wherever it sits).
        assertTrue("contrast ${after.contrast}", after.contrast >= 4.5f)
        // Still black and white: the bar is greys only.
        assertTrue("color in the bar: ${after.chroma}", after.chroma <= 2)
        val shot = Bitmap.createBitmap(before.picture.width, before.picture.height * 2, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(shot).apply {
            drawBitmap(before.picture, 0f, 0f, null)
            drawBitmap(after.picture, 0f, before.picture.height.toFloat(), null)
        }
        val dir = File("build/glass-previews").apply { mkdirs() }
        File(dir, "minimal-mini-player.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class Glass(val picture: Bitmap, val step: Float, val contrast: Float, val chroma: Int)

    /**
     * The screen with the mini player over the second row, as Frosted Glass draws it on a phone: the page on the screen's
     * black, the copy blurred by [frostedBlurDp] over it (Haze's radius is about √3 sigmas; three box passes make the
     * Gaussian), then the veil ([frostedWash] of surfaceContainerHigh).
     */
    private fun glass(copy: Copy): Glass {
        val w = copy.w; val h = copy.h
        val px = 2f // xhdpi
        val blurred = Array(4) { boxBlur(copy.ch[it], w, h, (frostedBlurDp(1f, thin = false) * px * 0.577f).roundToInt()) }
        val wash = scheme.surfaceContainerHigh
        val k = frostedWash(dark = true, frost = 1f, thin = false)
        val left = 12 * px; val right = w - 12 * px; val top = 76 * px; val bottom = 140 * px; val r = 22 * px
        fun inBar(x: Int, y: Int): Boolean {
            if (x < left || x >= right || y < top || y >= bottom) return false
            val cx = x.toFloat().coerceIn(left + r, right - r); val cy = y.toFloat().coerceIn(top + r, bottom - r)
            return (x - cx).pow(2) + (y - cy).pow(2) <= r * r
        }
        val out = IntArray(w * h)
        val lum = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            // The screen: the copy (the page as drawn) on black.
            var rr = copy.ch[1][i]; var gg = copy.ch[2][i]; var bb = copy.ch[3][i]
            if (inBar(x, y)) {
                val a = blurred[0][i]
                rr = blurred[1][i] + (1 - a) * rr; gg = blurred[2][i] + (1 - a) * gg; bb = blurred[3][i] + (1 - a) * bb
                rr = rr * (1 - k) + wash.red * k; gg = gg * (1 - k) + wash.green * k; bb = bb * (1 - k) + wash.blue * k
            }
            out[i] = android.graphics.Color.rgb((rr * 255).roundToInt(), (gg * 255).roundToInt(), (bb * 255).roundToInt())
            lum[i] = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
        }
        // Measured over the bar's body, away from its rounded ends and edges.
        var step = 0f; var brightest = 0f; var chroma = 0
        for (y in (top + 6 * px).toInt() until (bottom - 6 * px).toInt()) for (x in (left + r).toInt() until (right - r).toInt()) {
            val i = y * w + x
            step = max(step, abs(lum[i + 1] - lum[i]))
            brightest = max(brightest, linear(lum[i]))
            val c = out[i]
            chroma = max(chroma, maxOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF) - minOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF))
        }
        val picture = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
        // The mini player's own cover and lines, on top.
        android.graphics.Canvas(picture).apply {
            val cover = Paint().apply { color = 0xFF5A5A5A.toInt() }
            drawRect(left + 10 * px, top + 10 * px, left + 54 * px, top + 54 * px, cover)
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = scheme.onSurface.toArgb(); textSize = 15 * px; isFakeBoldText = true }
            val artist = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = scheme.onSurfaceVariant.toArgb(); textSize = 13 * px }
            drawText("HUMBLE.", left + 66 * px, top + 28 * px, title)
            drawText("Kendrick Lamar", left + 66 * px, top + 47 * px, artist)
        }
        return Glass(picture, step, (1.05f / (brightest + 0.05f)), chroma)
    }

    /** An sRGB value (0-1) as light. */
    private fun linear(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    /** Three box passes of [radius] across and down (a Gaussian of sigma about [radius]), edges held. */
    private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val a = src.copyOf()
        val t = FloatArray(a.size)
        fun pass(s: FloatArray, d: FloatArray, across: Boolean) {
            val major = if (across) w else h
            val minor = if (across) h else w
            val win = radius * 2 + 1
            for (m in 0 until minor) {
                fun at(p: Int) = p.coerceIn(0, major - 1).let { if (across) s[m * w + it] else s[it * w + m] }
                var sum = 0f
                for (o in -radius..radius) sum += at(o)
                for (p in 0 until major) {
                    d[if (across) m * w + p else p * w + m] = sum / win
                    sum += at(p + radius + 1) - at(p - radius)
                }
            }
        }
        repeat(3) { pass(a, t, true); pass(t, a, false) }
        return a
    }
}
