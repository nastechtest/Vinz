package com.vinz.music

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.EqSettings
import com.vinz.music.data.prefs.HomeSectionConfig
import com.vinz.music.data.prefs.LibraryView
import com.vinz.music.data.prefs.PinnedPlaylist
import com.vinz.music.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * The safety net for moving settings between [AppSettings]' groups (1.8.7): a stored key is a DataStore name, not a Kotlin
 * field, so a move must change nothing on anyone's phone. For every stored key (the `K` object) a value that isn't the default
 * is put in an empty store and read back through the model: the snapshot file records, per key, which model value
 * (by its field's name and the value read) it lands in. A store holding all of them at once is recorded as the model reads
 * it and as `forBackup(settings = true, accounts = true)` writes it. The same snapshot has to come out after any move.
 *
 * A field renamed when it moves is listed in [ALIASES] (its path in the model -> the name in the snapshot).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SettingsRoundTripTest {
    private val repo by lazy { SettingsRepository(RuntimeEnvironment.getApplication(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }

    @Before fun fresh() = runBlocking { repo.editNow { it.clear() } }
    @After fun tidy() = runBlocking { repo.editNow { it.clear() } }

    private val readMethod by lazy {
        SettingsRepository::class.java.getDeclaredMethod("read", Preferences::class.java).apply { isAccessible = true }
    }

    /** The store's keys with the type each holds ([Preferences.Key]'s own type argument is only in the field's signature). */
    private class KeyInfo(val key: Preferences.Key<*>, val type: String)

    private val keys: List<KeyInfo> by lazy {
        val k = Class.forName("com.vinz.music.data.prefs.SettingsRepository\$K")
        val inst = runCatching { k.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null) }.getOrNull()
        k.declaredFields.filter { Preferences.Key::class.java.isAssignableFrom(it.type) }.mapNotNull { f ->
            f.isAccessible = true
            val key = f.get(if (Modifier.isStatic(f.modifiers)) null else inst) as? Preferences.Key<*> ?: return@mapNotNull null
            val arg = (f.genericType as ParameterizedType).actualTypeArguments[0]
            KeyInfo(key, arg.typeName.substringAfterLast('.').let { if (arg is ParameterizedType) "Set" else it })
        }.distinctBy { it.key.name }.sortedBy { it.key.name }
    }

    // ---- The model, flattened to its values.

    private fun isGroup(c: Class<*>) =
        c == AppSettings::class.java ||
            (c.name.startsWith("com.vinz.music.data.prefs.") && !c.isEnum && listOf("Prefs", "Look", "Parts").any { c.simpleName.endsWith(it) })

    private fun flatten(o: Any?, path: String, out: MutableMap<String, String>) {
        if (o != null && isGroup(o.javaClass)) {
            for (f in o.javaClass.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
                f.isAccessible = true
                flatten(f.get(o), if (path.isEmpty()) f.name else "$path.${f.name}", out)
            }
        } else out[path] = o.toString()
    }

    private fun flat(s: AppSettings): Map<String, String> = LinkedHashMap<String, String>().also { flatten(s, "", it) }
    private fun readFlat(p: Preferences): Map<String, String> = flat(readMethod.invoke(repo, p) as AppSettings)

    /** The name a model value goes by in the snapshot: its field's name (or its [ALIASES] entry when it was renamed). */
    private fun nameOf(path: String) = ALIASES[path] ?: path.substringAfterLast('.')

    private val enumNames: List<String> by lazy {
        val seen = mutableSetOf<Class<*>>(); val enums = mutableSetOf<String>()
        fun walk(t: Type) {
            when (t) {
                is Class<*> -> when {
                    t.isEnum -> t.enumConstants.forEach { enums += (it as Enum<*>).name }
                    isGroup(t) && seen.add(t) -> t.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.forEach { walk(it.genericType) }
                }
                is ParameterizedType -> t.actualTypeArguments.forEach { walk(it) }
            }
        }
        walk(AppSettings::class.java)
        enums.sorted()
    }

    // ---- A value for each key that isn't what an empty store reads as.

    private val json = Json { encodeDefaults = true }

    private fun special(name: String): String? = when (name) {
        "backdrops" -> """{"HOME":"STYLE"}"""
        "eq" -> json.encodeToString(EqSettings(enabled = true, preset = "Rock", bassBoost = 77, preampDb = 2.5f))
        "home_layout" -> json.encodeToString(listOf(HomeSectionConfig("local:quick_picks", "Quick picks", visible = false, tile = true)))
        "tv_home_layout" -> json.encodeToString(listOf(HomeSectionConfig("local:liked", "Liked", visible = false)))
        "home_playlists" -> json.encodeToString(listOf(PinnedPlaylist("PL1", "A playlist")))
        "library_views" -> "ALBUMS=" + LibraryView.entries.last().name
        "tab_names" -> "HOME=Start"
        "spectrum_stops" -> "ff0000,00ff00"
        else -> null
    }

    private fun put(p: MutablePreferences, info: KeyInfo, v: Any) {
        @Suppress("UNCHECKED_CAST") p[info.key as Preferences.Key<Any>] = v
    }

    private fun prefs(info: KeyInfo, v: Any): MutablePreferences = mutablePreferencesOf().also { put(it, info, v) }

    private class Probe(val chosen: Any, val line: String)

    private fun probe(i: Int, info: KeyInfo, base: Map<String, String>): Probe {
        fun changes(v: Any): Map<String, String> = readFlat(prefs(info, v)).filter { (path, value) -> base[path] != value }
        val name = info.key.name
        fun show(c: Map<String, String>) = c.entries.map { nameOf(it.key) + "=" + it.value }.sorted().joinToString("; ").ifEmpty { "(none)" }
        return when (info.type) {
            "Boolean" -> {
                val t = changes(true); val f = changes(false)
                val names = (t.keys + f.keys).sortedBy { nameOf(it) }
                val line = names.joinToString("; ") { "${nameOf(it)}=" + (t[it] ?: base[it]) + "/" + (f[it] ?: base[it]) }.ifEmpty { "(none)" }
                Probe(true, "$name|Boolean|true/false: $line")
            }
            "Integer" -> pick(info, listOf(3000 + i, 7, 1), ::changes, ::show)
            "Long" -> pick(info, listOf(5000L + i, 7L, 1L), ::changes, ::show)
            "Float" -> pick(info, listOf(1.7f + i / 1000f, 0.45f, 0.9f), ::changes, ::show)
            "String" -> pick(info, listOfNotNull(special(name), "v_$name") + enumNames, ::changes, ::show)
            "Set" -> pick(info, listOf(setOf("v_$name")), ::changes, ::show)
            else -> error("$name: a ${info.type} key; the test doesn't know it")
        }
    }

    private fun pick(info: KeyInfo, candidates: List<Any>, changes: (Any) -> Map<String, String>, show: (Map<String, String>) -> String): Probe {
        for (c in candidates) {
            val d = changes(c)
            if (d.isNotEmpty()) return Probe(c, "${info.key.name}|${info.type}|$c: ${show(d)}")
        }
        return Probe(candidates.first(), "${info.key.name}|${info.type}|(no value changes the model): (none)")
    }

    private fun snapshotText(): String {
        val base = readFlat(mutablePreferencesOf())
        val probes = keys.mapIndexed { i, info -> info to probe(i, info, base) }
        // 1. Each key alone.
        val perKey = probes.map { it.second.line }
        // 2. All of them at once, as the model reads it, and as a backup holds it.
        val all = mutablePreferencesOf().also { p -> probes.forEach { (info, pr) -> put(p, info, pr.chosen) } }
        val model = readFlat(all).entries.map { nameOf(it.key) + "=" + it.value }.sorted()
        val backup = runBlocking {
            repo.editNow { it.clear(); probes.forEach { (info, pr) -> put(it, info, pr.chosen) } }
            val stored = withTimeout(10_000) { repo.stored.first { s -> flat(s) == readFlat(all) } }
            assertEquals("the store reads as the preferences do", readFlat(all), flat(stored))
            repo.forBackup(settings = true, accounts = true)
        }
        // A backup restores to the same store and the same model.
        runBlocking {
            repo.editNow { it.clear() }
            repo.fromBackup(backup, settings = true, accounts = true)
            assertEquals("a restored backup holds the same", backup, repo.forBackup(settings = true, accounts = true))
        }
        val lines = backup.entries.map { it.key + "=" + it.value }.sorted()
        return buildString {
            append("# Each stored key alone: key|type|value written: the model values it lands in (field name=value)\n")
            perKey.forEach { append(it).append('\n') }
            append("# All keys at once, as the model reads them (field name=value)\n")
            model.forEach { append(it).append('\n') }
            append("# All keys at once, forBackup(settings = true, accounts = true)\n")
            lines.forEach { append(it).append('\n') }
        }
    }

    @Test fun everyKeyReadsBackWhereItDidAndABackupHoldsTheSame() {
        assertTrue("the store's keys were found", keys.size > 250)
        val text = snapshotText()
        val file = File(System.getProperty("user.dir"), "src/test/resources/settings-roundtrip-snapshot.txt")
        val saved = javaClass.getResourceAsStream("/settings-roundtrip-snapshot.txt")?.bufferedReader()?.readText()
        if (saved == null) {
            file.parentFile.mkdirs(); file.writeText(text)
            error("No snapshot yet: written to ${file.absolutePath}; run again to compare")
        }
        val want = saved.replace("\r\n", "\n").lines(); val got = text.lines()
        val bad = (0 until maxOf(want.size, got.size)).filter { want.getOrNull(it) != got.getOrNull(it) }
        assertTrue(
            "The stored settings differ from the snapshot at ${bad.size} lines, e.g.:\n" +
                bad.take(12).joinToString("\n") { "  was: ${want.getOrNull(it)}\n  now: ${got.getOrNull(it)}" },
            bad.isEmpty(),
        )
    }

    companion object {
        /** Model path -> the name the snapshot knows it by (a field renamed when it moved into a group). */
        val ALIASES: Map<String, String> = mapOf(
            // 1.8.7: crossfade's group.
            "mix.on" to "mix", "mix.seconds" to "mixSeconds", "mix.auto" to "mixAuto", "mix.beatMatch" to "mixBeatMatch",
            "mix.keepAlbums" to "mixKeepAlbums", "mix.tempoOnCellular" to "mixTempoOnCellular",
            // 1.8.7: Canvas's on / full screen / mobile data / downloads, into `behavior.canvas`.
            "behavior.canvas.enabled" to "canvasEnabled", "behavior.canvas.fillsBackground" to "canvasFillsBackground",
            "behavior.canvas.onCellular" to "canvasOnCellular", "behavior.canvas.withDownloads" to "canvasWithDownloads",
            "behavior.canvas.withDownloadsCellular" to "canvasWithDownloadsCellular",
            // 1.8.7: home's fields, into AppSettings.home.
            "home.layout" to "homeLayout", "home.header" to "homeHeader", "home.headerHeight" to "homeHeaderHeight",
            "home.headerPicture" to "homeHeaderPicture", "home.hideKinds" to "homeHideKinds", "home.shortcuts" to "homeShortcuts",
            "home.recentTiles" to "homeRecentTiles", "home.playlists" to "homePlaylists", "home.customizeButton" to "homeCustomizeButton",
            "home.continueLine" to "homeContinue", "home.customizeTopButton" to "homeCustomizeTopButton", "home.podcastsButton" to "homePodcastsButton",
            // 1.8.7: library's fields, into AppSettings.library.
            "library.view" to "libraryView", "library.views" to "libraryViews", "library.columns" to "libraryColumns",
            "library.subtitles" to "librarySubtitles", "library.startTab" to "libraryStartTab", "library.tiles" to "libraryTiles",
            "library.playlistsFirst" to "libraryPlaylistsFirst",
            // 1.8.7: lyrics's fields, into AppSettings.lyrics.
            "lyrics.onPlayer" to "lyricsOnPlayer", "lyrics.order" to "lyricsOrder", "lyrics.off" to "lyricsOff",
            "lyrics.translate" to "lyricsTranslate", "lyrics.translateTo" to "lyricsTranslateTo", "lyrics.fileWords" to "lyricsFileWords",
            "lyrics.fileTranslation" to "lyricsFileTranslation", "lyrics.blur" to "lyricsBlur", "lyrics.progressLine" to "lyricsProgressLine",
            "lyrics.sourceButton" to "lyricsSourceButton", "lyrics.scale" to "lyricsScale", "lyrics.onMobileData" to "lyricsOnMobileData",
            "lyrics.align" to "lyricsAlign", "lyrics.stripAlign" to "lyricsStripAlign", "lyrics.stripTwoLines" to "lyricsStripTwoLines",
            "lyrics.font" to "lyricsFont", "lyrics.fontName" to "lyricsFontName",
            // 1.8.7: player's fields, into AppSettings.player.
            "player.shuffleRepeat" to "playerShuffleRepeat", "player.background" to "playerBackground", "player.backdropShow" to "playerBackdropShow",
            "player.artCorners" to "playerArtCorners", "player.layout" to "playerLayout", "player.deck" to "playerDeck",
            "player.buttons" to "playerButtons", "player.titleAlign" to "playerTitleAlign", "player.titleSize" to "playerTitleSize",
            "player.titleScroll" to "playerTitleScroll", "player.times" to "playerTimes", "player.blur" to "playerBlur",
            "player.handle" to "playerHandle", "player.navBand" to "playerNavBand",
        )
    }
}
