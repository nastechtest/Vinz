package com.vinz.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.components.LocalFrosted
import com.vinz.music.ui.components.VinzSwitch
import com.vinz.music.ui.components.pane
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.pow

/**
 * 1.8.5: on light glass a switch's off track was white on a near-white frosted card (1.8.4's check, the heavy look).
 * Drawn on a glass card, the off track and its edge now stand apart from the card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h400dp-xhdpi", application = android.app.Application::class)
class SwitchOffTrackRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun luminance(argb: Int): Double {
        fun ch(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** The switch, off, on a glass card on the page: (card, track's fill, darkest of its right edge) as colors. */
    private fun draw(theme: ThemeMode, frosted: Boolean): Triple<Int, Int, Int> {
        compose.setContent {
            VinzTheme(AppSettings(themeMode = theme), artworkColor = null) {
                CompositionLocalProvider(LocalFrosted provides frosted) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(200.dp, 100.dp).pane(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            VinzSwitch(false, null, Modifier.testTag("switch"))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val b = compose.onNodeWithTag("switch").fetchSemanticsNode().boundsInRoot
        val shot: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val px = 2 // xhdpi
        val y = b.center.y.toInt()
        val card = shot.getPixel((b.left - 20 * px).toInt(), y)
        val fill = shot.getPixel((b.right - 8 * px).toInt(), y)
        val edge = ((b.right - 2 * px).toInt() until b.right.toInt()).map { shot.getPixel(it, y) }.minByOrNull { luminance(it) }!!
        return Triple(card, fill, edge)
    }

    @Test fun onLightGlassTheOffTrackStandsApartFromItsCard() {
        val (card, fill, edge) = draw(ThemeMode.LIGHT, frosted = true)
        val f = contrast(card, fill); val e = contrast(card, edge)
        println("light glass: card ${Integer.toHexString(card)} fill ${Integer.toHexString(fill)} ($f) edge ${Integer.toHexString(edge)} ($e)")
        assertTrue("the off track's fill against the card: $f", f >= 1.15)
        assertTrue("the off track's edge against the card: $e", e >= 2.0)
    }

    /** Dark looks and pages off the glass get no ink: the switch draws there exactly as it did. */
    @Test fun darkAndPlainLooksAreUnchanged() {
        for (ink in listOf(androidx.compose.ui.graphics.Color(0xFF1C1B1F), androidx.compose.ui.graphics.Color.White)) {
            org.junit.Assert.assertNull(com.vinz.music.ui.components.switchOffInk(dark = true, frosted = true, ink = ink))
            org.junit.Assert.assertNull(com.vinz.music.ui.components.switchOffInk(dark = true, frosted = false, ink = ink))
            org.junit.Assert.assertNull(com.vinz.music.ui.components.switchOffInk(dark = false, frosted = false, ink = ink))
        }
        org.junit.Assert.assertNotNull(com.vinz.music.ui.components.switchOffInk(dark = false, frosted = true, ink = androidx.compose.ui.graphics.Color.Black))
        // And drawn: on dark glass the off track is the glass's own white, as before (no ink tint over it).
        val (card, fill, _) = draw(ThemeMode.DARK, frosted = true)
        assertTrue("dark glass: the track is lighter than its card, as before", luminance(fill) > luminance(card))
    }
}
