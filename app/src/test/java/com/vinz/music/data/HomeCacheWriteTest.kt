package com.vinz.music.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.management.ManagementFactory

/**
 * Keeping Home on the phone (#126) without the burst of large strings (1.8.7.5's A16 soak: each time Home loaded, about
 * a thousand large objects, 100+ MB, within a second, the Java heap from ~50 to 128-172 MB). JsonElement.toString()
 * copies the whole of every object inside another, so YouTube's deep nesting copied a 1.4 MB Home dozens of times.
 * Bytes, not milliseconds, so it holds on any machine.
 */
class HomeCacheWriteTest {
    @get:Rule val tmp = TemporaryFolder()

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** A page shaped like YouTube's: [depth] objects deep before its shelves, each shelf a list of renderers. */
    private fun page(n: Int, depth: Int = 20, shelves: Int = 8, items: Int = 25): JsonObject {
        fun item(i: Int) = JsonObject(mapOf(
            "musicTwoRowItemRenderer" to JsonObject(mapOf(
                "title" to JsonObject(mapOf("runs" to JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive("Song \"$n.$i\" — é ✓"))))))),
                "thumbnail" to JsonObject(mapOf("url" to JsonPrimitive("https://lh3.googleusercontent.com/" + "x".repeat(120) + "=w544-h544"))),
                "navigationEndpoint" to JsonObject(mapOf("watchEndpoint" to JsonObject(mapOf("videoId" to JsonPrimitive("id$n$i"), "index" to JsonPrimitive(i))))),
                "explicit" to JsonPrimitive(i % 2 == 0), "badge" to JsonNull, "ratio" to JsonPrimitive(1.5),
                "trackingParams" to JsonPrimitive("CB" + "A".repeat(300)),
            )),
        ))
        fun shelf(s: Int) = JsonObject(mapOf("musicCarouselShelfRenderer" to JsonObject(mapOf(
            "header" to JsonObject(mapOf("title" to JsonPrimitive("Shelf $s"))),
            "contents" to JsonArray((0 until items).map(::item)),
        ))))
        var inner: JsonObject = JsonObject(mapOf("contents" to JsonArray((0 until shelves).map(::shelf))))
        repeat(depth) { inner = JsonObject(mapOf("level$it" to inner, "responseContext" to JsonPrimitive("p$n"))) }
        return inner
    }

    private val pages = (0 until 3).map { page(it) }

    @Test fun theKeptHomeIsWrittenExactlyAsBefore() {
        val dir = tmp.newFolder("cache")
        HomeCache(dir).writeNow(pages)
        val written = File(dir, "home/feed.json").readText()
        assertEquals("the same text as JsonElement.toString() gave", JsonArray(pages).toString(), written)
        assertEquals("one value too", "\"a\\\"b\"", StringBuilder().apply { appendJson(JsonPrimitive("a\"b")) }.toString())
        assertEquals("[]", StringBuilder().apply { appendJson(JsonArray(emptyList())) }.toString())
        assertEquals("{}", StringBuilder().apply { appendJson(JsonObject(emptyMap())) }.toString())
    }

    @Test fun keepingHomeAllocatesAboutItsOwnSize() {
        val dir = tmp.newFolder("cache")
        val cache = HomeCache(dir)
        cache.writeNow(pages)   // warmed up (classes loaded)
        val size = File(dir, "home/feed.json").length()
        val id = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(id)
        cache.writeNow(pages)
        val used = threads.getThreadAllocatedBytes(id) - before
        println("Home kept: %.2f MB written, %.2f MB allocated".format(size / 1_048_576.0, used / 1_048_576.0))
        // Before: the text again at every level of nesting, 173 MB for this 0.41 MB Home (425 times its size). Now each
        // value's own small text, once: about 7 times its size, in small objects.
        assertTrue("allocated $used bytes for a $size-byte Home", used < 16 * size)
    }
}
