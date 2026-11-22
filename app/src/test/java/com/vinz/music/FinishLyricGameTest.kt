package com.vinz.music

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vinz.music.data.FinishLyric
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.screens.FinishLyricCardContent
import com.vinz.music.ui.screens.FinishLyricGame
import com.vinz.music.ui.screens.FinishLyricPool
import com.vinz.music.ui.screens.FinishLyricTags
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** "Finish the lyric" (1.8.5, plan §3.8) on screen: a round, its score, and the card hidden when it can't be played. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h900dp-xxhdpi")
class FinishLyricGameTest {
    @get:Rule val compose = createComposeRule()

    private val songs = (1..6).map { n ->
        FinishLyric.SongLyrics("id$n", "Song $n", "Artist $n", null, (1..5).map { l -> "we walk along the road number $n$l toward word${n}x$l" })
    }
    private val seed = 7L
    /** The round the screen builds from [seed] (its first round). */
    private val round = FinishLyric.round(songs, Random(seed))

    private fun game(played: MutableList<Int> = mutableListOf()) = compose.setContent {
        VinzTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) {
            FinishLyricGame(songs, onPlay = { _, i -> played += i }, onClose = {}, seed = seed)
        }
    }

    private fun hasState(s: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, s)

    @Test fun aRightPickScoresAndAWrongOneShowsTheRightEnding() {
        game()
        val q1 = round[0]
        // The line is read with its gap.
        compose.onNodeWithTag(FinishLyricTags.LINE).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(tr(R.string.finish_lyric_line_a11y, q1.prompt))),
        )
        compose.onNodeWithTag(FinishLyricTags.choice(q1.answerIndex)).performClick()
        compose.onNodeWithText(tr(R.string.finish_lyric_right)).assertExists()
        compose.onNodeWithTag(FinishLyricTags.choice(q1.answerIndex)).assert(hasState(tr(R.string.finish_lyric_right_one)))
        // Picked: the other choices can't be picked any more.
        compose.onNodeWithTag(FinishLyricTags.choice((q1.answerIndex + 1) % FinishLyric.CHOICES)).assertIsNotEnabled()
        compose.onNodeWithText(tr(R.string.finish_lyric_next)).performClick()

        val q2 = round[1]
        val wrong = (q2.answerIndex + 1) % FinishLyric.CHOICES
        compose.onNodeWithTag(FinishLyricTags.choice(wrong)).performClick()
        compose.onNodeWithText(tr(R.string.finish_lyric_wrong, q2.answer)).assertExists()
        compose.onNodeWithTag(FinishLyricTags.choice(wrong)).assert(hasState(tr(R.string.finish_lyric_your_pick)))
        compose.onNodeWithTag(FinishLyricTags.choice(q2.answerIndex)).assert(hasState(tr(R.string.finish_lyric_right_one)))
        // The line now reads in full, the right ending in place.
        compose.onNodeWithText(q2.prompt + " " + q2.answer).assertExists()
    }

    @Test fun theRoundEndsWithTheScoreAndItsSongs() {
        val played = mutableListOf<Int>()
        game(played)
        round.forEachIndexed { i, q ->
            // Right on all but the last.
            val pick = if (i == round.lastIndex) (q.answerIndex + 1) % FinishLyric.CHOICES else q.answerIndex
            compose.onNodeWithTag(FinishLyricTags.choice(pick)).performClick()
            compose.onNodeWithText(if (i == round.lastIndex) tr(R.string.finish_lyric_see_score) else tr(R.string.finish_lyric_next)).performClick()
        }
        compose.onNodeWithTag(FinishLyricTags.RESULT).assertExists()
        compose.onNodeWithText(tr(R.string.finish_lyric_score, round.size - 1, round.size)).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion),
        )
        // Every song of the round is listed; tapping one plays the round from it.
        for (q in round) compose.onNodeWithText(q.song.title).assertExists()
        compose.onNodeWithText(round[2].song.title).performClick()
        assertEquals(listOf(2), played)
        // Play again: a new round from the first line.
        compose.onNodeWithText(tr(R.string.finish_lyric_again)).performClick()
        compose.onNodeWithText(tr(R.string.finish_lyric_question, 1, FinishLyric.ROUND)).assertExists()
    }

    private fun card(enabled: Boolean, pool: FinishLyricPool?) = compose.setContent {
        VinzTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) {
            Box { FinishLyricCardContent(enabled, pool) {} }
        }
    }

    @Test fun theCardShowsWithEnoughSongs() {
        card(true, FinishLyricPool(songs, emptyMap()))
        compose.onNodeWithTag(FinishLyricTags.CARD).assertExists()
    }

    @Test fun theCardIsHiddenWhenOff() {
        card(false, FinishLyricPool(songs, emptyMap()))
        compose.onNodeWithTag(FinishLyricTags.CARD).assertDoesNotExist()
    }

    @Test fun theCardIsHiddenWithTooFewSongs() {
        card(true, FinishLyricPool(songs.take(FinishLyric.ROUND - 1), emptyMap()))
        compose.onNodeWithTag(FinishLyricTags.CARD).assertDoesNotExist()
    }

    @Test fun theCardIsHiddenWhileLoading() {
        card(true, null)
        compose.onNodeWithTag(FinishLyricTags.CARD).assertDoesNotExist()
    }
}
