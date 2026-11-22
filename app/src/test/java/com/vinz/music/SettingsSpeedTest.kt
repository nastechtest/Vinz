package com.vinz.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.components.FitAutoSize
import com.vinz.music.ui.components.LocalFrosted
import com.vinz.music.ui.screens.settings.SettingsPageScaffold
import com.vinz.music.ui.screens.settings.SwitchRow
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.8.4, the A16's Settings A/B (`.claude/release-check/1.8.4/a16-settings-ab/`): the Player page's first fling did
 * twice 1.8.3.1's main-thread work. What was cut, each shown here: the groups below the screen are composed before the
 * page is scrolled (a fling no longer composes a whole group in one frame), the bar's fade as a page starts scrolling
 * doesn't recompose the page, and the tab bar's names, measured again in every frame while the bar slims, are laid out
 * once a frame instead of five times, at the same sizes as before.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class SettingsSpeedTest {
    @get:Rule val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            VinzTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) {
                CompositionLocalProvider(LocalFrosted provides false) {
                    Box(Modifier.width(360.dp).background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private val groups = listOf("Layout", "Progress bar", "Title", "Cover", "On the player", "Modes")

    /** A page of six groups of six rows (about three screens), as the Player page; [composed] counts the times each group is composed anew. */
    @Composable
    private fun Page(composed: IntArray, state: LazyListState? = null) {
        val body: com.vinz.music.ui.screens.settings.SettingsPageScope.() -> Unit = {
            groups.forEachIndexed { g, name ->
                group(name) {
                    androidx.compose.runtime.DisposableEffect(Unit) { composed[g]++; onDispose {} }
                    repeat(6) { i -> SwitchRow("$name option ${i + 1}", i % 2 == 0, value = "On for albums") {} }
                }
            }
        }
        if (state != null) SettingsPageScaffold("Player", 0.dp, showBack = false, listState = state, content = body)
        else SettingsPageScaffold("Player", 0.dp, showBack = false, content = body)
    }

    /** Holds the list's prefetch requests until the test runs them (the idle time between frames, on a phone). */
    private class Idle : androidx.compose.foundation.lazy.layout.PrefetchScheduler {
        val waiting = ArrayDeque<androidx.compose.foundation.lazy.layout.PrefetchRequest>()
        override fun schedulePrefetch(prefetchRequest: androidx.compose.foundation.lazy.layout.PrefetchRequest) { waiting.addLast(prefetchRequest) }
        fun runAll() {
            val plenty = object : androidx.compose.foundation.lazy.layout.PrefetchRequestScope { override fun availableTimeNanos() = Long.MAX_VALUE }
            var guard = 0
            while (waiting.isNotEmpty() && guard++ < 1000) { val r = waiting.first(); if (!with(r) { plenty.execute() }) waiting.removeFirst() }
        }
    }

    @Test fun theGroupsBelowTheScreenAreComposedBeforeThePageIsScrolled() {
        val composed = IntArray(groups.size)
        val idle = Idle()
        show { Page(composed, state = com.vinz.music.ui.screens.settings.rememberSettingsListState(idle)) }
        assertTrue("the first group is on the screen", composed[0] > 0)
        assertTrue("the last group isn't, yet: ${composed.toList()}", composed.last() == 0)
        // The idle time after the page is up.
        compose.runOnIdle { idle.runAll() }
        compose.waitForIdle()
        assertTrue("every group is ready before a fling reaches it: ${composed.toList()}", composed.all { it > 0 })
        // A fling then only places them: nothing is composed again on the way down.
        val before = composed.toList()
        compose.onNodeWithTag(com.vinz.music.ui.screens.settings.SettingsTags.PAGE_LIST).performScrollToIndex(2 + groups.size - 1)
        compose.waitForIdle()
        assertEquals("composed anew while scrolled to", before, composed.toList())
    }
    /** How many times the window's composition has been recomposed so far. */
    private fun recompositions() = androidx.compose.runtime.Recomposer.runningRecomposers.value.sumOf { it.changeCount }

    @Test fun theBarsFadeDoesNotRecomposeThePage() {
        val composed = IntArray(groups.size)
        val state = LazyListState()
        show { Page(composed, state = state) }
        compose.mainClock.autoAdvance = false
        // A little scroll: the bar turns on ("scrolled", once), then fades in over 220 ms.
        compose.runOnIdle { state.dispatchRawDelta(300f) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val before = recompositions()
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val during = recompositions() - before
        // Not once a frame of the fade (about 13 times).
        assertTrue("the page was recomposed $during times while the bar faded in", during <= 2)
    }
    // ---------------------------------------------------------------- the tab bar's names

    /** Counts the times an auto-size is asked for a size. */
    private class Counting(val inner: TextAutoSize) : TextAutoSize {
        var asked = 0
        override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
            asked++
            return with(inner) { getFontSize(constraints, text) }
        }
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    private class Label(val text: String, val width: Dp, val size: TextAutoSize)

    /** Shows each of [labels] in turn as a tab's name: the size each is drawn at. */
    private fun labels(labels: List<Label>): List<TextUnit> {
        var now by androidx.compose.runtime.mutableStateOf(labels.first())
        var drawn = TextUnit.Unspecified
        show {
            val l = now
            androidx.compose.runtime.key(l) {
                BasicText(
                    l.text, Modifier.width(l.width), style = TextStyle(fontSize = 11.sp), maxLines = 1, softWrap = false,
                    overflow = TextOverflow.Ellipsis, autoSize = l.size, onTextLayout = { drawn = it.layoutInput.style.fontSize },
                )
            }
        }
        return labels.map { l ->
            now = l
            compose.waitForIdle()
            drawn
        }
    }

    @Test fun theTabNamesGetTheSameSizesAsBefore() {
        val names = listOf("Home", "Liked songs", "Downloads", "Bibliothèque", "Einstellungen")
        val widths = (20..90 step 3).map { it.dp }
        val cases = names.flatMap { n -> widths.map { w -> n to w } }
        val both = labels(cases.map { (n, w) -> Label(n, w, FitAutoSize(8.sp, 11.sp)) } + cases.map { (n, w) -> Label(n, w, TextAutoSize.StepBased(8.sp, 11.sp)) })
        val fit = both.take(cases.size)
        val step = both.drop(cases.size)
        cases.forEachIndexed { i, (n, w) -> assertEquals("$n at $w", step[i], fit[i]) }
        assertTrue("some names were made smaller", step.any { it != 11.sp })
        assertTrue("some kept their size", step.any { it == 11.sp })
    }

    /** A name that fits is sized with one layout, without StepBased's search (five layouts); one that doesn't still gets the search. */
    @Test fun aNameThatFitsIsSizedWithoutTheSearch() {
        val fits = Counting(TextAutoSize.StepBased(8.sp, 11.sp))
        val tight = Counting(TextAutoSize.StepBased(8.sp, 11.sp))
        val (roomy, squeezed) = labels(listOf(Label("Home", 80.dp, FitAutoSize(8.sp, 11.sp, stepBased = fits)), Label("Einstellungen", 40.dp, FitAutoSize(8.sp, 11.sp, stepBased = tight))))
        assertEquals(11.sp, roomy)
        assertEquals("the search wasn't run for a name that fits", 0, fits.asked)
        assertTrue("a name that doesn't fit is made smaller", squeezed.value < 11f)
        assertTrue("by the search", tight.asked > 0)
    }

    @Test fun theSearchsArithmeticIsStepBasedsWhenEverythingFits() {
        // 8 to 11 sp at 2.8125 px a dp (the A16): every mid StepBased tries, then its last step up to the largest size.
        val d = 2.8125f
        val (largest, answer) = FitAutoSize.allFitting(8 * d, 11 * d, 0.25f * d)
        assertEquals(11 * d, answer, 0.0001f)
        assertEquals(11 * d, largest, 0.0001f)
    }
}