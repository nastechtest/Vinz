package com.vinz.music

import com.vinz.music.data.prefs.PlayerButton
import com.vinz.music.ui.player.lyricsShowSing
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #501: with Sing among the player's row buttons, the open lyrics don't show a second Sing button. */
class LyricsSingButtonTest {
    @Test fun singOnTheRowHidesTheLyricsOne() {
        assertFalse(lyricsShowSing(true, listOf(PlayerButton.LYRICS, PlayerButton.SING, PlayerButton.QUEUE)))
    }

    @Test fun withoutItOnTheRowTheLyricsKeepTheirs() {
        assertTrue(lyricsShowSing(true, listOf(PlayerButton.LYRICS, PlayerButton.QUEUE)))
        // The row hidden: nothing on it.
        assertTrue(lyricsShowSing(true, emptyList()))
    }

    @Test fun nothingForWhatCantBeSung() {
        assertFalse(lyricsShowSing(false, listOf(PlayerButton.LYRICS)))
        assertFalse(lyricsShowSing(false, listOf(PlayerButton.SING)))
    }
}
