package com.prism.music.ui.screens

import com.prism.music.ui.components.PrismSlider
import com.prism.music.ui.components.PrismSwitch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer

/**
 * Prism Spatial (a software spatializer, so it works without Dolby Atmos or
 * spatial-audio hardware) and the equalizer, at the foot of Playback & sound's Sound group.
 */
@Composable
fun SoundSettings(onOpenEq: () -> Unit) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    Toggle(
        "Vinz Spatial", "Wider sound for any headphones", s.spatial,
        info = "Software spatial audio: a wider stage, sound placed outside your head and a sense of room. Built into Vinz, so it needs no Dolby Atmos or spatial audio hardware.",
    ) { c.settings.setSpatial(it) }
    if (s.spatial) SliderSetting("Intensity", s.spatialAmount, 0.1f..1f, { "${(it * 100).toInt()}%" }) { c.settings.setSpatialAmount(it) }
    Item(
        "Equalizer",
        if (s.eq.enabled) "On · ${s.eq.preset}" else "10 bands, bass boost, headphone correction",
        onClick = onOpenEq,
    ) { Chevron() }
}