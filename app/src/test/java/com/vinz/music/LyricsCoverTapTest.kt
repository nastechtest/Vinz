package com.vinz.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.ui.player.smallCoverTaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #464: the small cover beside the title while a panel is open. With "Tap the small cover to play or pause" off (the
 * default) a tap closes the lyrics, as before 1.8.5. On, over the lyrics a tap plays or pauses and a long press closes
 * them; TalkBack names the tap "Play" or "Pause" and the long press "Close lyrics". Over the queue or the stats a tap
 * closes them either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LyricsCoverTapTest {
    @get:Rule val compose = createComposeRule()

    private var toggles = 0
    private var closes = 0

    private fun show(tapPlays: Boolean, playing: Boolean = true, lyricsOpen: Boolean = true, otherPanelOpen: Boolean = false) = compose.setContent {
        Box(
            Modifier.size(56.dp).testTag("cover")
                .smallCoverTaps(lyricsOpen, otherPanelOpen, tapPlays, playing, onPlayPause = { toggles++ }, onClose = { closes++ }),
        )
    }

    private fun tap() { compose.onNodeWithTag("cover").performClick(); compose.waitForIdle() }
    private fun longPress() { compose.onNodeWithTag("cover").performTouchInput { longClick() }; compose.waitForIdle() }
    private fun node() = compose.onNodeWithTag("cover").fetchSemanticsNode()

    @Test fun offByDefault() {
        assertFalse(AppSettings().behavior.lyricsCoverPlays)
    }

    // ---- Off: as before 1.8.5.

    @Test fun offATapClosesTheLyrics() {
        show(tapPlays = false)
        tap()
        assertEquals(0, toggles)
        assertEquals(1, closes)
    }

    @Test fun offThereIsNoLongPress() {
        show(tapPlays = false)
        assertFalse(SemanticsActions.OnLongClick in node().config)
    }

    // ---- On.

    @Test fun onATapPlaysOrPauses() {
        show(tapPlays = true)
        tap()
        assertEquals(1, toggles)
        assertEquals(0, closes)
    }

    @Test fun onALongPressClosesTheLyrics() {
        show(tapPlays = true)
        longPress()
        assertEquals(0, toggles)
        assertEquals(1, closes)
    }

    @Test fun onTalkBackNamesWhatTheTapDoes() {
        show(tapPlays = true)
        assertEquals("Pause", node().config[SemanticsActions.OnClick].label)
        assertEquals("Close lyrics", node().config[SemanticsActions.OnLongClick].label)
    }

    @Test fun onPausedItSaysPlay() {
        show(tapPlays = true, playing = false)
        assertEquals("Play", node().config[SemanticsActions.OnClick].label)
    }

    // ---- Either way.

    @Test fun onOverTheQueueOrStatsATapStillCloses() {
        show(tapPlays = true, lyricsOpen = false, otherPanelOpen = true)
        tap()
        assertEquals(0, toggles)
        assertEquals(1, closes)
    }

    @Test fun onOverTheLyricsWithTheQueueUpATapCloses() {
        show(tapPlays = true, lyricsOpen = true, otherPanelOpen = true)
        tap()
        assertEquals(0, toggles)
        assertEquals(1, closes)
    }

    @Test fun noPanelOpenTheSmallCoverTakesNoTap() {
        show(tapPlays = true, lyricsOpen = false, otherPanelOpen = false)
        assertFalse(SemanticsActions.OnClick in node().config)
    }
}
