package com.vinz.music

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.data.innertube.InnerTube
import com.vinz.music.data.prefs.SettingsRepository
import com.vinz.music.data.stream.StreamResolver
import com.vinz.music.data.stream.StreamWatch
import com.vinz.music.data.stream.WayMemory
import com.vinz.music.data.stream.YouTubeStreamInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * GitHub #514: googlevideo answered HTTP 503 to ranged requests on visionOS links (39 and 42 times in a reporter's two
 * logs). Only 403/410 counted as a refusal, so the dead link was kept and asked for again and again: the song sat
 * buffering, a download retried the same link for half an hour, a music video fell back to its song. Now a 5xx is retried
 * quickly a few times; when it keeps coming the link is forgotten and the next lookup asks another client first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamServerBusy514Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ------------------------------------------------------------------ the stream's own quick retries

    /** googlevideo: answers each request with the next of [codes] (200 when they run out), [clen] bytes in all. */
    private class Googlevideo(private val clen: Long, vararg codes: Int) {
        private val codes = ArrayDeque(codes.toList())
        val asked = AtomicInteger()
        val interceptor = Interceptor { chain ->
            asked.incrementAndGet()
            val req = chain.request()
            val code = synchronized(this.codes) { this.codes.removeFirstOrNull() ?: 200 }
            val range = req.url.queryParameter("range") ?: "0-"
            val from = range.substringBefore('-').toLong()
            val to = range.substringAfter('-').toLongOrNull() ?: (clen - 1)
            val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fake")
            if (code != 200) b.body(ByteArray(0).toResponseBody(null)).build()
            else b.header("Content-Length", (to - from + 1).toString()).body(ByteArray((to - from + 1).toInt()) { 7 }.toResponseBody(null)).build()
        }
    }

    private val link = "https://rr1---sn-test.googlevideo.com/videoplayback?id=o-test&ei=e1&itag=251&expire=9999999999&clen=10&c=VISIONOS"

    private fun httpCode(e: Throwable): Int? = generateSequence(e) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode

    @Test fun a503OnceIsRetriedQuicklyAndTheStreamComes() {
        val g = Googlevideo(10, 503)
        val told = mutableListOf<Int>()
        val http = OkHttpClient.Builder().addInterceptor(YouTubeStreamInterceptor(watch = StreamWatch { _, code, _ -> told += code }))
            .addInterceptor(g.interceptor).build()
        val s = OkHttpDataSource.Factory(http).createDataSource()
        assertEquals(10L, s.open(DataSpec.Builder().setUri(Uri.parse(link)).build()))
        val buf = ByteArray(10)
        assertEquals(10, s.read(buf, 0, 10))
        s.close()
        assertEquals("asked once more", 2, g.asked.get())
        assertEquals("the watcher hears of the answer that counts only", listOf(200), told)
    }

    @Test fun a503ThatKeepsComingIsGivenUpOnAfterABoundedNumberOfTries() {
        val g = Googlevideo(10, *IntArray(20) { 503 })
        val told = mutableListOf<Int>()
        val http = OkHttpClient.Builder().addInterceptor(YouTubeStreamInterceptor(watch = StreamWatch { _, code, _ -> told += code }))
            .addInterceptor(g.interceptor).build()
        val s = OkHttpDataSource.Factory(http).createDataSource()
        val started = System.nanoTime()
        try {
            s.open(DataSpec.Builder().setUri(Uri.parse(link)).build())
            fail("a server that keeps answering 503 gave nothing")
        } catch (e: IOException) {
            assertEquals(503, httpCode(e))
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("the first ask and two quick retries, no more", 3, g.asked.get())
        assertEquals("told once, as given up on", listOf(503), told)
        assertTrue("quick: $tookMs ms", tookMs < 3_000)
    }

    @Test fun aLaterChunkThatGets503OnceStillComes() {
        val chunk = 1024L
        val clen = 4 * chunk
        // The first chunk comes from the player's own client; the later ones from the chunk client, the second of them 503 once.
        val first = Googlevideo(clen)
        val later = Googlevideo(clen, 200, 503)
        val http = OkHttpClient.Builder().addInterceptor(YouTubeStreamInterceptor(OkHttpClient.Builder().addInterceptor(later.interceptor).build(), chunk))
            .addInterceptor(first.interceptor).build()
        val s = OkHttpDataSource.Factory(http).createDataSource()
        val url = "https://rr1---sn-test.googlevideo.com/videoplayback?id=o-test&itag=251&clen=$clen&c=VISIONOS"
        assertEquals(clen, s.open(DataSpec.Builder().setUri(Uri.parse(url)).build()))
        var got = 0L
        val buf = ByteArray(4096)
        while (true) {
            val n = s.read(buf, 0, buf.size)
            if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) break
            got += n
        }
        s.close()
        assertEquals(clen, got)
    }

    @Test fun a403IsRetriedOnceAsBefore() {
        val g = Googlevideo(10, 403, 403, 403)
        val http = OkHttpClient.Builder().addInterceptor(YouTubeStreamInterceptor()).addInterceptor(g.interceptor).build()
        val s = OkHttpDataSource.Factory(http).createDataSource()
        try {
            s.open(DataSpec.Builder().setUri(Uri.parse(link)).build())
            fail("refused")
        } catch (e: IOException) {
            assertEquals(403, httpCode(e))
        }
        assertEquals("one quick retry, as in 1.8.7", 2, g.asked.get())
    }

    // ------------------------------------------------------------------ the resolver: another client next

    private var signedIn: SettingsRepository? = null

    /** The settings store is shared with the JVM's other tests: this sign-in doesn't stay in it. */
    @After fun signOut() {
        val s = signedIn ?: return
        s.logout()
        runBlocking { withTimeout(10_000) { s.flow.first { !it.isLoggedIn } } }
    }

    /** A YouTube where every client answers with a link of its own (its `c=` says which); NewPipe gets nothing. */
    private inner class FakeYouTube(account: Boolean) {
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val answer = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            val text = when {
                path.endsWith("/player") -> {
                    val buf = okio.Buffer().also { req.body?.writeTo(it) }
                    val body = InnerTube.json.parseToJsonElement(buf.readUtf8()).jsonObject
                    val client = body["context"]!!.jsonObject["client"]!!.jsonObject
                    val name = client["clientName"]!!.toString().trim('"').let { n -> if (n == "TVHTML5" && "deviceMake" in client) "TV_SAMSUNG" else n }
                    val id = body["videoId"].toString().trim('"')
                    asked += "$name $id"
                    playable(id, name, answer.incrementAndGet())
                }
                path.contains("visitor_id") -> """{"responseContext":{"visitorData":"v"}}"""
                else -> "{}"
            }
            val ok = path.endsWith("/player") || path.contains("visitor_id")
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(if (ok) 200 else 404).message("fake")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }).build()
        val settings = SettingsRepository(context, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val streams: StreamResolver

        init {
            context.getSharedPreferences(WayMemory.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            runBlocking {
                if (account) {
                    signedIn = settings
                    settings.saveAccount("SAPISID=abc; __Secure-3PAPISID=abc", "visitor", "user||")
                }
                settings.setPremiumStream(false)
                withTimeout(10_000) { settings.flow.first { it.isLoggedIn == account && !it.premiumStream } }
            }
            streams = StreamResolver(context, http, settings, InnerTube(http, { settings.current }))
            streams.networkNow = { "net-1" }
        }

        fun direct() = asked.count { it.startsWith("VISIONOS") }

        private fun playable(id: String, client: String, n: Int): String {
            val url = "https://rr1---sn-test.googlevideo.com/videoplayback?id=o-$id&ei=answer$n&itag=251&expire=9999999999&clen=1000&c=$client"
            return """{"playabilityStatus":{"status":"OK"},"streamingData":{"expiresInSeconds":"21600","adaptiveFormats":[""" +
                """{"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","url":"$url","averageBitrate":"130000","contentLength":"1000","approxDurationMs":"200000"}""" +
                """]},"videoDetails":{"lengthSeconds":"200"}}"""
        }
    }

    @Test fun aLinkWhoseServerKeeps503IsForgottenAndAnotherClientIsAsked() {
        val yt = FakeYouTube(account = true)
        val first = yt.streams.resolve("songA")
        assertEquals("VISIONOS", first.client)
        // The stream's quick retries are used up: the interceptor tells the resolver of the 503 that counts.
        yt.streams.fetched(first.audioUrl.toHttpUrl(), 503, null)
        val next = yt.streams.resolve("songA")
        assertNotEquals("a fresh link", first.audioUrl, next.audioUrl)
        assertNotEquals("from another client than the visionOS app: ${yt.asked}", "VISIONOS", next.client)
        assertEquals("the visionOS app isn't asked again for it", 1, yt.direct())
        // Another song isn't touched: the usual quick way still comes first.
        assertEquals("VISIONOS", yt.streams.resolve("songB").client)
    }

    @Test fun signedOutTheOtherWaysAreTriedThenTheUsualOneAgainNeverAnEndlessRound() {
        val yt = FakeYouTube(account = false)
        val first = yt.streams.resolve("songA")
        yt.streams.fetched(first.audioUrl.toHttpUrl(), 503, null)
        // NewPipe can't answer here, and there's no account: the visionOS app again, with a fresh link, asked once.
        val next = yt.streams.resolve("songA")
        assertNotEquals(first.audioUrl, next.audioUrl)
        assertEquals(2, yt.direct())
        // Its server says 503 again: once more a fresh link, still a bounded number of asks per lookup.
        yt.streams.fetched(next.audioUrl.toHttpUrl(), 503, null)
        val third = yt.streams.resolve("songA")
        assertNotEquals(next.audioUrl, third.audioUrl)
        assertEquals(3, yt.direct())
    }

    @Test fun a403ToldByTheStreamIsAsBefore() {
        val yt = FakeYouTube(account = true)
        val first = yt.streams.resolve("songA")
        yt.streams.fetched(first.audioUrl.toHttpUrl(), 403, null)
        assertEquals("a 403 is the player's to handle, as in 1.8.7: the link stays", first.audioUrl, yt.streams.resolve("songA").audioUrl)
        assertEquals(listOf("VISIONOS songA"), yt.asked.toList())
    }

    @Test fun thePlayersOwn5xxRetryAsksAnotherClientAndA403StillTakesTheIpv4Step() {
        val yt = FakeYouTube(account = true)
        assertEquals("VISIONOS", yt.streams.resolve("songA").client)
        // PlaybackService after a 503 the stream gave up on (told twice: once is noted).
        yt.streams.invalidate("songA", refused = false, unavailable = true)
        yt.streams.invalidate("songA", refused = false, unavailable = true)
        assertNotEquals("VISIONOS", yt.streams.resolve("songA").client)
        assertEquals(1, yt.direct())
        // A 403 on another song: as in 1.8.7, the same way once more over IPv4 only.
        yt.streams.resolve("songB")
        yt.streams.invalidate("songB", refused = true)
        val again = yt.streams.resolve("songB")
        assertEquals("VISIONOS", again.client)
        assertTrue(com.vinz.music.data.stream.Ipv4Links.isMarked(again.audioUrl))
    }

    @Test fun aMusicVideosRetryAfterA5xxAsksAnotherWayAndA403sIsAsBefore() {
        val busy = com.vinz.music.playback.videoRetry(503, unreadable = false, network = true, lastRetryAt = null, now = 1_000)
        assertEquals(com.vinz.music.playback.VideoRetry(forgetCachedSound = false, serverBusy = true), busy)
        assertEquals("a 5xx counts whatever its error code", true, com.vinz.music.playback.videoRetry(502, false, network = false, lastRetryAt = null, now = 1_000)?.serverBusy)
        assertEquals(com.vinz.music.playback.VideoRetry(forgetCachedSound = false, serverBusy = false), com.vinz.music.playback.videoRetry(403, false, false, null, 1_000))
        assertEquals("still once a minute", null, com.vinz.music.playback.videoRetry(503, false, true, lastRetryAt = 1_000, now = 2_000))
        assertTrue(com.vinz.music.data.stream.serverBusy(503))
        assertTrue(!com.vinz.music.data.stream.serverBusy(403) && !com.vinz.music.data.stream.serverBusy(410) && !com.vinz.music.data.stream.serverBusy(null))
    }

    @Test fun aBusyWayIsPassedOverForTenMinutesOnly() {
        var now = 0L
        val b = com.vinz.music.data.stream.BusyWays { now }
        b.note("songA", "visionOS")
        b.note("songA", "NewPipe")
        assertEquals(setOf("visionOS", "NewPipe"), b.of("songA"))
        assertEquals(emptySet<String>(), b.of("songB"))
        now += com.vinz.music.data.stream.BusyWays.FOR_MS
        assertEquals(emptySet<String>(), b.of("songA"))
        assertEquals(StreamResolver.AccountClients.order, StreamResolver.AccountClients.lastly(emptySet()))
        assertEquals(com.vinz.music.data.innertube.InnerTube.PlayerClient.TV_SAMSUNG,
            StreamResolver.AccountClients.lastly(setOf("account:TV_SAMSUNG")).last())
    }

    @Test fun playbackOpensAgainAtTheSamePlaceOnAnotherClientsLink() {
        val yt = FakeYouTube(account = true)
        // googlevideo: every visionOS link's server answers 503; the others play.
        val gAsked = mutableListOf<String>()
        val googlevideo = Interceptor { chain ->
            val req = chain.request()
            val c = req.url.queryParameter("c")
            synchronized(gAsked) { gAsked += "$c ${req.url.queryParameter("range")}" }
            val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
            if (c == "VISIONOS") return@Interceptor b.code(503).message("Service Unavailable").body(ByteArray(0).toResponseBody(null)).build()
            val range = req.url.queryParameter("range") ?: "0-"
            val from = range.substringBefore('-').toLong()
            val to = range.substringAfter('-').toLongOrNull() ?: 999
            b.code(200).message("OK").header("Content-Length", (to - from + 1).toString())
                .body(ByteArray((to - from + 1).toInt()) { 7 }.toResponseBody(null)).build()
        }
        val watch = StreamWatch { url, code, family -> yt.streams.fetched(url, code, family) }
        val streamHttp = OkHttpClient.Builder().addInterceptor(YouTubeStreamInterceptor(watch = watch)).addInterceptor(googlevideo).build()
        // As the player reads a song: vinz://audio/<id> resolved on each open, as Media3's retry opens it again.
        val source = ResolvingDataSource(OkHttpDataSource.Factory(streamHttp).createDataSource(), object : ResolvingDataSource.Resolver {
            override fun resolveDataSpec(dataSpec: DataSpec): DataSpec = dataSpec.buildUpon().setUri(yt.streams.resolve("songA").audioUrl).build()
        })
        val spec = DataSpec.Builder().setUri("vinz://audio/songA").setPosition(600).build()
        try {
            source.open(spec)
            fail("the visionOS link's server said 503")
        } catch (e: IOException) {
            assertEquals(503, httpCode(e))
        }
        source.close()
        // Media3's own retry opens it again from where it was: another client's link, and the bytes come.
        assertEquals(400L, source.open(spec))
        val buf = ByteArray(400)
        var got = 0
        while (got < 400) {
            val n = source.read(buf, got, 400 - got)
            if (n < 0) break
            got += n
        }
        source.close()
        assertEquals(400, got)
        assertEquals("three asks of the 503 link at most, then one of the next: $gAsked", 4, gAsked.size)
        assertTrue(gAsked.toString(), gAsked.take(3).all { it.startsWith("VISIONOS ") } && !gAsked.last().startsWith("VISIONOS "))
        assertEquals("from byte 600 on, both times", 4, gAsked.count { it.endsWith(" 600-") })
    }
}
