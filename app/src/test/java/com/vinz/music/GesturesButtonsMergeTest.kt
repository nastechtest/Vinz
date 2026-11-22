package com.vinz.music

import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.ui.screens.settings.GesturesWork
import com.vinz.music.ui.screens.settings.gesturesWorkChoices
import com.vinz.music.ui.screens.settings.gesturesWorkToMerged
import com.vinz.music.ui.screens.settings.gesturesWorkToStored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.4, plan §2.5 on Gestures & buttons: "Gestures anywhere on the player" + "Gestures below the lyrics" → the one row
 * "Gestures work". Two switches are four stored states and the row offers three choices; the player tells all four
 * apart, so the fourth (below the lyrics without anywhere) keeps a face of its own, and is only offered to whoever has
 * it. Every stored state goes to the row and back unchanged: nobody's gestures change by opening the page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GesturesButtonsMergeTest {
    @Test fun allFourStoredStatesGoThereAndBack() {
        val seen = HashSet<GesturesWork>()
        for (everywhere in listOf(false, true)) for (underLyrics in listOf(false, true)) {
            val merged = gesturesWorkToMerged(everywhere, underLyrics)
            assertEquals("$everywhere, $underLyrics", everywhere to underLyrics, gesturesWorkToStored(merged))
            assertTrue("$everywhere, $underLyrics has a face of its own", seen.add(merged))
            // What is stored is always among what the sheet lists (it is the ticked one).
            assertTrue("$merged is listed", merged in gesturesWorkChoices(merged))
        }
        assertEquals(4, seen.size)
        assertEquals(GesturesWork.entries.toSet(), seen)
        assertEquals("each reads differently", 4, GesturesWork.entries.map { it.label }.toSet().size)
    }

    @Test fun eachStateIsTheChoiceThatSaysWhatItDoes() {
        // Neither switch: the cover only (a fresh install).
        assertEquals(GesturesWork.COVER, gesturesWorkToMerged(everywhere = false, underLyrics = false))
        val d = AppSettings()
        assertEquals(GesturesWork.COVER, gesturesWorkToMerged(d.behavior.gesturesEverywhere, d.behavior.gesturesUnderLyrics))
        // "Anywhere" alone never reached below the open lyrics (NowPlaying's coverGestures): it is "Anywhere but the lyrics".
        assertEquals(GesturesWork.NOT_LYRICS, gesturesWorkToMerged(everywhere = true, underLyrics = false))
        // Both: anywhere.
        assertEquals(GesturesWork.ANYWHERE, gesturesWorkToMerged(everywhere = true, underLyrics = true))
        // "Below the lyrics" alone: not around the controls while the lyrics are closed, so none of the three.
        assertEquals(GesturesWork.COVER_AND_LYRICS, gesturesWorkToMerged(everywhere = false, underLyrics = true))
    }

    @Test fun threeChoicesAndTheFourthOnlyForWhoHasIt() {
        val three = listOf(GesturesWork.COVER, GesturesWork.ANYWHERE, GesturesWork.NOT_LYRICS)
        for (now in three) {
            assertEquals(three, gesturesWorkChoices(now))
            assertFalse(GesturesWork.COVER_AND_LYRICS in gesturesWorkChoices(now))
        }
        assertEquals(three + GesturesWork.COVER_AND_LYRICS, gesturesWorkChoices(GesturesWork.COVER_AND_LYRICS))
        // The three as the plan words them ("Only on the cover" since 1.8.7.6, W40: the heading is "On the cover").
        assertEquals(listOf("Only on the cover", "Anywhere", "Anywhere but the lyrics"), three.map { it.label })
    }
}
