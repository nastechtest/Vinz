package com.vinz.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.components.ActionGroup
import com.vinz.music.ui.components.ActionItem
import com.vinz.music.ui.components.ActionSheet
import com.vinz.music.ui.components.VinzSheet
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.ui.theme.VinzTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #401 (second report, 1.8.4: the player's ⋯ menu): a fast swipe in a sheet's list moved the sheet instead of the list,
 * "not every time". Material hands a sheet whatever a fling leaves over at its list's end. A sheet resting open then
 * springs with that speed although it's already where it belongs: up past its top (#147, kept from it for the menus
 * since then) or, flung back to the list's start, down and back up. While it springs, Material gives the next swipe to
 * the sheet itself instead of the list, so quick swipes kept the sheet bouncing. Now no sheet gets a leftover unless the
 * finger pulled that sheet down in the same swipe (a pull and a flick still close it).
 *
 * (Robolectric's injected swipes inside Material's sheet never reach the list, so the list's side of a swipe is played
 * through the same nested-scroll calls a list makes, from inside the sheet.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class SheetFlingTest {
    @get:Rule val compose = createComposeRule()

    private var open by mutableStateOf(true)
    private val list = NestedScrollDispatcher()
    private lateinit var scope: CoroutineScope

    /** A list's place in the nested-scroll chain: what it dispatches, the sheet above it gets. */
    @Composable
    private fun ListStandIn() {
        scope = rememberCoroutineScope()
        Box(Modifier.fillMaxWidth().height(1.dp).nestedScroll(object : NestedScrollConnection {}, list))
    }

    /** The player's ⋯ menu as drawn: an [ActionSheet] with more rows than fit. */
    private fun showMenu() = show {
        if (open) ActionSheet(null, { open = false }) { _ ->
            ListStandIn()
            ActionGroup("Playing", (1..40).map { ActionItem(VinzIcons.Song, "Action $it") {} }) {}
        }
    }

    /** Any other sheet: a plain [VinzSheet] with a list of its own and no #147 guard on it. */
    private fun showList() = show {
        if (open) VinzSheet({ open = false }) { _ ->
            ListStandIn()
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp)) { items(60) { Text("Row $it", Modifier.padding(16.dp)) } }
        }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { VinzTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) { content() } }
        compose.waitForIdle()
    }

    /** Where the sheet's list starts (it moves only with the sheet); far below the screen once the sheet has gone. */
    private fun top(): Float = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().firstOrNull()?.boundsInWindow?.top ?: 99_999f

    /** Plays [gesture] frame by frame; how far the sheet's top went up and down from where it settled. */
    private fun watch(gesture: suspend () -> Unit): Pair<Float, Float> {
        val settled = top()
        compose.mainClock.autoAdvance = false
        scope.launch { gesture() }
        var highest = settled
        var lowest = settled
        repeat(90) {
            compose.mainClock.advanceTimeByFrame()
            highest = minOf(highest, top())
            lowest = maxOf(lowest, top())
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        return (settled - highest) to (lowest - settled)
    }

    /** A swipe's end: its fling, of which the list (at its end) left [left] px/s over. */
    private suspend fun flingLeftOver(left: Float) {
        list.dispatchPreFling(Velocity(0f, left))
        list.dispatchPostFling(Velocity.Zero, Velocity(0f, left))
    }

    @Test fun aFlingBackToTheMenusStartLeavesTheSheetWhereItIs() {
        showMenu()
        val (_, down) = watch { flingLeftOver(6_000f) }
        assertTrue("the sheet was pulled down $down px", down <= 1f)
        assertTrue(open)
    }

    @Test fun aFlingToTheMenusEndLeavesTheSheetWhereItIs() {
        showMenu()
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 99_999f) }
        compose.waitForIdle()
        val (up, _) = watch { flingLeftOver(-6_000f) }
        assertTrue("the sheet was pushed up $up px", up <= 1f)
        assertTrue(open)
    }

    @Test fun everyVinzSheetKeepsStillToo() {
        showList()
        val (up, _) = watch { flingLeftOver(-6_000f) }
        assertTrue("the sheet was pushed up $up px", up <= 1f)
        val (_, down) = watch { flingLeftOver(6_000f) }
        assertTrue("the sheet was pulled down $down px", down <= 1f)
        assertTrue(open)
    }

    @Test fun aPullAndAFlickStillCloseTheMenu() {
        showMenu()
        // The list at its start, the finger pulls the sheet down a little and lets go quickly.
        watch {
            list.dispatchPostScroll(Offset.Zero, Offset(0f, 60f), NestedScrollSource.UserInput)
            flingLeftOver(6_000f)
        }
        compose.waitUntil(5_000) { !open }
    }

    @Test fun aSlowPullLetGoHalfwayStillSettlesTheSheet() {
        showList()
        // Pulled a little and let go without speed: the sheet goes back up, as before.
        val settled = top()
        watch {
            list.dispatchPostScroll(Offset.Zero, Offset(0f, 60f), NestedScrollSource.UserInput)
            flingLeftOver(0f)
        }
        assertTrue(open)
        assertTrue("the sheet went back to its place: ${top()} against $settled", kotlin.math.abs(top() - settled) <= 1f)
        // And the next swipe's leftover is kept from it again.
        val (_, down) = watch { flingLeftOver(6_000f) }
        assertTrue("the sheet was pulled down $down px", down <= 1f)
        assertTrue(open)
    }
}
