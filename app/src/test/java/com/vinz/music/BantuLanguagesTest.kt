package com.vinz.music

import com.vinz.music.data.language.BantuLanguages
import org.junit.Assert.*
import org.junit.Test

class BantuLanguagesTest {
    @Test fun priorityLanguagesKeepTheRequestedOrder() {
        assertEquals(listOf("lg", "xh", "zu", "tn", "st"), BantuLanguages.sections.first().languages.map { it.tag })
    }
    @Test fun sectionsKeepPriorityEastCentralSouthernOrder() {
        assertEquals(BantuLanguages.SectionId.entries.toList(), BantuLanguages.sections.map { it.id })
    }
    @Test fun everyLanguageAppearsOnce() {
        val shown = BantuLanguages.sections.flatMap { it.languages }.map { it.tag }
        assertEquals(BantuLanguages.languages.map { it.tag }.toSet(), shown.toSet())
        assertEquals(shown.size, shown.distinct().size)
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
