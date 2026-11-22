package com.vinz.music

import android.content.Intent
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vinz.music.playback.PlaybackServices
import com.vinz.music.playback.QueueKind
import com.vinz.music.playback.QueueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * 1.8.7 (plan 4.5, #388): Vinz killed while playing, then Play pressed on a headset (or the system's resume card):
 * the same song starts near the same second. An instrumented test runs in Vinz's own process, so the kill happens
 * between two runs, by hand:
 *
 * 1. run `a_playAndNoteThePlace` (plays a tone from 0:20 for ~15 s and notes where it was),
 * 2. `adb shell am kill <package>` (with Vinz in the background: press Home first), or swipe it from Recents,
 * 3. run `b_playOnTheSessionResumesThere` (sends Play as a headset does and checks the song and the second).
 *
 * Needs the step between (the end of an instrumented run stops Vinz as well, which serves): skipped in the in-app suite.
 */
// Only with `resume=true`: the two are run one after the other by hand or by a script, never by the suite.
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ResumeAfterKillAppTest {
    private val expect get() = File(Fixture.context.filesDir, "resume-test-expect.txt")

    private fun asked() = assumeTrue("run with resume=true, a then b", InstrumentationRegistry.getArguments().getString("resume") == "true")

    @Test fun a_playAndNoteThePlace() {
        asked()
        Fixture.ready()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        Fixture.tone("Vinz resume tone one", 90)
        Fixture.tone("Vinz resume tone two", 90)
        Fixture.launch()
        val local = Fixture.container.localMusic
        kotlinx.coroutines.runBlocking { local.scan() }
        val songs = listOf("one", "two").map { w -> local.tracks.value.first { it.song.title == "Vinz resume tone $w" }.song }
        val main = InstrumentationRegistry.getInstrumentation()
        main.runOnMainSync { Fixture.container.player.playQueue(songs, 1, QueueSource(QueueKind.OTHER, "Resume test")) }
        waitFor(20_000) { Fixture.container.player.currentSong.value?.id == songs[1].id && Fixture.container.player.isPlaying.value }
        main.runOnMainSync { Fixture.container.player.seekTo(20_000) }
        // The place is kept every 10 s while playing: two ticks.
        Thread.sleep(15_000)
        var at = 0L
        main.runOnMainSync { at = Fixture.container.player.position }
        expect.writeText("${songs[1].id}\n$at")
        assertTrue("the place file wasn't written", File(Fixture.context.filesDir, "queue-place.json").exists())
    }

    @Test fun b_playOnTheSessionResumesThere() {
        asked()
        val (id, at) = expect.readLines().let { it[0] to it[1].toLong() }
        val context = Fixture.context
        // As Android hands a headset's Play to the media app it last played with.
        val play = Intent(Intent.ACTION_MEDIA_BUTTON).setClass(context, PlaybackServices.target(context))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        context.startForegroundService(play)
        waitFor(20_000) { Fixture.container.player.currentSong.value?.id == id && Fixture.container.player.isPlaying.value }
        var now = 0L
        InstrumentationRegistry.getInstrumentation().runOnMainSync { now = Fixture.container.player.position }
        assertEquals("the same song", id, Fixture.container.player.currentSong.value?.id)
        // Kept every 10 s, so within ~12 s of where it was (and a little further, as it plays on).
        assertTrue("resumed at ${now / 1000} s, was at ${at / 1000} s", now in (at - 12_000)..(at + 20_000))
        expect.delete()
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("not there after $timeoutMs ms")
            Thread.sleep(200)
        }
    }
}
