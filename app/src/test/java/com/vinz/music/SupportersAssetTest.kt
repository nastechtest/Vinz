package com.vinz.music

import com.vinz.music.ui.support.Supporters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.8.7.6 (plan-1.8.7.6 §4.3): the Supporters list shipped in the app is valid, holds names only (no email, nothing that
 * looks like an amount), each name once and under 40 characters. Pure JVM: the file is read where it lives.
 */
class SupportersAssetTest {
    private val asset = File("src/main/assets/${Supporters.ASSET}")

    /** Everything wrong with a list's text, one line each (empty: it may ship). */
    private fun problems(text: String): List<String> {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { return listOf("not a JSON object: ${it.message}") }
        val out = ArrayList<String>()
        if (root.keys != setOf("members", "supporters")) out += "keys are ${root.keys}, not members and supporters"
        val names = ArrayList<String>()
        for (k in listOf("members", "supporters")) {
            val arr = root[k] as? JsonArray ?: run { out += "$k is not a list"; continue }
            for (e in arr) {
                val p = e as? JsonPrimitive
                if (p == null || !p.isString) { out += "$k: $e is not a name"; continue }
                names += p.content
            }
        }
        val money = Regex("""[$€£¥₹]|\d+[.,]\d{2}\b|\d+\s*(usd|eur|gbp|dollars?|euros?|pounds?|coffees?)\b""", RegexOption.IGNORE_CASE)
        for (n in names) {
            if (n.isBlank()) out += "a blank name"
            if ('@' in n) out += "\"$n\" has an @ (an email or a handle)"
            if (n.length > 40) out += "\"$n\" is longer than 40 characters"
            if (money.containsMatchIn(n)) out += "\"$n\" looks like it holds an amount"
        }
        names.groupBy { it.trim().lowercase() }.filterValues { it.size > 1 }.keys.forEach { out += "\"$it\" is listed twice" }
        return out
    }

    @Test fun theShippedListMayShip() {
        assertTrue(asset.absolutePath, asset.isFile)
        val text = asset.readText()
        assertEquals(emptyList<String>(), problems(text))
        assertNotNull("the app reads it", Supporters.parse(text))
    }

    /** The checks catch what they are there for. */
    @Test fun theChecksCatchABadList() {
        assertEquals(1, problems("not json").size)
        assertTrue(problems("""{"members": []}""").isNotEmpty())
        assertTrue(problems("""{"members": ["a@b.com"], "supporters": []}""").single().contains("@"))
        assertTrue(problems("""{"members": [], "supporters": ["${"x".repeat(41)}"]}""").single().contains("40"))
        assertTrue(problems("""{"members": ["Ana"], "supporters": ["ana"]}""").single().contains("twice"))
        for (n in listOf("Bo \$5", "Cy 3.00", "Di 10 EUR", "Ed €3")) assertTrue(n, problems("""{"members": [], "supporters": ["$n"]}""").single().contains("amount"))
        assertEquals(emptyList<String>(), problems("""{"members": ["Ana"], "supporters": ["Bo", "Cy from Utrecht"]}"""))
    }

    @Test fun theReaderKeepsTheOrderAndSkipsBlanks() {
        val s = Supporters.parse("""{"members": ["Ana", " "], "supporters": ["Bo", "Cy"]}""")!!
        assertEquals(listOf("Ana"), s.members)
        assertEquals(listOf("Bo", "Cy"), s.supporters)
        assertTrue(Supporters.parse("""{"members": [], "supporters": []}""")!!.isEmpty)
        assertEquals(null, Supporters.parse("nope"))
    }
}
