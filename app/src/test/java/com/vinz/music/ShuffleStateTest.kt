package com.vinz.music

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.vinz.music.ui.player.ShuffleGlyph
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The player's Shuffle button told TalkBack nothing of whether it was on. It now says Off, On, or Smart Shuffle (its
 * third press where the queue allows it), and is marked selected while on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShuffleStateTest {
    @get:Rule val compose = createComposeRule()

    private fun state(shuffle: Boolean, smart: Boolean): Pair<String?, Boolean?> {
        compose.setContent { ShuffleGlyph(shuffle, smart, modifier = Modifier.testTag("shuffle")) {} }
        val c = compose.onNodeWithTag("shuffle").fetchSemanticsNode().config
        return c.getOrElseNullable(SemanticsProperties.StateDescription) { null } to c.getOrElseNullable(SemanticsProperties.Selected) { null }
    }

    @Test fun off() = assertEquals("Off" to false, state(shuffle = false, smart = false))

    @Test fun on() = assertEquals("On" to true, state(shuffle = true, smart = false))

    @Test fun smartShuffle() = assertEquals("Smart Shuffle" to true, state(shuffle = true, smart = true))
}
