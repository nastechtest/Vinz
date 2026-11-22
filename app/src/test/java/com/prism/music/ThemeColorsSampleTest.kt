package com.vinz.music

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.screens.PageGround
import com.vinz.music.ui.screens.rememberColors
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.8.4, Theme & colors' pinned sample (`ColorSamples.kt`): a solid-colour Home background (#413) is that colour
 * exactly in the sample, as the screens draw it, whatever "theme color over backgrounds" is set to. It was washed with
 * the theme's background, as a design is.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class ThemeColorsSampleTest {
    @get:Rule val compose = createComposeRule()

    private var shown by androidx.compose.runtime.mutableStateOf<AppSettings?>(null)

    private fun ground(settings: AppSettings): Pair<Int, Int> {
        if (shown == null) compose.setContent {
            shown?.let { s -> VinzTheme(s, artworkColor = null) { PageGround(rememberColors(s, true), Modifier.size(120.dp).testTag("ground")) } }
        }
        shown = settings
        compose.waitForIdle()
        val shot = compose.onNodeWithTag("ground").captureToImage().asAndroidBitmap()
        // The top and the foot: the wash is stronger toward the bottom.
        return shot.getPixel(shot.width / 2, 4) to shot.getPixel(shot.width / 2, shot.height - 4)
    }

    @Test fun aSolidColorIsNotWashedInTheSample() {
        val red = 0xFFFF0000.toInt()
        val (top, foot) = ground(AppSettings(themeMode = ThemeMode.DARK, backdrops = mapOf("HOME" to "COLOR|#FF0000"), backdropDim = 0.8f))
        assertEquals("the colour as picked, at the top", Integer.toHexString(red), Integer.toHexString(top))
        assertEquals("and at the foot", Integer.toHexString(red), Integer.toHexString(foot))
    }

    /** A design keeps its wash: only the solid colour is drawn bare. */
    @Test fun aDesignStillTakesTheWash() {
        val bare = ground(AppSettings(themeMode = ThemeMode.DARK, backdrops = mapOf("HOME" to "DUSK"), backdropDim = 0f))
        val washed = ground(AppSettings(themeMode = ThemeMode.DARK, backdrops = mapOf("HOME" to "DUSK"), backdropDim = 0.9f))
        assertNotEquals(bare.second, washed.second)
    }
}
