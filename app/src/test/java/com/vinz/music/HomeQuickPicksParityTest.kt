package com.vinz.music

import com.vinz.music.data.innertube.Parser
import com.vinz.music.data.model.Shelf
import com.vinz.music.data.model.quickPicks
import com.vinz.music.data.model.speedDial
import com.vinz.music.ui.screens.earlyQuickPicks
import com.vinz.music.ui.screens.speedDialPages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.8.7 (plan §7, Home like YouTube Music's): Quick picks known by what they are, in every language. From YouTube
 * Music's own answers, the Home recorded for the in-app tests (signed out, English: its first page, with Quick picks,
 * and three more). Spanish is that answer with only the shelves' titles put into Spanish (no Spanish Home was
 * recorded). Quick picks' song rows each start the song's radio when played; "Trending songs for you", also song rows
 * four to a column, plays the song alone.
 *
 * Only one recorded Home has Quick picks, so "another load gives other picks" can't be checked from recordings.
 */
class HomeQuickPicksParityTest {
    private val dir = File("src/androidTest/assets/recorded")
    private val first = "music_youtube_com_youtubei_v1_browse_cc501cf54554.json"
    private val more = listOf(
        "music_youtube_com_youtubei_v1_browse_2fa6c1604384.json",
        "music_youtube_com_youtubei_v1_browse_3aa4e9636e03.json",
        "music_youtube_com_youtubei_v1_browse_eaeb301c5d2d.json",
    )

    /** The shelves' titles (and straplines) as a Spanish Home has them; nothing else in the answer changes. */
    private val spanish = mapOf(
        "Quick picks" to "Selección rápida",
        "Throwbacks" to "Clásicos de siempre",
        "CLASSICS FROM EVERY DECADE" to "CLÁSICOS DE CADA DÉCADA",
        "Trending songs for you" to "Canciones en tendencia para ti",
        "New releases" to "Nuevos lanzamientos",
        "Podcasts to get you started" to "Podcasts para empezar",
        "Fall vibes" to "Ambiente de otoño",
        "GET COZY" to "PONTE CÓMODO",
        "Low key vibes" to "Ambiente tranquilo",
        "EASE INTO THE DAY" to "EMPIEZA EL DÍA CON CALMA",
        "Today's biggest hits" to "Los grandes éxitos de hoy",
        "Pump it up" to "A todo volumen",
        "Trending community playlists" to "Playlists de la comunidad en tendencia",
        "All-time essentials" to "Esenciales de siempre",
        "THE HITS FROM EVERY GENRE" to "LOS ÉXITOS DE CADA GÉNERO",
        "Play all" to "Reproducir todo",
    )

    private fun body(name: String, inSpanish: Boolean): JsonElement {
        val recorded = Json.parseToJsonElement(File(dir, name).readText(Charsets.UTF_8)).jsonObject
        var text = recorded.getValue("body").jsonPrimitive.content
        if (inSpanish) spanish.forEach { (en, es) -> text = text.replace("\"text\":\"$en\"", "\"text\":\"$es\"") }
        return Json.parseToJsonElement(text)
    }

    private fun page(name: String, inSpanish: Boolean = false): List<Shelf> = Parser.shelves(body(name, inSpanish))
    private fun home(inSpanish: Boolean = false): List<Shelf> = (listOf(first) + more).flatMap { page(it, inSpanish) }

    @Test fun theSameShelvesAreFoundInBothLanguages() {
        val en = home()
        val es = home(inSpanish = true)
        assertEquals(en.size, es.size)
        en.zip(es).forEach { (e, s) ->
            assertEquals(e.items.map { it.id }, s.items.map { it.id })
            assertEquals(listOf(e.rows, e.stacked, e.startsRadios), listOf(s.rows, s.stacked, s.startsRadios))
        }
        assertTrue("the titles are Spanish", es.none { it.title == "Quick picks" || it.title == "Throwbacks" })
        // Quick picks: the same shelf, the same songs, in both.
        val enPicks = en.quickPicks()
        val esPicks = es.quickPicks()
        assertEquals("Quick picks", enPicks?.title)
        assertEquals("Selección rápida", esPicks?.title)
        assertEquals(20, esPicks!!.items.size)
        assertEquals(enPicks!!.items.map { it.id }, esPicks.items.map { it.id })
        // Signed out there's no Speed dial in either.
        assertNull(en.speedDial(null))
        assertNull(es.speedDial(null))
    }

    /** #371: the section shows as soon as its page is in; in Spanish it waited for every page (English title only). */
    @Test fun quickPicksShowFromTheirOwnPageInEveryLanguage() {
        assertEquals("Quick picks", earlyQuickPicks(page(first), null)?.title)
        assertEquals("Selección rápida", earlyQuickPicks(page(first, inSpanish = true), null)?.title)
    }

    @Test fun aSongShelfThatIsntQuickPicksIsNeverTaken() {
        for (inSpanish in listOf(false, true)) {
            val later = more.flatMap { page(it, inSpanish) }
            val trending = later.single { it.rows }
            assertEquals(if (inSpanish) "Canciones en tendencia para ti" else "Trending songs for you", trending.title)
            // It has Quick picks' shape: song rows, four to a column...
            assertTrue(trending.rows && trending.stacked)
            // ...but its songs play alone.
            assertFalse(trending.startsRadios)
            assertNull("pages without Quick picks have none (Spanish: $inSpanish)", later.quickPicks())
            assertNull(earlyQuickPicks(later, null))
            // Before Quick picks' own page, it isn't taken either.
            val picks = (later + page(first, inSpanish)).quickPicks()
            assertNotNull(picks)
            assertNotEquals(trending.title, picks!!.title)
            assertTrue(picks.startsRadios)
        }
    }

    @Test fun twentySevenTilesMakeThreePages() {
        val tiles = home(inSpanish = true).flatMap { it.items }.distinctBy { it.id }.take(27)
        assertEquals(27, tiles.size)
        val pages = speedDialPages(tiles)
        assertEquals(3, pages.size)
        assertTrue(pages.all { it.size == 9 })
    }
}
