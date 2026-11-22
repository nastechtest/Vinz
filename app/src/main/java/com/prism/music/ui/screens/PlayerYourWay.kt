package com.vinz.music.ui.screens

import com.vinz.music.R
import com.vinz.music.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import com.vinz.music.ui.player.drawRibbonStrands
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.vinz.music.data.prefs.DeckPart
import com.vinz.music.data.prefs.PlayerButton
import com.vinz.music.data.prefs.PlayerPart
import com.vinz.music.data.prefs.canHide
import com.vinz.music.data.prefs.shows
import com.vinz.music.data.prefs.PlayerGesture
import com.vinz.music.data.prefs.PlayerLayout
import com.vinz.music.data.prefs.PlayerTimes
import com.vinz.music.data.prefs.PlayerTitleAlign
import com.vinz.music.data.prefs.ScrubberColor
import com.vinz.music.data.prefs.SessionButton
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.ui.theme.VinzTokens
import kotlin.math.roundToInt

/*
 * Settings → Player, the parts that came with 1.7.4 ("the player, your way"): its layout, its buttons, the order of what's
 * under the title, the title and times, and gestures. Every choice shows what it does as a small example, drawn the way
 * the player is (always dark, white type), and the preview pinned at the top of the page shows the whole player as it'll be.
 */

private val PlayerDark = Color(0xFF17171C)
private val Ink = Color.White.copy(alpha = 0.85f)
private val Faint = Color.White.copy(alpha = 0.28f)

// ---------------------------------------------------------------------------- Layout

/** A tiny dark phone showing where things sit in [layout]. */
@Composable
internal fun LayoutSample(layout: PlayerLayout) {
    val light = Brush.horizontalGradient(*VinzTokens.spectrumColors(true).toTypedArray())
    SampleScreen(Modifier.fillMaxWidth().height(150.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (layout) {
                PlayerLayout.LYRICS -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)).background(Brush.linearGradient(listOf(Color(0xFF8A6BFF), Color(0xFFFF7A59)))))
                        Spacer(Modifier.width(5.dp))
                        Column { Bar(0.6f, 4.dp, Ink); Spacer(Modifier.height(2.dp)); Bar(0.4f, 3.dp, Faint) }
                    }
                    Spacer(Modifier.height(10.dp))
                    listOf(0.7f to Faint, 0.85f to Ink, 0.6f to Faint, 0.75f to Faint).forEach { (w, c) -> Bar(w, 4.dp, c, Alignment.Start); Spacer(Modifier.height(6.dp)) }
                }
                PlayerLayout.VINYL -> {
                    Box(Modifier.fillMaxWidth(0.52f).aspectRatio(1f)) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFF0B0B0D)).border(0.6.dp, Color.White.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxSize(0.66f).clip(CircleShape).border(0.5.dp, Color.White.copy(alpha = 0.07f), CircleShape))
                            Box(Modifier.fillMaxSize(0.42f).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF8A6BFF), Color(0xFFFF7A59)))))
                        }
                        Box(Modifier.align(Alignment.TopEnd).padding(top = 2.dp).size(2.dp, 34.dp).rotate(22f).background(Color(0xFFCFCFD6)))
                    }
                    Spacer(Modifier.height(6.dp)); Bar(0.6f, 4.dp, Ink); Spacer(Modifier.height(6.dp)); Transport()
                }
                PlayerLayout.CASSETTE -> {
                    Spacer(Modifier.height(10.dp))
                    // The tape: the cover as its label, two reels in the window.
                    Box(Modifier.fillMaxWidth(0.8f).aspectRatio(1.56f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1E1E23))) {
                        Box(
                            Modifier.padding(4.dp).fillMaxWidth().fillMaxHeight(0.62f).clip(RoundedCornerShape(2.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF8A6BFF), Color(0xFFFF7A59)))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                Modifier.fillMaxWidth(0.62f).clip(CircleShape).background(Color(0xFF0C0C0E)).padding(horizontal = 6.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) { repeat(2) { Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF4A372C)).border(2.dp, Color(0xFFE9E9EC), CircleShape)) } }
                        }
                    }
                    Spacer(Modifier.height(8.dp)); Bar(0.6f, 4.dp, Ink); Spacer(Modifier.height(6.dp)); Transport()
                }
                else -> {
                    val cover = when (layout) { PlayerLayout.COVER -> 0.7f; PlayerLayout.MINIMAL -> 0.52f; else -> 0.46f }
                    Box(Modifier.fillMaxWidth(cover).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(Brush.linearGradient(listOf(Color(0xFF8A6BFF), Color(0xFFFF7A59)))))
                    Spacer(Modifier.height(6.dp))
                    Bar(0.6f, 4.dp, Ink, Alignment.Start); Spacer(Modifier.height(3.dp)); Bar(0.4f, 3.dp, Faint, Alignment.Start)
                    when (layout) {
                        PlayerLayout.STANDARD -> {
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(Faint)) { Box(Modifier.fillMaxWidth(0.4f).height(2.dp).background(light)) }
                            Spacer(Modifier.height(5.dp)); Transport()
                        }
                        PlayerLayout.MINIMAL -> { Spacer(Modifier.height(6.dp)); Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White)) }
                        else -> {}
                    }
                }
            }
        }
    }
}

/** Previous, play and next, small. */
@Composable
private fun Transport() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Ink))
        Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
        Box(Modifier.size(6.dp).clip(CircleShape).background(Ink))
    }
}

/** A rounded bar [fraction] of the width: a line of text in a sample. */
@Composable
private fun Bar(fraction: Float, height: Dp, color: Color, align: Alignment.Horizontal = Alignment.CenterHorizontally) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (align == Alignment.Start) Alignment.CenterStart else Alignment.Center) {
        Box(Modifier.fillMaxWidth(fraction).height(height).clip(CircleShape).background(color))
    }
}

/** The player's dark, for an example to sit on. */
@Composable
internal fun SampleScreen(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF2A2140), PlayerDark))), content = content)
}

// ---------------------------------------------------------------------------- What's on the player (1.8.0)

/** The row's buttons (three to six), in their order, to drag; the others to add. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun RowButtons() {
    val s = LocalAppSettings.current
    val c = LocalContainer.current
    val prefs = c.settings
    val chosen = s.player.buttons
    // The Video button also needs the music video on (the old "Song / Video" switch: off, audio only).
    fun on(b: PlayerButton) = b != PlayerButton.VIDEO || s.showVideoSwitch
    Label(tr(R.string.buttons_in_the_row), info = tr(R.string.pick_to_for_the_row_under, PlayerButton.MIN, PlayerButton.MAX))
    com.vinz.music.ui.components.ReorderList(chosen, { it }, { prefs.setPlayerButtons(it) }) { b, _, _, mod ->
        OrderRow(
            b.label, buttonIcon(b), on(b),
            canCheck = chosen.size > PlayerButton.MIN || !on(b) || b == PlayerButton.VIDEO,
            onCheck = { v ->
                when {
                    b == PlayerButton.VIDEO && v -> prefs.setShowVideoSwitch(true)
                    v -> {}
                    chosen.size > PlayerButton.MIN -> prefs.setPlayerButtons(chosen - b)
                    // The last three: the music video can still go (audio only, as the old Song / Video switch did).
                    b == PlayerButton.VIDEO -> { prefs.setShowVideoSwitch(false); c.player.setVideoMode(false) }
                }
            },
            modifier = mod, grip = Modifier.dragHandle(),
        )
    }
    val rest = PlayerButton.entries.filter { it !in chosen }
    if (rest.isNotEmpty() && chosen.size < PlayerButton.MAX) {
        Label(tr(R.string.add_a_button))
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rest.forEach { b ->
                com.vinz.music.ui.components.VinzChip(false, {
                    prefs.setPlayerButtons(chosen + b)
                    if (b == PlayerButton.VIDEO) prefs.setShowVideoSwitch(true)
                }, b.label, icon = buttonIcon(b))
            }
        }
    }
    if (chosen != PlayerButton.DEFAULT) com.vinz.music.ui.components.VinzTextButton({ prefs.setPlayerButtons(PlayerButton.DEFAULT) }, Modifier.padding(start = 12.dp)) {
        Text(tr(R.string.back_to_lyrics_video_and_up))
    }
}

internal fun deckPart(d: DeckPart): PlayerPart? = when (d) {
    DeckPart.STRIP -> PlayerPart.LYRIC_LINE
    DeckPart.SCRUBBER -> PlayerPart.PROGRESS
    DeckPart.VOLUME -> PlayerPart.VOLUME
    DeckPart.BUTTONS -> PlayerPart.BUTTONS
    // Play stays; previous / next are hidden in the preview.
    DeckPart.TRANSPORT -> null
}

/**
 * The player as it is now, drawn small and dark the way the player is: every part ([PlayerPart]) a switch, faint with an
 * eye struck through while hidden. The parts under the title follow the listener's order; play / pause never hides.
 */
@Composable
fun PlayerPartsPreview(s: com.vinz.music.data.prefs.AppSettings, modifier: Modifier = Modifier, onToggle: (PlayerPart) -> Unit) {
    val light = Brush.horizontalGradient(*VinzTokens.spectrumColors(true).toTypedArray())
    @Composable
    fun Spot(part: PlayerPart, m: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
        val shown = s.shows(part)
        val state = if (shown) tr(R.string.shown) else tr(R.string.hidden)
        Box(
            m.clip(RoundedCornerShape(6.dp))
                .toggleable(shown, enabled = s.canHide(part), role = androidx.compose.ui.semantics.Role.Switch) { onToggle(part) }
                .semantics(mergeDescendants = true) { contentDescription = part.label; stateDescription = state }
                .then(if (shown) Modifier else Modifier.border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(6.dp)))
                .padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.graphicsLayer { alpha = if (shown) 1f else 0.22f }, contentAlignment = Alignment.Center, content = content)
            if (!shown) Icon(VinzIcons.Hidden, null, Modifier.align(Alignment.TopEnd).size(12.dp), tint = Color.White.copy(alpha = 0.7f))
        }
    }
    val tiny = 11.sp
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        SampleScreen(Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("playerPartsPreview")) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spot(PlayerPart.HANDLE) { Box(Modifier.padding(horizontal = 14.dp, vertical = 3.dp).size(30.dp, 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.6f))) }
                Spot(PlayerPart.FROM_ALBUM) { Text(tr(R.string.from_the_album_2, tr(R.string.northern_lights)), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1) }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(0.62f)) {
                    Spot(PlayerPart.COVER, Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(Color(0xFF8A6BFF), Color(0xFFFF7A59)))))
                    }
                    // With Crossfade on: the cover dissolving into the next song's, drawn as the next cover over a corner.
                    if (s.mix.on) Spot(PlayerPart.BLEND_COVERS, Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                        Box(Modifier.size(30.dp).clip(RoundedCornerShape(5.dp)).background(Brush.linearGradient(listOf(Color(0xFF2EC5CE).copy(alpha = 0.9f), Color(0xFF8A6BFF).copy(alpha = 0.2f)))))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Spot(PlayerPart.TITLE) { Text(tr(R.string.northern_lights), color = Color.White, fontSize = 16.sp, maxLines = 1) }
                        Spot(PlayerPart.ARTIST) { Text(tr(R.string.vinz_sample), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1) }
                    }
                    Spot(PlayerPart.LIKE) { Icon(VinzIcons.Heart, null, Modifier.padding(4.dp).size(18.dp), tint = Color.White) }
                    Spot(PlayerPart.MENU) { Icon(VinzIcons.More, null, Modifier.padding(4.dp).size(18.dp), tint = Color.White) }
                }
                s.player.deck.forEach { deck ->
                    Spacer(Modifier.height(6.dp))
                    when (deck) {
                        DeckPart.STRIP -> Spot(PlayerPart.LYRIC_LINE, Modifier.fillMaxWidth()) {
                            Text(tr(R.string.and_the_sky_lit_up), Modifier.fillMaxWidth(), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1)
                        }
                        DeckPart.SCRUBBER -> Column(Modifier.fillMaxWidth()) {
                            Spot(PlayerPart.PROGRESS, Modifier.fillMaxWidth()) {
                                Box(Modifier.padding(vertical = 5.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Faint)) {
                                    Box(Modifier.fillMaxWidth(0.4f).height(3.dp).background(light))
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Spot(PlayerPart.TIMES) { Text("1:12", color = Color.White.copy(alpha = 0.6f), fontSize = tiny) }
                                Spacer(Modifier.weight(1f))
                                Spot(PlayerPart.BEST_PART) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(VinzIcons.Flame, null, Modifier.size(11.dp), tint = Color.White.copy(alpha = 0.8f))
                                        Text(" " + tr(R.string.best_part), color = Color.White.copy(alpha = 0.8f), fontSize = tiny, maxLines = 1)
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Spot(PlayerPart.QUALITY) { Text(tr(R.string.sample_stream_quality), color = Color.White.copy(alpha = 0.6f), fontSize = tiny, maxLines = 1) }
                                // With Crossfade on: the mark where the next song comes in.
                                if (s.mix.on) {
                                    Spacer(Modifier.width(6.dp))
                                    Spot(PlayerPart.CROSSFADE_MARK) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(2.dp, 10.dp).background(Color.White.copy(alpha = 0.85f)))
                                            Text(" " + tr(R.string.crossfade_mark), color = Color.White.copy(alpha = 0.6f), fontSize = tiny, maxLines = 1)
                                        }
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                Spot(PlayerPart.TIMES) { Text("−2:31", color = Color.White.copy(alpha = 0.6f), fontSize = tiny) }
                            }
                        }
                        DeckPart.TRANSPORT -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            Spot(PlayerPart.SHUFFLE_REPEAT) { Icon(VinzIcons.Shuffle, null, Modifier.size(16.dp), tint = Ink) }
                            Spot(PlayerPart.PREV_NEXT) { Icon(VinzIcons.Previous, null, Modifier.size(22.dp), tint = Color.White) }
                            // Play / pause always stays.
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(Color.White).semantics { contentDescription = tr(R.string.play_pause_always_stays) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(VinzIcons.Pause, null, Modifier.size(20.dp), tint = Color.Black) }
                            Spot(PlayerPart.PREV_NEXT) { Icon(VinzIcons.Next, null, Modifier.size(22.dp), tint = Color.White) }
                            Spot(PlayerPart.SHUFFLE_REPEAT) { Icon(VinzIcons.Repeat, null, Modifier.size(16.dp), tint = Ink) }
                        }
                        DeckPart.VOLUME -> Spot(PlayerPart.VOLUME, Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(VinzIcons.VolumeDown, null, Modifier.size(12.dp), tint = Faint)
                                Box(Modifier.padding(horizontal = 6.dp).weight(1f).height(3.dp).clip(CircleShape).background(Faint)) {
                                    Box(Modifier.fillMaxWidth(0.7f).height(3.dp).background(Color.White.copy(alpha = 0.7f)))
                                }
                                Icon(VinzIcons.VolumeUp, null, Modifier.size(12.dp), tint = Faint)
                            }
                        }
                        DeckPart.BUTTONS -> Spot(PlayerPart.BUTTONS) {
                            val pill = s.view.buttonsPill
                            Row(
                                if (pill) Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp)
                                else Modifier.padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(if (pill) 10.dp else 18.dp), verticalAlignment = Alignment.CenterVertically,
                            ) { s.player.buttons.filter { it != PlayerButton.VIDEO || s.showVideoSwitch }.forEach { Icon(buttonIcon(it), null, Modifier.size(16.dp), tint = Ink) } }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Spot(PlayerPart.OUTPUT) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(light))
                        Text("  " + tr(R.string.this_phone) + "  ", color = Color.White.copy(alpha = 0.6f), fontSize = tiny, maxLines = 1)
                        Icon(VinzIcons.Cast, null, Modifier.size(10.dp), tint = Color.White.copy(alpha = 0.6f))
                    }
                }
                // Three-button navigation only: the band behind its buttons (#250).
                if (threeButtonNav()) {
                    Spacer(Modifier.height(4.dp))
                    Spot(PlayerPart.NAV_BAND, Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            repeat(3) { Box(Modifier.size(6.dp).clip(CircleShape).background(Faint)) }
                        }
                    }
                }
            }
        }
    }
}

/** A navigation bar tall enough to be three buttons (gesture navigation's is a thin strip). */
@Composable
private fun threeButtonNav(): Boolean {
    val density = androidx.compose.ui.platform.LocalDensity.current
    return with(density) { WindowInsets.navigationBars.getBottom(density).toDp() } >= 40.dp
}

/**
 * A tickable row with its icon; in a [com.vinz.music.ui.components.ReorderList] it also takes the list's [modifier] (hold
 * to carry it) and shows a [grip] to drag it at once, which also carries the list's TalkBack moves and keys (1.8.4: the
 * up / down arrows went).
 */
@Composable
internal fun OrderRow(
    label: String, icon: ImageVector, on: Boolean, canCheck: Boolean,
    onCheck: (Boolean) -> Unit,
    modifier: Modifier = Modifier, grip: Modifier? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        VinzCheck(on, enabled = canCheck, label = label, onChange = onCheck)
        Icon(icon, null, Modifier.size(20.dp), tint = if (on) scheme.onSurface else scheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (on) scheme.onSurface else scheme.onSurfaceVariant)
        if (grip != null) com.vinz.music.ui.components.DragGrip(grip.padding(start = 4.dp, end = 8.dp), tr(R.string.drag, label))
    }
}

fun buttonIcon(b: PlayerButton): ImageVector = when (b) {
    PlayerButton.LYRICS -> VinzIcons.Lyrics
    PlayerButton.VIDEO -> VinzIcons.Video
    PlayerButton.QUEUE -> VinzIcons.Queue
    PlayerButton.OUTPUT -> VinzIcons.Cast
    PlayerButton.SLEEP -> VinzIcons.Moon
    PlayerButton.LIKE -> VinzIcons.Heart
    PlayerButton.DISLIKE -> VinzIcons.ThumbDown
    PlayerButton.ADD_TO_PLAYLIST -> VinzIcons.PlaylistAdd
    PlayerButton.SHARE -> VinzIcons.Share
    PlayerButton.SING -> VinzIcons.Mic
    PlayerButton.DJ -> VinzIcons.Turntable
    PlayerButton.EQUALIZER -> VinzIcons.Equalizer
    PlayerButton.DOWNLOAD -> VinzIcons.Download
    PlayerButton.RADIO -> VinzIcons.Radio
    PlayerButton.SPEED -> VinzIcons.Speed
}

internal fun sessionIcon(b: SessionButton): ImageVector = when (b) {
    SessionButton.LIKE -> VinzIcons.Heart
    SessionButton.SHUFFLE -> VinzIcons.Shuffle
    SessionButton.REPEAT -> VinzIcons.Repeat
    SessionButton.CAR_LYRICS -> VinzIcons.Lyrics
    SessionButton.RADIO -> VinzIcons.Radio
    SessionButton.DISLIKE -> VinzIcons.ThumbDown
    SessionButton.BACK_10 -> VinzIcons.Backward
    SessionButton.AHEAD_30 -> VinzIcons.Forward
}

internal fun deckIcon(p: DeckPart): ImageVector = when (p) {
    DeckPart.STRIP -> VinzIcons.Lyrics
    DeckPart.SCRUBBER -> VinzIcons.Timer
    DeckPart.TRANSPORT -> VinzIcons.PlayCircle
    DeckPart.VOLUME -> VinzIcons.VolumeUp
    DeckPart.BUTTONS -> VinzIcons.More
}

// ---------------------------------------------------------------------------- Title, times and the progress bar's color

@Composable
internal fun KnobSample(knob: com.vinz.music.data.prefs.ScrubberThumb, thickness: Float) {
    val light = Brush.horizontalGradient(*VinzTokens.spectrumColors(true).toTypedArray())
    SampleScreen(Modifier.fillMaxWidth().height(40.dp)) {
        androidx.compose.foundation.Canvas(Modifier.align(Alignment.Center).padding(horizontal = 14.dp).fillMaxWidth().height(16.dp)) {
            val h = 4.dp.toPx() * thickness
            val y = size.height / 2
            val x = size.width * 0.55f
            drawRoundRect(Faint, androidx.compose.ui.geometry.Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            drawRoundRect(light, androidx.compose.ui.geometry.Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(x, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            val r = 6.dp.toPx()
            when (knob) {
                com.vinz.music.data.prefs.ScrubberThumb.DOT, com.vinz.music.data.prefs.ScrubberThumb.AUTO ->
                    if (knob == com.vinz.music.data.prefs.ScrubberThumb.DOT) drawCircle(Color.White, r, androidx.compose.ui.geometry.Offset(x, y))
                com.vinz.music.data.prefs.ScrubberThumb.LINE -> drawLine(Color.White, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 3.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                com.vinz.music.data.prefs.ScrubberThumb.RING -> {
                    drawCircle(Color.Black.copy(alpha = 0.6f), r, androidx.compose.ui.geometry.Offset(x, y))
                    drawCircle(Color.White, r, androidx.compose.ui.geometry.Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
                com.vinz.music.data.prefs.ScrubberThumb.NONE -> {}
            }
        }
    }
}

// ---------------------------------------------------------------------------- Visualizer

@Composable
internal fun VisualizerSample(style: com.vinz.music.data.prefs.VisualizerStyle, color: ScrubberColor) {
    val fill = com.vinz.music.ui.player.scrubberFill(color, LocalContainer.current.player.currentSong.value?.thumbnail)
        ?: Brush.horizontalGradient(*VinzTokens.spectrumColors(true).toTypedArray())
    // A still frame of each, from a made-up spectrum.
    val bands = remember { FloatArray(20) { i -> (0.85f - i * 0.025f) * (0.55f + 0.45f * kotlin.math.abs(kotlin.math.sin(i * 1.7f))) } }
    SampleScreen(Modifier.fillMaxWidth().height(96.dp)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val cover = size.height * 0.5f
            val cx = size.width / 2
            val coverTop = size.height * 0.06f
            fun coverRect() = drawRoundRect(
                Brush.linearGradient(listOf(Color(0xFF8A6BFF), Color(0xFFFF7A59))),
                androidx.compose.ui.geometry.Offset(cx - cover / 2, coverTop), androidx.compose.ui.geometry.Size(cover, cover), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            )
            when (style) {
                com.vinz.music.data.prefs.VisualizerStyle.GLOW -> drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent), androidx.compose.ui.geometry.Offset(cx, coverTop + cover / 2), cover * 1.3f),
                    cover * 1.3f, androidx.compose.ui.geometry.Offset(cx, coverTop + cover / 2),
                )
                com.vinz.music.data.prefs.VisualizerStyle.RING -> for (k in 3 downTo 1) {
                    val g = 3.dp.toPx() * k
                    drawRoundRect(
                        fill, androidx.compose.ui.geometry.Offset(cx - cover / 2 - g, coverTop - g), androidx.compose.ui.geometry.Size(cover + 2 * g, cover + 2 * g),
                        androidx.compose.ui.geometry.CornerRadius(4.dp.toPx() + g), alpha = 0.35f / k, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                    )
                }
                else -> {}
            }
            coverRect()
            val barY = size.height * 0.78f
            when (style) {
                com.vinz.music.data.prefs.VisualizerStyle.BARS -> {
                    val w = size.width / bands.size
                    bands.forEachIndexed { i, v ->
                        val h = size.height * 0.4f * v
                        drawRoundRect(fill, androidx.compose.ui.geometry.Offset(i * w + w * 0.15f, size.height - h), androidx.compose.ui.geometry.Size(w * 0.7f, h), androidx.compose.ui.geometry.CornerRadius(w * 0.35f), alpha = 0.5f)
                    }
                }
                com.vinz.music.data.prefs.VisualizerStyle.WAVE -> {
                    val path = androidx.compose.ui.graphics.Path()
                    val mid = size.height * 0.68f
                    val step = size.width / (bands.size - 1)
                    bands.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, mid - v * 10.dp.toPx()) else path.lineTo(i * step, mid - v * 10.dp.toPx()) }
                    bands.indices.reversed().forEach { i -> path.lineTo(i * step, mid + bands[i] * 10.dp.toPx()) }
                    path.close()
                    drawPath(path, fill, alpha = 0.35f)
                }
                else -> {}
            }
            // The progress bar (thicker and glowing for Pulse).
            val pulse = style == com.vinz.music.data.prefs.VisualizerStyle.PULSE
            val h = if (pulse) 4.dp.toPx() else 2.dp.toPx()
            if (pulse) drawRoundRect(fill, androidx.compose.ui.geometry.Offset(0f, barY - h * 1.5f), androidx.compose.ui.geometry.Size(size.width * 0.45f, h * 4), androidx.compose.ui.geometry.CornerRadius(h * 2), alpha = 0.25f)
            drawRoundRect(Faint, androidx.compose.ui.geometry.Offset(0f, barY), androidx.compose.ui.geometry.Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            drawRoundRect(fill, androidx.compose.ui.geometry.Offset(0f, barY), androidx.compose.ui.geometry.Size(size.width * 0.45f, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
        }
    }
}

@Composable
internal fun TitleSample(align: PlayerTitleAlign, weight: com.vinz.music.data.prefs.PlayerTitleWeight = LocalAppSettings.current.type.playerTitleWeight) {
    SampleScreen(Modifier.fillMaxWidth().height(56.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (align == PlayerTitleAlign.CENTER) Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f), horizontalAlignment = if (align == PlayerTitleAlign.CENTER) Alignment.CenterHorizontally else Alignment.Start) {
                Text(
                    tr(R.string.northern_lights), color = Color.White, fontSize = 12.sp, maxLines = 1, textAlign = if (align == PlayerTitleAlign.CENTER) TextAlign.Center else TextAlign.Start,
                    // Vinz's own big titles are light; at this size their own weight wouldn't show it.
                    fontWeight = weight.title ?: androidx.compose.ui.text.font.FontWeight.Light,
                )
                Text(tr(R.string.vinz_sample), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1, fontWeight = weight.artist)
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(Faint))
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(9.dp).clip(CircleShape).background(Faint))
        }
    }
}

/**
 * The artists under a title (#289): [separate], each name underlined on its own and the tap going straight to one;
 * else the whole line one target, a small menu of the names under it.
 */
@Composable
internal fun ArtistLinksSample(separate: Boolean) {
    SampleScreen(Modifier.fillMaxWidth().height(72.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(tr(R.string.northern_lights), color = Color.White, fontSize = 12.sp, maxLines = 1, fontWeight = androidx.compose.ui.text.font.FontWeight.Light)
            val names = listOf(tr(R.string.sample_artist_one), tr(R.string.sample_artist_two), tr(R.string.sample_artist_three))
            Row(verticalAlignment = Alignment.CenterVertically) {
                names.forEachIndexed { i, n ->
                    val mark = if (separate) Modifier.drawBehind {
                        drawLine(Color.White.copy(alpha = if (i == 1) 0.9f else 0.35f), androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1.dp.toPx())
                    } else Modifier
                    Text(n, mark, color = Color.White.copy(alpha = if (separate && i == 1) 0.95f else 0.6f), fontSize = 10.sp, maxLines = 1)
                    if (i < names.lastIndex) Text(", ", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            if (separate) Text(tr(R.string.opens_artist_page, names[1]), color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp)
            else Row(
                Modifier.clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) { names.forEach { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 8.sp) } }
        }
    }
}

@Composable
internal fun TimesSample(times: PlayerTimes) {
    SampleScreen(Modifier.fillMaxWidth().height(44.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 9.dp)) {
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Faint)) {
                Box(Modifier.fillMaxWidth(0.35f).height(3.dp).background(Brush.horizontalGradient(*VinzTokens.spectrumColors(true).toTypedArray())))
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (times == PlayerTimes.BOTH || times == PlayerTimes.ELAPSED) "1:12" else "", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                Text(if (times == PlayerTimes.BOTH || times == PlayerTimes.LEFT) "−2:31" else "", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
            }
        }
    }
}

@Composable
internal fun ScrubberStyleSample(style: com.vinz.music.data.prefs.ScrubberStyle, color: ScrubberColor) {
    val fill = com.vinz.music.ui.player.scrubberFill(color, LocalContainer.current.player.currentSong.value?.thumbnail)
        ?: Brush.horizontalGradient(*VinzTokens.spectrumColors(true).toTypedArray())
    val paths = remember { List(3) { androidx.compose.ui.graphics.Path() } }
    SampleScreen(Modifier.fillMaxWidth().height(44.dp)) {
        androidx.compose.foundation.Canvas(Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth().height(8.dp)) {
            val played = size.width * 0.58f
            val h = when (style) {
                com.vinz.music.data.prefs.ScrubberStyle.HAIRLINE -> 2.5.dp; com.vinz.music.data.prefs.ScrubberStyle.BOLD -> 5.dp
                com.vinz.music.data.prefs.ScrubberStyle.THUMB -> 2.dp; com.vinz.music.data.prefs.ScrubberStyle.WAVE -> 0.dp
                com.vinz.music.data.prefs.ScrubberStyle.RIBBONS -> 3.5.dp
            }.toPx()
            val y = size.height / 2
            if (style == com.vinz.music.data.prefs.ScrubberStyle.WAVE) {
                val n = 34
                val step = size.width / n
                for (i in 0 until n) {
                    val v = 0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(i * 0.9f) * kotlin.math.cos(i * 0.37f))
                    val bh = size.height * 1.6f * v
                    val x = i * step + step * 0.2f
                    val at = androidx.compose.ui.geometry.Offset(x, y - bh / 2)
                    val sz = androidx.compose.ui.geometry.Size(step * 0.6f, bh)
                    if (x < played) drawRoundRect(fill, at, sz, androidx.compose.ui.geometry.CornerRadius(step * 0.3f))
                    else drawRoundRect(Faint, at, sz, androidx.compose.ui.geometry.CornerRadius(step * 0.3f))
                }
                return@Canvas
            }
            val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
            drawRoundRect(Faint, androidx.compose.ui.geometry.Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width, h), r)
            drawRoundRect(fill, androidx.compose.ui.geometry.Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(played, h), r)
            if (style == com.vinz.music.data.prefs.ScrubberStyle.RIBBONS) {
                // The same strands as the player's, held still here.
                translate(top = y - h / 2) { drawRibbonStrands(paths, played, 0.35f, fill, barHeight = h) }
            }
            if (style == com.vinz.music.data.prefs.ScrubberStyle.THUMB || style == com.vinz.music.data.prefs.ScrubberStyle.RIBBONS) {
                drawCircle(Color.White, 5.dp.toPx(), androidx.compose.ui.geometry.Offset(played, y))
            }
        }
    }
}

// ---------------------------------------------------------------------------- Background

/** Background movement [v] (0 still … 1 lively) as the mesh's speed and its colors' strength (1 = as the cover … 1.5). */
fun meshMovement(v: Float): Pair<Float, Float> = if (v < 0.05f) 0f to 1f else v to 1f + 0.5f * v

/**
 * Why the color mesh holds still though its movement is up (#284), or null: Reduce motion, Performance mode, or the
 * phone's animations turned off (Settings → Accessibility, or developer options' animation scale).
 */
internal fun meshHeldBy(s: com.vinz.music.data.prefs.AppSettings, context: android.content.Context): String? = when {
    s.reduceMotion -> tr(R.string.reduce_motion_is_on_so_it)
    s.performanceMode -> tr(R.string.performance_mode_is_on_so_it_stays_still)
    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ->
        tr(R.string.your_phone_s_animations_are_off_so_it_stays_still)
    else -> null
}

/** A cover's colors for the examples while nothing's playing. */
private val SAMPLE_GRADIENT = com.vinz.music.ui.player.CoverGradient(0xFF8A6BFF.toInt(), 0xFFFF7A59.toInt())

@Composable
internal fun PlayerBackgroundSample(bg: com.vinz.music.data.prefs.PlayerBackground, art: String?) {
    val s = LocalAppSettings.current
    val scheme = MaterialTheme.colorScheme
    val standIn = Brush.linearGradient(listOf(Color(SAMPLE_GRADIENT.lead), Color(SAMPLE_GRADIENT.second)))
    Box(Modifier.fillMaxWidth().aspectRatio(0.66f).clip(RoundedCornerShape(10.dp)).background(com.vinz.music.ui.player.PlayerFallbackBackdrop)) {
        when (bg) {
            com.vinz.music.data.prefs.PlayerBackground.MESH ->
                if (art == null) Box(Modifier.fillMaxSize().background(standIn).background(Color.Black.copy(alpha = 0.35f)))
                // Still, and with no live blur: a page of small examples isn't the place to pay for it.
                else com.vinz.music.ui.player.ArtworkMeshBackdrop(com.vinz.music.ui.player.rememberArtworkMesh(art), intensity = s.meshIntensity, liveBlur = false)
            com.vinz.music.data.prefs.PlayerBackground.BLUR ->
                if (art == null) Box(Modifier.fillMaxSize().background(standIn).background(Color.Black.copy(alpha = 0.5f)))
                else com.vinz.music.ui.player.FullArtworkBlurBackdrop(com.vinz.music.ui.player.rememberFullArtworkBlur(art, s.player.blur))
            com.vinz.music.data.prefs.PlayerBackground.APPEARANCE -> {
                val (style, photo) = com.vinz.music.ui.theme.Backdrop.parse(
                    s.backdrops[com.vinz.music.ui.theme.PLAYER_BACKDROP] ?: s.backdrops[com.vinz.music.ui.theme.BackdropScreen.HOME.name],
                )
                com.vinz.music.ui.theme.BackdropLayer(style, photo, dim = 0.5f, motion = false, modifier = Modifier.fillMaxSize())
            }
            com.vinz.music.data.prefs.PlayerBackground.THEME -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(scheme.primary.copy(alpha = 0.55f), Color.Black))))
            com.vinz.music.data.prefs.PlayerBackground.BLACK -> Box(Modifier.fillMaxSize().background(Color.Black))
            com.vinz.music.data.prefs.PlayerBackground.WHITE -> Box(Modifier.fillMaxSize().background(com.vinz.music.ui.player.PlayerWhiteBackdrop))
            com.vinz.music.data.prefs.PlayerBackground.GRADIENT -> com.vinz.music.ui.player.CoverGradientBackdrop(
                com.vinz.music.ui.player.rememberCoverGradient(art) ?: SAMPLE_GRADIENT, s.view.gradientDepth,
            )
        }
        val white = bg == com.vinz.music.data.prefs.PlayerBackground.WHITE
        val ink = if (white) Color(0xFF1C1C1E) else Color.White
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Bottom) {
            Box(Modifier.fillMaxWidth(0.72f).height(6.dp).clip(CircleShape).background(ink.copy(alpha = 0.9f)))
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth(0.46f).height(4.dp).clip(CircleShape).background(ink.copy(alpha = 0.55f)))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(ink.copy(alpha = 0.35f)))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.size(16.dp).align(Alignment.CenterHorizontally).clip(CircleShape).background(ink))
        }
    }
}

