package com.prism.music.ui.player

import androidx.compose.foundation.border
import com.prism.music.ui.components.PrismSlider
import com.prism.music.ui.components.PrismSwitch
import com.prism.music.ui.components.PrismTextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prism.music.data.ModelStore
import com.prism.music.data.model.Song
import com.prism.music.ui.components.ActionSheet
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.rememberHaptics
import kotlinx.coroutines.launch

/** Whether Sing's control strip is up over the lyrics (it comes up when Sing is turned on; the pill held hides it). */
object SingUi {
    var panel by androidx.compose.runtime.mutableStateOf(false)
}

/**
 * Sing tapped (the pill, or the player's ⋯ menu): on or off, with its controls coming up over the lyrics, as on Apple
 * Music. Before the engine is on the phone, [onNeedsDownload] (its one-time download sheet).
 */
fun toggleSing(c: com.prism.music.AppContainer, onNeedsDownload: () -> Unit) {
    if (!c.sing.engineReady()) { onNeedsDownload(); return }
    val on = !c.sing.on.value
    c.sing.setOn(on)
    SingUi.panel = on
}

/**
 * The foot of the lyrics, as in the Aura × Spectrum proposal: a glass bar with where the lyrics come from (tap to change
 * source) and the Sing pill; while Sing is on, its controls sit just above it, the lyrics still playing behind.
 */
@Composable
fun LyricsFooter(state: LyricsState, song: Song, onChangeSource: () -> Unit, onNeedsDownload: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val on by c.sing.on.collectAsState()
    val singable = c.sing.supported && c.sing.canSing(song)
    Column(modifier.fillMaxWidth()) {
        androidx.compose.animation.AnimatedVisibility(
            on && SingUi.panel && singable,
            enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Bottom) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Bottom) + androidx.compose.animation.fadeOut(),
        ) { SingPanel(song, Modifier.padding(bottom = 8.dp)) }
        Row(
            Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .border(0.7.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.02f))), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val lyrics = state.lyrics
            val source = when {
                state.loading -> "Looking for lyrics…"
                lyrics == null -> "No lyrics found"
                else -> "Lyrics from ${lyrics.source.label}" + if (lyrics.wordSynced) " · Karaoke" else ""
            }
            Row(
                Modifier.weight(1f).clip(CircleShape).clickable(onClickLabel = "Change lyrics source", onClick = onChangeSource).padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(source, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.62f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(PrismIcons.ChevronRight, null, Modifier.padding(start = 2.dp).size(16.dp), tint = Color.White.copy(alpha = 0.4f))
            }
            if (singable) SingPill(song, onNeedsDownload)
        }
    }
}

/**
 * Sing as a pill: the microphone and its name, ringed in split light while on, with how far the playing song is ready.
 * Tapped, Sing goes on (its controls come up) or off; held while on, the controls hide or come back.
 */
@Composable
fun SingPill(song: Song, onNeedsDownload: () -> Unit) {
    val c = LocalContainer.current
    val on by c.sing.on.collectAsState()
    val progress by c.sing.progress.collectAsState()
    val p = progress[song.id]
    val haptics = rememberHaptics()
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    Row(
        Modifier.clip(shape).background(Color.White.copy(alpha = if (on) 0.18f else 0.10f))
            .then(if (on) Modifier.border(1.5.dp, com.prism.music.ui.theme.PrismTokens.spectrum, shape) else Modifier.border(1.dp, Color.White.copy(alpha = 0.16f), shape))
            .combinedClickable(
                onClickLabel = if (on) "Turn Sing off" else "Sing",
                onLongClickLabel = if (on) "Show or hide Sing's controls" else null,
                onLongClick = if (on) ({ haptics.tick(); SingUi.panel = !SingUi.panel }) else null,
            ) { haptics.tick(); toggleSing(c, onNeedsDownload) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (on) PrismIcons.MicFilled else PrismIcons.Mic, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (on && p != null) "Sing · ${(p * 100).toInt()}%" else "Sing",
            style = MaterialTheme.typography.labelLarge, color = Color.White,
        )
    }
}

/**
 * Sing's controls over the lyrics: how much of the lead voice stays in, the key, and how far the song is ready. Glass,
 * a few lines high, so the lyrics carry on above it.
 */
@Composable
fun SingPanel(song: Song, modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val sing = c.sing
    val level by sing.level.collectAsState()
    val semitones by sing.semitones.collectAsState()
    val progress by sing.progress.collectAsState()
    val problem by sing.problem.collectAsState()
    val p = progress[song.id]
    val status = when {
        sing.store.complete(song.id) -> "Ready"
        p != null -> "Getting ready… ${(p * 100).toInt()}%"
        problem != null -> problem!!
        else -> "Getting ready…"
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Color.Black.copy(alpha = 0.28f)).background(Color.White.copy(alpha = 0.08f))
            .border(0.7.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.02f))), shape)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Voice", style = MaterialTheme.typography.labelLarge, color = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(if (level <= 0.005f) "None" else "${(level * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.6f))
            Spacer(Modifier.weight(1f))
            Text(status, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.55f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(
                PrismIcons.ChevronDown, "Hide Sing's controls",
                Modifier.padding(start = 4.dp).size(32.dp).clip(CircleShape).clickable { SingUi.panel = false }.padding(6.dp),
                tint = Color.White.copy(alpha = 0.6f),
            )
        }
        Box(Modifier.padding(end = 8.dp)) { PrismSlider(level, { sing.setLevel(it) }, valueRange = 0f..1f) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Key", style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.weight(1f))
            KeyStep("−", "Key down", semitones > -6) { sing.setSemitones(semitones - 1) }
            Text(
                if (semitones == 0) "Original" else "%+d".format(semitones), style = MaterialTheme.typography.labelLarge, color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(72.dp),
            )
            KeyStep("+", "Key up", semitones < 6) { sing.setSemitones(semitones + 1) }
        }
    }
}

@Composable
private fun KeyStep(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = if (enabled) 0.12f else 0.05f))
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = if (enabled) 1f else 0.35f)) }
}
/**
 * Sing's sheet: the one-time download, Sing on or off, how much of the lead voice stays in, and the key moved for
 * your range; with how far the song's vocals are worked out, and why if they can't be.
 */
@Composable
fun SingSheet(song: Song, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val sing = c.sing
    val scope = rememberCoroutineScope()
    val on by sing.on.collectAsState()
    val level by sing.level.collectAsState()
    val semitones by sing.semitones.collectAsState()
    val progress by sing.progress.collectAsState()
    val problem by sing.problem.collectAsState()
    val models by c.models.states.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { c.models.refresh() }
    val runtime = ModelStore.Model.singRuntime()
    val downloading = listOfNotNull(runtime, ModelStore.Model.SING_MODEL).mapNotNull { models[it] as? ModelStore.State.Downloading }
    val failed = listOfNotNull(runtime, ModelStore.Model.SING_MODEL).mapNotNull { models[it] as? ModelStore.State.Failed }.firstOrNull()
    val ready = runtime != null && models[runtime] == ModelStore.State.Ready && models[ModelStore.Model.SING_MODEL] == ModelStore.State.Ready
    val singable = sing.canSing(song)

    ActionSheet(song.thumbnail, onDismiss) { _ ->
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(PrismIcons.MicFilled, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Sing", style = MaterialTheme.typography.titleLarge)
                    Text("The song's own music, with the voice taken out", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (ready) PrismSwitch(on, { sing.setOn(it) })
            }
            Spacer(Modifier.height(16.dp))
            when {
                runtime == null -> Text("Sing doesn't run on this phone's kind of processor.", style = MaterialTheme.typography.bodyMedium)
                !ready -> {
                    Text(
                        "Sing separates each song's voice from its music on your phone, with no server. It needs a one-time download: ${ModelStore.Model.sizeText(ModelStore.Model.SING_MODEL, runtime)}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    when {
                        downloading.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                            com.prism.music.ui.components.PrismSpinner(Modifier.size(18.dp), stroke = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Downloading… ${(downloading.first().fraction * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                        }
                        else -> {
                            if (failed != null) Text("Couldn't download it: ${failed.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(4.dp))
                            com.prism.music.ui.components.PrismButton(
                                onClick = { scope.launch { if (sing.downloadEngine()) { sing.setOn(true); SingUi.panel = true; onDismiss() } } },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (failed != null) "Try again" else "Download and turn on") }
                        }
                    }
                }
                !singable -> Text("Sing works with songs, not live streams or podcasts.", style = MaterialTheme.typography.bodyMedium)
                else -> {
                    val p = progress[song.id]
                    val status = when {
                        !on -> "Off: the song plays as it is."
                        sing.store.complete(song.id) -> "Ready: the voice is out."
                        p != null -> "Getting this song ready… ${(p * 100).toInt()}% (you can start singing as it goes)"
                        problem != null -> problem!!
                        else -> "Getting this song ready…"
                    }
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Row {
                        Text("Lead voice", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(if (level <= 0.005f) "None" else "${(level * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    PrismSlider(level, { sing.setLevel(it) }, enabled = on, valueRange = 0f..1f)
                    Text("Keep a little of it in to sing along with, or none for karaoke. Backing vocals go with it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Key", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        PrismTextButton(onClick = { sing.setSemitones(semitones - 1) }, enabled = on && semitones > -6) { Text("−", style = MaterialTheme.typography.titleLarge) }
                        Text(if (semitones == 0) "Original" else "%+d".format(semitones), Modifier.width(72.dp), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        PrismTextButton(onClick = { sing.setSemitones(semitones + 1) }, enabled = on && semitones < 6) { Text("+", style = MaterialTheme.typography.titleLarge) }
                    }
                    Text("Moves the song up or down to suit your voice (the tempo stays).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
