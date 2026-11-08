package com.vinz.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vinz.music.ui.theme.VinzTokens

/** An opaque, readable foreground. Never adds a background or changes a vector's duotone fill alpha. */
internal fun settingsIconColor(hue: Color, background: Color): Color {
    val ink = hue.copy(alpha = 1f)
    fun contrast(color: Color): Float {
        val a = color.luminance()
        val b = background.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
    if (contrast(ink) >= 3f) return ink
    val target = if (contrast(Color.Black) > contrast(Color.White)) Color.Black else Color.White
    // Keep as much of the original hue as possible, also on light themes where yellow needs darkening.
    for (step in 1..100) {
        val candidate = lerp(ink, target, step / 100f)
        if (contrast(candidate) >= 3f) return candidate
    }
    return target
}

/** Preview the actual paths on a clear background rather than putting a neutral icon on a coloured tile. */
@Composable
internal fun SettingsIconSamples(icons: List<ImageVector>) {
    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        icons.forEachIndexed { index, icon ->
            val hue = VinzTokens.spectrumAt(index / (icons.size - 1).coerceAtLeast(1).toFloat())
            Icon(icon, null, Modifier.size(22.dp), tint = settingsIconColor(hue, MaterialTheme.colorScheme.surface))
        }
    }
}
