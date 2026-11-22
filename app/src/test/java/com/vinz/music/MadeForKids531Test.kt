package com.vinz.music

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.data.innertube.InnerTube
import com.vinz.music.data.prefs.SettingsRepository
import com.vinz.music.data.stream.StreamException
import com.vinz.music.data.stream.StreamResolver
import com.vinz.music.data.stream.WayMemory
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * GitHub #531 (1.8.7.6): signed out, every "made for kids" video failed with "No playable audio stream was found". The
 * visionOS app (Vinz's direct way, and the client NewPipe asks as) answers them "UNPLAYABLE: This video is not
 * available" (yt-dlp says the same of it); the older TV app as a Samsung TV answers them with links, without an account,
 * and those links are refused for their first seconds, then served whole (live, 2026-11-16: [MadeForKids531LiveTest]).
 *
 * The answers are YouTube's saved ones for Baby Shark Dance (XqZsoesa55w), trimmed; the TV app's links stand in plain
 * (its real ones are ciphered, solved by YouTube's player JS, which doesn't run on the PC).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MadeForKids531Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("stream531/$name")!!.readText()

    private inner class FakeYouTube(
        /** googlevideo refuses each link this many times before serving it. */
        refusedFirst: Int = 0,
        /** The TV app has links for the made-for-kids video. */
        tvAnswers: Boolean = true,
    ) {
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        /** The Cookie header each TV ask went out with. */
        val tvCookies = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val streamAsks = AtomicInteger()
        private val refusals = AtomicInteger(refusedFirst)
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            fun answer(code: Int, text: String) = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fake")
                .body(text.toResponseBody("application/json".toMediaType())).build()
            when {
                req.url.host.endsWith("googlevideo.com") -> {
                    streamAsks.incrementAndGet()
                    if (refusals.getAndDecrement() > 0) answer(403, "") else answer(206, "x".repeat(1024))
                }
                path.endsWith("/player") -> {
                    val buf = okio.Buffer().also { req.body?.writeTo(it) }
                    val body = InnerTube.json.parseToJsonElement(buf.readUtf8()).jsonObject
                    val client = body["context"]!!.jsonObject["client"]!!.jsonObject
                    val name = client["clientName"].toString().trim('"')
                    val make = client["deviceMake"]?.toString()?.trim('"')
                    val id = body["videoId"].toString().trim('"')
                    asked += "$name${make?.let { "/$it" }.orEmpty()} $id"
                    if (name == "TVHTML5") tvCookies += req.header("Cookie")
                    answer(200, when {
                        id != KIDS && name == "VISIONOS" -> playable(id)
                        id == KIDS && name == "VISIONOS" -> fixture("visionos-made-for-kids.json")
                        id == KIDS && name == "TVHTML5" && make == "Samsung" && tvAnswers -> fixture("tv-samsung-signed-out-made-for-kids.json")
                        else -> """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"The page needs to be reloaded."}}"""
                    })
                }
                path.contains("visitor_id") -> answer(200, """{"responseContext":{"visitorData":"v"}}""")
                else -> answer(404, "{}")
            }
        }).build()
        val settings = SettingsRepository(context, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val streams = StreamResolver(context, http, settings, InnerTube(http, { settings.current }))

        init {
            context.getSharedPreferences(WayMemory.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            streams.networkNow = { "net-1" }
            streams.servedPausesMs = List(10) { 0L }
        }

        fun tvAsks() = asked.count { it.startsWith("TVHTML5/Samsung") }

        private fun playable(id: String): String {
            val url = "https://rr1---sn-test.googlevideo.com/videoplayback?id=o-$id&itag=251&c=VISIONOS&expire=9999999999&ip=203.0.113.7&clen=1000"
            return """{"playabilityStatus":{"status":"OK"},"streamingData":{"expiresInSeconds":"21600","adaptiveFormats":[""" +
                """{"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","url":"$url","averageBitrate":"130000","contentLength":"1000","approxDurationMs":"200000"}""" +
                """]},"videoDetails":{"lengthSeconds":"200"}}"""
        }
    }

    @Test fun signedOutAMadeForKidsVideoPlaysFromTheTvApp() {
        val yt = FakeYouTube()
        assertFalse("signed out", yt.settings.current.isLoggedIn)
        val s = yt.streams.resolve(KIDS)
        assertEquals("TVHTML5", s.audioUrl.toHttpUrl().queryParameter("c"))
        assertEquals("Opus 251, as for any song", 251, s.itag)
        assertTrue("the visionOS app was asked first: ${yt.asked}", yt.asked.first().startsWith("VISIONOS"))
        assertEquals("the TV app once: ${yt.asked}", 1, yt.tvAsks())
        assertTrue("no account's cookies: ${yt.tvCookies}", yt.tvCookies.all { it.isNullOrBlank() })
    }

    @Test fun itsLinkIsHandedOutOnlyOnceItIsServed() {
        val yt = FakeYouTube(refusedFirst = 3)
        yt.streams.resolve(KIDS)
        assertEquals("asked until served (three refusals, then its bytes)", 4, yt.streamAsks.get())
    }

    @Test fun aLinkStillRefusedAfterTheWaitIsHandedOutAnyway() {
        val yt = FakeYouTube(refusedFirst = 100)
        val s = yt.streams.resolve(KIDS)
        assertEquals("TVHTML5", s.audioUrl.toHttpUrl().queryParameter("c"))
        assertEquals("the first ask and one after each pause", 11, yt.streamAsks.get())
    }

    @Test fun aSongTheVisionOsAppPlaysNeverAsksTheTvApp() {
        val yt = FakeYouTube()
        yt.streams.resolve("songA")
        assertEquals(listOf("VISIONOS/Apple songA"), yt.asked.toList())
        assertEquals("no wait for the usual links", 0, yt.streamAsks.get())
    }

    @Test fun whenTheTvAppHasNothingEitherItFailsAsBeforeAndIsAskedAgainNextTime() {
        val yt = FakeYouTube(tvAnswers = false)
        repeat(2) {
            val failed = runCatching { yt.streams.resolve(KIDS) }.exceptionOrNull()
            assertTrue("nothing plays it: $failed", failed is StreamException)
        }
        // Each lookup asks the TV app twice: once more on UNPLAYABLE, as for the account ([AccountClients.retries]).
        assertEquals("not kept as refused: asked again: ${yt.asked}", 4, yt.tvAsks())
    }

    private companion object {
        const val KIDS = "XqZsoesa55w"
    }
}
