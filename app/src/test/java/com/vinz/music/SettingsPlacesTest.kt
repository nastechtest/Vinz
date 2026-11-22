package com.vinz.music

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings were rebuilt in 1.8.4 (17 pages in five groups): the pages of before are gone. Text the app shows must not
 * send anyone to one of them ("Settings → Playback & sound", "in Devices & connections"…). Fails on any string in
 * res/values/strings.xml that the code uses and that names a page of before 1.8.4, with its name and text.
 *
 * The old pages' own names stay ([PAGE_NAMES]): Settings search still finds the new pages by them.
 */
class SettingsPlacesTest {
    @Test fun noTextNamesAPageOfBefore184() {
        val strings = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/values/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("&amp;", "&") }
        val used = File("src/main/java").walkTopDown().filter { it.extension == "kt" || it.extension == "java" }
            .flatMap { f -> Regex("""\bR\.string\.(\w+)\b""").findAll(f.readText()).map { it.groupValues[1] } }
            .toSet()
        assertTrue("strings.xml or the code wasn't read", strings.size > 1000 && used.size > 1000)

        val found = strings.filter { (name, _) -> name in used && name !in PAGE_NAMES }
            .mapNotNull { (name, text) -> OLD_PAGES.filter { it in text }.takeIf { it.isNotEmpty() }?.let { "$name (${it.joinToString()}): $text" } }
        assertTrue("Text naming a Settings page that's gone since 1.8.4:\n" + found.joinToString("\n"), found.isEmpty())
    }

    /**
     * The root's "Listening" heading had borrowed Replay's `listening`, which is the listening time in a story: Japanese
     * says 再生時間 ("play time") there, so the heading read as play time. It has its own string now, in every language.
     */
    @Test fun theListeningHeadingHasItsOwnStringInEveryLanguage() {
        val map = File("src/main/java/com/vinz/music/ui/screens/settings/SettingsMap.kt").readText()
        assertTrue("the Listening heading uses root_listening", "LISTENING(tr(R.string.root_listening))" in map)
        val files = File("src/main/res").listFiles { f -> f.name.startsWith("values") }!!.map { File(it, "strings.xml") }.filter { it.exists() }
        assertTrue(files.size >= 32)
        val missing = files.filter { "name=\"root_listening\"" !in it.readText() }.map { it.parentFile.name }
        assertTrue("root_listening missing in $missing", missing.isEmpty())
        val ja = Regex("""name="root_listening">([^<]*)<""").find(File("src/main/res/values-ja/strings.xml").readText())!!.groupValues[1]
        assertTrue("Japanese heading isn't play time: $ja", ja != "再生時間")
    }

    companion object {
        /** The pages of before 1.8.4, as their names were written. */
        val OLD_PAGES = listOf(
            "Customize Vinz", "Playback & sound", "Controls & gestures", "Recommendations & Replay",
            "Downloads, storage & data", "Devices & connections", "Glass & backgrounds", "Icons & shapes",
        )

        /** The old pages' names themselves: what Settings search still answers to. */
        val PAGE_NAMES = setOf(
            "customize_vinz", "playback_sound", "controls_gestures", "recommendations_replay",
            "downloads_storage_data", "devices_connections", "glass_backgrounds", "icons_shapes",
        )
    }
}
