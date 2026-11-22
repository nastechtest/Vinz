package com.vinz.music.data.canvas

import android.content.Context
import com.vinz.music.SpotifyFixtures
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.CanvasPrefs
import com.vinz.music.data.spotify.SessionFetch
import com.vinz.music.data.spotify.SessionFetcher
import com.vinz.music.data.spotify.SessionToken
import com.vinz.music.data.spotify.SessionTokens
import com.vinz.music.data.spotify.SpotifyCanvas
import com.vinz.music.data.spotify.SpotifyCanvasParse
import com.vinz.music.data.spotify.SpotifyCodeBook
import com.vinz.music.data.spotify.SpotifyStore
import com.vinz.music.data.spotify.SpotifyWeb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1.8.7.6, "Spotify's canvas first" (plan-spotify-canvas.md): a song's canvas asked of Spotify with the session's reads,
 * against a made-up Spotify (trimmed answers in the shapes the A16 trial saw on 2026-11-16, nothing real). Only a sure
 * match gives a loop; a still picture, a link off Spotify's file hosts, a slow or refusing Spotify, no session, the own
 * developer app, and the switch off all leave Vinz Canvas as it is. Nothing here reaches the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SpotifyCanvasTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app = RuntimeEnvironment.getApplication()
    private val auth = app.getSharedPreferences(SpotifyStore.AUTH_PREFS, Context.MODE_PRIVATE)
    private val prefs = app.getSharedPreferences(SpotifyStore.PREFS, Context.MODE_PRIVATE)
    private val store = SpotifyStore(auth, prefs, SpotifyFixtures.ReverseBox)
    private var clock = 1_800_000_000_000L
    private val token = "tok-secret-0123456789"

    /** A made-up Spotify: answers by the GraphQL operation asked, notes every request (its host and operation). */
    private inner class FakeSpotify {
        val requests = mutableListOf<Pair<String, String>>()
        val headers = mutableMapOf<String, String>()
        @Volatile var sleepMs = 0L
        var answer: (op: String) -> Pair<Int, String> = { 404 to "{}" }
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val r = chain.request()
            val body = r.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            val op = Regex("\"operationName\":\"(\\w+)\"").find(body)?.groupValues?.get(1).orEmpty()
            synchronized(requests) { requests += r.url.host to op }
            if (sleepMs > 0) Thread.sleep(sleepMs)
            val (code, text) = if (r.url.toString() == SpotifyWeb.URL) answer(op) else 404 to "{}"
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }).build()

        fun count(op: String? = null) = synchronized(requests) { requests.count { it.first == "api-partner.spotify.com" && (op == null || it.second == op) } }
        val all get() = synchronized(requests) { requests.toList() }
    }

    private val net = FakeSpotify()
    private val tokens = SessionTokens(store, SessionFetcher { SessionFetch.Got(SessionToken(token, clock + 3_600_000)) }, now = { clock })
    private val web = SpotifyWeb(net.client, tokens, SpotifyCodeBook(prefs, net.client, now = { clock }), userAgent = { "UA" }, now = { clock }, pause = {})
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logged = mutableListOf<String>()

    /** "Spotify's canvas first", as the app reads it ([spotifyCanvasOn]): the switch and the session sign-in ([SpotifyStore.sessionIn]). */
    private var settings = AppSettings().let { it.copy(behavior = it.behavior.copy(canvas = CanvasPrefs(spotifyFirst = true))) }
    private val enabled = { spotifyCanvasOn(settings, SpotifyStore.sessionIn(auth)) }

    private fun source(file: File? = null, waitMs: Long = 4_000) = SpotifyCanvasSource(
        scope, enabled, { SpotifyCanvas { op, vars -> web.read(op, vars) } }, { web.quietUntil }, file, now = { clock }, waitMs = waitMs,
        log = { synchronized(logged) { logged += it } },
    )

    private val espresso = Song("yt-espresso", "Espresso", listOf(ArtistRef("Sabrina Carpenter")), durationSec = 175)
    private val loopUrl = "https://canvaz.scdn.co/upload/artist/abc/video/def.cnvs.mp4"

    private fun search(vararg hits: Triple<String, String, Long>) = """{"data":{"searchV2":{"tracksV2":{"items":[${hits.mapIndexed { i, (name, artist, ms) ->
        """{"item":{"data":{"__typename":"Track","uri":"spotify:track:t$i","name":"$name","artists":{"items":[{"profile":{"name":"$artist"}}]},"duration":{"totalMilliseconds":$ms}}}}"""
    }.joinToString(",")}]}}}}"""

    private fun canvas(url: String?, type: String = "VIDEO_LOOPING_RANDOM") =
        if (url == null) """{"data":{"trackUnion":{"__typename":"Track","canvas":null}}}"""
        else """{"data":{"trackUnion":{"__typename":"Track","canvas":{"url":"$url","type":"$type","fileId":"x"}}}}"""

    private val sureSearch = search(Triple("Espresso", "Sabrina Carpenter", 175_459), Triple("Espresso (Live)", "Sabrina Carpenter", 190_000))

    private fun answers(search: String = sureSearch, canvas: String = canvas(loopUrl)) {
        net.answer = { op -> when (op) { "searchDesktop" -> 200 to search; "canvas" -> 200 to canvas; else -> 404 to "{}" } }
    }

    @Before fun signedIn() = runBlocking {
        auth.edit().clear().commit()
        prefs.edit().clear().commit()
        assertTrue(tokens.begin())
        assertTrue(SpotifyStore.sessionIn(auth))
    }

    @After fun stop() = scope.cancel()

    // ---- what Spotify's answers mean ----

    @Test fun aSureMatchGivesTheLoop() = runBlocking {
        answers()
        val art = source().lookup(espresso)!!
        assertEquals(loopUrl, art.url)
        assertTrue("marked as Spotify's", art.spotify)
        assertEquals(listOf("searchDesktop", "canvas"), net.all.map { it.second })
        assertTrue("only Spotify's own host is asked", net.all.all { it.first == "api-partner.spotify.com" })
    }

    @Test fun theCanvasIsAskedForTheSureTrack() = runBlocking {
        // The first hit is another version; the matcher's sure one is the second.
        answers(search(Triple("Espresso (Acoustic)", "Sabrina Carpenter", 160_000), Triple("Espresso", "Sabrina Carpenter", 175_100)))
        val bodies = mutableListOf<String>()
        val probe = SpotifyCanvas { op, vars -> bodies += "$op $vars"; web.read(op, vars) }
        assertTrue(probe.find(espresso) is com.vinz.music.data.spotify.SpotifyCanvasAnswer.Loop)
        assertTrue(bodies.last(), bodies.last().contains("spotify:track:t1"))
    }

    @Test fun anUnsureMatchGivesVinzCanvas() = runBlocking {
        // Same title and artist, 25 s longer: another recording, not sure.
        answers(search(Triple("Espresso", "Sabrina Carpenter", 200_000)))
        assertNull(source().lookup(espresso))
        assertEquals("no canvas is asked for an unsure match", 0, net.count("canvas"))
        // Another artist altogether.
        answers(search(Triple("Espresso", "Someone Else", 175_000)))
        assertNull(source().lookup(espresso.copy(id = "other")))
        // Nothing found at all.
        answers(search())
        assertNull(source().lookup(espresso.copy(id = "none")))
        assertEquals(0, net.count("canvas"))
    }

    @Test fun aStillPictureIsNone() = runBlocking {
        answers(canvas = canvas("https://canvaz.scdn.co/upload/artist/abc/image/def.jpg", type = "IMAGE"))
        assertNull(source().lookup(espresso))
        answers(canvas = canvas(null))
        assertNull(source().lookup(espresso.copy(id = "b")))
        answers(canvas = canvas(loopUrl, type = "GIF"))
        assertNull(source().lookup(espresso.copy(id = "c")))
    }

    @Test fun aLinkOffSpotifysFileHostsIsNone() = runBlocking {
        for ((i, url) in listOf(
            "https://evil.example.com/x.mp4", "http://canvaz.scdn.co/x.mp4", "https://canvaz.scdn.co.evil.example/x.mp4",
            "https://scdn.co.example.com/x.mp4", "https://user:pw@canvaz.scdn.co/x.mp4", "https://canvaz.scdn.co:8443/x.mp4", "ftp://canvaz.scdn.co/x.mp4", "not a link",
        ).withIndex()) {
            answers(canvas = canvas(url))
            assertNull(url, source().lookup(espresso.copy(id = "s$i")))
        }
        assertTrue(SpotifyCanvasParse.isSpotifyFile(loopUrl))
        assertTrue(SpotifyCanvasParse.isSpotifyFile("https://video-fa.spotifycdn.com/x.mp4"))
    }

    // ---- when Spotify is slow, refuses or fails ----

    @Test fun aSlowSpotifyGivesVinzCanvasAndIsNotRememberedAsNone() = runBlocking {
        answers()
        net.sleepMs = 1_500
        val s = source(waitMs = 200)
        val started = System.nanoTime()
        assertNull(s.lookup(espresso))
        assertTrue("the wait is capped", (System.nanoTime() - started) / 1_000_000 < 1_200)
        assertEquals("a slow answer is not a none", 0, s.rememberedCount)
    }

    @Test fun aTooManyRequestsAnswerQuietsEveryCanvasAsk() = runBlocking {
        net.headers["Retry-After"] = "120"
        net.answer = { 429 to "" }
        val s = source()
        assertNull(s.lookup(espresso))
        assertEquals(1, net.count())
        assertEquals("a refusal is not remembered", 0, s.rememberedCount)
        // Quiet: another song is not asked about at all.
        net.headers.clear()
        answers()
        assertNull(s.lookup(espresso.copy(id = "two")))
        clock += 119_000
        assertNull(s.lookup(espresso.copy(id = "three")))
        assertEquals("nothing left during the quiet", 1, net.count())
        // Once it has passed, the asks start again.
        clock += 2_000
        assertEquals(loopUrl, s.lookup(espresso)?.url)
        assertEquals(3, net.count())
    }

    @Test fun anErrorIsNotRememberedAndPausesTheAsksForAMinute() = runBlocking {
        net.answer = { 500 to """{"errors":[{"message":"boom"}]}""" }
        val s = source()
        assertNull(s.lookup(espresso))
        assertEquals(0, s.rememberedCount)
        answers()
        assertNull("paused after an error", s.lookup(espresso))
        assertEquals(1, net.count())
        clock += SpotifyCanvasSource.ERROR_PAUSE_MS + 1
        assertEquals(loopUrl, s.lookup(espresso)?.url)
    }

    // ---- when nothing is asked at all ----

    @Test fun notSignedInAsksNothing() = runBlocking {
        answers()
        auth.edit().clear().commit()
        assertNull(source().lookup(espresso))
        // Signed out by Spotify (the session ended): nothing either.
        auth.edit().putBoolean("session_on", true).putBoolean("session_ended", true).commit()
        assertNull(source().lookup(espresso))
        assertEquals(0, net.all.size)
    }

    @Test fun theOwnDeveloperAppConnectionAsksNothing() = runBlocking {
        answers()
        auth.edit().clear().commit()
        store.saveTokens(com.vinz.music.data.spotify.SpotifyTokens("own", "ref", clock + 3_600_000, ""))
        assertTrue("connected, by the own app", store.connected)
        assertFalse(SpotifyStore.sessionIn(auth))
        assertNull(source().lookup(espresso))
        assertEquals(0, net.all.size)
    }

    @Test fun theSwitchOffAsksNothing() = runBlocking {
        answers()
        settings = AppSettings()
        assertFalse("off by default", settings.behavior.canvas.spotifyFirst)
        val s = source()
        assertNull(s.lookup(espresso))
        assertNull(s.known(espresso))
        // Paused by Performance mode (the settings Vinz uses read it as off), whatever Canvas says.
        for (canvasOn in listOf(true, false)) {
            settings = AppSettings(performanceMode = true).let { it.copy(behavior = it.behavior.copy(canvas = CanvasPrefs(spotifyFirst = true, enabled = canvasOn))) }.effective()
            assertFalse(settings.behavior.canvas.spotifyFirst)
            assertNull(s.lookup(espresso))
        }
        assertEquals(0, net.all.size)
    }

    /** 1.8.7.6, the A16 check: Spotify's canvas no longer hangs on Vinz's own Canvas being on. */
    @Test fun vinzsCanvasOffStillAsksSpotify() = runBlocking {
        answers()
        settings = AppSettings().let { it.copy(behavior = it.behavior.copy(canvas = CanvasPrefs(spotifyFirst = true, enabled = false, covers = false))) }
        assertEquals(loopUrl, source().lookup(espresso)?.url)
        assertEquals(2, net.count())
    }

    @Test fun notASongAsksNothing() = runBlocking {
        answers()
        val s = source()
        assertNull(s.lookup(espresso.copy(id = Song.LOCAL_PREFIX + "1")))
        assertNull(s.lookup(espresso.copy(id = "e1", isEpisode = true)))
        assertEquals(0, net.all.size)
    }

    // ---- remembered answers ----

    @Test fun aLoopIsRememberedForADay() = runBlocking {
        answers()
        val s = source()
        assertEquals(loopUrl, s.lookup(espresso)?.url)
        assertEquals(loopUrl, s.known(espresso)?.url)
        clock += SpotifyCanvasSource.LOOP_MS - 1
        assertEquals(loopUrl, s.lookup(espresso)?.url)
        assertEquals("asked once", 2, net.count())
        clock += 2
        assertNull("too old to show without asking", s.known(espresso))
        assertEquals(loopUrl, s.lookup(espresso)?.url)
        assertEquals("asked again after a day", 4, net.count())
    }

    @Test fun noneAndNoSureMatchAreRememberedForAWeek() = runBlocking {
        answers(canvas = canvas(null))
        val s = source()
        assertNull(s.lookup(espresso))
        answers(search(Triple("Espresso", "Someone Else", 175_000)))
        val other = espresso.copy(id = "unsure")
        assertNull(s.lookup(other))
        val asked = net.count()
        assertEquals(3, asked)
        answers()
        clock += 6 * 86_400_000L
        assertNull(s.lookup(espresso))
        assertNull(s.lookup(other))
        assertEquals("both still remembered", asked, net.count())
        clock += 86_400_000L + 1
        assertEquals(loopUrl, s.lookup(espresso)?.url)
        assertEquals(loopUrl, s.lookup(other)?.url)
    }

    @Test fun theMemoryIsKeptInAFileWithNothingAboutTheAccount() = runBlocking {
        answers()
        val file = File(tmp.root, "spotify_canvas.json")
        assertEquals(loopUrl, source(file).lookup(espresso)?.url)
        val end = System.currentTimeMillis() + 5_000
        while (!file.exists() && System.currentTimeMillis() < end) Thread.sleep(20)
        val text = file.readText()
        assertTrue(text, text.contains(loopUrl))
        for (secret in listOf(token, "Bearer", "spotify:track:")) assertFalse(secret, text.contains(secret))
        // Read back by the next start.
        val again = source(file)
        val until = System.currentTimeMillis() + 5_000
        while (again.known(espresso) == null && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(loopUrl, again.known(espresso)?.url)
        again.forget()
        assertNull(again.known(espresso))
    }

    @Test fun noTokenInTheLog() = runBlocking {
        net.answer = { 500 to """{"errors":[{"message":"bad token $token"}]}""" }
        source().lookup(espresso)
        net.headers["Retry-After"] = "5"
        net.answer = { 429 to "" }
        clock += SpotifyCanvasSource.ERROR_PAUSE_MS + 1
        source().lookup(espresso.copy(id = "x"))
        assertTrue(logged.isNotEmpty())
        assertTrue(logged.toString(), logged.none { token in it || "Bearer" in it || "boom" in it || "bad token" in it })
    }

    // ---- the repository: Spotify first, then Vinz Canvas as it always was ----

    private fun repo() = CanvasRepository(net.client).also { it.spotify = source() }
    private fun vinzAsked() = net.all.count { it.first != "api-partner.spotify.com" }

    @Test fun theRepositoryShowsSpotifysLoopFirst() = runBlocking {
        answers()
        val r = repo()
        assertEquals(loopUrl, r.canvasFor(espresso)?.url)
        assertEquals("Vinz's sources not asked when Spotify has a loop", 0, vinzAsked())
        assertEquals(loopUrl, r.cached(espresso)?.url)
        assertTrue(r.spotifyAsked())
    }

    @Test fun whenSpotifyHasNoneTheExistingOrderRuns() = runBlocking {
        answers(canvas = canvas(null))
        val r = repo()
        assertNull(r.canvasFor(espresso))
        assertTrue("Apple, Tidal, the community index were asked", vinzAsked() > 0)
    }

    @Test fun withTheSwitchOffTheRepositoryNeverAsksSpotify() = runBlocking {
        answers()
        settings = AppSettings()
        val r = repo()
        assertNull(r.canvasFor(espresso))
        assertNull(r.cached(espresso))
        assertFalse(r.spotifyAsked())
        assertEquals("zero Spotify requests", 0, net.count())
        assertTrue("Vinz Canvas looked as before", vinzAsked() > 0)
    }

    @Test fun whereSpotifyMayNotBeFetchedOnlyVinzCanvasIsAsked() = runBlocking {
        answers()
        val r = repo()
        assertNull(r.canvasFor(espresso, spotifyOk = false))
        assertEquals(0, net.count())
    }

    @Test fun spotifysLoopIsNeverSaved() = runBlocking {
        answers()
        val r = repo()
        r.canvasFor(espresso)
        assertNull("saves and downloads look at Vinz's own covers only", r.vinzCoverFor(espresso))
        assertEquals(1, net.count("canvas"))
        assertTrue("nothing of Spotify's in the saved clips", r.savedClipUrls().isEmpty())
    }

    @Test fun theCoversPlaceIsWantedForSpotifysCanvas() {
        val off = AppSettings().let { it.copy(behavior = it.behavior.copy(canvas = CanvasPrefs(covers = false))) }
        assertFalse(canvasCoverWanted(off, coverHidden = false))
        assertTrue("Animated covers off, Spotify's canvas on", canvasCoverWanted(off, coverHidden = false, spotify = true))
        // Owner, 2026-11-16: each of the song's switches on its own. "Animated cover" off leaves Spotify's canvas;
        // "Spotify canvas" off leaves Vinz's animated cover.
        assertTrue("\"Animated cover\" off for the song: Spotify's still shows", canvasCoverWanted(off, coverHidden = true, spotify = true))
        assertFalse("\"Spotify canvas\" off for the song", canvasCoverWanted(off, coverHidden = false, spotify = true, spotifyHidden = true))
        assertTrue("and Vinz's animated cover still may", canvasCoverWanted(AppSettings(), coverHidden = false, spotify = true, spotifyHidden = true))
        assertFalse("both off", canvasCoverWanted(AppSettings(), coverHidden = true, spotify = true, spotifyHidden = true))
        // 1.8.7.6: Canvas off as a whole too (both of Vinz's switches off): Spotify's canvas still has the cover's place.
        val allOff = off.copy(behavior = off.behavior.copy(canvas = off.behavior.canvas.copy(enabled = false, covers = true)))
        assertTrue("Canvas off", canvasCoverWanted(allOff, false, spotify = true))
        assertFalse("and nothing changes with Spotify's off", canvasCoverWanted(allOff, false))
        assertFalse(vinzCoversOn(allOff))
    }

    @Test fun diagnosticsNameSpotifyAndItsHostOnly() {
        val stored = AppSettings().let { it.copy(behavior = it.behavior.copy(canvas = CanvasPrefs(spotifyFirst = true))) }
        val text = canvasDiagnostics(stored, stored, false, false, espresso, false, CanvasArtwork(loopUrl, spotify = true), null, emptyList(), clock, spotify = true)
        assertTrue(text, text.lines()[0].endsWith(", Spotify's canvas first on"))
        assertTrue(text, text.lines()[1].startsWith("Canvas now: Spotify's canvas from canvaz.scdn.co"))
        assertFalse(text, text.contains("/upload/"))
        // Vinz's own Canvas off: Spotify's canvas is still named; with Spotify's off, as before.
        val off = stored.copy(behavior = stored.behavior.copy(canvas = stored.behavior.canvas.copy(enabled = false)))
        val offText = canvasDiagnostics(off, off, false, false, espresso, false, CanvasArtwork(loopUrl, spotify = true), null, emptyList(), clock, spotify = true)
        assertTrue(offText, offText.lines()[1].startsWith("Canvas now: Spotify's canvas from canvaz.scdn.co"))
        val none = canvasDiagnostics(off, off, false, false, espresso, false, null, null, emptyList(), clock, spotify = false)
        assertEquals("Canvas now: still cover (Canvas off)", none.lines()[1])
    }

    // ---- the player ([playerCanvas]): what shows for each way Vinz's two switches can be set ----

    private val videoLoop = CanvasArtwork("clip:v1", clip = VideoClip("v1", 10_000, 18_000, 720))
    private val bohemian = Song("yt-bohemian", "Bohemian Rhapsody", listOf(ArtistRef("Queen")), durationSec = 354)
    private val bohemianSearch = search(Triple("Bohemian Rhapsody", "Queen", 354_320))

    /** The settings Vinz uses for these two switches (both off keeps their flags, as the page stores it) and Spotify's. */
    private fun switches(covers: Boolean, videos: Boolean, spotify: Boolean, videoFirst: Boolean = false, performance: Boolean = false): AppSettings {
        val k = com.vinz.music.data.prefs.CanvasKinds(covers, videos).toStored(com.vinz.music.data.prefs.CanvasStored(true, true, true))
        return AppSettings(performanceMode = performance).let {
            it.copy(behavior = it.behavior.copy(canvas = CanvasPrefs(enabled = k.enabled, covers = k.covers, useVideos = k.videos, videoFirst = videoFirst, spotifyFirst = spotify)))
        }.effective()
    }

    private class Player { var shown: CanvasArtwork? = null; val history = mutableListOf<CanvasArtwork?>() }

    /** The player for [song] with [s], as NowPlaying wires it: the loop from the music video is [videoLoop]. */
    private fun play(song: Song, s: AppSettings, r: CanvasRepository, coverHidden: Boolean = false, spotifyHidden: Boolean = false, choice: CanvasSource? = null): Player = runBlocking {
        settings = s
        r.vinzCovers = { vinzCoversOn(settings) }
        val p = Player()
        val videos = s.behavior.canvas.enabled && s.behavior.canvas.useVideos
        val loopFirst = videos && playerLoopFirst(s, false, choice, hidden = false, isSong = true, spotifyTried = r.spotifyAsked() && !spotifyHidden)
        playerCanvas(
            s, song, r, coverHidden = coverHidden, metered = false, coverAllowed = true, loopFirst = loopFirst,
            loop = { hasCover, spotifys -> if (videos && canvasWant(if (spotifys) spotifyBeforeVideo(s) else s, false, hasCover, choice, false, true).video) videoLoop else null },
            shown = { p.shown }, show = { p.shown = it; p.history += it },
            spotifyHidden = spotifyHidden,
        )
        p
    }

    /** Owner, 2026-11-16: "Spotify canvas" off for a song asks Spotify nothing for it and leaves Vinz's own two. */
    @Test fun theSongsSpotifySwitchOffLeavesVinzsOwn() {
        answers()
        for ((covers, videos) in listOf(true to true, true to false, false to true, false to false)) {
            val case = "covers $covers, videos $videos"
            clearRequests()
            val p = play(espresso, switches(covers, videos, spotify = true), repo(4_000), spotifyHidden = true)
            assertEquals("$case: zero Spotify requests", 0, net.count())
            assertTrue("$case: Spotify's loop never up", p.history.none { it?.spotify == true })
            assertEquals(case, if (videos) videoLoop else null, p.shown)
            assertEquals("$case: Vinz's animated covers as without Spotify", covers, vinzAsked() > 0)
        }
    }

    /** And "Animated cover" off for a song leaves Spotify's canvas: it is Vinz's own cover that switch is for. */
    @Test fun theSongsAnimatedCoverSwitchOffLeavesSpotifysCanvas() {
        for ((covers, videos) in listOf(true to true, true to false, false to true, false to false)) {
            val case = "covers $covers, videos $videos"
            val s = switches(covers, videos, spotify = true)
            answers()
            clearRequests()
            assertEquals(case, loopUrl, play(espresso, s, repo(4_000), coverHidden = true).shown?.url)
            assertEquals("$case: Vinz's cover not looked up", 0, vinzAsked())
            // A song Spotify has none for: Vinz's animated cover stays off for it.
            answers(bohemianSearch, canvas(null))
            clearRequests()
            assertEquals(case, if (videos) videoLoop else null, play(bohemian, s, repo(4_000), coverHidden = true).shown)
            assertEquals("$case: Vinz's cover not looked up", 0, vinzAsked())
        }
    }

    private fun repo(waitMs: Long) = CanvasRepository(net.client).also { it.spotify = source(waitMs = waitMs) }
    private fun clearRequests() = synchronized(net.requests) { net.requests.clear() }

    @Test fun eachWayVinzsSwitchesAreSetSpotifysCanvasShowsWithItsSwitchOn() {
        for ((covers, videos) in listOf(true to true, true to false, false to true, false to false)) {
            val case = "covers $covers, videos $videos"
            val s = switches(covers, videos, spotify = true)
            // A song Spotify has a canvas for: it shows, and Vinz's own sources aren't asked.
            answers()
            clearRequests()
            assertEquals(case, loopUrl, play(espresso, s, repo(4_000)).shown?.url)
            assertEquals(case, 2, net.count())
            assertEquals(case, 0, vinzAsked())
            // A song it has none for: what Vinz shows without Spotify (the video's loop, else the still cover).
            answers(bohemianSearch, canvas(null))
            clearRequests()
            val shown = play(bohemian, s, repo(4_000)).shown
            assertEquals(case, if (videos) videoLoop else null, shown)
            assertEquals(case, 2, net.count())
            assertEquals("$case: Vinz's animated covers looked up only while they are on", covers, vinzAsked() > 0)
        }
    }

    @Test fun eachWayVinzsSwitchesAreSetSpotifysOffAsksSpotifyNothing() {
        answers()
        for ((covers, videos) in listOf(true to true, true to false, false to true, false to false)) {
            val case = "covers $covers, videos $videos"
            clearRequests()
            val shown = play(espresso, switches(covers, videos, spotify = false), repo(4_000)).shown
            assertEquals("$case: zero Spotify requests", 0, net.count())
            assertEquals(case, if (videos) videoLoop else null, shown)
            assertEquals("$case: Vinz's animated covers as before", covers, vinzAsked() > 0)
        }
    }

    @Test fun performanceModePausesSpotifysCanvasWhateverTheSwitches() {
        answers()
        for ((covers, videos) in listOf(true to true, true to false, false to true, false to false)) {
            clearRequests()
            val p = play(espresso, switches(covers, videos, spotify = true, performance = true), repo(4_000))
            assertNull(p.shown)
            assertEquals(0, net.count())
        }
    }

    /**
     * The A16 check (2026-11-16): Canvas off, Animated covers on, Spotify's on; Espresso showed the still cover. Spotify
     * was asked, but the first answer after a start took 7.7 s (the connection built and its token fetched), past the
     * player's 4 s; it was remembered and only showed once something made the player look again (Canvas switched on).
     * A late loop now shows when it comes.
     */
    @Test fun aLateAnswerStillShows() {
        answers()
        net.sleepMs = 1_000
        val p = play(espresso, switches(covers = true, videos = false, spotify = true), repo(waitMs = 200))
        assertEquals(loopUrl, p.shown?.url)
        assertTrue("Vinz carried on meanwhile", vinzAsked() > 0)
    }

    @Test fun aLateAnswerTakesTheVideoLoopsPlaceUnlessTheVideoComesFirst() {
        answers()
        net.sleepMs = 1_000
        // No animated cover: the video's loop shows while Spotify is slow, then Spotify's canvas in its place.
        val p = play(espresso, switches(covers = false, videos = true, spotify = true), repo(waitMs = 200))
        assertEquals(loopUrl, p.shown?.url)
        assertTrue(p.history.toString(), videoLoop in p.history)
        // The listener put the video first for this song: once its loop is up it stays.
        val first = play(espresso.copy(id = "yt-espresso-2"), switches(covers = false, videos = true, spotify = true), repo(waitMs = 200), choice = CanvasSource.VIDEO)
        assertEquals(videoLoop, first.shown)
    }

    /**
     * Owner, 2026-11-16: "Spotify canvas is not being used first even with the option selected". With "When a song has
     * both: music video first" the video's loop won for every song. Spotify's canvas is tried first for every song; only
     * a song set to Vinz Canvas by hand shows its video, and the next song tries Spotify again.
     */
    @Test fun spotifysCanvasComesBeforeTheVideoUnlessTheSongItselfIsSetToIt() {
        for (covers in listOf(true, false)) {
            val s = switches(covers, videos = true, spotify = true, videoFirst = true)
            answers()
            assertEquals("video first in settings: still Spotify's", loopUrl, play(espresso, s, repo(4_000)).shown?.url)
            // This song set to Vinz Canvas from its menu: its video.
            val set = play(espresso, s, repo(4_000), choice = CanvasSource.VIDEO)
            assertEquals(videoLoop, set.shown)
            assertTrue("and Spotify's never took its place", set.history.none { it?.spotify == true })
            // The next song, nothing set for it: Spotify's again.
            assertEquals("the next song", loopUrl, play(espresso.copy(id = "yt-espresso-next"), s, repo(4_000)).shown?.url)
            // A song Spotify has none for: the video, as the setting says.
            answers(bohemianSearch, canvas(null))
            assertEquals(videoLoop, play(bohemian, s, repo(4_000)).shown)
        }
        // Slow Spotify with video first in settings: the video shows meanwhile, then Spotify's takes its place.
        answers()
        net.sleepMs = 1_000
        val late = play(espresso.copy(id = "yt-espresso-late"), switches(covers = false, videos = true, spotify = true, videoFirst = true), repo(waitMs = 200))
        assertEquals(loopUrl, late.shown?.url)
        // Spotify canvas switched off for a song: the setting counts again.
        net.sleepMs = 0
        assertTrue(playerLoopFirst(switches(true, true, spotify = true, videoFirst = true), false, null, hidden = false, isSong = true, spotifyTried = false))
        assertFalse(playerLoopFirst(switches(true, true, spotify = true, videoFirst = true), false, null, hidden = false, isSong = true, spotifyTried = true))
    }
}
