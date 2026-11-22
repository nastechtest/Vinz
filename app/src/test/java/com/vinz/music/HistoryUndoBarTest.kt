package com.vinz.music

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import com.vinz.music.data.HistoryRemoval
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.ui.components.undoBarMillis
import com.vinz.music.ui.screens.HISTORY_UNDO_TAG
import com.vinz.music.ui.screens.HistoryUndoBar
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * History's "Removed from history" bar: TalkBack reads it out when it comes (a polite live region), and it stays five
 * seconds, or as long as the listener's "Time to take action" asks for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HistoryUndoBarTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun a11y() = context.getSystemService(AccessibilityManager::class.java)

    private var gone = 0

    private fun show() {
        var removed by mutableStateOf<HistoryRemoval.Removed?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VinzTheme(AppSettings(), artworkColor = null) {
                HistoryUndoBar(removed, Modifier, onGone = { gone++; removed = null }, onUndo = {})
            }
        }
        removed = HistoryRemoval.Removed(emptyList(), emptyMap())
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun bars() = compose.onAllNodes(hasTestTag(HISTORY_UNDO_TAG), useUnmergedTree = true).fetchSemanticsNodes()

    @Test fun theBarIsALiveRegion() {
        show()
        compose.mainClock.advanceTimeBy(500)
        val bar = bars().single()
        assertEquals(LiveRegionMode.Polite, bar.config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test fun theBarGoesAfterFiveSecondsByDefault() {
        assertEquals(5_000L, undoBarMillis(context))
        show()
        compose.mainClock.advanceTimeBy(4_500)
        assertEquals(0, gone)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(1, gone)
    }

    @Test fun aLongerTimeToActKeepsTheBarLonger() {
        shadowOf(a11y()).setInteractiveUiTimeout(20_000)
        assertEquals(20_000L, undoBarMillis(context))
        show()
        compose.mainClock.advanceTimeBy(10_000)
        assertEquals("still there after 10 s", 0, gone)
        compose.mainClock.advanceTimeBy(11_000)
        assertEquals(1, gone)
    }
}
