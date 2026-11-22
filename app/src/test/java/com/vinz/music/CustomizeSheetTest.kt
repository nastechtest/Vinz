package com.vinz.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.navigation.compose.rememberNavController
import com.vinz.music.data.prefs.CoverSize
import com.vinz.music.data.prefs.LyricsMotion
import com.vinz.music.data.prefs.MiniPlayerStyle
import com.vinz.music.data.prefs.NavBarSize
import com.vinz.music.data.prefs.SectionStyle
import com.vinz.music.data.prefs.UiDensity
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.components.CustomizeTarget
import com.vinz.music.ui.screens.CustomizeSheet
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.LocalWindowLayout
import com.vinz.music.ui.theme.VinzTheme
import com.vinz.music.ui.theme.WindowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.8.4: "Customize this" (the sheet a tap on an outlined part opens, with "Customize by tapping" on) draws the rows of
 * the rebuilt settings pages: the same composables the pages call, so the merged controls and pick sheets, and nothing
 * of the old pages. For every target the sheet has: its groups hold only the kit's five rows (the old groups carried no
 * kit tags at all), and a setting changes through it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h4000dp-xhdpi")
class CustomizeSheetTest : TextTabsPageHarness(SettingsPageId.TABS_MINI_PLAYER) {
    private fun open(target: CustomizeTarget, key: String? = null) {
        compose.setContent {
            val s by container.settings.stored.collectAsState()
            val nav = Navigator(rememberNavController(), openPlayer = {})
            CompositionLocalProvider(LocalContainer provides container, LocalNavigator provides nav, LocalWindowLayout provides WindowLayout.PHONE) {
                VinzTheme(s, artworkColor = null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { CustomizeSheet(target, key) {} }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun stored(what: String, condition: () -> Boolean) {
        await(what, condition)
        compose.waitForIdle()
    }

    /** The sheet as drawn: groups of kit rows only, each row one of the five types, nothing loose in a card. */
    private fun assertKitRowsOnly(target: CustomizeTarget, title: String = target.title) {
        val drawn = groups()
        assertTrue("$target: the sheet draws kit groups", drawn.isNotEmpty())
        for (g in drawn) {
            assertTrue("$target › ${g.title}: has rows", g.rows.isNotEmpty())
            assertTrue("$target › ${g.title}: outside any row: ${g.loose}", g.loose.isEmpty())
            assertTrue("$target › ${g.title}: only the five rows: ${g.rows.map { typeOf(it) }}", g.rows.all { typeOf(it) in SettingsTags.rowTypes })
        }
        // Its own shape: the title and the way to the whole page.
        assertTrue("$target: its title, $title", compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty())
        assertEquals("$target: one link to its page", 1, compose.onAllNodes(hasText("All ", substring = true) and hasText(" settings", substring = true)).fetchSemanticsNodes().size)
    }

    /** No target is left out: a new one fails here until it has its test below. */
    @Test fun everyTargetIsCovered() {
        assertEquals(listOf("MINI_PLAYER", "TAB_BAR", "PLAYER", "HOME_SECTION"), CustomizeTarget.entries.map { it.name })
    }

    @Test fun theMiniPlayersSheetDrawsItsPagesRows() {
        open(CustomizeTarget.MINI_PLAYER)
        assertKitRowsOnly(CustomizeTarget.MINI_PLAYER)
        assertEquals(listOf("Mini player"), groups().map { it.title })
        // The page's merged control, not the three old switches.
        assertEquals(
            listOf("Mini player › Style", "Mini player › Joined to the tab bar", "Mini player › Mini player buttons", "Mini player › Progress", "Mini player › Mini player progress color"),
            rowNames(),
        )
        chip("Mini player", "Style", "Slim")
        stored("mini_player_style") { stored.miniPlayerStyle == MiniPlayerStyle.SLIM }
        chip("Mini player", "Mini player buttons", "Previous")
        stored("mini_prev_button") { stored.miniPrevButton }
    }

    @Test fun theTabBarsSheetDrawsItsPagesRowsWithoutSwipeBetweenTabs() {
        open(CustomizeTarget.TAB_BAR)
        assertKitRowsOnly(CustomizeTarget.TAB_BAR)
        assertEquals(listOf("Navigation bar"), groups().map { it.title })
        // Its one home is Gestures & buttons.
        assertTrue(rowNames().toString(), rowNames().none { it.contains("Swipe") })
        compose.onAllNodes(hasText("Swipe between tabs")).fetchSemanticsNodes().let { assertTrue("Swipe between tabs is back", it.isEmpty()) }
        chip("Navigation bar", "Height", "Tall")
        stored("nav_bar_size") { stored.navBarSize == NavBarSize.TALL }
        pick("Navigation bar", "Labels", "Never")
        stored("nav_labels") { stored.navLabels == com.vinz.music.data.prefs.NavLabels.NEVER }
    }

    @Test fun thePlayersSheetDrawsThePlayerPagesAndTheLyricsRows() {
        open(CustomizeTarget.PLAYER)
        assertKitRowsOnly(CustomizeTarget.PLAYER)
        assertEquals(listOf("Layout and controls", "Progress bar and times", "Cover", "On the player", "Visualizer", "Lyrics"), groups().map { it.title })
        // The merged controls of the page, not the old switches.
        val names = rowNames()
        assertTrue(names.toString(), "Cover › Reaches the screen's edges" in names && "Cover › Cover animation" in names)
        assertTrue(names.toString(), names.none { it.endsWith("Edge-to-edge cover") || it.endsWith("Cover flies into place") })
        chip("Cover", "Cover size", "Large")
        stored("cover_size") { stored.coverSize == CoverSize.LARGE }
        chip("Progress bar and times", "Right-hand time", "Song length")
        stored("show_track_length") { stored.showTrackLength }
        // The lyrics' rows are Lyrics & Sing's Style group: a pick in its sheet.
        val other = LyricsMotion.entries.first { it != stored.lyricsLook.motionNow }
        pick("Lyrics", "Animation", other.label)
        stored("lyrics_motion") { stored.lyricsLook.motionNow == other }
    }

    @Test fun aHomeSectionsSheetDrawsItsOwnRowsAndThePagesRows() {
        val section = stored.home.layout.first()
        open(CustomizeTarget.HOME_SECTION, section.key)
        // The sheet is named after the section.
        assertKitRowsOnly(CustomizeTarget.HOME_SECTION, section.title)
        assertEquals(listOf("This section", "Pages", "Shapes", "Buttons"), groups().map { it.title })
        assertEquals(listOf("This section › Style", "This section › Hide this section"), rowNames().filter { it.startsWith("This section") })
        assertEquals(SettingsTags.ROW_LINK, typeOf(rowNode("This section", "Hide this section")))
        val other = SectionStyle.entries.first { it != section.style }
        pick("This section", "Style", other.label)
        stored("home_layout: style") { stored.home.layout.first { it.key == section.key }.style == other }
        chip("Pages", "Spacing", "Compact")
        stored("ui_density") { stored.uiDensity == UiDensity.COMPACT }
        tap("Shapes", "Square video covers")
        stored("square_video_covers") { stored.squareVideoCovers != com.vinz.music.data.prefs.AppSettings().squareVideoCovers }
    }

    /**
     * 1.8.4 review: a pin shown as one tile. The sheet names it as Home does (its own name), says "One tile", and a style
     * picked there shows the section in that style (as Customize Home's menu does): it was stored under the tile, which
     * Home kept drawing.
     */
    @Test fun aStylePickedForAPinShownAsOneTileShowsItInThatStyle() {
        val pin = com.vinz.music.data.prefs.HomeSectionConfig(
            com.vinz.music.data.prefs.HomeSections.pinKey("album", "MPREb_test"), "Pinned album", style = SectionStyle.LIST, name = "My album", tile = true,
        )
        container.settings.setHomeLayout(stored.home.layout + pin)
        stored("pinned") { stored.home.layout.any { it.key == pin.key } }
        open(CustomizeTarget.HOME_SECTION, pin.key)
        assertKitRowsOnly(CustomizeTarget.HOME_SECTION, "My album")
        assertEquals(tr(R.string.one_tile), valueOf(rowNode("This section", "Style")))
        val other = SectionStyle.entries.first { it != SectionStyle.LIST }
        pick("This section", "Style", other.label)
        stored("home_layout: style, no tile") { stored.home.layout.first { it.key == pin.key }.let { it.style == other && !it.tile } }
        assertEquals(other.label, valueOf(rowNode("This section", "Style")))

        // The style it had under the tile, picked again, shows it in that style too.
        container.settings.setHomeLayout(stored.home.layout.map { if (it.key == pin.key) it.copy(style = SectionStyle.LIST, tile = true) else it })
        stored("a tile again") { stored.home.layout.first { it.key == pin.key }.tile }
        pick("This section", "Style", SectionStyle.LIST.label)
        stored("home_layout: no tile") { stored.home.layout.first { it.key == pin.key }.let { it.style == SectionStyle.LIST && !it.tile } }
    }

    /** Without a section's key (the target alone) the sheet still draws the pages' rows. */
    @Test fun aHomeSectionsSheetWithoutItsKeyDrawsThePagesRows() {
        open(CustomizeTarget.HOME_SECTION)
        assertKitRowsOnly(CustomizeTarget.HOME_SECTION)
        assertEquals(listOf("Pages", "Shapes", "Buttons"), groups().map { it.title })
    }
}
