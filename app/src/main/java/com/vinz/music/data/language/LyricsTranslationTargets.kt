package com.vinz.music.data.language

/**
 * Machine codes are data, never translated resource values.
 * Non-null Bantu codes were smoke-tested against LyricsTranslator's actual public POST endpoint.
 * This is not a native-language quality review or an availability guarantee for every song.
 */
object LyricsTranslationTargets {
    const val PHONE = ""
    const val ENGLISH = "en"
    /** Keep existing saved choices working until a separately reviewed retirement/migration. */
    val legacyTags = listOf("es", "pt", "fr", "de", "it", "nl", "pl", "tr", "ru", "uk", "ar", "hi", "id", "vi", "tl", "ja", "ko", "zh-CN", "zh-TW")
    val bantuTags = BantuLanguages.sections.flatMap { section -> section.languages.mapNotNull { it.translationCode } }
    val selectableTags = listOf(PHONE, ENGLISH) + bantuTags + legacyTags

    fun isSelectable(tag: String): Boolean = selectableTags.any { it.equals(tag, ignoreCase = true) }

    /** Canonicalize codes without rewriting valid saved targets or the independent on/off setting. */
    fun normalize(stored: String): String = selectableTags.firstOrNull { it.equals(stored, ignoreCase = true) } ?: ENGLISH
}
