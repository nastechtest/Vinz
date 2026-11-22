package com.vinz.music

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.data.innertube.InnerTube
import com.vinz.music.data.innertube.arr
import com.vinz.music.data.innertube.obj
import com.vinz.music.data.innertube.str
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.SettingsRepository
import com.vinz.music.data.stream.StreamResolver
import com.vinz.music.data.stream.WayMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.robolectric.shadows.ShadowLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * GitHub #531, live against YouTube, signed out: "made for kids" videos (YouTube's watch page says so) get no link from
 * the visionOS app, Vinz's direct way and NewPipe's, and get links from the older TV app as a Samsung TV; Vinz's own
 * lookup now finds them. A song that isn't for kids still comes the direct way. The links themselves are ciphered and
 * solved by YouTube's player JS, which only runs on a phone: on the PC (2026-11-16, the solver's scripts run in
 * another engine) they were refused for 4 to 23 s after the answer, then served past the first megabyte.
 */
@Category(LiveNetwork::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MadeForKids531LiveTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    /** Baby Shark Dance (Pinkfong), Wheels on the Bus and Bath Song (Cocomelon). */
    private val kids = listOf("XqZsoesa55w", "e_04ZrNroTo", "WRVsOCh907o")
    /** Bohemian Rhapsody (Queen). */
    private val song = "fJ9rUzIMcZQ"

    private fun watchPage(id: String) = http.newCall(
        Request.Builder().url("https://www.youtube.com/watch?v=$id&hl=en").header("User-Agent", InnerTube.USER_AGENT).build(),
    ).execute().use { it.body.string() }

    private fun describe(res: JsonObject): String {
        val audio = res.obj("streamingData")?.arr("adaptiveFormats").orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.str("mimeType")?.startsWith("audio/") == true }
        return "${res.str("playabilityStatus", "status")} (${res.str("playabilityStatus", "reason")}), " +
            "${audio.size} audio formats, ${audio.count { it.str("url") != null }} plain, ${audio.count { it.str("signatureCipher") != null }} ciphered"
    }

    @Test
    fun madeForKidsVideosResolveSignedOut() {
        var settings = AppSettings()
        val innerTube = InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) })
        val api = http.newCall(Request.Builder().url("https://www.youtube.com/iframe_api").build()).execute().use { it.body.string() }
        val player = Regex("""player\\?/([0-9a-fA-F]{8})\\?/""").find(api)!!.groupValues[1]
        val js = http.newCall(Request.Builder().url("https://www.youtube.com/s/player/$player/player_ias.vflset/en_US/base.js").build()).execute().use { it.body.string() }
        val sts = Regex("""(?:signatureTimestamp|sts)\s*:\s*(\d{5})""").find(js)!!.groupValues[1].toInt()
        Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(watchPage(song))?.groupValues?.get(1)?.let { settings = settings.copy(visitorData = it) }

        for (id in kids + song) {
            val page = watchPage(id)
            val forKids = page.contains("made for kids", ignoreCase = true)
            println("[531] $id: watch page says made for kids: $forKids")
            assertEquals("$id made for kids", id in kids, forKids)
            val vision = innerTube.visionOsPlayer(id)
            val tv = innerTube.signedInPlayer(InnerTube.PlayerClient.TV_SAMSUNG, id, sts)
            println("[531] $id visionOS: ${describe(vision)}")
            println("[531] $id TV_SAMSUNG signed out: ${describe(tv)}")
            if (id in kids) {
                assertEquals("$id: the visionOS app refuses it", "UNPLAYABLE", vision.str("playabilityStatus", "status"))
                assertEquals("$id: the TV app plays it", "OK", tv.str("playabilityStatus", "status"))
            } else {
                assertEquals("$id: the visionOS app plays it", "OK", vision.str("playabilityStatus", "status"))
            }
        }

        // Vinz's own lookup, signed out, up to the links (solving them needs the phone's JavaScript sandbox). As in the
        // app, Home's first page comes first: it gets the visitor id every request then carries (the TV app answers a
        // request without one with no formats).
        ShadowLog.stream = System.out
        val repo = SettingsRepository(context, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        context.getSharedPreferences(WayMemory.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        val appTube = InnerTube(http, { repo.current }, repo::setVisitorData)
        runBlocking { appTube.post("browse", buildJsonObject { put("browseId", "FEmusic_home") }) }
        val streams = StreamResolver(context, http, repo, appTube)
        assertTrue("signed out", !repo.current.isLoggedIn)
        for (id in kids + song) {
            val started = System.nanoTime()
            val length = streams.durationSec(id)
            println("[531] $id: Vinz's lookup found it, $length s long, in ${(System.nanoTime() - started) / 1_000_000} ms")
            assertTrue("$id has a length", length > 0)
        }
    }
}
