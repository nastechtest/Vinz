package com.vinz.music.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.vinz.music.AppLanguage
import com.vinz.music.R
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.tr
import com.vinz.music.ui.screens.SettingEntry
import com.vinz.music.ui.screens.SettingsSection
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer

/**
 * Settings → Language & region (1.8.4, the kit's sample page): Vinz's language and the country YouTube Music is asked
 * as, each a Pick row that opens the list sheet with search. Reads and writes what the old Language page and the old
 * Region group did ([AppLanguage], `contentCountry`). Call it inside `StoredSettings`, as every settings page.
 */
@Composable
fun LanguageRegionPage(bottomPadding: Dp, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val picked = remember { AppLanguage.picked(context) }
    LanguageRegionContent(
        bottomPadding, language = picked, country = LocalAppSettings.current.contentCountry,
        onLanguage = { AppLanguage.pick(context, it) }, onCountry = { c.settings.setContentCountry(it) },
        showBack = showBack, onSearch = onSearch,
    )
}

private val page = SettingsPageId.LANGUAGE_REGION

/**
 * The root's line for Language & region: the language Vinz is in, and the content country when one is picked. (As
 * every page of the map, this file is the only place for the page's parts: this line, [languageRegionLookKeys],
 * [languageRegionOldGroups] and [languageRegionIndex].)
 */
fun languageRegionValue(s: AppSettings, dark: Boolean): String = listOfNotNull(
    SettingsSection.LANGUAGE.summary(s, dark),
    s.contentCountry.takeIf { it.isNotBlank() }?.let { countryName(it) },
).joinToString(" · ")

/** The look settings "Reset this page" puts back: none (the page's own reset puts the content country back). */
val languageRegionLookKeys: List<String> = emptyList()

/** Old group titles → this page's group keys: the old Language and Region groups are the page's one card. */
val languageRegionOldGroups: Map<String, String> by lazy {
    mapOf(tr(R.string.language) to LANGUAGE_REGION_GROUP, tr(R.string.region) to LANGUAGE_REGION_GROUP)
}

/** The key of the page's one (untitled) group. */
internal const val LANGUAGE_REGION_GROUP = "language-region"

/** The page's lines in Settings' search (its card has no title, so they name no group). */
val languageRegionIndex: List<SettingEntry> by lazy {
    listOf(
        SettingEntry(page, "", tr(R.string.language), tr(R.string.language_keywords)),
        SettingEntry(page, "", tr(R.string.content_country), tr(R.string.region_location_gl) + " " + tr(R.string.region)),
    )
}

/** A country's name in the language Vinz is in. */
private fun countryName(code: String): String =
    java.util.Locale("", code).displayCountry.takeIf { it.isNotBlank() && it != code } ?: code

/**
 * The page itself, on plain values (drawn in tests and previews without the app behind it). [language]: the picked
 * language's tag or [AppLanguage.PHONE]; [country]: the stored content country, blank for Automatic.
 */
@Composable
internal fun LanguageRegionContent(
    bottomPadding: Dp,
    language: String,
    country: String,
    onLanguage: (String) -> Unit,
    onCountry: (String) -> Unit,
    showBack: Boolean = true,
    onBack: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    // The phone's own first language, named in itself: whole for the sheet ("Deutsch (Österreich)"), short for the row.
    val phoneLocale = remember { android.content.res.Resources.getSystem().configuration.locales[0] }
    val phoneLanguage = remember { phoneLocale.getDisplayName(phoneLocale).replaceFirstChar { it.titlecase() } }
    val phoneLanguageShort = remember { phoneLocale.getDisplayLanguage(phoneLocale).replaceFirstChar { it.titlecase() }.ifBlank { phoneLanguage } }
    // By each language's own name, as the phone's own language list does ("Deutsch", "Español", "日本語"…).
    val languages = remember {
        listOf(AppLanguage.PHONE) + AppLanguage.available.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.ROOT)) { AppLanguage.nativeName(it) })
    }
    val languageNow = languages.firstOrNull { it.equals(language, ignoreCase = true) } ?: AppLanguage.PHONE
    val phoneCountry = java.util.Locale.getDefault().country
    val automatic = tr(R.string.automatic_3, if (phoneCountry.isBlank()) tr(R.string.united_states) else countryName(phoneCountry))
    // Automatic, then the United States (where YouTube Music has everything), then every country by name.
    val countries = remember {
        listOf("", "US") + java.util.Locale.getISOCountries().filter { it != "US" }.sortedBy { countryName(it) }
    }
    val countryLabel: (String) -> String = { code ->
        when (code) { "" -> tr(R.string.automatic_follow_the_phone); "US" -> tr(R.string.united_states); else -> countryName(code) }
    }
    val countryValue = if (country.isBlank()) automatic else countryLabel(country)

    SettingsPageScaffold(
        title = tr(R.string.language_and_region),
        bottomPadding = bottomPadding,
        showBack = showBack, onBack = onBack, onSearch = onSearch,
        // The language isn't reset from here: putting it back restarts Vinz, which a page reset shouldn't do.
        reset = PageReset(
            changes = if (country.isBlank()) emptyList() else listOf(resetLine(tr(R.string.content_country), countryValue, automatic)),
            onReset = { onCountry("") },
        ),
    ) {
        // One group: its name would only repeat the page's.
        group(null, key = LANGUAGE_REGION_GROUP) {
            PickListRow(
                tr(R.string.language), languages, languageNow,
                label = { if (it == AppLanguage.PHONE) tr(R.string.your_phone_s_language) else AppLanguage.nativeName(it) },
                value = if (languageNow == AppLanguage.PHONE) tr(R.string.automatic_3, phoneLanguageShort) else AppLanguage.nativeName(languageNow),
                info = tr(R.string.language_info),
                // Each in its own words, with its name in the language Vinz is in now beneath it when that differs.
                detail = { tag ->
                    if (tag == AppLanguage.PHONE) phoneLanguage
                    else java.util.Locale.forLanguageTag(tag).getDisplayName(java.util.Locale.getDefault()).replaceFirstChar { it.titlecase() }
                        .takeIf { it != AppLanguage.nativeName(tag) }
                },
                searchable = true,
                onPick = onLanguage,
            )
            PickListRow(
                tr(R.string.content_country), countries, if (country in countries) country else "",
                label = countryLabel, value = countryValue,
                info = tr(R.string.home_charts_and_search_follow_this),
                changed = country.isNotBlank(),
                searchable = true,
                onPick = onCountry,
            )
        }
    }
}
