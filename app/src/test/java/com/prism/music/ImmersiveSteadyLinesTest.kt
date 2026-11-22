package com.vinz.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinz.music.data.lyrics.LyricLine
import com.vinz.music.data.lyrics.LyricWord
import com.vinz.music.ui.player.ImmersiveListWords
import com.vinz.music.ui.player.LyricsLook
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Immersive mode's list of every line still jumped at each line change after #399 ([LyricsSteadyLinesTest]): only the
 * sung line had the progress line's row under it, so the old line shrank and the new one grew in the middle of the
 * glide to it. Every line with an end keeps that room now, drawn only under the sung one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class ImmersiveSteadyLinesTest {
    @get:Rule val compose = createComposeRule()

    private val style = TextStyle(fontSize = 28.sp, lineHeight = 34.sp)

    /** Heights of [line] in the immersive list, sung and resting. */
    private fun heights(line: LyricLine): Pair<Int, Int> {
        val clock = mutableLongStateOf(300L)
        compose.setContent {
            Column {
                for (current in listOf(true, false)) Column(Modifier.width(300.dp).testTag(if (current) "sung" else "resting")) {
                    ImmersiveListWords(line, current, clock, style, LyricsLook(), null)
                }
                Column(Modifier.width(300.dp).testTag("plain")) { androidx.compose.material3.Text(line.text, style = style) }
            }
        }
        compose.waitForIdle()
        return compose.onNodeWithTag("sung").fetchSemanticsNode().size.height to compose.onNodeWithTag("resting").fetchSemanticsNode().size.height
    }

    @Test fun aWordSyncedLineIsAsTallSungAsResting() {
        val (sung, resting) = heights(LyricLine(0, "Hold on", 900, listOf(LyricWord(0, 400, "Hold "), LyricWord(400, 900, "on"))))
        assertEquals("the sung line (with its progress line) is as tall as when it rests", resting, sung)
    }

    @Test fun aLineTimedLineIsAsTallSungAsResting() {
        val (sung, resting) = heights(LyricLine(0, "Hold on", 900))
        assertEquals("the sung line (with its progress line) is as tall as when it rests", resting, sung)
    }

    @Test fun aLineWithNoEndHasNoProgressRoom() {
        // Lines timed without an end never show the progress line: they get no room for it, sung or resting.
        val (sung, resting) = heights(LyricLine(0, "Hold on"))
        assertEquals(resting, sung)
        assertEquals(compose.onNodeWithTag("plain").fetchSemanticsNode().size.height, resting)
    }

    @Test fun aBreakIsAsTallNowAsBeforeOrAfter() {
        // An instrumental break (♪) never shows the progress line.
        val (now, other) = heights(LyricLine(0, "", 900, isGap = true))
        assertEquals(other, now)
    }
}
