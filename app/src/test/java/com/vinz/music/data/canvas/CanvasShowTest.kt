package com.vinz.music.data.canvas

import com.vinz.music.data.prefs.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.7: where the song's video comes first anyway, its loop is asked for beside the animated cover's lookup instead of
 * after it (on the A16 that lookup was two to three seconds of a new song's wait), and what shows, in which order, stays
 * as it was: the cover meanwhile, the cover when there is no loop, and nothing over the loop once it is up.
 */
class CanvasShowTest {
    private val coverArt = CanvasArtwork("https://cover.example/a.mp4")
    private val loopArt = CanvasArtwork("file:///clips/v1.mp4#1-2", clip = VideoClip("v1", 1, 2, 1080))

    private fun settings(videoFirst: Boolean) = AppSettings().let { s ->
        s.copy(behavior = s.behavior.copy(canvas = s.behavior.canvas.copy(enabled = true, covers = true, useVideos = true, videoFirst = videoFirst)))
    }

    @Test fun theLoopComesFirstAnywayWhenTheVideoIsPutFirst() {
        assertTrue(canvasLoopFirst(settings(videoFirst = true), metered = false, songChoice = null, hidden = false, isSong = true))
        assertTrue("the song's own Use: video", canvasLoopFirst(settings(videoFirst = false), false, CanvasSource.VIDEO, hidden = false, isSong = true))
        // Cover first: whether the loop is wanted depends on the cover's answer, so it waits for it.
        assertFalse(canvasLoopFirst(settings(videoFirst = false), false, null, hidden = false, isSong = true))
        assertFalse(canvasLoopFirst(settings(videoFirst = true), false, CanvasSource.COVER, hidden = false, isSong = true))
        assertFalse("hidden for the song", canvasLoopFirst(settings(videoFirst = true), false, null, hidden = true, isSong = true))
        assertFalse("not a song", canvasLoopFirst(settings(videoFirst = true), false, null, hidden = false, isSong = false))
        // Whenever it says so, the loop is wanted with or without a cover (the cover's answer could not have changed it).
        for (vf in listOf(true, false)) for (choice in listOf(null, CanvasSource.VIDEO, CanvasSource.COVER)) {
            val s = settings(vf)
            if (canvasLoopFirst(s, false, choice, false, true)) {
                assertTrue(canvasWant(s, false, hasCover = false, songChoice = choice, hidden = false, isSong = true).video)
                assertTrue(canvasWant(s, false, hasCover = true, songChoice = choice, hidden = false, isSong = true).video)
            }
        }
    }

    @Test fun theLoopIsAskedForWhileTheCoverIsStillLookedUpAndNothingReplacesIt() = runBlocking {
        val coverAnswer = CompletableDeferred<CanvasArtwork?>()
        val shown = mutableListOf<CanvasArtwork?>()
        var loopAsked = false
        val job = launch {
            showCanvas(
                loopFirst = true,
                cover = { early -> early(null); coverAnswer.await() },
                loop = { hasCover -> loopAsked = true; assertTrue("asked as the loop that shows whatever the cover", hasCover); loopArt },
                show = { shown += it },
            )
        }
        repeat(5) { yield() }
        assertTrue("asked before the cover's answer", loopAsked)
        assertEquals(listOf(null, loopArt), shown)
        // The cover's answer comes late: it doesn't take the loop's place.
        coverAnswer.complete(coverArt)
        job.join()
        assertEquals(listOf(null, loopArt), shown)
    }

    @Test fun theCoverShowsWhileTheLoopComesAndStaysWhenThereIsNone() = runBlocking {
        val loopAnswer = CompletableDeferred<CanvasArtwork?>()
        val shown = mutableListOf<CanvasArtwork?>()
        val job = launch { showCanvas(true, cover = { coverArt }, loop = { loopAnswer.await() }, show = { shown += it }) }
        repeat(5) { yield() }
        assertEquals(listOf<CanvasArtwork?>(coverArt), shown)
        loopAnswer.complete(loopArt)
        job.join()
        assertEquals(listOf(coverArt, loopArt), shown)
        // No loop: the cover stays.
        val none = mutableListOf<CanvasArtwork?>()
        showCanvas(true, cover = { coverArt }, loop = { null }, show = { none += it })
        assertEquals(listOf<CanvasArtwork?>(coverArt), none)
    }

    @Test fun coverFirstTheLoopWaitsForTheCoversAnswerAsBefore() = runBlocking {
        val order = mutableListOf<String>()
        val shown = mutableListOf<CanvasArtwork?>()
        showCanvas(
            loopFirst = false,
            cover = { order += "cover"; null },
            loop = { hasCover -> order += "loop, cover $hasCover"; loopArt },
            show = { shown += it },
        )
        assertEquals(listOf("cover", "loop, cover false"), order)
        assertEquals(listOf(null, loopArt), shown)
        // A song with an animated cover: the loop is asked with that said (and gives none: the cover shows).
        order.clear(); shown.clear()
        showCanvas(false, cover = { order += "cover"; coverArt }, loop = { hasCover -> order += "loop, cover $hasCover"; null }, show = { shown += it })
        assertEquals(listOf("cover", "loop, cover true"), order)
        assertEquals(listOf<CanvasArtwork?>(coverArt), shown)
    }
}
