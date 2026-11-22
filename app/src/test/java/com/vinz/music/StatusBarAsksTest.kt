package com.vinz.music

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.theme.VinzTheme
import com.vinz.music.ui.theme.SolidInk
import com.vinz.music.ui.theme.StatusBarIcons
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.4 review: a page leaving can't undo the status-bar icons the page coming in asked for. Navigation disposes the
 * page going after the one coming has entered: with Home on a solid dark color in the light theme, opening an artist
 * (light icons over its photo) used to end with Home's leaving putting the theme's dark icons over the photo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatusBarAsksTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** True: dark icons (for a light page); false: light icons. */
    private fun lightBar(): Boolean {
        val window = compose.activity.window
        return WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
    }

    /** As the artist page's top bar asks: light icons over its photo, the theme's once its frost has taken over. */
    @Composable
    private fun ArtistAsks(overPhoto: Boolean) = StatusBarIcons(lightBar = !overPhoto)

    @Test
    fun aSolidPageLeavingKeepsTheIconsTheNextPageAskedFor() {
        var home by mutableStateOf(true)
        var artist by mutableStateOf(false)
        var overPhoto by mutableStateOf(true)
        compose.setContent {
            VinzTheme(AppSettings(themeMode = ThemeMode.LIGHT), artworkColor = null) {
                Box {
                    if (home) SolidInk(Color(0xFF000080), bars = true) {}
                    if (artist) ArtistAsks(overPhoto)
                }
            }
        }
        compose.waitForIdle()
        assertEquals("light icons on Home's navy page, in the light theme", false, lightBar())

        artist = true
        compose.waitForIdle()
        assertEquals("light icons over the artist's photo", false, lightBar())

        // The slide is over: Home goes, after the artist page came in.
        home = false
        compose.waitForIdle()
        assertEquals("Home leaving keeps the artist's light icons over its photo", false, lightBar())

        // Scrolled on: the page's frost takes over, the theme's (dark) icons.
        overPhoto = false
        compose.waitForIdle()
        assertEquals(true, lightBar())

        // Back to Home: it comes in over the artist page (its light icons), then the artist page leaves, and they stay.
        home = true
        compose.waitForIdle()
        assertEquals("Home's light icons as it comes back", false, lightBar())
        artist = false
        compose.waitForIdle()
        assertEquals("the artist leaving keeps Home's light icons", false, lightBar())

        // The last asker gone: the theme's icons again.
        home = false
        compose.waitForIdle()
        assertEquals("the light theme's dark icons", true, lightBar())
    }
}
