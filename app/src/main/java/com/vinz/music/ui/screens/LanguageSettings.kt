package com.vinz.music.ui.screens

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.vinz.music.AppLanguage
import com.vinz.music.R
import com.vinz.music.tr
import com.vinz.music.ui.theme.VinzIcons

/** Settings → Language (1.7.9): the phone's language, or one of Vinz's own; picking one restarts Vinz in it. */
@Composable
fun LanguageSettings() {
    val context = LocalContext.current
    val picked = remember { AppLanguage.picked(context) }
    // The phone's own first language, named in itself ("Deutsch"), for the "Your phone's" row.
    val phone = remember {
        android.content.res.Resources.getSystem().configuration.locales[0].let { it.getDisplayName(it) }
            .replaceFirstChar { it.titlecase() }
    }
    Group(tr(R.string.language), info = tr(R.string.language_info)) {
        Item(tr(R.string.your_phone_s_language), phone, onClick = { AppLanguage.pick(context, AppLanguage.PHONE) }) {
            if (picked == AppLanguage.PHONE) Icon(VinzIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
        // By each language's own name, as the phone's own language list does ("Deutsch", "Español", "日本語"…).
        AppLanguage.available.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.ROOT)) { AppLanguage.nativeName(it) }).forEach { tag ->
            // Each in its own words, with the name in the language Vinz is in now beneath it when that differs.
            val own = AppLanguage.nativeName(tag)
            val here = java.util.Locale.forLanguageTag(tag).getDisplayName(java.util.Locale.getDefault()).replaceFirstChar { it.titlecase() }
            Item(own, here.takeIf { it != own }, onClick = { AppLanguage.pick(context, tag) }) {
                if (picked.equals(tag, ignoreCase = true)) Icon(VinzIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
