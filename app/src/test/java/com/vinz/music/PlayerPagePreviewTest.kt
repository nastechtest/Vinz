package com.vinz.music

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.screens.StoredSettings
import com.vinz.music.ui.screens.settings.SettingsPageContent
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.8.5: Settings → Player pins a live small player (its background, cover, fonts, in an offscreen layer). In Performance
 * mode it's a still drawing instead, cheap to open; otherwise the live one as before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2000dp-xhdpi", application = android.app.Application::class)
class PlayerPagePreviewTest {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val container by made
    private fun asVinzComes() = kotlinx.coroutines.runBlocking { container.settings.editNow { it.clear() } }

    @org.junit.Before fun fresh() = asVinzComes()
    @org.junit.After fun close() { asVinzComes(); made.closeAfterTest() }

    private val live = "settings:player:preview:live"
    private val plain = "settings:player:preview:plain"

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun showPage() {
        compose.setContent {
            val nav = Navigator(rememberNavController(), openPlayer = {})
            CompositionLocalProvider(LocalContainer provides container, LocalNavigator provides nav) {
                VinzTheme(AppSettings(), artworkColor = null) { StoredSettings { SettingsPageContent(SettingsPageId.PLAYER, 0.dp, onSearch = {}) } }
            }
        }
        compose.waitForIdle()
    }

    @Test fun theLivePlayerOutsidePerformanceMode() {
        showPage()
        assertEquals(1, count(SettingsTags.PREVIEW))
        assertEquals("the live small player", 1, count(live))
        assertEquals(0, count(plain))
    }

    @Test fun aStillPlayerInPerformanceMode() {
        container.settings.setPerformanceMode(true)
        compose.waitUntil(10_000) { container.settings.stored.value.performanceMode }
        showPage()
        assertEquals(1, count(SettingsTags.PREVIEW))
        assertEquals("the still drawing", 1, count(plain))
        assertEquals("no live player", 0, count(live))
        // The same words beside it: the song's title (a stand-in with nothing playing).
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText(tr(R.string.northern_lights)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }
}
