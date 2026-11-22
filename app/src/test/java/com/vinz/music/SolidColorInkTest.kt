package com.vinz.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.theme.Backdrop
import com.vinz.music.ui.theme.BackdropLayer
import com.vinz.music.ui.theme.LocalIsDark
import com.vinz.music.ui.theme.VinzTheme
import com.vinz.music.ui.theme.VinzViolet
import com.vinz.music.ui.theme.SolidInk
import com.vinz.music.ui.theme.contrastRatio
import com.vinz.music.ui.theme.schemeFromSeed
import com.vinz.music.ui.theme.solidIsDark
import com.vinz.music.ui.theme.solidScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #413 (1.8.4): on a solid color background the page is exactly that color, and what's read on it (text, secondary
 * text, the accent, on the page, its cards and its glass) reads at 4.5:1 or better, whatever Vinz's theme is.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class SolidColorInkTest {
    @get:Rule val compose = createComposeRule()

    private val samples = linkedMapOf(
        "black" to 0xFF000000, "white" to 0xFFFFFFFF, "mid grey" to 0xFF808080, "pure red" to 0xFFFF0000,
        "yellow" to 0xFFFFFF00, "navy" to 0xFF000080, "pastel pink" to 0xFFFFD1DC, "neon green" to 0xFF39FF14,
    ).mapValues { Color(it.value) }

    /** Vinz's own themes the page's colors start from: dark and light, the default accent and a white one. */
    private val themes: Map<String, ColorScheme> = mapOf(
        "dark" to schemeFromSeed(VinzViolet, dark = true, pureBlack = false),
        "light" to schemeFromSeed(VinzViolet, dark = false, pureBlack = false),
        "dark, white accent" to schemeFromSeed(Color.White, dark = true, pureBlack = false),
        "light, pale yellow accent" to schemeFromSeed(Color(0xFFFFE066), dark = false, pureBlack = false),
    )

    private fun check(what: String, ink: Color, on: Color) {
        val ratio = contrastRatio(ink, on)
        assertTrue("$what: ${"%.2f".format(ratio)}:1 (#%06X on #%06X)".format(ink.toArgb() and 0xFFFFFF, on.toArgb() and 0xFFFFFF), ratio >= 4.5f)
    }

    private fun checkPage(name: String, page: Color, scheme: ColorScheme) {
        assertEquals("$name: the page is the color", page, scheme.background)
        assertEquals("$name: and so is the surface", page, scheme.surface)
        val surfaces = mapOf(
            "the page" to page, "low card" to scheme.surfaceContainerLow, "card" to scheme.surfaceContainer,
            "high card" to scheme.surfaceContainerHigh, "highest card" to scheme.surfaceContainerHighest, "variant" to scheme.surfaceVariant,
        )
        for ((where, surface) in surfaces) {
            check("$name, text on $where", scheme.onSurface, surface)
            check("$name, secondary text on $where", scheme.onSurfaceVariant, surface)
            check("$name, accent on $where", scheme.primary, surface)
        }
        check("$name, text on the background", scheme.onBackground, page)
        check("$name, text on an accent button", scheme.onPrimary, scheme.primary)
    }

    @Test
    fun textReadsOnTheEightSampleColors() {
        for ((theme, base) in themes) for ((name, page) in samples) {
            val scheme = solidScheme(base, page)
            checkPage("$name ($theme theme)", page, scheme)
            // The page's glass (Frosted.kt's frostFill: white at 10–14% on a dark page, 55–77% on a light one).
            val glass = if (solidIsDark(page)) listOf(0.10f, 0.14f) else listOf(0.55f, 0.77f)
            for (a in glass) check("$name ($theme theme), text on glass at $a", scheme.onSurface, Color.White.copy(alpha = a).compositeOver(page))
        }
        // Light text on the dark ones, dark text on the light ones.
        assertEquals(listOf("black", "navy"), samples.filterValues(::solidIsDark).keys.toList())
    }

    /** Not only the samples: a grid over every color (18 steps a channel, 5,832 colors), on a dark and a light theme. */
    @Test
    fun textReadsOnAnyColor() {
        val steps = (0..255 step 15).toList()
        for ((theme, base) in themes.entries.take(2)) for (r in steps) for (g in steps) for (b in steps) {
            val page = Color(r, g, b)
            checkPage("#%02X%02X%02X ($theme theme)".format(r, g, b), page, solidScheme(base, page))
        }
    }

    /** The page as Compose draws it: every pixel that color, and the content's colors the ones that read on it. */
    @Test
    fun thePageIsExactlyTheColorAndItsContentReadsOnIt() {
        var value by mutableStateOf("COLOR|#1A2B3C")
        var seen: Triple<Color, Color, Boolean>? = null
        compose.setContent {
            // A light theme under a dark color, then a dark... the theme stays light: the color decides.
            VinzTheme(AppSettings(themeMode = ThemeMode.LIGHT), artworkColor = null) {
                val (style, detail) = Backdrop.parse(value)
                Box(Modifier.fillMaxSize().background(Color.Magenta), contentAlignment = Alignment.Center) {
                    BackdropLayer(style, detail, dim = 0f, motion = false, modifier = Modifier.fillMaxSize())
                    SolidInk(Backdrop.solidColor(detail)!!) {
                        seen = Triple(MaterialTheme.colorScheme.onSurface, LocalContentColor.current, LocalIsDark.current)
                        Box(Modifier.size(80.dp).background(MaterialTheme.colorScheme.onSurface))
                    }
                }
            }
        }
        for ((saved, argb) in listOf("COLOR|#1A2B3C" to 0xFF1A2B3C.toInt(), "COLOR|#FFE066" to 0xFFFFE066.toInt())) {
            value = saved
            compose.waitForIdle()
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            val dir = File("build/backdrop-previews").apply { mkdirs() }
            val file = File(dir, "solid-page-${saved.substringAfter('#')}.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("wrote ${file.absolutePath}")
            // Every pixel outside the square in the middle is the color, to the bit: no wash, no gradient, no grain.
            val cx = bmp.width / 2; val cy = bmp.height / 2; val half = (80 * 2) / 2 + 2
            var other = 0
            for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
                if (kotlin.math.abs(x - cx) <= half && kotlin.math.abs(y - cy) <= half) continue
                if (bmp.getPixel(x, y) != argb) other++
            }
            assertEquals("$saved: pixels that aren't the color", 0, other)
            val (onSurface, content, dark) = seen!!
            assertEquals(saved, solidIsDark(Color(argb)), dark)
            assertEquals(saved, onSurface, content)
            assertTrue("$saved: text ${contrastRatio(onSurface, Color(argb))}:1", contrastRatio(onSurface, Color(argb)) >= 4.5f)
            // The square is drawn in that text color.
            assertEquals(saved, onSurface.toArgb(), bmp.getPixel(cx, cy))
        }
    }
}
