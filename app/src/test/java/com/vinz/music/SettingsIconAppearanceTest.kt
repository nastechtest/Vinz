package com.vinz.music

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.vinz.music.ui.screens.NavRow
import com.vinz.music.ui.screens.SliderSetting
import com.vinz.music.ui.screens.settingsIconColor
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.ui.theme.VinzTokens
import com.vinz.music.ui.theme.contrastRatio
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** Settings-section icons stay readable, coloured and untiled in both schemes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h160dp-xxhdpi", application = android.app.Application::class)
class SettingsIconAppearanceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun spectrumIconsMeetMinimumContrastOnLightAndNearBlack() {
        val backgrounds = listOf(Color.White, Color(0xFF09090C))
        backgrounds.forEach { background ->
            VinzTokens.spectrumColors.forEach { (_, hue) ->
                val result = settingsIconColor(hue, background)
                assertTrue("$hue on $background is translucent", result.alpha == 1f)
                assertTrue("$hue on $background contrast=${contrastRatio(result, background)}", contrastRatio(result, background) >= 3f)
            }
        }
    }

    @Test
    fun explicitHueIsForegroundOnlyOnLight() =
        assertRow(false, Color(0xFFFAFAFC), Color(0xFFFF1744))

    @Test
    fun explicitHueIsForegroundOnlyOnDark() =
        assertRow(true, Color(0xFF09090C), Color(0xFFFF1744))

    @Test
    fun defaultHueIsForegroundOnlyOnLight() =
        assertRow(false, Color(0xFFFAFAFC))

    @Test
    fun defaultHueIsForegroundOnlyOnDark() =
        assertRow(true, Color(0xFF09090C))

    @Test
    fun sliderFormatterReceivesRangeStartForNaN() =
        assertSliderValue(Float.NaN, 0)

    @Test
    fun sliderFormatterReceivesRangeEndForOutOfRangeValue() =
        assertSliderValue(99f, 10)

    private fun assertRow(dark: Boolean, background: Color, hue: Color? = null) {
        val image = renderRow(dark, background, hue)
        if (hue == null) {
            assertTrue("default icon absent on $background", image.hasNonBackgroundForeground(background))
        } else {
            assertTrue("explicit hue absent on $background", image.hasSaturatedForeground())
        }
        assertTrue("icon slot paints a tile on $background", image.slotGuttersAre(background))
    }

    private fun renderRow(dark: Boolean, background: Color, hue: Color?): android.graphics.Bitmap {
        compose.setContent {
            SettingsTheme(dark, background) {
                Box(
                    Modifier.width(240.dp).height(76.dp).background(background).testTag("settings-row"),
                ) {
                    NavRow(VinzIcons.Home, "Appearance", "Icons", hue = hue, onClick = {})
                }
            }
        }
        compose.waitForIdle()
        return compose.onNodeWithTag("settings-row").captureToImage().asAndroidBitmap()
    }

    private fun assertSliderValue(value: Float, expected: Int) {
        compose.setContent {
            SettingsTheme(false, Color.White) {
                SliderSetting(
                    title = "Corner scale",
                    value = value,
                    range = 0f..10f,
                    valueText = { formatted ->
                        assertTrue("formatter received $formatted", formatted.isFinite())
                        assertTrue("formatter received $formatted outside 0..10", formatted in 0f..10f)
                        formatted.roundToInt().toString()
                    },
                    onSave = {},
                )
            }
        }
        compose.onNodeWithText(expected.toString()).assertExists()
    }

    @Composable
    private fun SettingsTheme(dark: Boolean, background: Color, content: @Composable () -> Unit) {
        val colors = if (dark) darkColorScheme(
            background = background, surface = background, onBackground = Color.White,
            onSurface = Color.White, onSurfaceVariant = Color(0xFFD0D0D8),
        ) else lightColorScheme(
            background = background, surface = background, onBackground = Color(0xFF101014),
            onSurface = Color(0xFF101014), onSurfaceVariant = Color(0xFF54545C),
        )
        MaterialTheme(colorScheme = colors, content = content)
    }

    private fun android.graphics.Bitmap.hasSaturatedForeground(): Boolean = iconSlotPixels().count { pixel ->
        val spread = maxOf(AndroidColor.red(pixel), AndroidColor.green(pixel), AndroidColor.blue(pixel)) -
            minOf(AndroidColor.red(pixel), AndroidColor.green(pixel), AndroidColor.blue(pixel))
        AndroidColor.alpha(pixel) > 240 && spread > 65
    } >= 6

    private fun android.graphics.Bitmap.hasNonBackgroundForeground(background: Color): Boolean =
        iconSlotPixels().count { !it.near(background.toArgb()) } >= 6

    /** The outermost columns of the fixed 34 dp icon slot stay the row's background, not a coloured tile. */
    private fun android.graphics.Bitmap.slotGuttersAre(background: Color): Boolean {
        val left = (width * (17f / 240f)).toInt()
        val right = (width * (49f / 240f)).toInt()
        val middle = height / 2
        return listOf(left, right).all { x ->
            (-3..3).count { dy -> getPixel(x, middle + dy).near(background.toArgb()) } >= 5
        }
    }

    private fun android.graphics.Bitmap.iconSlotPixels(): Sequence<Int> = sequence {
        val left = (width * (16f / 240f)).toInt()
        val right = (width * (50f / 240f)).toInt()
        for (y in 0 until height) for (x in left..right) yield(getPixel(x, y))
    }

    private fun Int.near(other: Int): Boolean =
        kotlin.math.abs(AndroidColor.red(this) - AndroidColor.red(other)) <= 3 &&
            kotlin.math.abs(AndroidColor.green(this) - AndroidColor.green(other)) <= 3 &&
            kotlin.math.abs(AndroidColor.blue(this) - AndroidColor.blue(other)) <= 3
}
