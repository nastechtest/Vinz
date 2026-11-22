package com.vinz.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.components.LocalFrosted
import com.vinz.music.ui.screens.StoredSettings
import com.vinz.music.ui.screens.settings.SettingsPageContent
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.theme.Backdrop
import com.vinz.music.ui.theme.BackdropLayer
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.8.4, Settings → Player drawn for a look at it: the whole page at 360 dp in light, dark and Performance mode, and
 * scrolled on a phone-sized window with the preview as a strip. PNGs go to app/build/settings-pages/player/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h3400dp-xhdpi")
class PlayerPageRenderTest {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val container by made
    /** The settings' store is one for the whole test run: each test starts from Vinz as it comes, and leaves it so. */
    private fun asVinzComes() = kotlinx.coroutines.runBlocking { container.settings.editNow { it.clear() } }

    @org.junit.Before fun fresh() = asVinzComes()
    @org.junit.After fun close() { asVinzComes(); made.closeAfterTest() }

    private fun show(theme: AppSettings, glass: Boolean) {
        compose.setContent {
            val nav = Navigator(rememberNavController(), openPlayer = {})
            CompositionLocalProvider(LocalContainer provides container, LocalNavigator provides nav) {
                VinzTheme(theme, artworkColor = null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        if (glass) BackdropLayer(Backdrop.SPECTRUM, null, 0.35f, motion = false, modifier = Modifier.fillMaxSize())
                        CompositionLocalProvider(LocalFrosted provides glass) { StoredSettings { SettingsPageContent(SettingsPageId.PLAYER, 0.dp, onSearch = {}) } }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun save(name: String): Bitmap {
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/settings-pages/player").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return shot
    }

    @Test fun light() {
        show(AppSettings(themeMode = ThemeMode.LIGHT), glass = true)
        assertEquals("360 dp wide at xhdpi", 720, save("player-light").width)
    }

    @Test fun dark() {
        show(AppSettings(themeMode = ThemeMode.DARK), glass = true)
        save("player-dark")
    }

    @Test fun performance() {
        container.settings.setPerformanceMode(true)
        compose.waitUntil(10_000) { container.settings.stored.value.performanceMode }
        show(AppSettings(themeMode = ThemeMode.DARK, performanceMode = true), glass = false)
        save("player-performance")
    }

    @Test fun largeText() {
        show(AppSettings(themeMode = ThemeMode.DARK, textScale = 1.5f), glass = true)
        save("player-dark-large-text")
    }

    /** A phone's window, scrolled to Cover: the preview is a strip under the bar. */
    @Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h760dp-xhdpi")
    @Test fun scrolledWithThePreviewAsAStrip() {
        show(AppSettings(themeMode = ThemeMode.DARK), glass = true)
        save("player-dark-top")
        compose.onNodeWithTag(SettingsTags.PAGE_LIST).performScrollToIndex(6)
        compose.waitForIdle()
        save("player-dark-scrolled")
    }
}
