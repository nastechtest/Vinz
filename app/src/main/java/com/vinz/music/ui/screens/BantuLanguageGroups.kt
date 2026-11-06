package com.vinz.music.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.language.BantuLanguages
import com.vinz.music.data.language.BantuLanguages.Language
import com.vinz.music.data.language.BantuLanguages.SectionId
import com.vinz.music.tr
import com.vinz.music.ui.theme.VinzIcons

/** Shared grouped Bantu-language picker for app and lyric translation settings. */
@Composable
fun BantuLanguageGroups(
    selected: String?,
    canSelect: (Language) -> Boolean,
    pendingText: String,
    onSelect: (String) -> Unit,
) {
    BantuLanguages.sections.forEach { section ->
        Label(
            when (section.id) {
                SectionId.PRIORITY -> tr(R.string.bantu_priority_languages)
                SectionId.EAST -> tr(R.string.bantu_east)
                SectionId.CENTRAL -> tr(R.string.bantu_central)
                SectionId.SOUTHERN -> tr(R.string.bantu_southern)
            },
        )
        section.languages.forEach { language ->
            if (canSelect(language)) {
                Item(language.nativeName, null, onClick = { onSelect(language.tag) }) {
                    if (selected.equals(language.tag, ignoreCase = true)) {
                        Icon(VinzIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().semantics { disabled() }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(language.nativeName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(pendingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    Note(tr(R.string.bantu_more_languages_coming))
}
