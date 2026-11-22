package com.vinz.music

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.data.innertube.InnerTube
import com.vinz.music.data.model.Song
import com.vinz.music.data.prefs.SettingsRepository
import com.vinz.music.data.stream.Ipv4Links
import com.vinz.music.data.stream.Ipv4OnlyDns
import com.vinz.music.data.stream.NetFamily
import com.vinz.music.data.stream.SongStreamException
import com.vinz.music.data.stream.StreamException
import com.vinz.music.data.stream.StreamResolver
import com.vinz.music.data.stream.StreamWatch
import com.vinz.music.data.stream.StreamWay
import com.vinz.music.data.stream.WayMemory
import com.vinz.music.data.stream.YouTubeStreamInterceptor
import com.vinz.music.download.DownloadFallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * GitHub #408 (1.8.7): a listener's links refused (403) on one Wi-Fi while mobile data worked. The diagnostics now say
 * which address family (only that: v4 or v6) each link was asked for over, made for and fetched over; after a refusal the
 * same way is asked once more over IPv4 only, link and stream alike; the way that then plays is remembered per network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamPath408Test {
    @get:Rule val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A YouTube where only the visionOS player answers, each time with a link of its own (made for a v4 address). */
    private inner class FakeYouTube {
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val answer = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            val text = when {
                path.endsWith("/player") -> {
                    val buf = okio.Buffer().also { req.body?.writeTo(it) }
                    val body = InnerTube.json.parseToJsonElement(buf.readUtf8()).jsonObject
                    val client = body["context"]!!.jsonObject["client"]!!.jsonObject["clientName"].toString().trim('"')
                    val id = body["videoId"].toString().trim('"')
                    asked += "$client $id"
                    if (client == "VISIONOS") playable(id, answer.incrementAndGet())
                    else """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"nope"}}"""
                }
                path.contains("visitor_id") -> """{"responseContext":{"visitorData":"v"}}"""
                else -> "{}"
            }
            val ok = path.endsWith("/player") || path.contains("visitor_id")
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(if (ok) 200 else 404).message("fake")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }).build()
        val settings = SettingsRepository(context, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val streams = StreamResolver(context, http, settings, InnerTube(http, { settings.current }))
        var network = "net-1"

        init {
            context.getSharedPreferences(WayMemory.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            streams.networkNow = { network }
        }

        fun direct() = asked.count { it.startsWith("VISIONOS") }

        private fun playable(id: String, n: Int): String {
            val url = "https://rr1---sn-test.googlevideo.com/videoplayback?id=o-$id&ei=answer$n&itag=251&expire=9999999999&ip=203.0.113.7&clen=1000"
            return """{"playabilityStatus":{"status":"OK"},"streamingData":{"expiresInSeconds":"21600","adaptiveFormats":[""" +
                """{"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","url":"$url","averageBitrate":"130000","contentLength":"1000","approxDurationMs":"200000"}""" +
                """]},"videoDetails":{"lengthSeconds":"200"}}"""
        }
    }

    // ------------------------------------------------------------------ the ladder

    @Test fun nothingRefusedTheOrderIsAsBefore() {
        val yt = FakeYouTube()
        val a = yt.streams.resolve("songA")
        val b = yt.streams.resolve("songB")
        assertEquals(listOf("VISIONOS songA", "VISIONOS songB"), yt.asked.toList())
        assertFalse("not over IPv4 only by default", Ipv4Links.isMarked(a.audioUrl) || Ipv4Links.isMarked(b.audioUrl))
    }

    @Test fun afterA403TheSameWayIsAskedOverIpv4OnlyThenTheLadderMovesOn() {
        val yt = FakeYouTube()
        assertFalse(Ipv4Links.isMarked(yt.streams.resolve("songA").audioUrl))
        // Told twice (a picture's data source and the player both tell): one step, not two.
        yt.streams.invalidate("songA", refused = true)
        yt.streams.invalidate("songA", refused = true)
        val again = yt.streams.resolve("songA")
        assertEquals("the direct way once more", 2, yt.direct())
        assertTrue("its link is fetched over IPv4 only: ${again.audioUrl}", Ipv4Links.isMarked(again.audioUrl))
        yt.streams.invalidate("songA", refused = true)
        // Refused over IPv4 too: the direct way is done with for this song (NewPipe next, which this YouTube refuses).
        try {
            yt.streams.resolve("songA")
            fail("nothing else answers here")
        } catch (e: StreamException) {
            Unit
        }
        assertEquals("not asked directly a third time", 2, yt.direct())
    }

    @Test fun theWayThatPlayedIsTriedFirstOnThatNetworkOnlyAndForgottenWhenRefused() {
        val yt = FakeYouTube()
        yt.streams.resolve("songA")
        yt.streams.invalidate("songA", refused = true)
        val v4 = yt.streams.resolve("songA").audioUrl
        // Its bytes came: the way is remembered for this network.
        yt.streams.fetched(Ipv4Links.strip(v4.toHttpUrl()), 200, NetFamily.V4)
        assertTrue("remembered on the same network", Ipv4Links.isMarked(yt.streams.resolve("songB").audioUrl))
        yt.network = "net-2"
        assertFalse("not on another", Ipv4Links.isMarked(yt.streams.resolve("songC").audioUrl))
        yt.network = "net-1"
        // A survivor of a restart reads it from its file.
        assertEquals(StreamWay(StreamWay.DIRECT, ipv4 = true), WayMemory(context.getSharedPreferences(WayMemory.PREFS, Context.MODE_PRIVATE)).get("net-1"))
        // Refused: forgotten.
        yt.streams.invalidate("songB", refused = true)
        assertFalse(Ipv4Links.isMarked(yt.streams.resolve("songD").audioUrl))
        assertNull(WayMemory(context.getSharedPreferences(WayMemory.PREFS, Context.MODE_PRIVATE)).get("net-1"))
    }

    @Test fun aLinkThatPlayedTheUsualWayIsNotRemembered() {
        val yt = FakeYouTube()
        val url = yt.streams.resolve("songA").audioUrl
        yt.streams.fetched(url.toHttpUrl(), 200, NetFamily.V6)
        assertNull(WayMemory(context.getSharedPreferences(WayMemory.PREFS, Context.MODE_PRIVATE)).get("net-1"))
    }

    // ------------------------------------------------------------------ the diagnostics

    @Test fun familiesAreReadWithoutTheAddress() {
        assertEquals(NetFamily.V4, NetFamily.ofLink("https://x.googlevideo.com/videoplayback?ip=203.0.113.7&id=1"))
        assertEquals(NetFamily.V6, NetFamily.ofLink("https://x.googlevideo.com/videoplayback?ip=2001%3Adb8%3A%3A1&id=1"))
        assertNull(NetFamily.ofLink("https://x.googlevideo.com/videoplayback?id=1"))
        assertEquals(NetFamily.V4, NetFamily.of(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))))
        assertEquals(NetFamily.V6, NetFamily.of(InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })))
        assertEquals("asked over v6, link for v4, fetched over v4 (families differ)", NetFamily.line("v6", "v4", "v4"))
        assertEquals("asked over v4, link for v4, fetched over v4", NetFamily.line("v4", "v4", "v4"))
        assertEquals("asked over ?, link for v4, fetched over ?", NetFamily.line(null, "v4", null))
        assertEquals("asked over v6, link for v4 (families differ)", NetFamily.linkLine("v6", "v4"))
    }

    @Test fun aRefusalSaysBothFamiliesInOneLineAndNoAddress() {
        ShadowLog.clear()
        val yt = FakeYouTube()
        val url = yt.streams.resolve("songA").audioUrl.toHttpUrl()
        yt.streams.fetched(url, 403, NetFamily.V6)
        val lines = ShadowLog.getLogsForTag("VinzStream").map { it.msg }
        val refused = lines.single { "refused (HTTP 403)" in it }
        assertEquals("songA: refused (HTTP 403), link from visionOS: asked over ?, link for v4, fetched over v6 (families differ)", refused)
        assertTrue(lines.any { it == "songA: link from visionOS: asked over ?, link for v4" })
        assertTrue("no address in the log: $lines", lines.none { "203.0.113" in it })
    }

    // ------------------------------------------------------------------ IPv4 only

    @Test fun theIpv4OnlyLookupKeepsOnlyIpv4Addresses() {
        val v4 = InetAddress.getByAddress("h", byteArrayOf(10, 0, 0, 1))
        val v6 = InetAddress.getByAddress("h", ByteArray(16).also { it[15] = 1 })
        fun dns(vararg all: InetAddress) = object : okhttp3.Dns { override fun lookup(hostname: String) = all.toList() }
        assertEquals(listOf(v4), Ipv4OnlyDns(dns(v6, v4)).lookup("h"))
        assertEquals("none: all of them, rather than nothing", listOf(v6), Ipv4OnlyDns(dns(v6)).lookup("h"))
    }

    @Test fun aLinkAskedOverIpv4IsFetchedOverIpv4WithoutItsMark() {
        val seen = mutableListOf<String>()
        fun googlevideo(name: String) = Interceptor { chain ->
            seen += "$name ${chain.request().url}"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header(NetFamily.HEADER, if (name == "v4") NetFamily.V4 else NetFamily.V6)
                .body(ByteArray(10) { 1 }.toResponseBody(null)).build()
        }
        val told = mutableListOf<Pair<Int, String?>>()
        val v4 = OkHttpClient.Builder().addInterceptor(googlevideo("v4")).build()
        val chunks = OkHttpClient.Builder().addInterceptor(googlevideo("usual")).build()
        val client = OkHttpClient.Builder()
            .addInterceptor(YouTubeStreamInterceptor(chunks, ipv4Client = v4, watch = StreamWatch { _, code, family -> told += code to family }))
            .addInterceptor(googlevideo("usual")).build()
        val link = "https://rr1---sn-test.googlevideo.com/videoplayback?id=o-x&itag=251&clen=10"
        for (url in listOf(Ipv4Links.mark(link), link)) {
            val s = OkHttpDataSource.Factory(client).createDataSource()
            s.open(DataSpec.Builder().setUri(Uri.parse(url)).build())
            s.close()
        }
        assertEquals(2, seen.size)
        assertTrue(seen.toString(), seen[0].startsWith("v4 ") && Ipv4Links.PARAM !in seen[0])
        assertTrue(seen.toString(), seen[1].startsWith("usual "))
        assertEquals(listOf(200 to NetFamily.V4, 200 to NetFamily.V6), told)
    }

    // ------------------------------------------------------------------ the way memory

    @Test fun theWayMemoryKeepsTwentyNetworksAndSurvivesARestart() {
        val prefs = context.getSharedPreferences("stream_ways_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val m = WayMemory(prefs)
        repeat(25) { m.worked("net$it", StreamWay(StreamWay.NEWPIPE, ipv4 = it % 2 == 0)); Thread.sleep(2) }
        assertEquals(WayMemory.CAP, m.size())
        assertNull("the least recently used go", m.get("net0"))
        assertEquals(StreamWay(StreamWay.NEWPIPE, ipv4 = true), m.get("net24"))
        val again = WayMemory(prefs)
        assertEquals(WayMemory.CAP, again.size())
        assertNull(again.get("net4"))
        assertEquals(StreamWay(StreamWay.NEWPIPE, ipv4 = false), again.get("net5"))
        assertTrue(again.refused("net5", StreamWay(StreamWay.NEWPIPE, ipv4 = true)))
        assertNull(WayMemory(prefs).get("net5"))
        assertFalse("another way's refusal leaves it", again.refused("net6", StreamWay(StreamWay.DIRECT, ipv4 = false)))
    }

    @Test fun theWayMemoryIsNeverInABackup() {
        assertNull(com.vinz.music.data.Backup.prefsPart(WayMemory.PREFS))
        assertEquals(com.vinz.music.data.BackupPart.DOWNLOADS, com.vinz.music.data.Backup.prefsPart(AppContainer.DOWNLOAD_SOUNDS))
    }

    // ------------------------------------------------------------------ the prebuffer and downloads

    private fun refused403(): androidx.media3.datasource.DataSource = object : androidx.media3.datasource.DataSource {
        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long =
            throw HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), dataSpec, ByteArray(0))
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = error("not open")
        override fun getUri(): Uri? = null
        override fun close() = Unit
    }

    @Test fun aPrebufferThatIsRefusedForgetsTheLink() {
        val cache = SimpleCache(tmp.newFolder("cache"), NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
        val forgot = mutableListOf<Pair<String, Boolean>>()
        // As AppContainer.warmUp saves a song's start: the song cache over the stream, under the song's key.
        val source = CacheDataSource(cache, AppContainer.forgetsRefused(refused403()) { id, refused -> forgot += id to refused })
        val spec = DataSpec.Builder().setUri("https://rr1---sn-test.googlevideo.com/videoplayback?id=o-x").setKey("songA").setLength(100).build()
        try {
            CacheWriter(source, spec, null, null).cache()
            fail("a refused link saved nothing")
        } catch (e: IOException) {
            Unit
        }
        cache.release()
        // (The cache's reader may open it twice; the resolver takes a refusal told twice as one, see above.)
        assertEquals(setOf("songA" to true), forgot.toSet())
    }

    @Test fun aDownloadRefusedEveryWayMayComeAsItsVideosSound() {
        val song = Song(id = "songA", title = "A", durationSec = 200)
        val spec = DataSpec.Builder().setUri("https://x").build()
        val forbidden = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), spec, ByteArray(0))
        val noWay = SongStreamException("songA", "nope", StreamException("Every client refused it"))
        assertTrue(DownloadFallback.mayTry(song, IOException("download failed", forbidden), already = false))
        assertTrue(DownloadFallback.mayTry(song, noWay, already = false))
        assertFalse("once", DownloadFallback.mayTry(song, noWay, already = true))
        assertFalse("a dropped connection isn't a refusal", DownloadFallback.mayTry(song, IOException("connection reset"), already = false))
        assertFalse("the video won't help an age-restricted song",
            DownloadFallback.mayTry(song, SongStreamException("songA", "x", StreamException("Sign in to play age-restricted songs")), already = false))
        assertFalse("a video is its own sound", DownloadFallback.mayTry(song.copy(isVideo = true), noWay, already = false))
        assertTrue(DownloadFallback.sameLength(200, 205))
        assertFalse(DownloadFallback.sameLength(200, 480))
        assertFalse(DownloadFallback.sameLength(200, 0))
    }
}
