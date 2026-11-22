package com.vinz.music.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import com.vinz.music.R
import com.vinz.music.data.canvas.CanvasSource
import com.vinz.music.data.canvas.clipRow
import com.vinz.music.data.canvas.NoClip
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.BehaviorPrefs
import com.vinz.music.data.prefs.MenuAction
import com.vinz.music.data.prefs.MenuGroup
import com.vinz.music.data.prefs.PlayerParts
import com.vinz.music.data.prefs.menuLayout
import com.vinz.music.tr
import com.vinz.music.ui.components.ActionGroup
import com.vinz.music.ui.components.QuickActions
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.6 Canvas in the player's ⋯ menu (plan-canvas 4.6): the "Canvas" switch tells a screen reader whether it is on, as
 * a tile and as a row; "Use:" shows what is in effect; the new rows sit beside it in "Playing".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CanvasMenuTest {
    @get:Rule val compose = createComposeRule()

    private fun isSwitch(on: Boolean) = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        .and(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, if (on) ToggleableState.On else ToggleableState.Off))
        .and(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, tr(if (on) R.string.on_2 else R.string.off)))

    @Test fun theCanvasSwitchSaysItsStateAsATileAndAsARow() {
        var on by mutableStateOf(true)
        var taps = 0
        compose.setContent {
            VinzTheme(AppSettings(), artworkColor = null) {
                Column {
                    QuickActions(listOf(canvasToggleItem(on) { taps++; on = !on })) { it.onClick() }
                    ActionGroup(null, listOf(canvasToggleItem(on) { taps++; on = !on })) { it.onClick() }
                }
            }
        }
        val canvas = hasText(tr(R.string.vinz_canvas))
        val nodes = compose.onAllNodes(canvas, useUnmergedTree = false)
        nodes.assertCountEquals(2)
        nodes[0].assert(isSwitch(true))
        nodes[1].assert(isSwitch(true))
        nodes[0].performClick()
        compose.waitForIdle()
        assertEquals(1, taps)
        nodes[0].assert(isSwitch(false))
        nodes[1].assert(isSwitch(false))
        nodes[1].performClick()
        compose.waitForIdle()
        nodes[1].assert(isSwitch(true))
    }

    @Test fun anItemThatIsNoSwitchStaysAButton() {
        compose.setContent {
            VinzTheme(AppSettings(), artworkColor = null) {
                ActionGroup(null, listOf(com.vinz.music.ui.components.ActionItem(com.vinz.music.ui.theme.VinzIcons.Block, tr(R.string.canvas_not_this_video)) {}))
                { it.onClick() }
            }
        }
        compose.onAllNodes(hasText(tr(R.string.canvas_not_this_video)))[0]
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
    }

    @Test fun useShowsWhatIsInEffect() {
        val coverFirst = AppSettings()
        val videoFirst = AppSettings(behavior = BehaviorPrefs(canvas = coverFirst.behavior.canvas.copy(videoFirst = true)))
        val row = clipRow("s", null, NoClip.NO_VIDEO, null, 1)
        assertEquals(CanvasSource.COVER, canvasUseNow(coverFirst, null))
        assertEquals(CanvasSource.VIDEO, canvasUseNow(videoFirst, row))
        assertEquals(CanvasSource.VIDEO, canvasUseNow(coverFirst, row.copy(prefer = "video")))
        assertEquals(CanvasSource.COVER, canvasUseNow(videoFirst, row.copy(prefer = "cover")))
        // 1.8.7.6: beside Spotify's canvas the setting "video first" doesn't count, the song's own choice does.
        assertEquals(CanvasSource.COVER, canvasUseNow(videoFirst, row, spotifys = true))
        assertEquals(CanvasSource.VIDEO, canvasUseNow(videoFirst, row.copy(prefer = "video"), spotifys = true))
        assertEquals(tr(R.string.animated_cover), canvasSourceName(CanvasSource.COVER))
        assertEquals(tr(R.string.music_video_3), canvasSourceName(CanvasSource.VIDEO))
    }

    @Test fun stillsAskTheNetworkByThePlayersRuleAndShowTimesOtherwise() {
        assertTrue(videoNetworkAllowed(metered = false, videosOnCellular = false, dataSaver = true))
        assertFalse(videoNetworkAllowed(metered = true, videosOnCellular = false, dataSaver = false))
        assertTrue(videoNetworkAllowed(metered = true, videosOnCellular = true, dataSaver = false))
        assertFalse(videoNetworkAllowed(metered = true, videosOnCellular = true, dataSaver = true))
        assertEquals("1:04", partTime(64_000))
        assertEquals("0:09", partTime(9_999))
        assertEquals("12:00", partTime(720_000))
    }

    /** The new rows join "Playing" right after the Canvas switch; a saved layout from before 1.8.6 still reads. */
    @Test fun theNewRowsSitBesideCanvasInPlaying() {
        val all = MenuAction.entries.filter { !it.fromPlayer }
        val playing = PlayerParts().menuLayout(all).groups.first { it.first == MenuGroup.PLAYING }.second
        val at = playing.indexOf(MenuAction.SPOTIFY_CANVAS)
        assertEquals(
            listOf(
                MenuAction.SPOTIFY_CANVAS, MenuAction.ANIMATED_COVER, MenuAction.CANVAS, MenuAction.CANVAS_PART, MenuAction.CANVAS_VIDEO, MenuAction.CANVAS_USE,
                MenuAction.CANVAS_NOT_THIS_VIDEO, MenuAction.SAVE_ANIMATED_COVER,
            ),
            playing.subList(at, at + 8),
        )
        // A layout saved before: the switch hidden under More, under its stored name; the new rows show.
        val old = PlayerParts(menuHidden = setOf(MenuAction.valueOf("ANIMATED_COVER")))
        val l = old.menuLayout(all)
        assertEquals(listOf(MenuAction.ANIMATED_COVER), l.more)
        assertTrue(l.groups.first { it.first == MenuGroup.PLAYING }.second.containsAll(listOf(MenuAction.CANVAS, MenuAction.CANVAS_PART, MenuAction.CANVAS_USE, MenuAction.CANVAS_NOT_THIS_VIDEO)))
        // Owner, 2026-11-13: two switches, each named for what it switches.
        assertEquals(tr(R.string.animated_cover), menuLabel(MenuAction.ANIMATED_COVER))
        assertEquals(tr(R.string.vinz_canvas), menuLabel(MenuAction.CANVAS))
        // Owner, 2026-11-16: the menu says whose loop it is.
        assertEquals("Vinz Canvas", tr(R.string.vinz_canvas))
        // Later that day: three switches, each its own row with its own stored name.
        assertEquals("Spotify canvas", tr(R.string.spotify_canvas))
        assertEquals(tr(R.string.spotify_canvas), menuLabel(MenuAction.SPOTIFY_CANVAS))
        assertEquals(3, setOf(menuLabel(MenuAction.SPOTIFY_CANVAS), menuLabel(MenuAction.ANIMATED_COVER), menuLabel(MenuAction.CANVAS)).size)
    }

    /** "Spotify canvas" is offered for a song Spotify has a canvas for, and stays once switched off (the way back). */
    @Test fun theSpotifySwitchShowsForASongWithSpotifysCanvas() {
        assertTrue(canvasSpotifySwitchShown(asked = true, hasCanvas = true, switchedOff = false))
        assertTrue("switched off: still there", canvasSpotifySwitchShown(asked = true, hasCanvas = false, switchedOff = true))
        assertFalse("Spotify has none for the song", canvasSpotifySwitchShown(asked = true, hasCanvas = false, switchedOff = false))
        assertFalse("Spotify's canvas not asked", canvasSpotifySwitchShown(asked = false, hasCanvas = true, switchedOff = true))
    }

    /** Owner, 2026-11-16: with Spotify canvas selected, Vinz Canvas reads as unchecked, and the other way round. */
    @Test fun onlyTheOneThatPlaysIsCheckedWhenTheCoverIsSpotifys() {
        // Both on for the song: the one in use.
        assertEquals(CanvasPair(spotify = true, vinz = false), canvasPairShown(coverOff = false, loopOff = false, now = CanvasSource.COVER))
        assertEquals(CanvasPair(spotify = false, vinz = true), canvasPairShown(coverOff = false, loopOff = false, now = CanvasSource.VIDEO))
        // One switched off for the song: the other, whatever "Use" says.
        assertEquals(CanvasPair(spotify = false, vinz = true), canvasPairShown(coverOff = true, loopOff = false, now = CanvasSource.COVER))
        assertEquals(CanvasPair(spotify = true, vinz = false), canvasPairShown(coverOff = false, loopOff = true, now = CanvasSource.VIDEO))
        // Both off: neither. Never both.
        assertEquals(CanvasPair(spotify = false, vinz = false), canvasPairShown(coverOff = true, loopOff = true, now = CanvasSource.COVER))
        for (c in listOf(true, false)) for (l in listOf(true, false)) for (n in CanvasSource.entries) {
            val p = canvasPairShown(c, l, n)
            assertFalse("never both checked", p.spotify && p.vinz)
        }
    }

    /** The "Animated cover" switch says its own name and state, apart from "Canvas". */
    @Test fun theAnimatedCoverSwitchIsItsOwn() {
        var cover by mutableStateOf(false)
        compose.setContent {
            VinzTheme(AppSettings(), artworkColor = null) {
                ActionGroup(null, listOf(
                    canvasToggleItem(cover, tr(R.string.animated_cover), com.vinz.music.ui.theme.VinzIcons.AnimatedCover) { cover = !cover },
                    canvasToggleItem(true) {},
                )) { it.onClick() }
            }
        }
        val coverRow = compose.onAllNodes(hasText(tr(R.string.animated_cover)))[0]
        coverRow.assert(isSwitch(false))
        compose.onAllNodes(hasText(tr(R.string.vinz_canvas)))[0].assert(isSwitch(true))
        coverRow.performClick()
        compose.waitForIdle()
        coverRow.assert(isSwitch(true))
        compose.onAllNodes(hasText(tr(R.string.vinz_canvas)))[0].assert(isSwitch(true))
    }
}
