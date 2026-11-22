package com.vinz.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vinz.music.data.model.Song
import com.vinz.music.playback.QueueKind
import com.vinz.music.playback.QueueSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.8.7 (plan 4.6), live (only with `record=true`: it reaches YouTube and plays a real video, muted by the phone):
 * #411's video stopped at about 1:08 on 1.8.2.1 ("Skipping atom with length > 2147483647"). It is played from its
 * start, with no seek (a seek could step over the place it broke at), and has to be the same video, still playing, past
 * 1:30, with no error on the way.
 *
 * Another video: `video=<id>`; how far it must get: `past=<seconds>` (default 95).
 */
@Live
@RunWith(AndroidJUnit4::class)
class PlaysPastLiveTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { Fixture.container.player.stop() } }
    }

    @Test fun theVideoThatStoppedAtOneOhEightPlaysOn() = runBlocking {
        assumeTrue("live test: run with record=true", args.getString("record") == "true")
        val id = args.getString("video") ?: "52Gg9CqhbP8"
        val past = (args.getString("past")?.toLongOrNull() ?: 95L) * 1_000
        Fixture.ready()
        val c = Fixture.container
        Fixture.launch()
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val listening = launch(kotlinx.coroutines.Dispatchers.Default) { c.player.errors.collect { errors += it.toString() } }
        val video = Song(id = id, title = "Issue 411", isVideo = true)
        instrumentation.runOnMainSync { c.player.playQueue(listOf(video), 0, QueueSource(QueueKind.SINGLE, "test")) }
        fun position(): Long { var at = 0L; instrumentation.runOnMainSync { at = c.player.position }; return at }
        // Counted from the video's own start: until it is the one playing, the place read is the last queue's
        // (2026-11-14: a song left at 0:58 by the test before made the first 53 s read as a fall back).
        val isIt = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < isIt && !(c.player.currentSong.value?.id == id && position() < 5_000)) Thread.sleep(200)
        assertEquals("the video is the one playing", id, c.player.currentSong.value?.id)
        var furthest = 0L; var back = 0
        val end = System.currentTimeMillis() + past + 60_000
        while (System.currentTimeMillis() < end && furthest < past) {
            Thread.sleep(500)
            val at = position()
            // A start over from 0 (a retry that lost its place) is what a "fixed" stop could hide behind.
            if (at + 5_000 < furthest) back++
            furthest = maxOf(furthest, at)
        }
        listening.cancel()
        android.util.Log.i("PlaysPast", "$id: got to ${furthest / 1000} s, stepped back $back, errors $errors, plays as ${c.streamDetails[id]?.first}")
        assertTrue("it got to ${furthest / 1000} s of ${past / 1000} (errors: $errors)", furthest >= past)
        assertEquals("it never fell back to an earlier place", 0, back)
        assertEquals("the same video is still the one playing", id, c.player.currentSong.value?.id)
        assertTrue("no error was shown: $errors", errors.isEmpty())
    }
}
