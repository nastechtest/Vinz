package com.vinz.music

import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.BehaviorPrefs
import com.vinz.music.data.prefs.LyricsAlign
import com.vinz.music.data.prefs.LyricsBackground
import com.vinz.music.data.prefs.LyricsLookPrefs
import com.vinz.music.data.prefs.LyricsStyle
import com.vinz.music.data.prefs.RomanizeMode
import com.vinz.music.ui.screens.settings.LS_BEHIND
import com.vinz.music.ui.screens.settings.LyricsEffect
import com.vinz.music.ui.screens.settings.RomanizePick
import com.vinz.music.ui.screens.settings.STRIP_LINES
import com.vinz.music.ui.screens.settings.SavedWith
import com.vinz.music.ui.screens.settings.effectsToMerged
import com.vinz.music.ui.screens.settings.effectsToStored
import com.vinz.music.ui.screens.settings.lyricsOffsetSnap
import com.vinz.music.ui.screens.settings.lyricsOffsetText
import com.vinz.music.ui.screens.settings.lyricsSingChanges
import com.vinz.music.ui.screens.settings.romanizeLabel
import com.vinz.music.ui.screens.settings.romanizeOptions
import com.vinz.music.ui.screens.settings.romanizeToMerged
import com.vinz.music.ui.screens.settings.romanizeToStored
import com.vinz.music.ui.screens.settings.savedWithToMerged
import com.vinz.music.ui.screens.settings.savedWithToStored
import com.vinz.music.ui.screens.settings.stripLineLabel
import com.vinz.music.ui.screens.settings.stripLineToMerged
import com.vinz.music.ui.screens.settings.stripLineToStored
import com.vinz.music.ui.screens.settings.translateLabel
import com.vinz.music.ui.screens.settings.translateToMerged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.4, Lyrics & Sing's merged controls (plan §2.5 and the ledger's "Effects"): one control instead of several, the
 * same stored values underneath. Every combination of the stored values maps to the control and back unchanged, and
 * is one the control offers. Also the words of "Lyrics earlier / later" (#404).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LyricsSingMergeTest {
    private val bools = listOf(false, true)

    @Test fun theLineOnThePlayerKeepsAllFourCombinations() {
        var n = 0
        for (align in LyricsAlign.entries) for (two in bools) {
            val merged = stripLineToMerged(align, two)
            assertEquals(align to two, stripLineToStored(merged))
            assertTrue("offered: $merged", merged in STRIP_LINES)
            n++
        }
        assertEquals(4, n)
        assertEquals("each is offered once", 4, STRIP_LINES.toSet().size)
        assertEquals("each reads apart", 4, STRIP_LINES.map(::stripLineLabel).toSet().size)
        assertEquals(tr(R.string.left), stripLineLabel(stripLineToMerged(LyricsAlign.START, false)))
        assertEquals(tr(R.string.ls_two_lines, tr(R.string.centered)), stripLineLabel(stripLineToMerged(LyricsAlign.CENTER, true)))
    }

    @Test fun romanizeKeepsAllSixCombinations() {
        var n = 0
        for (mode in RomanizeMode.entries) for (all in bools) {
            val merged = romanizeToMerged(mode, all)
            assertEquals(mode to all, romanizeToStored(merged))
            // Offered as things are stored now, so picking what's picked changes nothing.
            assertTrue("offered: $merged", merged in romanizeOptions(all))
            n++
        }
        assertEquals(6, n)
        // Off with "my own language too" stored stays so: Off is offered with it as it is, and reads "Off" either way.
        assertEquals(RomanizeMode.OFF to true, romanizeToStored(romanizeOptions(true).first()))
        assertEquals(RomanizeMode.OFF to false, romanizeToStored(romanizeOptions(false).first()))
        assertEquals(tr(R.string.off), romanizeLabel(RomanizePick(RomanizeMode.OFF, true)))
        assertEquals(tr(R.string.off), romanizeLabel(RomanizePick(RomanizeMode.OFF, false)))
        for (all in bools) {
            val options = romanizeOptions(all)
            assertEquals("Off, then each way without and with", 5, options.size)
            assertEquals("each reads apart", 5, options.map(::romanizeLabel).toSet().size)
        }
        assertEquals(RomanizeMode.UNDER.label, romanizeLabel(RomanizePick(RomanizeMode.UNDER, false)))
    }

    @Test fun effectsKeepAllEightCombinations() {
        var n = 0
        val seen = HashSet<Set<LyricsEffect>>()
        for (glow in bools) for (shadow in bools) for (line in bools) {
            val merged = effectsToMerged(glow, shadow, line)
            assertEquals(Triple(glow, shadow, line), effectsToStored(merged))
            seen += merged
            n++
        }
        assertEquals(8, n)
        assertEquals(8, seen.size)
        // Glow as it's drawn: a style's own glow (not stored yet) shows ticked, and unticking it stores "off".
        for (style in LyricsStyle.entries) for (halo in listOf(null, false, true)) {
            val p = LyricsLookPrefs(style = style, halo = halo)
            assertEquals(p.haloNow, LyricsEffect.GLOW in effectsToMerged(p.haloNow, p.shadow, false))
        }
    }

    @Test fun whenSavingKeepsAllFourCombinations() {
        val seen = HashSet<Set<SavedWith>>()
        for (words in bools) for (translation in bools) {
            val merged = savedWithToMerged(words, translation)
            assertEquals(words to translation, savedWithToStored(merged))
            seen += merged
        }
        assertEquals(4, seen.size)
    }

    /** Already one control before 1.8.4; kept as it was. */
    @Test fun translateIntoAndTheBackgroundAreStillOneControlEach() {
        assertEquals(tr(R.string.off), translateLabel(translateToMerged(false, "de")))
        assertEquals(tr(R.string.german), translateLabel(translateToMerged(true, "de")))
        assertEquals("a language Vinz doesn't list is the phone's", tr(R.string.phone_s_language), translateLabel(translateToMerged(true, "xx")))
        assertEquals("the six backgrounds and Bold card", LyricsBackground.entries.size + 1, LS_BEHIND.toSet().size)
        assertEquals(1, LS_BEHIND.count { it.card })
    }

    @Test fun lyricsEarlierOrLaterInWords() {
        assertEquals(tr(R.string.ls_offset_none), lyricsOffsetText(0))
        assertEquals("120 ms earlier", lyricsOffsetText(120))
        assertEquals("80 ms later", lyricsOffsetText(-80))
        assertEquals("As timed", lyricsOffsetText(0))
        // The slider lands on whole steps of 10 ms, inside the stored range.
        assertEquals(120, lyricsOffsetSnap(118.7f))
        assertEquals(-80, lyricsOffsetSnap(-84f))
        assertEquals(0, lyricsOffsetSnap(3f))
        assertEquals(BehaviorPrefs.LYRICS_OFFSET_MS.last, lyricsOffsetSnap(9_999f))
        assertEquals(BehaviorPrefs.LYRICS_OFFSET_MS.first, lyricsOffsetSnap(-9_999f))
        assertEquals(-500..500, BehaviorPrefs.LYRICS_OFFSET_MS)
    }

    @Test fun resetListsWhatChangedAndNothingElse() {
        val d = AppSettings()
        assertEquals("as Vinz comes: nothing to reset", emptyList<String>(), lyricsSingChanges(d, d))
        val changed = d.copy(behavior = d.behavior.copy(lyricsOffsetMs = 120), lyrics = d.lyrics.copy(sourceButton = !d.lyrics.sourceButton, font = "SYSTEM"))
        val lines = lyricsSingChanges(changed, d)
        assertEquals(lines.toString(), 3, lines.size)
        assertTrue(lines.any { tr(R.string.ls_offset) in it && "120 ms earlier" in it && tr(R.string.ls_offset_none) in it })
        assertTrue(lines.any { tr(R.string.lyrics_font) in it && tr(R.string.app_font) in it })
    }
}
