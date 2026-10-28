package com.prism.music.ui.player

import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import com.prism.music.ui.components.PrismSlider
import com.prism.music.ui.components.PrismTextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SliderDefaults
import com.prism.music.playback.VideoSyncState
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.MonoStyle
import kotlin.math.roundToInt

/** Plain-language codec name from a codec / mime string ("opus" → "Opus", "mp4a.40.2" → "AAC"). */
fun codecName(codec: String): String = when {
    codec.contains("opus", true) -> "Opus"
    codec.contains("mp4a", true) || codec.contains("aac", true) -> "AAC"
    codec.contains("vorbis", true) -> "Vorbis"
    codec.contains("flac", true) -> "FLAC"
    codec.contains("alac", true) -> "ALAC"
    // Uncompressed audio (WAV, AIFF) reaches the player as "audio/raw".
    codec.contains("raw", true) || codec.contains("pcm", true) || codec.contains("wav", true) -> "PCM"
    codec.contains("mp3", true) || codec.contains("mpeg", true) -> "MP3"
    codec.isBlank() -> "—"
    else -> codec.substringBefore('.').uppercase()
}

fun sampleRateText(hz: Int): String = if (hz <= 0) "—" else if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000f)

/**
 * Stats for nerds as one of the player's panels: the artwork tucks into the header, as it does for
 * lyrics and the queue, and the stats sit on the player's own backdrop below it.
 */
@Composable
fun NerdStatsPanel(modifier: Modifier = Modifier) {
    val stats by LocalContainer.current.nerdStats.collectAsState()
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        Text("Stats for nerds", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(10.dp))
        // Nothing has been decoded yet (e.g. a restored queue that hasn't started).
        if (stats.codec.isBlank() && stats.streamBitrateKbps <= 0) {
            Text("Play the song to see its audio details", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        // The essentials, big: what the audio is.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Codec", codecName(stats.codec), Modifier.weight(1f))
            StatTile("Bitrate", stats.streamBitrateKbps.takeIf { it > 0 }?.let { "$it kbps" } ?: "—", Modifier.weight(1f))
            StatTile("Sample rate", sampleRateText(stats.sampleRate), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        StatTile("Source", stats.source.ifBlank { "—" }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        val rows = listOf(
            "Video ID" to stats.videoId,
            "Source" to stats.source,
            "Stream" to listOfNotNull(stats.itag.takeIf { it > 0 }?.let { "itag $it" }, stats.container.takeIf { it.isNotBlank() }, stats.client.takeIf { it.isNotBlank() }?.let { "client $it" }).joinToString(" · "),
            "Codec" to listOfNotNull(stats.codec.takeIf { it.isNotBlank() }, stats.decoder.takeIf { it.isNotBlank() }).joinToString(" · "),
            "Bitrate" to "${stats.streamBitrateKbps} kbps" + if (stats.contentLength > 0) " · %.2f MB".format(stats.contentLength / 1_048_576f) else "",
            "Format" to "${stats.sampleRate} Hz · ${stats.channels} ch",
            "Output" to "${stats.outputEncoding} @ ${stats.outputSampleRate} Hz" + if (stats.offload) " · offload" else "",
            "Device" to stats.outputDevice,
            "Spatializer" to stats.spatializer,
            "Loudness" to (stats.loudnessDb?.let {
                (if (stats.loudnessMeasured) "%.1f LUFS".format(it) else "%.1f dB (YouTube)".format(it)) + " · gain %.1f dB".format(stats.normalizationGainDb)
            } ?: "—"),
            "Effects" to stats.effects,
            "Buffer" to "%.1f s · %d kbps est.".format(stats.bufferMs / 1000f, stats.bandwidthKbps),
            "Session" to "${stats.audioSessionId} · underruns ${stats.underruns}",
        ) + if (stats.videoResolution.isNotBlank()) listOf("Video" to "${stats.videoResolution} · ${stats.videoCodec} · dropped ${stats.droppedFrames}") else emptyList()
        rows.forEach { (k, v) ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(k, style = MonoStyle, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.width(92.dp))
                Text(v.ifBlank { "—" }, style = MonoStyle, color = Color.White.copy(alpha = 0.9f))
            }
        }
    }
}

/** How far lyrics can be moved either way, in ms (#109). */
const val MAX_LYRIC_OFFSET_MS = 120_000L

/**
 * Per-song lyric timing: nudge earlier / later, plus how the lyrics were
 * matched to the music video when one is playing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsTimingSheet(onDismiss: () -> Unit) {
    val pc = LocalContainer.current.player
    val song by pc.currentSong.collectAsState()
    val offset by pc.lyricOffset.collectAsState()
    val sync by pc.videoSync.collectAsState()
    val videoMode by pc.videoMode.collectAsState()
    val scheme = MaterialTheme.colorScheme
    fun fmt(ms: Long) = (if (ms > 0) "+" else if (ms < 0) "−" else "") + "%.1f s".format(kotlin.math.abs(ms) / 1000f)
    // The slider spans ±15 s, and grows to keep a bigger offset (set with the buttons) on it with room to spare; only the
    // buttons grow it, so it never shifts under a dragging finger.
    fun reachFor(ms: Long) = maxOf(15f, kotlin.math.ceil(kotlin.math.abs(ms) / 5000f) * 5f + 5f).coerceAtMost(MAX_LYRIC_OFFSET_MS / 1000f)
    var reach by remember { mutableFloatStateOf(reachFor(pc.lyricOffset.value)) }
    // Up to two minutes either way (#109: some lyrics are an intro or a whole verse out, and 15 s wasn't enough).
    fun nudge(d: Long) {
        val to = (offset + d).coerceIn(-MAX_LYRIC_OFFSET_MS, MAX_LYRIC_OFFSET_MS)
        reach = maxOf(reach, reachFor(to))
        pc.setLyricOffset(to)
    }
    // The same frosted sheet as the player's ⋯ menu it opens from.
    com.prism.music.ui.components.ActionSheet(song?.thumbnail, onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp)) {
            Text("Lyrics timing", style = MaterialTheme.typography.titleLarge)
            Text(
                if (videoMode) "For this song's music video" else "For this song",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                if (offset == 0L) "In sync" else "${fmt(offset)} · lyrics ${if (offset > 0) "earlier" else "later"}",
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(-5000L to "−5", -500L to "−0.5", -100L to "−0.1", 100L to "+0.1", 500L to "+0.5", 5000L to "+5").forEach { (d, label) ->
                    Text(
                        label, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(com.prism.music.ui.components.quietFill())
                            .clickable(onClickLabel = "Lyrics ${label.replace("−", "-")} seconds") { nudge(d) }.padding(horizontal = 11.dp, vertical = 10.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp)) {
                Text("Later", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("Earlier", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            PrismSlider(offset / 1000f, { pc.setLyricOffset((it * 10).roundToInt() * 100L) }, valueRange = -reach..reach)
            Text(
                "Kept on this phone, for this song only",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
            if (offset != 0L) PrismTextButton(onClick = { pc.setLyricOffset(0) }, Modifier.align(Alignment.End)) { Text("Reset") }
            if (videoMode) {
                Spacer(Modifier.height(10.dp))
                Text("Music video", style = MaterialTheme.typography.titleSmall)
                Text(
                    when (val s = sync) {
                        VideoSyncState.Measuring -> "Matching the lyrics to the video's audio…"
                        is VideoSyncState.Synced -> when {
                            kotlin.math.abs(s.lagMs) < 150 -> "Matched automatically — the video lines up with the song."
                            s.lagMs > 0 -> "Matched automatically — the song starts ${fmt(s.lagMs).removePrefix("+")} into the video, so the lyrics wait for it."
                            else -> "Matched automatically — the video skips the song's first ${fmt(-s.lagMs).removePrefix("−")}."
                        }
                        VideoSyncState.Unmatched -> "Couldn't match this video to the song automatically. Use the controls above to line the lyrics up."
                        VideoSyncState.Off -> "The video uses the song's own timing."
                    },
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                if (sync is VideoSyncState.Synced || sync == VideoSyncState.Unmatched) {
                    PrismTextButton(onClick = { pc.resyncVideo() }) {
                        Text(if (sync == VideoSyncState.Unmatched) "Try again" else "Match again")
                    }
                }
            }
        }
    }
}

/**
 * "Your best part": where this song's best part starts, set by the listener. Opens at their own (or the most replayed
 * part, or where the song is now); pick it on the bar, nudge it, hear it, save it. Saved, it's what the Best part button,
 * "Play from the best part", Rewind and Blend previews use for this song. Times are in the song's own (lyrics') timeline.
 */
@Composable
fun BestPartSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val pc = c.player
    val song by pc.currentSong.collectAsState()
    val s = song ?: run { onDismiss(); return }
    val current by c.bestParts.current.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val duration = (s.durationSec * 1000L).coerceAtLeast(1L)
    val mine = remember(s.id) { c.bestParts.mine(s.id) }
    var auto by remember(s.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(s.id) { auto = c.bestParts.automaticMs(s) }
    var at by remember(s.id) {
        mutableStateOf((mine ?: current?.takeIf { it.songId == s.id }?.startMs ?: pc.lyricPosition).coerceIn(0L, duration))
    }
    fun fmt(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
    fun set(ms: Long) { at = ms.coerceIn(0L, duration - 1_000L) }
    com.prism.music.ui.components.ActionSheet(s.thumbnail, onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp)) {
            Text("Your best part", style = MaterialTheme.typography.titleLarge)
            Text(
                "Where Best part takes you in this song, and where it starts in Rewind and Blend",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Text(fmt(at), style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"), modifier = Modifier.align(Alignment.CenterHorizontally))
            // Where it'd be without the listener's own: looked up, or (until then) what the player already has.
            val automatic = auto ?: current?.takeIf { it.songId == s.id && !it.mine }?.startMs
            Text(
                when {
                    automatic != null && kotlin.math.abs(at - automatic) < 1_000 -> "The part people replay most"
                    mine != null && at == mine -> "Your pick"
                    else -> "Pick a moment on the bar"
                },
                style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(8.dp))
            PrismSlider(at.toFloat() / duration, { set((it * duration).toLong()) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-5_000L to "−5 s", 5_000L to "+5 s").forEach { (d, label) ->
                    Text(
                        label, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(com.prism.music.ui.components.quietFill())
                            .clickable { set(at + d) }.padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
                val now = pc.lyricPosition
                Text(
                    "Now · ${fmt(now)}", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(com.prism.music.ui.components.quietFill())
                        .clickable { set(pc.lyricPosition) }.padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PrismTextButton(onClick = { pc.seekToLyric(at) }) { Text("Listen from here") }
                Spacer(Modifier.weight(1f))
                com.prism.music.ui.components.PrismButton(onClick = { scope.launch { c.bestParts.setMine(s, at); onDismiss() } }) { Text("Save") }
            }
            if (mine != null) PrismTextButton(onClick = { scope.launch { c.bestParts.setMine(s, null); onDismiss() } }) {
                Text(if (auto != null) "Back to the part people replay most" else "Forget my best part")
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2)
    }
}

@Composable
fun SleepTimerDialog(onDismiss: () -> Unit) {
    val pc = LocalContainer.current.player
    val sleepAt by pc.sleepAt.collectAsState()
    val endOfSong by pc.sleepEndOfSong.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                if (endOfSong) Text("Stops when this song ends", color = MaterialTheme.colorScheme.primary)
                else if (sleepAt > 0) Text("Stops in ${((sleepAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1} min", color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(endOfSong, { pc.setSleepEndOfSong(true); onDismiss() }, { Text("End of song") })
                    listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
                        FilterChip(false, { pc.setSleepTimer(m); onDismiss() }, { Text("$m min") })
                    }
                }
            }
        },
        confirmButton = { PrismTextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (sleepAt > 0 || endOfSong) ({
            PrismTextButton(onClick = { pc.setSleepTimer(0); pc.setSleepEndOfSong(false); onDismiss() }) { Text("Turn off") }
        }) else null,
    )
}
