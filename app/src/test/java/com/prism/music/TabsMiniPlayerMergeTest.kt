package com.vinz.music

import com.vinz.music.ui.screens.settings.MiniButton
import com.vinz.music.ui.screens.settings.StatusBarChoice
import com.vinz.music.ui.screens.settings.miniButtonsToMerged
import com.vinz.music.ui.screens.settings.miniButtonsToStored
import com.vinz.music.ui.screens.settings.statusBarToMerged
import com.vinz.music.ui.screens.settings.statusBarToStored
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.4, plan §2.5 on Tabs & mini player: the status bar's two switches are one choice, the mini player's three button
 * switches are show chips. Every combination of the stored values maps to the control and back unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TabsMiniPlayerMergeTest {
    private val bools = listOf(false, true)

    @Test fun everyStatusBarCombinationRoundTrips() {
        var combinations = 0
        for (hide in bools) for (hidePlayer in bools) {
            val merged = statusBarToMerged(hide, hidePlayer)
            // Showing the page (the choice it already shows) stores what was stored: both-on stays both-on.
            assertEquals("hide=$hide, onThePlayer=$hidePlayer", hide to hidePlayer, statusBarToStored(merged, hidePlayer))
            combinations++
        }
        assertEquals(4, combinations)
        assertEquals(StatusBarChoice.SHOWN, statusBarToMerged(false, false))
        assertEquals(StatusBarChoice.HIDDEN_ON_THE_PLAYER, statusBarToMerged(false, true))
        // Hidden everywhere covers the player too, whatever its own switch says.
        assertEquals(StatusBarChoice.HIDDEN_EVERYWHERE, statusBarToMerged(true, false))
        assertEquals(StatusBarChoice.HIDDEN_EVERYWHERE, statusBarToMerged(true, true))
    }

    /** A pick stores what reads back as that pick, from every stored state. */
    @Test fun everyStatusBarPickReadsBackAsItself() {
        for (choice in StatusBarChoice.entries) for (hidePlayerNow in bools) {
            val (hide, hidePlayer) = statusBarToStored(choice, hidePlayerNow)
            assertEquals("$choice from onThePlayer=$hidePlayerNow", choice, statusBarToMerged(hide, hidePlayer))
        }
        // Shown really shows it: neither switch is left on underneath.
        assertEquals(false to false, statusBarToStored(StatusBarChoice.SHOWN, true))
        assertEquals(3, StatusBarChoice.entries.map { it.label }.toSet().size)
    }

    @Test fun everyMiniPlayerButtonCombinationRoundTrips() {
        var combinations = 0
        val seen = HashSet<Set<MiniButton>>()
        for (previous in bools) for (playPause in bools) for (next in bools) {
            val merged = miniButtonsToMerged(previous, playPause, next)
            assertEquals("$previous, $playPause, $next", Triple(previous, playPause, next), miniButtonsToStored(merged))
            seen += merged
            combinations++
        }
        assertEquals(8, combinations)
        assertEquals("each combination is its own set of chips", 8, seen.size)
        assertEquals(setOf(MiniButton.PLAY_PAUSE, MiniButton.NEXT), miniButtonsToMerged(previous = false, playPause = true, next = true))
        assertEquals(listOf("Previous", "Play/pause", "Next"), MiniButton.entries.map { it.label })
    }
}
