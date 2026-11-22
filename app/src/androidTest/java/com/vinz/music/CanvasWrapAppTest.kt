package com.vinz.music

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vinz.music.data.canvas.CanvasArtwork
import com.vinz.music.data.canvas.ClipFetcher
import com.vinz.music.data.canvas.VideoClip
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.player.CanvasFrames
import com.vinz.music.ui.player.CanvasPlayer
import com.vinz.music.ui.player.CanvasStats
import com.vinz.music.ui.player.WrapGaps
import com.vinz.music.ui.player.wrapGaps
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 1.8.7.6 (#528, "it freezes for about a second and then resumes"): a short bundled loop plays through the real
 * [CanvasPlayer] for ten restarts, and the picture must not stand still when it starts over. What is measured is when
 * frames reach the video view ([CanvasFrames]): the longest gap between two frames around each restart, against the
 * usual gap inside the loop. No network: both loops are files made for the test (ffmpeg's test picture, H.264 with
 * B-frames, as YouTube's streams are).
 *
 * - `video-loop.mp4`: a loop from a music video as the phone keeps it (a fragmented MP4 with an index, key frames every
 *   2 s, the loop cut from its second key frame for 4 s, its start found by the app's own [ClipFetcher.keyFramesOf]);
 * - `animated-cover.mp4`: an animated cover, a whole 4 s file going round.
 */
@RunWith(AndroidJUnit4::class)
class CanvasWrapAppTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = Fixture.container

    @After fun cleanUp() { CanvasFrames.on = false; CanvasFrames.clear() }

    private fun asset(name: String): File {
        val out = File(Fixture.context.cacheDir, "canvas-wrap-test/$name")
        out.parentFile!!.mkdirs()
        instrumentation.context.assets.open("canvas/$name").use { i -> out.outputStream().use { i.copyTo(it) } }
        return out
    }

    private fun <T> onMain(read: () -> T): T {
        var out: Any? = null
        instrumentation.runOnMainSync { out = read() }
        @Suppress("UNCHECKED_CAST") return out as T
    }

    /** Plays [canvas] until it has gone round [rounds] times; the gaps, logged under "CanvasWrap". */
    private fun measure(kind: String, canvas: CanvasArtwork, rounds: Int = 10, loopMs: Long): WrapGaps {
        Fixture.ready()
        // Canvas holds still on purpose in battery saver or on a hot phone: that must not be what is measured.
        val power = Fixture.context.getSystemService(android.os.PowerManager::class.java)
        assertFalse("battery saver is on: Canvas would hold still", power.isPowerSaveMode)
        if (android.os.Build.VERSION.SDK_INT >= 29) assertTrue("the phone is hot (${power.currentThermalStatus})", power.currentThermalStatus < 3)
        val errors = CanvasStats.errors
        val coveredBefore = CanvasStats.covered
        CanvasFrames.clear()
        CanvasFrames.on = true
        compose.setContent {
            // Reduce motion off (it would hold the picture), as AppSettings has it by default.
            VinzTheme(AppSettings(themeMode = ThemeMode.DARK, reduceMotion = false), artworkColor = null) {
                CompositionLocalProvider(LocalContainer provides c) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        CanvasPlayer(canvas, saved = false, isPlaying = true, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        val until = System.currentTimeMillis() + (rounds + 4) * loopMs + 15_000
        // One round more than counted, so the last one has frames after it.
        while (onMain { CanvasFrames.roundsNs().size } < rounds + 1 && System.currentTimeMillis() < until) Thread.sleep(200)
        Thread.sleep(1_500)
        val frames = onMain { CanvasFrames.framesNs() }
        val went = onMain { CanvasFrames.roundsNs() }
        val drawn = wrapGaps(frames, went)
        // What the viewer sees: the kept first frame standing over the video counts as standing still.
        val gaps = wrapGaps(onMain { CanvasFrames.visibleNs() }, went)
        val wraps = synchronized(CanvasFrames.wraps) { CanvasFrames.wraps.toList() }
        android.util.Log.i(
            "CanvasWrap",
            "$kind:${went.size} rounds (${CanvasStats.covered - coveredBefore} meant to be under the kept first frame, ${onMain { CanvasFrames.coversUp() }} shown), ${frames.size} frames; seen at the restarts ${gaps.atWrapsMs.map { "%.0f".format(it) }} ms, " +
                "drawn by the video view ${drawn.atWrapsMs.map { "%.0f".format(it) }} ms; " +
                "inside the loop typically ${"%.1f".format(drawn.typicalMs)} ms (longest ${"%.0f".format(drawn.longestElsewhereMs)} ms); " +
                "decoder: $wraps",
        )
        Fixture.screenshot("CanvasWrap-$kind")
        assertEquals("nothing failed (${CanvasStats.lastError})", errors, CanvasStats.errors)
        assertTrue("$kind went round ${went.size} times, wanted ${rounds + 1}", went.size >= rounds + 1)
        assertTrue("frames came inside the loop (typical gap ${drawn.typicalMs} ms)", drawn.typicalMs in 5.0..60.0)
        return gaps.copy(typicalMs = drawn.typicalMs)
    }

    private fun assertNoStandStill(kind: String, gaps: WrapGaps) {
        val bound = WRAP_BOUND_MS
        assertTrue(
            "$kind stood still at a restart: ${gaps.atWrapsMs.map { it.toInt() }} ms, bound $bound ms (frames ${"%.1f".format(gaps.typicalMs)} ms apart inside)",
            gaps.atWrapsMs.isNotEmpty() && gaps.atWrapsMs.all { it <= bound },
        )
    }

    @Test fun aLoopFromAMusicVideoGoesRoundWithoutStandingStill() {
        val file = asset("video-loop.mp4")
        val keys = ClipFetcher.keyFramesOf(file)
        assertTrue("the test file has key frames every 2 s ($keys)", keys.size >= 3)
        // The loop starts on the second key frame, as the app's own refine pass cuts it, and lasts 4 s.
        val start = keys[1]
        val loop = VideoClip("wrap-test", startMs = start, endMs = start + LOOP_MS, height = 720, refined = true, fileStartMs = 0)
        val gaps = measure("video-loop", CanvasArtwork(Uri.fromFile(file).toString(), clip = loop), loopMs = LOOP_MS)
        assertNoStandStill("the loop from a music video", gaps)
    }

    @Test fun anAnimatedCoverGoesRoundWithoutStandingStill() {
        val file = asset("animated-cover.mp4")
        val gaps = measure("animated-cover", CanvasArtwork(Uri.fromFile(file).toString()), loopMs = LOOP_MS)
        assertNoStandStill("the animated cover", gaps)
    }

    private companion object {
        const val LOOP_MS = 4_000L
        /**
         * The longest a frame may stand at a restart. A16, 2026-11-16: frames 33.3 ms apart inside the loop; at the
         * restarts 1.8.7.5's cut stood 399 to 454 ms (the loop from a video) and the animated cover 36 to 91 ms; with
         * [com.vinz.music.ui.player.loopCut] the loop from a video 44 to 47 ms. A missed vsync or two more is allowed.
         */
        const val WRAP_BOUND_MS = 120.0
    }
}
