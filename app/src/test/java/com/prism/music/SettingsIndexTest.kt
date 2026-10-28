package com.prism.music

import com.prism.music.ui.screens.SettingsIndex
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings search (#73) only finds what [SettingsIndex] lists: every setting row and group on the settings pages has to
 * be there, so a new setting without a line in the index fails here.
 */
class SettingsIndexTest {
    private val sources = listOf("SettingsScreen.kt", "SoundSettings.kt", "BackupUi.kt", "ConnectSettings.kt", "ColorSettings.kt", "LookSettings.kt")
        .map { File("src/main/java/com/prism/music/ui/screens/$it").readText() }

    private fun literals(pattern: String) = sources.flatMap { src ->
        Regex(pattern).findAll(src).map { m ->
            // "Space to use: ${…}" is listed by its fixed start.
            m.groupValues[1].replace("\\\"", "\"").substringBefore("\${").trim().removeSuffix(":")
        }.toList()
    }.filter { it.isNotBlank() }.distinct()

    @Test
    fun everyRowIsSearchable() {
        val rows = literals("""\b(?:Toggle|Item|SliderSetting|Label)\(\s*"((?:[^"\\]|\\.)*)"""")
        val missing = rows.filter { row -> SettingsIndex.entries.none { it.title == row || row.startsWith(it.title) } }
        assertTrue("Not in SettingsIndex: $missing", missing.isEmpty())
    }

    @Test
    fun everyGroupIsSearchable() {
        val groups = literals("""\bGroup\("((?:[^"\\]|\\.)*)"""")
        val missing = groups.filter { g -> SettingsIndex.entries.none { it.group == g } }
        assertTrue("Groups not in SettingsIndex: $missing", missing.isEmpty())
    }

    @Test
    fun findsBySynonymAndTitle() {
        assertTrue(SettingsIndex.search("vibration").any { it.title == "Haptics" })
        assertTrue(SettingsIndex.search("crossfade").first().title.startsWith("Crossfade"))
        assertTrue(SettingsIndex.search("eq").any { it.title == "Equalizer" })
        assertTrue(SettingsIndex.search("zzzz").isEmpty())
    }
}
