package com.vinz.music

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vinz.music.data.prefs.GlassLook
import com.vinz.music.ui.theme.LEGIBLE_BLUR_DP
import com.vinz.music.ui.theme.frostedBlurDp
import com.vinz.music.ui.theme.liquidOptics
import dev.chrisbanes.haze.glass.GlassDefaults
import dev.chrisbanes.haze.glass.GlassOptics
import dev.chrisbanes.haze.glass.GlassStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp

/**
 * #429: with Liquid Glass (Regular and Clear) a song's title scrolling under the tab bar or the mini player could be read
 * through the bar. What a bar's body lets through is two numbers of Haze's: how far it blurs, and `depth`, the share of
 * its picture that is the blur (the rest is the page laid over it sharp). These read both back through Haze's own code,
 * for bars of the sizes Vinz draws.
 */
class Glass429Test {
    private val density = Density(2.625f)

    /** The floating tab bar, the mini player, the round "Listen Together" pill, the side rail, and a tall mini player. */
    private val bars = listOf(Size(300f, 56f), Size(380f, 64f), Size(120f, 34f), Size(72f, 400f), Size(380f, 132f))

    private class Body(val blurDp: Float, val depth: Float)

    /** Haze's own resolver (internal to it, so called by name): the blur and depth it will draw this bar with. */
    private fun body(optics: GlassOptics, bar: Size): Body {
        val kt = Class.forName("dev.chrisbanes.haze.glass.GlassRenderParamsKt")
        val resolve = kt.declaredMethods.single { it.name.startsWith("resolveGlassOptics") }
        // A Size as Compose packs it: width in the high half, height in the low.
        fun bits(dp: Float) = java.lang.Float.floatToRawIntBits(dp * density.density).toLong() and 0xFFFFFFFFL
        val r = resolve.invoke(null, optics, (bits(bar.width) shl 32) or bits(bar.height), density)
        fun f(name: String) = r.javaClass.getMethod(name).invoke(r) as Float
        return Body(f("getBlurRadiusPx") / density.density, f("getDepth"))
    }

    /**
     * How much of a 16 sp title's letter shapes is left behind the glass, 0 to 1 (strokes about 3.5 dp apart): the sharp
     * share passes whole, the blurred share by what a Gaussian of Haze's width leaves (its sigma is 0.577 of the radius,
     * plus half a pixel).
     */
    private fun lettersLeft(b: Body): Double {
        val sigmaDp = 0.57735 * b.blurDp + 0.5 / density.density
        val blurred = exp(-2 * PI * PI * sigmaDp * sigmaDp / (3.5 * 3.5))
        return (1 - b.depth) + b.depth * blurred
    }

    @Test fun noTextShowsThroughABar() {
        for (clear in listOf(false, true)) for (blur in listOf(GlassLook.BLUR_RANGE.start, 1f, GlassLook.BLUR_RANGE.endInclusive)) for (bar in bars) {
            val b = body(liquidOptics(clear, blur), bar)
            val what = "clear=$clear blur=$blur bar=${bar.width}x${bar.height}"
            // None of the page is laid over the blur sharp.
            assertEquals("depth, $what", 1f, b.depth, 0.0001f)
            assertTrue("blur ${b.blurDp} dp, $what", b.blurDp >= LEGIBLE_BLUR_DP - 0.01f)
            assertTrue("letters left ${lettersLeft(b)}, $what", lettersLeft(b) < 0.01)
        }
        // The floor itself, and the flat (docked) tab bar's thin frost under Clear.
        assertTrue(LEGIBLE_BLUR_DP >= 8f)
        assertTrue(frostedBlurDp(1f, thin = true) >= LEGIBLE_BLUR_DP)
    }

    /** What the test is measuring against: Haze's own two styles on a bar do let text through. */
    @Test fun hazesOwnStylesLetTextThrough() {
        val regular = body(GlassDefaults.optics, bars[1])
        assertEquals(0.65f, regular.depth, 0.0001f)
        assertEquals(20f, regular.blurDp, 0.01f)
        assertTrue(lettersLeft(regular) > 0.3)
        val clear = body(hazeClear(), bars[1])
        assertEquals(1.25f, clear.blurDp, 0.01f)
        assertTrue(lettersLeft(clear) > 0.2)
    }

    @Test fun clearStaysClearerThanRegular() {
        for (bar in bars) {
            assertTrue(body(liquidOptics(clear = true, 1f), bar).blurDp < body(liquidOptics(clear = false, 1f), bar).blurDp * 0.5f)
            for (blur in listOf(GlassLook.BLUR_RANGE.start, 2f)) {
                assertTrue(body(liquidOptics(clear = true, blur), bar).blurDp <= body(liquidOptics(clear = false, blur), bar).blurDp)
            }
        }
        assertTrue(frostedBlurDp(1f, thin = true) < frostedBlurDp(1f, thin = false))
    }

    /** Only the body changes: the bending at the rim is Haze's own, for both. A newer Haze with other numbers fails here. */
    @Test fun theRimIsHazesOwn() {
        val clear = hazeClear()
        val ours = liquidOptics(clear = true, 1f)
        assertEquals(clear, ours.copy(blurRadius = clear.blurRadius, depth = clear.depth))
        val regular = GlassDefaults.optics
        assertEquals(regular, liquidOptics(clear = false, 1f).copy(blurRadius = regular.blurRadius, depth = regular.depth))
        // The Blur slider still doubles Regular's.
        assertEquals(40f, body(liquidOptics(clear = false, 2f), bars[1]).blurDp, 0.01f)
        assertEquals(20f, body(liquidOptics(clear = false, 1f), bars[1]).blurDp, 0.01f)
    }

    private fun hazeClear(): GlassOptics {
        val c = GlassStyle.Companion
        return c.javaClass.declaredMethods.single { it.name.startsWith("getClearOptics") }.invoke(c) as GlassOptics
    }
}
