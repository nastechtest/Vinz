package com.vinz.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ScrubberColor
import com.vinz.music.data.prefs.ScrubberStyle
import com.vinz.music.data.prefs.VolumeControl
import com.vinz.music.ui.player.ThinSlider
import com.vinz.music.ui.player.VolumeSlider
import com.vinz.music.ui.player.barHeights
import com.vinz.music.ui.player.scrubberFill
import com.vinz.music.ui.screens.LookTheme
import com.vinz.music.ui.screens.MiniPlayerScreen
import com.vinz.music.ui.theme.LocalAppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.8.7.6: with "Volume bar like the progress bar" the volume bar takes the progress bar's light too (the split light or
 * the color picked for it), not only its heights; with it off the volume bar is white as before. And the Look preview
 * shows the volume bar whenever the player does (Vinz's own volume keeps it on the player).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class VolumeBarLookTest {
    @get:Rule val compose = createComposeRule()

    /**
     * The volume bar, the progress bar and 1.8.7.5's white volume bar for [s], each at the same level on the
     * player's dark: their pixels, in that order.
     */
    private fun pixels(s: AppSettings): Triple<IntArray, IntArray, IntArray> {
        compose.setContent {
            CompositionLocalProvider(LocalAppSettings provides s) {
                Column(Modifier.background(Color(0xFF0D0D12))) {
                    VolumeSlider(0.6f, {}, {}, null, Modifier.width(300.dp).testTag("volume"))
                    progressLike(s, Modifier.width(300.dp).testTag("progress"))
                    white(Modifier.width(300.dp).testTag("white"))
                }
            }
        }
        compose.waitForIdle()
        fun of(tag: String): IntArray {
            val b: Bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        }
        return Triple(of("volume"), of("progress"), of("white"))
    }

    /**
     * Pixels of [a] and [b] (same size) further apart than [slack] in any channel. A gradient is dithered by where it
     * sits on the screen, so the split light drawn one bar lower differs by a step here and there: [slack] 2 lets that by.
     */
    private fun differ(a: IntArray, b: IntArray, slack: Int = 0): Int {
        if (a.size != b.size) return maxOf(a.size, b.size)
        fun far(x: Int, y: Int) = (0 until 32 step 8).any { sh -> kotlin.math.abs(((x shr sh) and 0xFF) - ((y shr sh) and 0xFF)) > slack }
        return a.indices.count { far(a[it], b[it]) }
    }

    /** The progress bar's own call (PlayerScrubber): its style's heights, the split light, its color over it. */
    @Composable
    private fun progressLike(s: AppSettings, m: Modifier) {
        val (idle, held) = barHeights(s.scrubberStyle, s.scrubberThickness)
        ThinSlider(0.6f, {}, m, idleHeight = idle, activeHeight = held, spectrum = true, fill = scrubberFill(s.scrubberColor, null))
    }

    /** 1.8.7.5's volume bar: white, its own heights. */
    @Composable
    private fun white(m: Modifier) = ThinSlider(0.6f, {}, m, idleHeight = 6.dp, activeHeight = 10.dp)

    @Test fun likeTheProgressBarTakesItsSplitLight() {
        val (volume, progress, plain) = pixels(AppSettings(volumeMatchesProgress = true, scrubberColor = ScrubberColor.SPECTRUM, scrubberStyle = ScrubberStyle.BOLD))
        assertEquals("pixels that differ from the progress bar's", 0, differ(volume, progress, slack = 2))
        assertNotEquals("the split light is not the plain white bar", 0, differ(progress, plain, slack = 2))
    }

    @Test fun likeTheProgressBarTakesItsPickedColor() {
        val (volume, progress, _) = pixels(AppSettings(volumeMatchesProgress = true, scrubberColor = ScrubberColor.ACCENT, scrubberStyle = ScrubberStyle.BOLD))
        assertEquals("pixels that differ from the progress bar's", 0, differ(volume, progress))
    }

    @Test fun offTheVolumeBarStaysWhite() {
        val (volume, _, was) = pixels(AppSettings(volumeMatchesProgress = false, scrubberColor = ScrubberColor.ACCENT))
        assertEquals("pixels that differ from 1.8.7.5's white bar", 0, differ(volume, was))
    }

    private fun previewHasVolume(s: AppSettings): Boolean {
        compose.setContent { LookTheme(s, true) { c -> MiniPlayerScreen(c, null) } }
        compose.waitForIdle()
        return compose.onAllNodes(androidx.compose.ui.test.hasTestTag("previewVolume"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun thePreviewHidesTheVolumeBarByDefault() = assertEquals(false, previewHasVolume(AppSettings()))

    @Test fun thePreviewShowsTheVolumeBarWhenSwitchedOn() = assertEquals(true, previewHasVolume(AppSettings(hideVolumeBar = false)))

    @Test fun thePreviewKeepsTheVolumeBarWithVinzsOwnVolume() {
        // The player keeps it then (shows(VOLUME)), whatever hideVolumeBar says; the preview did not.
        assertEquals(true, previewHasVolume(AppSettings(hideVolumeBar = true, volumeControl = VolumeControl.VINZ)))
    }
}
