package com.vinz.music.data.canvas

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.8.7: the fetch right after a find takes its stream link from the player answer the finder just had for the same
 * video, instead of asking again; once, while fresh, and a link turned away still gets a fresh answer.
 */
class PlayerAnswersTest {
    @get:Rule val tmp = TemporaryFolder()

    private val stream = SyntheticStream()
    private val files by lazy { ClipFiles(File(tmp.root, "recent"), File(tmp.root, "saved"), { 100L shl 20 }) }
    private val calls = AtomicInteger()

    private fun answer(link: String): JsonObject = buildJsonObject {
        putJsonObject("playabilityStatus") { put("status", "OK") }
        putJsonObject("streamingData") {
            putJsonArray("adaptiveFormats") {
                add(buildJsonObject {
                    put("itag", 136); put("url", link); put("mimeType", "video/mp4; codecs=\"avc1.4d401f\"")
                    put("width", 1280); put("height", 720); put("fps", 24); put("qualityLabel", "720p"); put("contentLength", "1000")
                    putJsonObject("initRange") { put("start", "0"); put("end", (stream.indexStart - 1).toString()) }
                    putJsonObject("indexRange") { put("start", stream.indexStart.toString()); put("end", stream.indexEnd.toString()) }
                })
            }
        }
    }

    private val fresh = "https://rr1---sn-test.googlevideo.com/videoplayback?id=x&sig=fresh"
    private val finders = "https://rr1---sn-test.googlevideo.com/videoplayback?id=x&sig=finders"

    private fun fetcher(fake: ClipFetcherTest.FakeGooglevideo, answers: PlayerAnswers) =
        ClipFetcher(OkHttpClient.Builder().addInterceptor(fake).build(), { calls.incrementAndGet(); answer(fresh) }, files, answers = answers)

    private fun serve() = ClipFetcherTest.FakeGooglevideo({ { from, to -> stream.bytes.copyOfRange(from.toInt(), to.toInt() + 1) } })

    @Test fun theFetchUsesTheFindersAnswerAndAsksForNone() = runBlocking {
        val answers = PlayerAnswers().apply { put("vidvidvid01", answer(finders)) }
        val got = fetcher(serve(), answers).fetch("vidvidvid01", 0, 3_000, 720, saved = false, allowed = { true })
        assertTrue(got.toString(), got is ClipFetch.Got)
        assertEquals("no second player request", 0, calls.get())
        assertTrue((got as ClipFetch.Got).steps, got.steps.startsWith("answer ") && got.steps.contains("(the finder's)"))
        // Taken once: the next fetch of that video (another part) asks for its own.
        assertNull(answers.take("vidvidvid01"))
    }

    @Test fun aTurnedAwayLinkStillGetsAFreshAnswer() = runBlocking {
        val fake = serve()
        fake.refuse = { it.queryParameter("sig") == "finders" }
        val got = fetcher(fake, PlayerAnswers().apply { put("vidvidvid01", answer(finders)) })
            .fetch("vidvidvid01", 0, 3_000, 720, saved = false, allowed = { true })
        assertTrue(got.toString(), got is ClipFetch.Got)
        assertEquals("one fresh answer after the refusal", 1, calls.get())
    }

    @Test fun anAnswerKeptTooLongOrForAnotherVideoIsNotUsed() = runBlocking {
        var now = 0L
        val answers = PlayerAnswers(keepMs = 600_000, clock = { now })
        answers.put("vidvidvid01", answer(finders))
        now = 600_001
        assertNull("too old", answers.take("vidvidvid01"))
        answers.put("other", answer(finders))
        val got = fetcher(serve(), answers).fetch("vidvidvid01", 0, 3_000, 720, saved = false, allowed = { true })
        assertTrue(got is ClipFetch.Got)
        assertEquals(1, calls.get())
        // Not allowed on the network: nothing is taken or asked.
        answers.put("vidvidvid02", answer(finders))
        assertEquals(ClipFetch.None(ClipFetch.Why.NOT_ALLOWED), fetcher(serve(), answers).fetch("vidvidvid02", 0, 3_000, 720, saved = false, allowed = { false }))
        assertEquals(1, calls.get())
        // Only a handful are kept.
        repeat(20) { answers.put("v$it", answer(finders)) }
        assertNull(answers.take("v0"))
        assertTrue(answers.take("v19") != null)
    }
}
