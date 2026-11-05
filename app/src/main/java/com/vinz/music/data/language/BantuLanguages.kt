package com.vinz.music.data.language

/** Geographic browsing groups, not a formal classification of Bantu subfamilies. */
object BantuLanguages {
    enum class Region { EAST, CENTRAL, SOUTHERN }
    enum class SectionId { PRIORITY, EAST, CENTRAL, SOUTHERN }
    data class Language(
        val tag: String,
        val nativeName: String,
        val region: Region,
        /** Public-endpoint target tested 2026-11-06; null remains planned. Not a translation-quality guarantee. */
        val translationCode: String? = null,
    )
    data class Section(val id: SectionId, val languages: List<Language>)

    private val priorityTags = listOf("lg", "xh", "zu", "tn", "st")
    val languages = listOf(
        Language("lg", "Luganda", Region.EAST, "lg"),
        Language("xh", "isiXhosa", Region.SOUTHERN, "xh"),
        Language("zu", "isiZulu", Region.SOUTHERN, "zu"),
        Language("tn", "Setswana", Region.SOUTHERN, "tn"),
        Language("st", "Sesotho", Region.SOUTHERN, "st"),
        Language("sw", "Kiswahili", Region.EAST, "sw"),
        Language("rw", "Ikinyarwanda", Region.EAST, "rw"),
        Language("rn", "Ikirundi", Region.EAST, "rn"),
        Language("ln", "Lingála", Region.CENTRAL, "ln"),
        Language("kg", "Kikongo", Region.CENTRAL),
        Language("sn", "chiShona", Region.SOUTHERN, "sn"),
        Language("ny", "Chichewa", Region.SOUTHERN, "ny"),
        Language("bem", "Ichibemba", Region.SOUTHERN, "bem"),
        Language("toi", "chiTonga", Region.SOUTHERN),
        Language("loz", "Silozi", Region.SOUTHERN),
        Language("ng", "Oshindonga", Region.SOUTHERN),
        Language("kj", "Oshikwanyama", Region.SOUTHERN),
        Language("hz", "Otjiherero", Region.SOUTHERN),
        Language("nr", "isiNdebele", Region.SOUTHERN, "nr"),
        Language("ss", "siSwati", Region.SOUTHERN, "ss"),
        Language("nso", "Sepedi", Region.SOUTHERN, "nso"),
        Language("ts", "Xitsonga", Region.SOUTHERN, "ts"),
        Language("ve", "Tshivenḓa", Region.SOUTHERN),
    )
    fun find(tag: String): Language? = languages.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
    val sections = listOf(
        Section(SectionId.PRIORITY, priorityTags.map { tag -> languages.first { it.tag == tag } }),
        Section(SectionId.EAST, languages.filter { it.region == Region.EAST && it.tag !in priorityTags }),
        Section(SectionId.CENTRAL, languages.filter { it.region == Region.CENTRAL && it.tag !in priorityTags }),
        Section(SectionId.SOUTHERN, languages.filter { it.region == Region.SOUTHERN && it.tag !in priorityTags }),
    )
}
