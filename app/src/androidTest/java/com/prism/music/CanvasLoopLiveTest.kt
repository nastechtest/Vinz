package com.vinz.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.vinz.music.data.innertube.SearchFilter
import com.vinz.music.data.model.SongItem
import com.vinz.music.playback.QueueKind
import com.vinz.music.playback.QueueSource
import com.vinz.music.ui.player.CanvasStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.8.6 Canvas, live (only with `record=true`: it reaches YouTube and plays a real song, muted by the phone): a song
 * with an official music video gets a loop cut from it, the loop plays full screen from the file kept on the phone,
 * goes round with its first frame over the restart, and nothing fails. The pictures it takes are looked at by eye:
 * the loop behind the player, the controls faded away, and back after a tap.
 *
 * The song can be changed with `song=<search words>`; `seconds=<n>` is how long it watches (default 40).
 */
@Live
@RunWith(AndroidJUnit4::class)
class CanvasLoopLiveTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { Fixture.container.player.stop() } }
    }

    /**
     * The long run for heat and battery (`canvas-heat.ps1` reads the phone meanwhile): one song on repeat for
     * `minutes=<n>`, the player open, with `cond=loop` (Full screen, its music video's loop) or `cond=still` (Canvas off).
     * 1.8.7: `cond=frame` is the loop in the cover's frame; `loop-paused`, `frame-paused` and `still-paused` pause the
     * song once that is on screen (what a paused player costs); `newinstall=true` leaves a new install's own defaults
     * (the cover flight, the backdrop fade) as they are, where the tests' setup otherwise switches them off.
     */
    @Test fun aLongRunForHeat() = runBlocking {
        assumeTrue("live test: run with record=true", args.getString("record") == "true")
        val cond = args.getString("cond") ?: "still"
        val paused = cond.endsWith("-paused")
        val framed = cond.startsWith("frame")
        val loop = cond.startsWith("loop") || framed
        val minutes = args.getString("minutes")?.toDoubleOrNull() ?: 20.0
        Fixture.ready()
        val c = Fixture.container
        if (args.getString("newinstall") == "true") c.settings.editNow {
            for (name in com.vinz.music.data.prefs.NewInstall.on + com.vinz.music.data.prefs.NewInstall.motion) {
                it[androidx.datastore.preferences.core.booleanPreferencesKey(name)] = true
            }
        }
        c.settings.setPerformanceMode(false); c.settings.setReduceMotion(false); c.settings.setImmersivePlayer(false); c.settings.setFullBleedArtwork(false)
        c.settings.setCanvasEnabled(loop); c.settings.setCanvasFillsBackground(loop && !framed); c.settings.setCanvasUseVideos(loop)
        c.settings.setCanvasVideoFirst(true); c.settings.setCanvasKeepControls(false)
        // strip=false: without the lyric line over the progress bar (what the open player costs on its own).
        // Both are set every run: a run's choice stays in the store for the next one otherwise (2026-11-14: a whole
        // comparison ran without the strip after one run had switched it off).
        c.settings.setLyricsOnPlayer(args.getString("strip") != "false")
        // lyrics=true: 1.8.7's "Keep lyrics showing" (the sung line over the picture while the controls are away).
        // (An older build of the app, driven by this test for a comparison, has no such switch.)
        try { c.settings.setCanvasLyrics(args.getString("lyrics") == "true") } catch (_: NoSuchMethodError) { }
        Fixture.launch()
        val words = (args.getString("song") ?: "Flowers_Miley_Cyrus").replace('_', ' ')
        val song = withContext(Dispatchers.IO) { c.ytm.search(words, SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song }
        CanvasStats.showing = null
        val before = CanvasStats.rounds to CanvasStats.errors
        withContext(Dispatchers.Main) { c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, "test")) }
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vinz://player"))
                .setClass(Fixture.context, VinzActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        delay(3_000)
        repeat(3) { if (c.player.repeatMode.value != androidx.media3.common.Player.REPEAT_MODE_ONE) { withContext(Dispatchers.Main) { c.player.cycleRepeat() }; delay(700) } }
        val until = System.currentTimeMillis() + (minutes * 60_000).toLong()
        if (loop) while (System.currentTimeMillis() < until && CanvasStats.showing?.startsWith("file://") != true) delay(200)
        if (paused) {
            delay(4_000)
            if (c.player.isPlaying.value) withContext(Dispatchers.Main) { c.player.togglePlay() }
            delay(3_000)
        }
        android.util.Log.i("CanvasHeat", "start $cond: \"${song.title}\", repeat ${c.player.repeatMode.value}, playing ${c.player.isPlaying.value}, showing ${CanvasStats.showing}, row ${c.canvasClips.row(song.id)?.height}p")
        while (System.currentTimeMillis() < until) delay(1_000)
        android.util.Log.i(
            "CanvasHeat",
            "end $cond: rounds ${CanvasStats.rounds - before.first}, covered ${CanvasStats.covered}, errors ${CanvasStats.errors - before.second} " +
                "(${CanvasStats.lastError}), showing ${CanvasStats.showing}, playing ${c.player.isPlaying.value}",
        )
        if (loop) assertEquals("no loop failed in the long run", before.second, CanvasStats.errors)
    }

    /**
     * The by-eye round on real loops: `songs=Words_of_one+Words_of_another` (underscores for spaces), each played until
     * its loop shows (or 45 s), with three pictures two seconds apart, the controls away. Nothing is asserted but that
     * it ran: the pictures and the log line per song ("CanvasEye") are what is looked at.
     */
    @Test fun manySongsForTheEye() = runBlocking {
        assumeTrue("live test: run with record=true", args.getString("record") == "true")
        val list = args.getString("songs")?.split('+')?.map { it.replace('_', ' ') }?.filter { it.isNotBlank() }
        assumeTrue("give songs=A+B", !list.isNullOrEmpty())
        Fixture.ready()
        val c = Fixture.container
        c.settings.setPerformanceMode(false); c.settings.setReduceMotion(false); c.settings.setImmersivePlayer(false); c.settings.setFullBleedArtwork(false)
        c.settings.setCanvasEnabled(true); c.settings.setCanvasFillsBackground(true); c.settings.setCanvasUseVideos(true)
        // cover=true: animated covers first, as a new install has it (what shows is in the screenshot and the log).
        val coverFirst = args.getString("cover") == "true"
        c.settings.setCanvasVideoFirst(!coverFirst); c.settings.setCanvasKeepControls(false)
        Fixture.launch()
        list!!.forEachIndexed { i, words ->
            val song = runCatching { withContext(Dispatchers.IO) { c.ytm.search(words, SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song } }.getOrNull()
            if (song == null) { android.util.Log.i("CanvasEye", "$i \"$words\": no song found"); return@forEachIndexed }
            CanvasStats.showing = null
            val asked = System.currentTimeMillis()
            withContext(Dispatchers.Main) { c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, "test")) }
            if (i == 0) Fixture.context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vinz://player"))
                    .setClass(Fixture.context, VinzActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            while (System.currentTimeMillis() < asked + 45_000 && CanvasStats.showing?.startsWith("file://") != true) delay(100)
            val took = System.currentTimeMillis() - asked
            val row = c.canvasClips.row(song.id)
            val shown = CanvasStats.showing?.startsWith("file://") == true
            if (coverFirst) android.util.Log.i("CanvasEye", "$i showing ${CanvasStats.showing}; cover ${c.canvas.cached(song)}")
            // A song with no loop: which video was looked at (the row keeps none), to see why by its title.
            if (!shown) android.util.Log.i("CanvasEye", "$i looked at video ${runCatching { withContext(Dispatchers.IO) { c.ytm.findMusicVideo(song) } }.getOrNull()} for song ${song.id}")
            android.util.Log.i(
                "CanvasEye",
                "$i \"${song.title}\" by ${song.artistText}: ${if (shown) "shown after $took ms" else "NO LOOP (${row?.why})"}; video ${row?.videoId} ${row?.videoType} " +
                    "${row?.height}p ${row?.startMs}-${row?.endMs} crop ${row?.cropX} scenes ${row?.crops}; ${c.canvasClips.lastTimes}; ${com.vinz.music.data.canvas.ClipDecode.last?.substringBefore(" [")}",
            )
            if (shown) {
                // Past the controls' four seconds, then three moments of the loop.
                delay(5_000)
                for (k in 1..3) { Fixture.screenshot("CanvasEye-${i.toString().padStart(2, '0')}-$k"); delay(2_000) }
            } else Fixture.screenshot("CanvasEye-${i.toString().padStart(2, '0')}-none")
        }
    }

    /**
     * 1.8.7.6 (#528): how long the picture stands at a real loop's restart, on the phone's own network. `kind=video` (the
     * default) plays a song's music-video loop, `kind=cover` its animated cover; `song=<search words>`; `rounds=<n>`
     * (default 10). Logged under "CanvasWrap"; nothing asserted but that the loop showed and went round.
     */
    @Test fun wrapGapsOnARealLoop() = runBlocking {
        assumeTrue("live test: run with record=true", args.getString("record") == "true")
        val cover = args.getString("kind") == "cover"
        val rounds = args.getString("rounds")?.toIntOrNull() ?: 10
        Fixture.ready()
        val c = Fixture.container
        c.settings.setPerformanceMode(false); c.settings.setReduceMotion(false); c.settings.setImmersivePlayer(false); c.settings.setFullBleedArtwork(false)
        c.settings.setCanvasEnabled(true); c.settings.setCanvasFillsBackground(true); c.settings.setCanvasUseVideos(true)
        c.settings.setCanvasVideoFirst(!cover); c.settings.setCanvasKeepControls(false)
        Fixture.launch()
        val words = (args.getString("song") ?: if (cover) "Anti-Hero_Taylor_Swift" else "Flowers_Miley_Cyrus").replace('_', ' ')
        val song = withContext(Dispatchers.IO) { c.ytm.search(words, SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song }
        CanvasStats.showing = null
        com.vinz.music.ui.player.CanvasFrames.clear()
        com.vinz.music.ui.player.CanvasFrames.on = true
        try {
            withContext(Dispatchers.Main) { c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, "test")) }
            Fixture.context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vinz://player"))
                    .setClass(Fixture.context, VinzActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            val end = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < end && CanvasStats.showing == null) delay(100)
            assertNotNull("a canvas shows (${CanvasStats.lastError})", CanvasStats.showing)
            // Counted from the clip on screen now (the player view may have been made again once the clip came).
            withContext(Dispatchers.Main) { com.vinz.music.ui.player.CanvasFrames.clear() }
            val until = System.currentTimeMillis() + 240_000
            while (System.currentTimeMillis() < until && withContext(Dispatchers.Main) { com.vinz.music.ui.player.CanvasFrames.roundsNs().size } < rounds + 1) delay(250)
            delay(1_500)
            val frames = withContext(Dispatchers.Main) { com.vinz.music.ui.player.CanvasFrames.framesNs() }
            val went = withContext(Dispatchers.Main) { com.vinz.music.ui.player.CanvasFrames.roundsNs() }
            val g = com.vinz.music.ui.player.wrapGaps(frames, went)
            val seen = com.vinz.music.ui.player.wrapGaps(withContext(Dispatchers.Main) { com.vinz.music.ui.player.CanvasFrames.visibleNs() }, went)
            val row = c.canvasClips.row(song.id)
            android.util.Log.i(
                "CanvasWrap",
                "live ${if (cover) "cover" else "video"} (kept frame up ${withContext(Dispatchers.Main) { com.vinz.music.ui.player.CanvasFrames.coversUp() }} times) \"${song.title}\": showing ${CanvasStats.showing}; row ${row?.height}p ${row?.startMs}-${row?.endMs} refined ${row?.refined}; " +
                    "${went.size} rounds; seen at the restarts ${seen.atWrapsMs.map { "%.0f".format(it) }} ms, drawn ${g.atWrapsMs.map { "%.0f".format(it) }} ms, inside typically ${"%.1f".format(g.typicalMs)} ms " +
                    "(longest ${"%.0f".format(g.longestElsewhereMs)} ms); decoder: ${synchronized(com.vinz.music.ui.player.CanvasFrames.wraps) { com.vinz.music.ui.player.CanvasFrames.wraps.toList() }}",
            )
            assertTrue("it went round (${went.size})", went.size >= 2)
        } finally {
            com.vinz.music.ui.player.CanvasFrames.on = false
        }
    }

    @Test fun aSongsMusicVideoLoopsBehindThePlayer() = runBlocking {
        assumeTrue("live test: run with record=true", args.getString("record") == "true")
        Fixture.ready()
        val c = Fixture.container
        c.settings.setPerformanceMode(false)
        c.settings.setReduceMotion(false)
        c.settings.setImmersivePlayer(false)
        c.settings.setFullBleedArtwork(args.getString("bleed") == "true")
        c.settings.setCanvasEnabled(true)
        c.settings.setCanvasFillsBackground(true)
        c.settings.setCanvasUseVideos(true)
        c.settings.setCanvasVideoFirst(true)
        c.settings.setCanvasKeepControls(false)
        // mono=true: the clean look the launch film is shot in (white light, black player, no spectrum ring).
        if (args.getString("mono") == "true") {
            c.settings.setThemeMode(com.vinz.music.data.prefs.ThemeMode.DARK); c.settings.setPureBlack(true)
            c.settings.setLightStyle(com.vinz.music.data.prefs.LightStyle.WHITE); c.settings.setSurfaceTint(com.vinz.music.data.prefs.SurfaceTint.NEUTRAL)
            c.settings.setPlayRing(false); c.settings.setPlayerBackground(com.vinz.music.data.prefs.PlayerBackground.BLACK)
        }
        Fixture.launch()
        val words = (args.getString("song") ?: "Rick Astley Never Gonna Give You Up").replace('_', ' ')
        val song = withContext(Dispatchers.IO) { c.ytm.search(words, SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song }
        val before = CanvasStats.rounds to CanvasStats.errors
        val played = System.currentTimeMillis()
        withContext(Dispatchers.Main) { c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, "test")) }
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vinz://player"))
                .setClass(Fixture.context, VinzActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        // The loop is found, fetched and on screen (the budget for a song met for the first time on Wi-Fi is 3 s; the
        // time it took is printed, the limit here is only for a slow emulator).
        val asked = System.currentTimeMillis()
        val end = asked + 60_000
        while (System.currentTimeMillis() < end && CanvasStats.showing?.startsWith("file://") != true) delay(100)
        val took = System.currentTimeMillis() - asked
        val row = c.canvasClips.row(song.id)
        println("CanvasLoop: ${song.title} (${song.id}) row=$row shown after $took ms")
        android.util.Log.i("CanvasLoop", "${song.title} (${song.id}) shown after $took ms, row $row")
        android.util.Log.i("CanvasLoop", "times: ${c.canvasClips.lastTimes}; asked ${asked - played} ms after play")
        android.util.Log.i("CanvasLoop", "second look:${com.vinz.music.data.canvas.ClipDecode.last}; kept ${c.clipFiles.recentBytes / 1024} KB")
        assertTrue("the loop was looked at on its real frames (${com.vinz.music.data.canvas.ClipDecode.last})", row?.refined == true)
        assertNotNull("a row for the song", row)
        assertNotNull("a music video for it (${row?.why})", row?.videoId)
        assertTrue("the loop shows from its kept file (showing ${CanvasStats.showing}, error ${CanvasStats.lastError})", CanvasStats.showing?.startsWith("file://") == true)
        delay(1_500)
        Fixture.screenshot("CanvasLoopLiveTest-1-loop")

        // It goes round, covered, and keeps drawing; the controls fade meanwhile.
        val watch = (args.getString("seconds")?.toLongOrNull() ?: 40L) * 1000
        val framesAt = CanvasStats.frames
        delay(6_000)
        Fixture.screenshot("CanvasLoopLiveTest-2-controls-away")
        delay(watch - 6_000)
        val rounds = CanvasStats.rounds - before.first
        android.util.Log.i("CanvasLoop", "rounds $rounds, covered ${CanvasStats.covered}, frames ${CanvasStats.frames - framesAt}, errors ${CanvasStats.errors - before.second} ${CanvasStats.lastError}")
        assertEquals("nothing failed (${CanvasStats.lastError})", before.second, CanvasStats.errors)
        assertTrue("the loop went round ($rounds times in ${watch / 1000} s)", rounds >= 2)
        assertTrue("frames kept coming (${CanvasStats.frames - framesAt})", CanvasStats.frames - framesAt > watch / 1000 * 10)
        assertTrue("its first frame covered the restarts after the first (${CanvasStats.covered} of $rounds)", CanvasStats.covered >= rounds - 1)

        // A tap brings the controls back.
        val device = UiDevice.getInstance(instrumentation)
        device.click(device.displayWidth / 2, device.displayHeight / 3)
        delay(700)
        Fixture.screenshot("CanvasLoopLiveTest-3-controls-back")
    }

    /**
     * 1.8.7 "Keep lyrics showing": over a full-screen loop the sung line comes once the controls have gone, changes with
     * the song, and goes when a tap brings the controls back; with the switch off there is never one.
     */
    @Test fun theSungLineStaysWhenTheControlsGo() = runBlocking {
        assumeTrue("live test: run with record=true", args.getString("record") == "true")
        Fixture.ready()
        val c = Fixture.container
        c.settings.setPerformanceMode(false); c.settings.setReduceMotion(false); c.settings.setImmersivePlayer(false); c.settings.setFullBleedArtwork(false)
        c.settings.setCanvasEnabled(true); c.settings.setCanvasFillsBackground(true); c.settings.setCanvasUseVideos(true)
        c.settings.setCanvasVideoFirst(true); c.settings.setCanvasKeepControls(false)
        c.settings.setLyricsOnPlayer(true)
        c.settings.setCanvasLyrics(false)
        Fixture.launch()
        val words = (args.getString("song") ?: "Flowers_Miley_Cyrus").replace('_', ' ')
        val song = withContext(Dispatchers.IO) { c.ytm.search(words, SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song }
        CanvasStats.showing = null
        withContext(Dispatchers.Main) { c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, "test")) }
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vinz://player"))
                .setClass(Fixture.context, VinzActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end && CanvasStats.showing?.startsWith("file://") != true) delay(100)
        assertTrue("the loop shows (${CanvasStats.showing}, ${CanvasStats.lastError})", CanvasStats.showing?.startsWith("file://") == true)
        // Into the singing, so a line is due; the controls go a few seconds after the seek.
        withContext(Dispatchers.Main) { c.player.seekTo(30_000) }
        suspend fun seen(ms: Long): Set<String> {
            val lines = mutableSetOf<String>(); val until = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < until) { CanvasStats.lyricLine?.takeIf { it.isNotBlank() }?.let(lines::add); delay(200) }
            return lines
        }
        // Switched off: the controls go, no line comes.
        assertTrue("off: no line over the picture", seen(15_000).isEmpty())
        Fixture.screenshot("CanvasLyricLive-1-off")
        // Switched on while the controls are away: the line comes, and moves on with the song.
        c.settings.setCanvasLyrics(true)
        val lines = seen(25_000)
        Fixture.screenshot("CanvasLyricLive-2-on")
        android.util.Log.i("CanvasLyric", "lines seen: $lines")
        assertTrue("on: the sung line shows and changes (saw $lines)", lines.size >= 2)
        // A tap brings the controls back and the line goes.
        val device = UiDevice.getInstance(instrumentation)
        device.click(device.displayWidth / 2, device.displayHeight / 3)
        delay(1_200)
        Fixture.screenshot("CanvasLyricLive-3-controls-back")
        assertEquals("with the controls up there is no line", null, CanvasStats.lyricLine)
        // And it returns when they go again.
        assertTrue("it comes back once the controls have gone", seen(15_000).isNotEmpty())
    }
}
