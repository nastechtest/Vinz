package com.vinz.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.components.LocalFrosted
import com.vinz.music.ui.screens.AccountCard
import com.vinz.music.ui.screens.settings.SettingsCardGlass
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsRootContent
import com.vinz.music.ui.screens.settings.SettingsRootEntry
import com.vinz.music.ui.screens.settings.SettingsRootTags
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.theme.Backdrop
import com.vinz.music.ui.theme.BackdropLayer
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
import java.io.File

/**
 * 1.8.4, the new Settings root drawn for real (plan §2.2, §2.3), as [SettingsKitRenderTest] draws the kit: in light,
 * dark and Performance mode at 360 dp, at large text, and with a search typed. The screen is tall enough to hold the
 * whole root, so one picture shows all 17 entries. PNGs go to app/build/settings-root/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h1800dp-xxhdpi")
class SettingsRootRenderTest {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val container by made
    @org.junit.After fun close() = made.closeAfterTest()

    /** [glass]: on a background, as the app's frosted pages are (the Spectrum field stands in for the ambient frost). */
    private fun show(settings: AppSettings, glass: Boolean) {
        compose.setContent {
            val nav = Navigator(rememberNavController(), openPlayer = {})
            CompositionLocalProvider(LocalContainer provides container, LocalNavigator provides nav) {
                VinzTheme(settings, artworkColor = null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        if (glass) BackdropLayer(Backdrop.SPECTRUM, null, 0.35f, motion = false, modifier = Modifier.fillMaxSize())
                        CompositionLocalProvider(LocalFrosted provides glass) {
                            // Settings as a tab: no back button. The account card is the app's own (nobody signed in).
                            SettingsRootContent(
                                0.dp, settings, dark = settings.themeMode != ThemeMode.LIGHT, showBack = false, selected = null,
                                account = { AccountCard() }, onPerformance = {}, onOpen = { _, _ -> }, onRoute = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun save(name: String): Bitmap {
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/settings-root").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return shot
    }

    private fun rows() = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SettingsRootEntry)).fetchSemanticsNodes()

    private fun root(name: String, settings: AppSettings, glass: Boolean) {
        show(settings, glass)
        val shot = save(name)
        assertEquals("360 dp wide at xxhdpi", 1080, shot.width)
        // The whole root is in the picture: all 17 entries, none below the fold.
        val rows = rows()
        assertEquals(SettingsPageId.entries.map { it.key }, rows.map { it.config[SettingsRootEntry] })
        val bottom = shot.height
        assertTrue("an entry is cut off at the foot of the picture", rows.all { it.boundsInRoot.bottom <= bottom })
        // No two entries overlap, and each is at least a finger tall.
        rows.zipWithNext().forEach { (a, b) -> assertTrue("entries overlap", a.boundsInRoot.bottom <= b.boundsInRoot.top + 0.5f) }
        assertTrue(rows.all { it.boundsInRoot.height >= 48 * 3 })
        assertEquals("the cards are glass on a frosted page, plain in Performance mode", glass, compose.onAllNodes(hasTestTag(SettingsTags.GROUP_CARD)).fetchSemanticsNodes().first().config[SettingsCardGlass])
    }

    @Test fun rootLight() = root("root-light", AppSettings(themeMode = ThemeMode.LIGHT), glass = true)
    @Test fun rootDark() = root("root-dark", AppSettings(themeMode = ThemeMode.DARK), glass = true)

    /** Performance mode: no frosted page behind it, the cards a plain surface, the switch on. */
    @Test fun rootPerformance() = root("root-performance", AppSettings(themeMode = ThemeMode.DARK, performanceMode = true), glass = false)

    /** At 150% text nothing is cut off: the rows grow, and a long value wraps. The root is longer, so the screen is too. */
    @Config(qualifiers = "w360dp-h2800dp-xxhdpi")
    @Test fun rootAtLargeText() = root("root-dark-large-text", AppSettings(themeMode = ThemeMode.DARK, textScale = 1.5f), glass = true)

    /** A search typed into the root's field: results in place of the groups, each with where it lives. */
    @Test fun rootSearch() {
        show(AppSettings(themeMode = ThemeMode.DARK), glass = true)
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsRootTags.SEARCH)).fetchSemanticsNodes().size)
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("playback & sound")
        compose.waitForIdle()
        save("root-dark-search")
        assertEquals("the groups make way for the results", 0, rows().size)
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsRootTags.RESULTS)).fetchSemanticsNodes().size)
        // The old page's name finds the two pages that took it over, first: their names head the list, each above the
        // root group it's in (the settings below them say "Playback › …").
        val drawn = ArrayList<String>()
        fun texts(n: androidx.compose.ui.semantics.SemanticsNode) {
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.forEach { drawn += it.text }
            n.children.forEach { texts(it) }
        }
        texts(compose.onNodeWithTag(SettingsRootTags.RESULTS, useUnmergedTree = true).fetchSemanticsNode())
        assertEquals(
            listOf(SettingsPageId.PLAYBACK.title, SettingsPageId.PLAYBACK.group.title, SettingsPageId.SOUND_QUALITY.title, SettingsPageId.SOUND_QUALITY.group.title),
            drawn.take(4),
        )
    }
}
