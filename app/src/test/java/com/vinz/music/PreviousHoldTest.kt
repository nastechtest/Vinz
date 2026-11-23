package com.vinz.music

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.vinz.music.ui.player.PreviousGlyph
import com.vinz.music.ui.theme.VinzIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #481: a tap on the player's Previous does what it did (back to the song's start, or the song before near its start);
 * holding it always goes to the song before (the player's own previous item, so a shuffled queue's order). With
 * "Hold to seek" set, holding seeks as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PreviousHoldTest {
    @get:Rule val compose = createComposeRule()

    private var taps = 0
    private var songs = 0
    private var seeks = 0

    private fun show(holdSeek: Boolean) = compose.setContent {
        PreviousGlyph(
            VinzIcons.Previous, 44.dp, 1f, enabled = true,
            holdSeek = if (holdSeek) ({ seeks++ }) else null,
            onPrevious = { taps++ }, onPreviousSong = { songs++ },
            modifier = Modifier.testTag("prev"),
        )
    }

    @Test fun tapKeepsTheOldPrevious() {
        show(holdSeek = false)
        compose.onNodeWithTag("prev").performClick()
        compose.waitForIdle()
        assertEquals(1, taps)
        assertEquals(0, songs)
    }

    @Test fun holdGoesToThePreviousSong() {
        show(holdSeek = false)
        compose.onNodeWithTag("prev").performTouchInput { longClick(center, durationMillis = 900) }
        compose.waitForIdle()
        assertEquals(0, taps)
        assertEquals(1, songs)
    }

    @Test fun talkBackOffersThePreviousSong() {
        show(holdSeek = false)
        val node = compose.onNodeWithTag("prev").fetchSemanticsNode()
        assertEquals("Previous song", node.config[SemanticsActions.OnLongClick].label)
    }

    @Test fun holdToSeekStillSeeks() {
        show(holdSeek = true)
        compose.onNodeWithTag("prev").performTouchInput { longClick(center, durationMillis = 900) }
        compose.waitForIdle()
        assertEquals(0, songs)
        assertTrue(seeks > 0)
        assertNull(compose.onNodeWithTag("prev").fetchSemanticsNode().config.getOrNull(SemanticsActions.OnLongClick))
    }
}
