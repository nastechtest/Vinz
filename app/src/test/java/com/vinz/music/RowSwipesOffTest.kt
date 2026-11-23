package com.vinz.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.ui.components.SwipeToQueue
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #406 (1.8.4): with "Swipe song rows to queue" off, a sideways drag on a row queues nothing and the row stays where it
 * is; with it on (as Vinz ships), the swipe is the one it always was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp", application = android.app.Application::class)
class RowSwipesOffTest {
    @get:Rule val compose = createComposeRule()

    private fun settings(rowSwipes: Boolean) = AppSettings().let { it.copy(behavior = it.behavior.copy(rowSwipes = rowSwipes)) }

    /**
     * A 300 dp drag to the right, then the same to the left (after the few pixels a drag needs before it counts as one):
     * what was queued, and how far (dp) the row had followed the finger before it was let go. 300 dp, not the 150 dp of
     * the swipe's reach: the row moves under the finger, and here that halves what each step of the drag counts for
     * (150 dp of finger moved the row 76 dp, short of the 84 dp mark, so nothing queued with the swipe on either).
     */
    private fun swipeBothWays(rowSwipes: Boolean): Pair<List<Boolean>, Float> {
        val got = mutableListOf<Boolean>()
        // The page's own measure of a dp (Vinz's themes can scale it).
        var density = Density(1f)
        compose.setContent {
            VinzTheme(settings(rowSwipes), artworkColor = null) {
                density = LocalDensity.current
                SwipeToQueue({ got += it }) { Box(Modifier.fillMaxWidth().height(64.dp).testTag("row")) }
            }
        }
        compose.waitForIdle()
        val row = compose.onNodeWithTag("row")
        val far = with(density) { 300.dp.toPx() }
        fun x() = row.fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().x
        val from = x()
        row.performTouchInput {
            down(Offset(30f, centerY))
            moveBy(Offset(viewConfiguration.touchSlop + 1f, 0f))
            repeat(10) { moveBy(Offset(far / 10, 0f)) }
        }
        compose.waitForIdle()
        val moved = with(density) { (x() - from).toDp().value }
        row.performTouchInput { up() }
        compose.waitForIdle()
        row.performTouchInput {
            down(Offset(right - 30f, centerY))
            moveBy(Offset(-viewConfiguration.touchSlop - 1f, 0f))
            repeat(10) { moveBy(Offset(-far / 10, 0f)) }
            up()
        }
        compose.waitForIdle()
        return got to moved
    }

    @Test fun offASwipeQueuesNothingAndTheRowStaysPut() {
        val (queued, moved) = swipeBothWays(rowSwipes = false)
        assertEquals("nothing was played next or queued", emptyList<Boolean>(), queued)
        assertEquals("the row didn't follow the finger", 0f, moved, 0.01f)
    }

    @Test fun onItIsTheSwipeItAlwaysWas() {
        val (queued, moved) = swipeBothWays(rowSwipes = true)
        assertTrue("the row follows the finger past the mark (84 dp): it had moved $moved dp", moved > 84f)
        assertEquals("right plays next, left goes on the end of the queue", listOf(true, false), queued)
    }
}
