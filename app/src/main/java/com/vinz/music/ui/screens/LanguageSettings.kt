package com.vinz.music.ui.screens

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.vinz.music.AppLanguage
import com.vinz.music.R
import com.vinz.music.data.language.AppLanguageChoices
import com.vinz.music.data.language.BantuLanguages
import com.vinz.music.tr
import com.vinz.music.ui.theme.VinzIcons

/** Settings → Language: phone, English, Bantu choices, then Vinz's other installed packs. */
@Composable
fun LanguageSettings() {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val picked = remember { AppLanguage.picked(context) }
    val phone = locale.getDisplayName(locale).replaceFirstChar { it.titlecase() }
    val installed = AppLanguage.available
    Group(tr(R.string.language), info = tr(R.string.language_info)) {
        Item(tr(R.string.your_phone_s_language), phone, onClick = { AppLanguage.pick(context, AppLanguage.PHONE) }) {
            if (picked == AppLanguage.PHONE) Icon(VinzIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
        Item(AppLanguage.nativeName("en"), null, onClick = { AppLanguage.pick(context, "en") }) {
            if (picked.equals("en", ignoreCase = true)) Icon(VinzIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
        Label(tr(R.string.bantu_language_groups))
        BantuLanguageGroups(
            selected = picked,
            canSelect = { AppLanguageChoices.isSelectable(it.tag, installed) },
            pendingText = tr(R.string.bantu_app_translation_coming),
            onSelect = { AppLanguage.pick(context, it) },
        )
    }
    MoreOptions(listOf(tr(R.string.bantu_installed_languages))) {
        Group(tr(R.string.bantu_installed_languages)) {
            val bantuTags = BantuLanguages.languages.map { it.tag }.toSet()
            AppLanguageChoices.legacyTags(installed).filterNot { it in bantuTags || it.equals("en", ignoreCase = true) }.forEach { tag ->
                val own = AppLanguage.nativeName(tag)
                val here = java.util.Locale.forLanguageTag(tag).getDisplayName(locale).replaceFirstChar { it.titlecase() }
                Item(own, here.takeIf { it != own }, onClick = { AppLanguage.pick(context, tag) }) {
                    if (picked.equals(tag, ignoreCase = true)) Icon(VinzIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
