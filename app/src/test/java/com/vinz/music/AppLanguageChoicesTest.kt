package com.vinz.music

import com.vinz.music.data.language.AppLanguageChoices
import org.junit.Assert.*
import org.junit.Test

class AppLanguageChoicesTest {
    @Test fun plannedTranslationsAreNotSelectableWithoutResources() {
        assertFalse(AppLanguageChoices.isSelectable("lg", listOf("en", "fr")))
        assertFalse(AppLanguageChoices.isSelectable("xh", emptyList()))
    }
    @Test fun installedBantuAndEnglishAreSelectable() {
        assertTrue(AppLanguageChoices.isSelectable("lg", listOf("en", "lg")))
        assertTrue(AppLanguageChoices.isSelectable("en", listOf("en")))
        assertTrue(AppLanguageChoices.isSelectable("ZU", listOf("zu")))
    }
    @Test fun existingPacksAreRetainedWithoutDuplication() {
        assertEquals(listOf("fr", "de"), AppLanguageChoices.legacyTags(listOf("en", "lg", "fr", "ZU", "de")))
        assertFalse(AppLanguageChoices.isSelectable("fr", listOf("fr")))
    }
}
