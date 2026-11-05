@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
package com.vinz.music.ui.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.vinz.music.playback.nextPreviewWindow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NextTrackPreviewTest {
    private open class TestTimeline(private val count: Int) : Timeline() {
        override fun getWindowCount() = count
        override fun getPeriodCount() = count
        override fun getWindow(index: Int, window: Window, projectionUs: Long): Window = error("Not needed for play order")
        override fun getPeriod(index: Int, period: Period, setIds: Boolean): Period = error("Not needed for play order")
        override fun getIndexOfPeriod(uid: Any): Int = (uid as? Int)?.takeIf { it in 0 until count } ?: C.INDEX_UNSET
        override fun getUidOfPeriod(index: Int): Any = index
    }

    @Test fun emptyQueueHasNoPreview() {
        assertEquals(C.INDEX_UNSET, nextPreviewWindow(Timeline.EMPTY, 0, Player.REPEAT_MODE_ALL, false))
    }
    @Test fun exhaustedQueueDoesNotInventAnItem() {
        assertEquals(C.INDEX_UNSET, nextPreviewWindow(TestTimeline(3), 2, Player.REPEAT_MODE_OFF, false))
    }
    @Test fun ordinaryPlaybackUsesTimelineOrder() {
        assertEquals(1, nextPreviewWindow(TestTimeline(3), 0, Player.REPEAT_MODE_OFF, false))
    }
    @Test fun repeatOnePreviewsTheCurrentTrack() {
        assertEquals(1, nextPreviewWindow(TestTimeline(3), 1, Player.REPEAT_MODE_ONE, false))
    }
    @Test fun repeatAllWrapsToTheFirstTrack() {
        assertEquals(0, nextPreviewWindow(TestTimeline(3), 2, Player.REPEAT_MODE_ALL, false))
    }
    @Test fun aSingleTrackCanRepeat() {
        assertEquals(0, nextPreviewWindow(TestTimeline(1), 0, Player.REPEAT_MODE_ALL, true))
    }
    @Test fun invalidCurrentIndicesHideThePreview() {
        assertEquals(C.INDEX_UNSET, nextPreviewWindow(TestTimeline(3), -1, Player.REPEAT_MODE_OFF, false))
        assertEquals(C.INDEX_UNSET, nextPreviewWindow(TestTimeline(3), 3, Player.REPEAT_MODE_ALL, false))
    }
    @Test fun shuffleAndRepeatAreForwardedToTheActualTimeline() {
        val timeline = object : TestTimeline(3) {
            override fun getNextWindowIndex(index: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
                assertEquals(0, index)
                assertEquals(Player.REPEAT_MODE_ALL, repeatMode)
                assertTrue(shuffleModeEnabled)
                return 2
            }
        }
        assertEquals(2, nextPreviewWindow(timeline, 0, Player.REPEAT_MODE_ALL, true))
    }
}
