package com.vinz.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.data.model.AlbumRef
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import com.vinz.music.ui.screens.Group
import com.vinz.music.ui.screens.Item
import com.vinz.music.ui.screens.NavRow
import com.vinz.music.ui.screens.Note
import com.vinz.music.ui.screens.SliderSetting
import com.vinz.music.ui.screens.Toggle
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.ui.theme.VinzTokens

/**
 * The Design gallery (debug builds: Settings → About → Design gallery): every shared component of the 1.7 redesign in
 * each of its states, on one page, so the look can be checked at a glance and screenshotted (`DesignGalleryTest`).
 */
@Composable
fun DesignGallery(bottomPadding: Dp) {
    SubPage("Design gallery", bottomPadding, subtitle = "Spectrum · every component, every state") {
        item { DesignGalleryContent() }
    }
}

/** The gallery's content alone: needs only the theme (no app around it), so tests can draw it. */
@Composable
fun DesignGalleryContent(modifier: Modifier = Modifier) {
    val ty = MaterialTheme.typography
    Column(modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Section("Type") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("3 h 52 m", style = ty.displayLarge)
                Text("Good evening", style = ty.headlineLarge)
                Text("After Hours", style = ty.headlineMedium)
                Text("Made for you", style = ty.titleLarge)
                Text("Blinding Lights", style = ty.titleMedium)
                Text("Waterfalls Coming Out Your Mouth", style = ty.bodyLarge)
                Text("The Weeknd · After Hours", style = ty.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Eyebrow("Monday 19 October")
            }
        }
        Section("The split light") {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(120.dp).height(2.dp).background(VinzTokens.spectrum))
                Spacer(Modifier.width(20.dp))
                PlayingBars(Modifier.size(13.dp, 14.dp), spectrum = true)
            }
        }
        Section("Buttons") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VinzButton({}) { Text("Play") }
                    VinzOutlinedButton({}) { Text("Shuffle") }
                    VinzFilledTonalButton({}) { Text("Save") }
                    VinzTextButton({}) { Text("Cancel") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VinzButton({}, enabled = false) { Text("Play") }
                    VinzOutlinedButton({}, enabled = false) { Text("Shuffle") }
                    RoundAction(VinzIcons.Search, "Search") {}
                    RoundAction(VinzIcons.More, "More") {}
                }
            }
        }
        Section("Choices") {
            var chip by remember { mutableStateOf("Songs") }
            ChipRow(listOf("Playlists", "Songs", "Albums", "Artists"), { it == chip }, { it }) { chip = it }
            var seg by remember { mutableStateOf("Dark") }
            Segmented(listOf("System", "Light", "Dark"), seg, { it }, Modifier.padding(horizontal = 16.dp)) { seg = it }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                var a by remember { mutableStateOf(true) }
                var b by remember { mutableStateOf(false) }
                VinzSwitch(a, { a = it })
                VinzSwitch(b, { b = it })
                VinzSwitch(true, null, enabled = false)
            }
            var v by remember { mutableFloatStateOf(0.4f) }
            VinzSlider(v, { v = it }, Modifier.padding(horizontal = 16.dp))
            var s by remember { mutableFloatStateOf(10f) }
            VinzSlider(s, { s = it }, Modifier.padding(horizontal = 16.dp), valueRange = 0f..20f, steps = 3)
        }
        Section("Settings") {
            Group("Playback") {
                var on by remember { mutableStateOf(true) }
                Toggle("Crossfade", "Songs blend into each other", on) { on = it }
                Item("Audio quality", "High · Opus 160 kbps", onClick = {})
                NavRow(VinzIcons.Equalizer, "Playback & sound", "Lossless · Crossfade · Normalized") {}
                SliderSetting("Crossfade length", 6f, 0f..12f, { "${it.toInt()} s" }) {}
                Note("The equalizer and Crossfade are the phone's own, so they're off while casting.")
            }
        }
        Section("Song rows") {
            val weeknd = listOf(ArtistRef("The Weeknd"))
            val album = AlbumRef("After Hours")
            SongRowLayout(Song("a", "Blinding Lights", weeknd, album), index = 9, playing = true, liked = true)
            SongRowLayout(Song("b", "Heartless", weeknd, album, explicit = true), index = 7, starred = true, downloaded = true)
            SongRowLayout(Song("c", "Save Your Tears", weeknd, album), liked = true, videoSaved = true)
            SongRowLayout(Song("d", "Waterfalls Coming Out Your Mouth", listOf(ArtistRef("Glass Animals")), AlbumRef("Dreamland")))
        }
        Section("Menus") {
            ActionHeader(null, "Blinding Lights", "The Weeknd · After Hours") {
                SheetIconButton(VinzIcons.HeartFilled, "Liked", active = true) {}
                SheetIconButton(VinzIcons.Share, "Share") {}
            }
            QuickActions(
                listOf(
                    ActionItem(VinzIcons.PlaylistPlay, "Play next") {}, ActionItem(VinzIcons.QueueMusic, "Add to queue") {},
                    ActionItem(VinzIcons.Radio, "Radio") {}, ActionItem(VinzIcons.Download, "Download", active = true) {},
                ),
            ) {}
            ActionGroup(
                "Go to",
                listOf(ActionItem(VinzIcons.Album, "Album") {}, ActionItem(VinzIcons.Person, "Artist") {}, ActionItem(VinzIcons.Delete, "Remove from playlist", danger = true) {}),
            ) {}
        }
        Section("Play on") {
            com.vinz.music.ui.player.OutputGroup("This phone") {
                com.vinz.music.ui.player.DeviceRow(VinzIcons.Phone, "Pixel 10 Pro XL", "Phone speaker") {}
                com.vinz.music.ui.player.DeviceRow(VinzIcons.Headphones, "AirPods Pro", "Bluetooth") {}
            }
            com.vinz.music.ui.player.OutputGroup("Speakers & TVs") {
                com.vinz.music.ui.player.DeviceRow(VinzIcons.Speaker, "Kitchen speaker", "Google Cast", selected = true) {}
                com.vinz.music.ui.player.DeviceRow(VinzIcons.Tv, "Living Room TV", "DLNA · Connecting…", busy = true) {}
                com.vinz.music.ui.player.DeviceRow(VinzIcons.Close, "Stop casting", "The music pauses; pick this phone to carry on here") {}
            }
        }
        Section("Fields and states") {
            var q by remember { mutableStateOf("") }
            SearchField(q, "Search in playlist", { q = it }, Modifier.padding(horizontal = 16.dp))
            EmptyState(VinzIcons.Download, "Nothing downloaded yet", "Songs you download play without a connection.")
            ErrorState("Couldn't reach Vinz Music. Check your connection.", onRetry = {})
            LoadingState()
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(title, Modifier.padding(horizontal = 16.dp))
        content()
    }
}
