package com.vinz.music

import com.vinz.music.data.looks.Backgrounds
import com.vinz.music.data.looks.Look
import com.vinz.music.data.looks.LookCode
import com.vinz.music.data.looks.LookGallery
import com.vinz.music.data.prefs.LyricsBackground
import com.vinz.music.data.prefs.PlayerBackground
import com.vinz.music.data.prefs.SettingsRepository
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.theme.Backdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.io.File

/** Original, asset-free community presets: check the raw wire, not just already-sanitized values. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class NastechResearchLooksTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private fun repo() = SettingsRepository(
        org.robolectric.RuntimeEnvironment.getApplication(),
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it },
    )
    @After
    fun stopRepositoryCollectors() {
        scopes.forEach { it.cancel() }
    }

    private val profiles get() = Json.parseToJsonElement(File("../themes/nastech-research.json").readText()).jsonObject
    private val rawEntries get() = Json.parseToJsonElement(File("../themes/index.json").readText())
        .jsonObject.getValue("looks").jsonArray.map { it.jsonObject }
        .filter { it["by"]?.jsonPrimitive?.content == "Nastech Research" }
    private fun pack(r: SettingsRepository) = LookGallery.parse(File("../themes/index.json").readText(), r.lookKinds)
        .filter { it.entry.by == "Nastech Research" }

    @Test
    fun tenDistinctOriginalLooksKeepTheirNamesAndCreator() {
        val looks = pack(repo())
        assertEquals(10, rawEntries.size)
        assertEquals(10, looks.size)
        assertEquals(10, looks.map { it.entry.id }.toSet().size)
        assertEquals(10, looks.map { it.entry.name }.toSet().size)
        assertEquals(10, looks.map { it.look.values }.toSet().size)
        looks.forEach { g ->
            assertTrue(g.entry.id.startsWith("nr-"))
            assertTrue(g.entry.name.length <= Look.MAX_NAME)
            assertTrue(g.entry.note.length <= 80)
            assertTrue(g.look.note.contains("Nastech Research"))
            assertTrue(g.entry.code.length <= LookCode.MAX_QR_CHARS)
            assertTrue(g.look.photoAssets.isEmpty())
            assertEquals(null, g.look.font)
            assertEquals(null, g.entry.file)
            assertEquals("VINZ", g.look.icons.pack)
        }
    }

    @Test
    fun everyIntendedValueSurvivesTheRealDecoderAndSanitizer() {
        val r = repo()
        val base = profiles.getValue("base").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val definitions = profiles.getValue("looks").jsonArray.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
        rawEntries.forEach { entry ->
            val id = entry.getValue("id").jsonPrimitive.content
            val definition = definitions.getValue(id).jsonObject
            val expected = (base + definition.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.content }).toMutableMap()
            val accent = ("FF" + definition.getValue("accent").jsonPrimitive.content).toLong(16).toInt()
            expected["accent"] = "i:$accent"
            definition["backdrop"]?.jsonPrimitive?.content?.let { style ->
                expected[Look.BACKDROPS] = Backgrounds.tag(
                    listOf("HOME", "SEARCH", "LIBRARY", "REPLAY", "SETTINGS", "PLAYER").associateWith { style },
                )
            }
            assertTrue(id, expected.keys.all { it in r.lookKinds })
            val decoded = LookCode.decode(entry.getValue("code").jsonPrimitive.content, r.lookKinds)
            assertNotNull(id, decoded)
            assertEquals(id, definition.getValue("name").jsonPrimitive.content, decoded!!.name)
            assertEquals("$id: a generated value was lost", expected, decoded.values)
            assertEquals(id, expected, LookCode.sanitized(decoded, r.lookKinds).values)
            assertEquals(id, decoded.values, LookCode.decode(LookCode.encode(decoded), r.lookKinds)!!.values)
        }
    }

    @Test
    fun allPresetsAreDarkAndUseOnlyRealBackdropNames() {
        val r = repo()
        pack(r).forEach { g ->
            val preview = r.previewLook(g.look.values)
            assertEquals(g.entry.id, ThemeMode.DARK, preview.themeMode)
            // Tagged strings alone are not sufficient: read() must not silently fall back.
            val readEnums = mapOf(
                "color_source" to preview.colorSource.name,
                "light_style" to preview.lightStyle.name,
                "surface_tint" to preview.surfaceTint.name,
                "contrast_level" to preview.contrast.name,
                "font_choice" to preview.fontChoice.name,
                "corners" to preview.corners.name,
                "glass_kind" to preview.glassKind.name,
                "player_background" to preview.playerBackground.name,
                "lyrics_background" to preview.lyricsBackground.name,
                "immersive_background" to preview.immersiveBackground.name,
            )
            readEnums.forEach { (key, value) -> assertEquals("${g.entry.id}: $key", g.look.values[key], "s:$value") }
            assertFalse("${g.entry.id}: competing glass modes", preview.liquidGlass && preview.frostedGlass)
            Backgrounds.parse(g.look.values[Look.BACKDROPS]).forEach { (screen, style) ->
                assertTrue(screen, screen in setOf("HOME", "SEARCH", "LIBRARY", "REPLAY", "SETTINGS", "PLAYER"))
                assertTrue(style, Backdrop.entries.any { it.name == style })
                assertEquals(style, Backdrop.parse(style).first.name)
            }
        }
    }

    @Test
    fun threeAmoledPresetsAreBlackAcrossPagesPlayerAndBothLyricsModes() {
        val r = repo()
        val amoled = pack(r).filter { it.entry.name.endsWith("AMOLED") }
        assertEquals(3, amoled.size)
        amoled.forEach { g ->
            val preview = r.previewLook(g.look.values)
            assertTrue(preview.pureBlack)
            assertEquals(PlayerBackground.BLACK, preview.playerBackground)
            assertEquals(LyricsBackground.COLOR, preview.lyricsBackground)
            assertEquals(LyricsBackground.COLOR, preview.immersiveBackground)
            assertEquals(0xFF000000.toInt(), preview.lyricsBgColor)
            assertEquals(0, preview.lyricsBgColor2)
            assertTrue(preview.backdrops.isEmpty())
            assertFalse(preview.frostedPages)
            assertFalse(preview.liquidGlass)
            assertFalse(preview.frostedGlass)
            assertFalse(preview.backdropMotion)
        }
    }

    @Test
    fun sixMovingLooksUseExistingAnimatedBackdropsAndFourRemainStill() {
        val r = repo()
        val moving = pack(r).filter { it.look.values["backdrop_motion"] == "b:1" }
        assertEquals(6, moving.size)
        moving.forEach { g ->
            assertEquals("s:APPEARANCE", g.look.values["player_background"])
            assertFalse(g.look.values.containsKey("reduce_motion"))
            val backdrops = Backgrounds.parse(g.look.values[Look.BACKDROPS])
            assertEquals(6, backdrops.size)
            assertTrue(backdrops.values.all { it in setOf("MIDNIGHT", "OCEAN", "RIPPLES", "WAVES", "SILK", "EMBER") })
        }
        val still = pack(r).filter { it.look.values["backdrop_motion"] == "b:0" }
        assertEquals(4, still.size) // three AMOLED presets plus the still Glacier Frost
    }

    @Test
    fun switchingEveryPresetPreservesAccessibilityPlaybackAndExperimentalOptIn(): Unit = runBlocking {
        val r = repo()
        val seed = mapOf(
            "reduce_motion" to "b:true", "performance_mode" to "b:true", "audio_quality" to "s:LOW",
            "normalize" to "b:true", "music_speed" to "f:1.25", "lyrics_translate" to "b:true",
            "experimental_split_navigation" to "b:true", "queue_locked" to "b:true",
        )
        r.fromBackup(seed, settings = true, accounts = false)
        val restored = r.forBackup(settings = true, accounts = false)
        seed.forEach { (key, value) -> assertEquals("Seed was not restored: $key", value, restored[key]) }
        withTimeout(5_000) { r.flow.first { it.reduceMotion && it.experimentalSplitNavigation } }
        val before = r.forBackup(settings = true, accounts = true).filterKeys { it !in r.lookKinds }
        pack(r).forEach { g ->
            assertTrue(r.previewLook(g.look.values).reduceMotion)
            r.applyLook(g.look.values)
            assertEquals(g.entry.id, before, r.forBackup(settings = true, accounts = true).filterKeys { it !in r.lookKinds })
        }
    }
}
