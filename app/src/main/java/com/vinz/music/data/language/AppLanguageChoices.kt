package com.vinz.music.data.language

/** An entry in a plan does not constitute an installed, reviewed app translation. */
object AppLanguageChoices {
    fun isSelectable(tag: String, installed: Collection<String>): Boolean =
        installed.any { it.equals(tag, ignoreCase = true) } &&
            (tag.equals("en", ignoreCase = true) || BantuLanguages.find(tag) != null)

    /** Retain existing packs during migration instead of deleting a listener's saved choice. */
    fun legacyTags(installed: Collection<String>): List<String> = installed.filter { tag ->
        !tag.equals("en", ignoreCase = true) && BantuLanguages.find(tag) == null
    }
}
