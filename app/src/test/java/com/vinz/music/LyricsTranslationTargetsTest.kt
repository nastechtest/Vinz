package com.vinz.music

import com.vinz.music.data.language.BantuLanguages
import com.vinz.music.data.language.LyricsTranslationTargets
import org.junit.Assert.*
import org.junit.Test

class LyricsTranslationTargetsTest {
    @Test fun priorityTargetsUseUnlocalizedMachineCodes() {
        listOf("lg", "xh", "zu", "tn", "st").forEach { assertTrue(LyricsTranslationTargets.isSelectable(it)) }
    }
    @Test fun englishAndPhoneStayAvailableAndCodesAreUnique() {
        val tags = LyricsTranslationTargets.selectableTags
        assertEquals(listOf("", "en"), tags.take(2))
        assertEquals(tags.size, tags.distinct().size)
    }
    @Test fun unverifiedLanguagesCannotBeSelected() {
        listOf("kg", "toi", "loz", "ng", "kj", "hz", "ve").forEach {
            assertFalse(LyricsTranslationTargets.isSelectable(it))
            assertNull(BantuLanguages.find(it)?.translationCode)
        }
        assertFalse(LyricsTranslationTargets.isSelectable("to"))
    }
    @Test fun existingTargetsArePreservedAndMachineCodesAreCanonical() {
        assertEquals("fr", LyricsTranslationTargets.normalize("fr"))
        assertEquals("zh-CN", LyricsTranslationTargets.normalize("zh-cn"))
        assertEquals("en", LyricsTranslationTargets.normalize("unknown"))
        assertEquals("", LyricsTranslationTargets.normalize(""))
        assertEquals("zu", LyricsTranslationTargets.normalize("ZU"))
        assertEquals("lg", LyricsTranslationTargets.normalize("lg"))
    }
    @Test fun everySelectableBantuTargetHasAnExactCatalogMapping() {
        LyricsTranslationTargets.bantuTags.forEach { tag ->
            assertEquals(tag, BantuLanguages.find(tag)?.translationCode)
        }
    }
}
