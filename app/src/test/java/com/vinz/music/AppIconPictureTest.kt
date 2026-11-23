package com.vinz.music

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.vinz.music.ui.screens.settings.AppIconPicture
import com.vinz.music.ui.theme.AppIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** Checks the actual Compose previews against Android's adaptive-icon layers, including Classic's layer-list. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h1400dp-mdpi")
class AppIconPictureTest {
    @get:Rule val compose = createComposeRule()

    private fun expected(icon: AppIcon, includeForeground: Boolean = true): Bitmap {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val layers = if (includeForeground) listOf(icon.background, icon.foreground) else listOf(icon.background)
        layers.forEach { id ->
            val drawable = requireNotNull(context.getDrawable(id))
            drawable.setBounds(0, 0, 108, 108)
            drawable.draw(canvas)
        }
        return bitmap
    }

    private fun difference(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    @Test fun previewsRenderAllChoicesAndMatchLauncherLayers() {
        compose.setContent {
            Column {
                AppIcon.entries.forEach { icon ->
                    Box(Modifier.testTag(icon.name)) { AppIconPicture(icon, 72.dp) }
                }
            }
        }
        for (icon in AppIcon.entries) {
            val actual = compose.onNodeWithTag(icon.name).captureToImage().asAndroidBitmap()
            assertEquals(72, actual.width)
            assertEquals(72, actual.height)
            val reference = expected(icon)
            val background = if (icon == AppIcon.CLASSIC) expected(icon, includeForeground = false) else null
            var error = 0L
            var logoPixels = 0
            var logoError = 0L
            var count = 0
            // 108dp adaptive layer, zoomed to show its central 72dp; avoid the rounded corners.
            for (y in 8 until 64) for (x in 8 until 64) {
                val want = reference.getPixel(x + 18, y + 18)
                val got = actual.getPixel(x, y)
                val delta = difference(want, got)
                error += delta
                count++
                if (background != null && difference(want, background.getPixel(x + 18, y + 18)) > 90) {
                    logoPixels++
                    logoError += delta
                }
            }
            assertTrue("${icon.name} differs from its launcher layers: RGB error ${error.toDouble() / (count * 3)}", error.toDouble() / (count * 3) < 25.0)
            if (icon == AppIcon.CLASSIC) {
                assertTrue("Classic's launcher foreground must contain visible logo pixels", logoPixels > 20)
                assertTrue("Classic logo is missing or incorrectly sized", logoError.toDouble() / (logoPixels * 3) < 30.0)
            }
        }
    }
}
