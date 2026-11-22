package com.vinz.music

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
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
 * 1.8.5: after "Reset this page" the Undo bar said "Theme → … and 2 more" while the confirm had listed 2 lines: it
 * counted the stored values that changed (Theme is two of them: the mode and pure black), not the lines. It now counts
 * what the confirm listed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h4000dp-xhdpi", application = android.app.Application::class)
class ResetUndoCountTest {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val container by made
    private val prefs get() = container.settings
    private val stored get() = container.settings.stored.value
    private fun asVinzComes() = kotlinx.coroutines.runBlocking { container.settings.editNow { it.clear() } }

    @org.junit.Before fun fresh() = asVinzComes()
    @org.junit.After fun close() { asVinzComes(); made.closeAfterTest() }

    private fun until(what: String, cond: () -> Boolean) {
        compose.waitUntil(10_000) { cond() }
        compose.waitForIdle()
        assertTrue(what, cond())
    }

    @Test fun theUndoBarCountsTheLinesTheConfirmListed() {
        // Three stored look values, two lines: Theme (mode and pure black) and Reduce motion.
        prefs.setThemeMode(ThemeMode.DARK); prefs.setPureBlack(true); prefs.setReduceMotion(true)
        compose.setContent {
            val nav = Navigator(rememberNavController(), openPlayer = {})
            CompositionLocalProvider(LocalContainer provides container, LocalNavigator provides nav) {
                VinzTheme(AppSettings(), artworkColor = null) { StoredSettings { SettingsPageContent(SettingsPageId.THEME_COLORS, 0.dp, onSearch = {}) } }
            }
        }
        until("changed") { stored.themeMode == ThemeMode.DARK && stored.pureBlack && stored.reduceMotion }
        container.looks.clearHistory()
        compose.onNodeWithTag(SettingsTags.PAGE_MORE).performClick()
        compose.onNodeWithTag(SettingsTags.RESET_ITEM).performClick()
        val listed = compose.onAllNodes(hasTestTag(SettingsTags.RESET_CHANGE)).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString { t -> t.text } }
        assertEquals("$listed", 2, listed.size)
        compose.onNodeWithTag(SettingsTags.RESET_CONFIRM).performClick()
        until("reset") { !stored.pureBlack && !stored.reduceMotion && stored.themeMode == AppSettings().themeMode }
        until("an Undo step") { container.looks.steps.value.isNotEmpty() }
        val label = container.looks.steps.value.first().label
        assertTrue("one listed line more than the first, not two: $label", label.endsWith(" and 1 more"))
    }
}
