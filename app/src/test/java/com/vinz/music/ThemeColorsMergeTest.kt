package com.vinz.music

import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.GlassKind
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.screens.LookChoices
import com.vinz.music.ui.screens.settings.GlassAlso
import com.vinz.music.ui.screens.settings.ThemeChoice
import com.vinz.music.ui.screens.settings.glassAlsoToMerged
import com.vinz.music.ui.screens.settings.glassAlsoToStored
import com.vinz.music.ui.screens.settings.themeToMerged
import com.vinz.music.ui.screens.settings.themeToStored
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.8.4, Theme & colors' merges (plan §2.5): one control over the values Vinz already stores. Every combination that
 * could be stored before maps to the control and back unchanged, and can still be reached from the control.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class ThemeColorsMergeTest {
    private val themes = ThemeMode.entries.flatMap { m -> listOf(false, true).map { m to it } }

    /** Theme chips + Pure black → Theme: System / Light / Dark / Black. 3 × 2 = 6 stored combinations. */
    @Test fun everyStoredThemeComesBackUnchanged() {
        assertEquals(6, themes.size)
        for (was in themes) assertEquals("$was", was, themeToStored(themeToMerged(was.first, was.second), was))
        // What each shows as: pure black is Black only with Dark; under System and Light it is kept, unseen.
        assertEquals(ThemeChoice.BLACK, themeToMerged(ThemeMode.DARK, true))
        assertEquals(ThemeChoice.DARK, themeToMerged(ThemeMode.DARK, false))
        assertEquals(ThemeChoice.LIGHT, themeToMerged(ThemeMode.LIGHT, true))
        assertEquals(ThemeChoice.LIGHT, themeToMerged(ThemeMode.LIGHT, false))
        assertEquals(ThemeChoice.SYSTEM, themeToMerged(ThemeMode.SYSTEM, true))
        assertEquals(ThemeChoice.SYSTEM, themeToMerged(ThemeMode.SYSTEM, false))
        assertEquals(listOf("System", "Light", "Dark", "Black"), ThemeChoice.entries.map { it.label })
    }

    @Test fun aPickStoresWhatItSays() {
        for (was in themes) {
            assertEquals(ThemeMode.DARK to true, themeToStored(ThemeChoice.BLACK, was))
            assertEquals(ThemeMode.DARK to false, themeToStored(ThemeChoice.DARK, was))
            // System and Light don't touch pure black.
            assertEquals(ThemeMode.LIGHT to was.second, themeToStored(ThemeChoice.LIGHT, was))
            assertEquals(ThemeMode.SYSTEM to was.second, themeToStored(ThemeChoice.SYSTEM, was))
            // The control shows what was picked.
            for (c in ThemeChoice.entries) assertEquals(c, themeToStored(c, was).let { themeToMerged(it.first, it.second) })
        }
    }

    /** Nothing that could be set with the chips and the switch is out of reach of the one control. */
    @Test fun everyStoredThemeCanStillBeReached() {
        val reached = HashSet<Pair<ThemeMode, Boolean>>()
        var edge = setOf(AppSettings().let { it.themeMode to it.pureBlack })
        while (edge.isNotEmpty()) {
            reached += edge
            edge = edge.flatMap { was -> ThemeChoice.entries.map { themeToStored(it, was) } }.toSet() - reached
        }
        assertEquals(themes.toSet(), reached)
    }

    /** Glass on more of the screen + Frosted pages → Glass also on. 2 × 2 = 4 stored combinations. */
    @Test fun everyStoredGlassAlsoComesBackUnchanged() {
        var n = 0
        for (more in listOf(false, true)) for (pages in listOf(false, true)) {
            assertEquals("$more, $pages", more to pages, glassAlsoToStored(glassAlsoToMerged(more, pages)))
            n++
        }
        assertEquals(4, n)
        assertEquals(setOf(GlassAlso.MORE), glassAlsoToMerged(more = true, frostedPages = false))
        assertEquals(setOf(GlassAlso.PAGES), glassAlsoToMerged(more = false, frostedPages = true))
        assertEquals(emptySet<GlassAlso>(), glassAlsoToMerged(more = false, frostedPages = false))
        // And every set of chips is a stored combination.
        for (on in listOf(emptySet(), setOf(GlassAlso.MORE), setOf(GlassAlso.PAGES), GlassAlso.entries.toSet())) {
            assertEquals(on, glassAlsoToStored(on).let { glassAlsoToMerged(it.first, it.second) })
        }
    }

    /**
     * Glass (one choice since 1.8.0, now in a pick sheet): liquid × kind × frosted = 8 stored combinations; writing the
     * choice a combination shows as leaves it as it was.
     */
    @Test fun everyStoredGlassComesBackUnchanged() {
        var n = 0
        for (liquid in listOf(false, true)) for (kind in GlassKind.entries) for (frosted in listOf(false, true)) {
            val v = LookChoices.glassValues(LookChoices.glass(AppSettings(liquidGlass = liquid, glassKind = kind, frostedGlass = frosted)))
            assertEquals("$liquid, $kind, $frosted", Triple(liquid, kind, frosted), Triple(v.liquid, v.kind ?: kind, v.frosted ?: frosted))
            n++
        }
        assertEquals(2 * GlassKind.entries.size * 2, n)
    }
}
