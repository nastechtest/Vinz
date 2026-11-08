package com.vinz.music

import com.vinz.music.data.language.BantuLanguages
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class BantuLanguagesTest {
    @Test fun priorityLanguagesKeepTheRequestedOrder() {
        assertEquals(listOf("lg", "xh", "zu", "tn", "st"), BantuLanguages.sections.first().languages.map { it.tag })
    }
    @Test fun sectionsKeepPriorityEastCentralSouthernOrder() {
        assertEquals(BantuLanguages.SectionId.entries.toList(), BantuLanguages.sections.map { it.id })
    }
    @Test fun everyLanguageAppearsExactlyOnceWithoutDuplicateTagsOrNames() {
        val shown = BantuLanguages.sections.flatMap { it.languages }
        val canonicalTags = BantuLanguages.languages.map { it.tag.lowercase(Locale.ROOT) }
        val canonicalNames = BantuLanguages.languages.map { it.nativeName.trim().lowercase(Locale.ROOT) }
        assertEquals(BantuLanguages.languages.map { it.tag }.toSet(), shown.map { it.tag }.toSet())
        assertEquals(shown.size, shown.map { it.tag }.distinct().size)
        assertEquals("Duplicate Bantu language tags are not allowed", canonicalTags.size, canonicalTags.distinct().size)
        assertEquals("Duplicate Bantu endonyms are not allowed", canonicalNames.size, canonicalNames.distinct().size)
        assertEquals("Every registry language must appear in exactly one picker section",
            BantuLanguages.languages.size, shown.size)
    }
    @Test fun regionalSectionsContainOnlyTheirRegion() {
        BantuLanguages.sections.drop(1).forEach { section ->
            assertTrue(section.languages.all { it.region.name == section.id.name })
        }
    }
    @Test fun lookupDoesNotConfuseTongaWithTongan() {
        assertNotNull(BantuLanguages.find("toi"))
        assertNull(BantuLanguages.find("to"))
        assertEquals("isiZulu", BantuLanguages.find("ZU")?.nativeName)
    }
}
