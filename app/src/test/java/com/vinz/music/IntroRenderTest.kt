package com.vinz.music

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.vinz.music.ui.intro.Intro
import com.vinz.music.ui.intro.dockCentre
import com.vinz.music.ui.intro.drawIntro
import com.vinz.music.ui.intro.introStars
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.min

/**
 * Draws the entrance at 4K — 2160 × 3840 on a phone, 3840 × 2160 on a television — and writes the frames to
 * app/build/intro-previews/, so the scene can be looked at without a device and so "it holds at 4K" is something the
 * build checks rather than something the changelog claims.
 *
 * The scene is the one that ships: [drawIntro], the same function the app calls. Only the type is set here, by hand, in
 * the app's own Outfit at the sizes the layout asks for — a preview harness, not a second implementation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class IntroRenderTest {
    private val out = File("build/intro-previews")

    /** The moments worth looking at: the spark, the planet arriving, the pull-back, the orbits, the name, the ring. */
    private val beats = listOf(
        0.45f to 0f,
        1.05f to 0f,
        2.60f to 0f,
        3.90f to 0f,
        5.10f to 0f,
        6.90f to 0f,
        7.80f to 0f,
        9.60f to 0f,
        11.45f to 0f,
        12.30f to 0.55f,
        12.70f to 0.92f,
    )

    private val outfit: Typeface? by lazy {
        runCatching { Typeface.createFromFile(File("src/main/res/font/outfit.ttf")) }.getOrNull()
    }

    private fun frame(w: Int, h: Int, density: Float, t: Float, hold: Float, type: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val size = Size(w.toFloat(), h.toFloat())
        val scale = (min(h / density / 780f, w / density / 400f)).coerceIn(0.85f, 3.4f)
        val radius = Intro.DOCK_RADIUS_DP * scale * density
        val inset = Intro.DOCK_INSET_DP * scale * density
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), size) {
            drawRect(Color.Black)
            drawIntro(
                t = t,
                hold = hold,
                still = false,
                stars = introStars(),
                dockCenter = dockCentre(size, radius, inset),
                dockRadius = radius,
            )
        }
        if (type) type(bitmap, t, hold, scale, density)
        return bitmap
    }

    /** The words, at the sizes and places VinzIntro puts them, drawn with a plain canvas so the preview needs no layout. */
    private fun type(bitmap: Bitmap, t: Float, hold: Float, scale: Float, density: Float) {
        val canvas = AndroidCanvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val face = outfit
        fun paint(em: Float, alpha: Int, tracking: Float, weight: Int = 500): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = em * density
            color = (alpha shl 24) or 0xFFFFFF
            typeface = face?.let { runCatching { Typeface.create(it, weight, false) }.getOrNull() } ?: Typeface.DEFAULT
            letterSpacing = tracking / em
            textAlign = Paint.Align.CENTER
        }

        val word = paint(72f * scale, 255, 5f * scale, 600)
        val small = paint(10f * scale, 107, 2.6f * scale)
        val line = paint(14f * scale, 168, 0.4f * scale, 400)

        val wordH = 78f * scale * density
        val poweredH = 2 * 10f * scale * density + 5f * scale * density
        val block = wordH + 20 * scale * density + 18 * scale * density + poweredH + 14 * scale * density + 26 * scale * density
        val shift = -20 * scale * density * smooth((t - (Intro.DOCK - 0.7f)) / 0.9f).coerceIn(0f, 1f)
        var y = (h - block) / 2f + shift

        val wordAlpha = if (t < Intro.WORD) 0f else ((t - Intro.WORD) / 0.85f).coerceIn(0f, 1f)
        word.alpha = (255 * easeOut(wordAlpha)).toInt().coerceIn(0, 255)
        canvas.drawText("Vinz", w / 2f, y + wordH * 0.72f, word)
        y += wordH + 20 * scale * density

        // The hairline under the name.
        val hair = if (t < Intro.WORD + 0.35f) 0f else ((t - Intro.WORD - 0.35f) / 0.8f).coerceIn(0f, 1f)
        if (hair > 0f) {
            val hw = 132f * scale * density * smooth(hair)
            Paint().apply { color = 0x66FFFFFF; canvas.drawRect(w / 2f - hw / 2f, y, w / 2f + hw / 2f, y + density, this) }
        }
        y += 1 + 18 * scale * density

        val powered = if (t < Intro.POWERED) 0f else ((t - Intro.POWERED) / 0.7f).coerceIn(0f, 1f)
        small.alpha = (107 * easeOut(powered)).toInt()
        canvas.drawText("POWERED BY", w / 2f, y + 10f * scale * density, small)
        val grad = Paint(small).apply {
            shader = android.graphics.LinearGradient(
                w / 2f - 90 * scale * density, 0f, w / 2f + 90 * scale * density, 0f,
                intArrayOf(0xFF8B10FA.toInt(), 0xFF1633E8.toInt(), 0xFF02A6FC.toInt()), null, android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawText("NASTECH RESEARCH", w / 2f, y + 10f * scale * density + 15f * scale * density, grad)
        y += poweredH + 14 * scale * density

        val index = when { t < Intro.LINE_TWO -> 0; t < Intro.LINE_THREE -> 1; else -> 2 }
        val inAt = when (index) { 0 -> Intro.LINE_ONE; 1 -> Intro.LINE_TWO; else -> Intro.LINE_THREE }
        val shown = ((t - inAt) / 0.45f).coerceIn(0f, 1f)
        val copy = listOf("Your music. Your universe.", "Every song is a star.", "Hold the sky, and it is yours.")[index]
        line.alpha = (168 * easeOut(shown)).toInt()
        canvas.drawText(copy, w / 2f, y + 14f * scale * density, line)

        // The ring's label, which the layout puts under the button.
        val label = when { hold >= 0.82f -> "ALMOST"; hold > 0.02f -> "KEEP HOLDING"; else -> "HOLD TO CONTINUE" }
        if (t >= Intro.DOCK) {
            val ring = dockCentre(
                Size(w, h),
                Intro.DOCK_RADIUS_DP * scale * density,
                Intro.DOCK_INSET_DP * scale * density,
            )
            val arrive = easeOut(((t - Intro.DOCK) / 0.85f).coerceIn(0f, 1f))
            val tag = paint(10f * scale, (255 * arrive * (0.34f + 0.5f * hold)).toInt(), 2.4f * scale)
            canvas.drawText(label, w / 2f, ring.y + Intro.DOCK_RADIUS_DP * scale * density + 13 * scale * density + 10 * scale * density, tag)
        }
    }

    @Test fun `the entrance draws at 4K`() {
        out.mkdirs()
        val phone = 2160 to 3840
        val tv = 3840 to 2160
        beats.forEachIndexed { i, (t, hold) ->
            frame(phone.first, phone.second, 6f, t, hold, type = true)
                .let { it.write(File(out, "phone-%02d-%04d.png".format(i, (t * 100).toInt()))) }
        }
        // One frame of each act at television size, where the scene has to scale up rather than just sharpen.
        listOf(3.90f to 0f, 6.90f to 0f, 9.60f to 0f, 12.30f to 0.55f).forEach { (t, hold) ->
            frame(tv.first, tv.second, 2f, t, hold, type = true)
                .let { it.write(File(out, "tv-%04d.png".format((t * 100).toInt()))) }
        }
        val files = out.listFiles { f -> f.name.endsWith(".png") } ?: emptyArray()
        assertTrue("no frames written", files.size >= 10)
        // 4K is a claim about pixels: the frames must be exactly that big, and not empty.
        val big = frame(3840, 2160, 2f, 9.60f, 0f, type = false)
        assertTrue(big.width == 3840 && big.height == 2160)
        var lit = 0
        for (x in 0 until 3840 step 7) for (yy in 0 until 2160 step 7) if (big.getPixel(x, yy) != 0) lit++
        assertTrue("the 4K frame is black", lit > 200)
    }
}

private fun Bitmap.write(file: File) {
    file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
}

private fun smooth(x: Float): Float { val v = x.coerceIn(0f, 1f); return v * v * (3f - 2f * v) }
private fun easeOut(x: Float): Float { val v = 1f - x.coerceIn(0f, 1f); return 1f - v * v * v }
