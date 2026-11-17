package com.vinz.music.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeCacheWriteTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun streamedJsonMatchesOriginalSerialization() {
        val nested = JsonObject(linkedMapOf(
            "quote\"\\\n é" to JsonPrimitive("a\"b\\c\n\t é ✓"),
            "array" to JsonArray(listOf(JsonNull, JsonPrimitive(true), JsonPrimitive(1.5), JsonArray(emptyList()), JsonObject(emptyMap()))),
            "object" to JsonObject(linkedMapOf("first" to JsonPrimitive(0), "second" to JsonPrimitive("x")))
        ))
        val values = listOf(nested, JsonArray(listOf(nested, nested)), JsonPrimitive("x\n"), JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()))
        for (value in values) {
            val actual = StringBuilder().apply { appendJson(value) }.toString()
            assertEquals(value.toString(), actual)
            assertEquals(value, Json.parseToJsonElement(actual))
        }
    }

    @Test fun cacheWritesSameBytesAndEmptyInputLeavesItAlone() {
        val dir = tmp.newFolder()
        val cache = HomeCache(dir)
        val pages = listOf(JsonObject(mapOf("nested" to JsonArray(listOf(JsonPrimitive("é\n\""), JsonNull)))))
        val file = File(dir, "home/feed.json")
        cache.writeNow(pages)
        assertEquals(JsonArray(pages).toString(), file.readText())
        assertFalse(File(dir, "home/feed.json.new").exists())
        cache.writeNow(emptyList())
        assertEquals(JsonArray(pages).toString(), file.readText())
    }
}
