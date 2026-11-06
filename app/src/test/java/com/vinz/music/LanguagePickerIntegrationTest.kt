package com.vinz.music

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Integration contracts that do not require an emulator or a network provider. */
class LanguagePickerIntegrationTest {
    private fun app(): File = if (File("src/main").isDirectory) File(".") else File("app")
    private fun source(name: String) = File(app(), "src/main/java/com/vinz/music/ui/screens/$name.kt").readText()

    @Test fun bothPickersShareTheSameGroups() {
        assertTrue(source("LanguageSettings").contains("BantuLanguageGroups("))
        assertTrue(source("SettingsScreen").contains("BantuLanguageGroups("))
        assertFalse(source("SettingsScreen").contains("TRANSLATE_LANGUAGES"))
    }
    @Test fun plannedRowsAreDisabledAndHaveNoPickerAction() {
        val groups = source("BantuLanguageGroups")
        assertTrue(groups.contains("semantics { disabled() }"))
        assertTrue(groups.contains("if (canSelect(language))"))
        assertTrue(groups.contains("bantu_more_languages_coming"))
    }
    @Test fun everyInstalledResourcePackHasAllGroupLabels() {
        val keys = setOf("bantu_priority_languages", "bantu_east", "bantu_central", "bantu_southern",
            "bantu_more_languages_coming", "bantu_app_translation_coming", "bantu_translation_unverified",
            "bantu_installed_languages", "bantu_translation_availability", "bantu_language_groups")
        File(app(), "src/main/res").listFiles()!!.filter { File(it, "strings.xml").isFile }.forEach { dir ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml"))
            val nodes = doc.getElementsByTagName("string")
            val names = (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("name").nodeValue }
            keys.forEach { key -> assertEquals("${dir.name}: $key", 1, names.count { it == key }) }
        }
    }
}
