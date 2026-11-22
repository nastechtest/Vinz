package com.vinz.music

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #476 (1.8.7.5), in the app: songs picked with Select many in a playlist and sent with "Play next" or "Add to queue"
 * while nothing plays become the queue, all of them, in the order they were picked (only the first used to play). The
 * unit test (PickedSongsQueue476Test) drives PlayerConnection with a test player; this one goes through the selection
 * bar and the playback service.
 *
 * No network: a Vinz playlist of test songs (ids no service knows). Their streams can't be had, so only the queue right
 * after the tap is asserted, read in the same main-thread step that first sees it filled, before any failure handling.
 */
@RunWith(AndroidJUnit4::class)
class PickedSongsQueue476AppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { c.player.stop() } }
        instrumentation.uiAutomation.executeShellCommand("cmd media_session volume --stream 3 --set 0").close()
    }

    @Test fun playNextWithNothingPlayingQueuesEveryPickedSong() = pickAndSend("Play next")

    @Test fun addToQueueWithNothingPlayingQueuesEveryPickedSong() = pickAndSend("Add to queue")

    private fun <T> onMain(read: () -> T): T {
        var out: Any? = null
        instrumentation.runOnMainSync { out = read() }
        @Suppress("UNCHECKED_CAST") return out as T
    }

    private fun waitFor(what: String, ms: Long, done: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!onMain(done)) {
            if (System.currentTimeMillis() > until) throw AssertionError("never: $what")
            Thread.sleep(50)
        }
    }

    /** The playback service up with an empty queue: a run without its data cleared may have restored the last one. */
    private fun nothingPlaying() {
        java.io.File(Fixture.context.filesDir, "queue.json").delete()
        // stop() binds the service if it isn't yet (it restores what queue.json held first), then empties the queue.
        instrumentation.runOnMainSync { c.player.stop() }
        waitFor("the playback service with an empty queue", 20_000) { c.player.player.let { it != null && it.mediaItemCount == 0 } }
        assertEquals("nothing playing before the test", null, onMain { c.player.currentSong.value })
    }

    private fun openLibraryPlaylist(title: String, first: String) {
        compose.tapTab("Library")
        // Library keeps the page it was on: its first page, left of Songs, holds the playlists.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Songs")).fetchSemanticsNodes().isNotEmpty() }
        repeat(6) {
            if (compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()) return@repeat
            compose.onRoot().performTouchInput { swipeRight(startX = width * 0.15f, endX = width * 0.95f) }
            compose.waitForIdle()
        }
        compose.clickWhenFound(hasText(title) and hasClickAction(), 15_000)
        compose.waitForText(first)
    }

    private fun bar() = compose.onAllNodes(hasTestTag("selectionBar")).fetchSemanticsNodes()

    /** A song's row, acted on through its own actions: on a short screen the lower rows sit under the bottom bar, where a touch
     *  aimed at them lands on the bar instead. */
    private fun row(title: String) = compose.onAllNodes(hasText(title) and hasClickAction()).onFirst()

    private fun pickAndSend(action: String) {
        Fixture.ready()
        instrumentation.uiAutomation.executeShellCommand("cmd media_session volume --stream 3 --set 0").close()
        val songs = Fixture.songs(5)
        Fixture.vinzPlaylist("Picked for the queue", songs)
        Fixture.launch()
        nothingPlaying()
        openLibraryPlaylist("Picked for the queue", "Test song 1")

        // Picked out of the list's order: 3, then 1, then 5.
        val picked = listOf(songs[2], songs[0], songs[4])
        row(picked[0].title).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitUntil(5_000) { bar().isNotEmpty() }
        row(picked[1].title).performSemanticsAction(SemanticsActions.OnClick)
        row(picked[2].title).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("3 songs picked")
        assertEquals("still nothing playing", 0, onMain { c.player.player?.mediaItemCount ?: 0 })

        compose.clickWhenFound(hasText(action) and hasClickAction() and hasAnyAncestor(hasTestTag("selectionBar")))
        compose.waitUntil(5_000) { bar().isEmpty() }

        // The queue as the tap left it, read in the same step that first sees it hold songs.
        var queued = emptyList<String>()
        var current = -1
        var asked = false
        waitFor("the picked songs in the queue", 10_000) {
            val p = c.player.player ?: return@waitFor false
            if (p.mediaItemCount == 0) return@waitFor false
            queued = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
            current = p.currentMediaItemIndex
            asked = p.playWhenReady
            true
        }
        Fixture.screenshot("picked-songs-queue-476-${action.replace(' ', '-').lowercase()}")
        // Autoplay's suggestions may follow later, never before them: the picked songs lead, all of them, in their order.
        assertTrue("every picked song is queued: $queued", queued.size >= picked.size)
        assertEquals("$action: the picked songs, in the order picked", picked.map { it.id }, queued.take(picked.size))
        assertEquals("the first picked song is the one on", 0, current)
        assertTrue("and it was asked to play", asked)
    }
}
