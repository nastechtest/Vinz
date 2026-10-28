package com.vinz.music.ui.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.translate
import com.vinz.music.ui.player.drawRibbonStrands
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinz.music.data.prefs.DeckPart
import com.vinz.music.data.prefs.PlayerButton
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

@Composable
fun PlayerLayoutGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val layoutInfo = when (s.playerLayout) {
        PlayerLayout.MINIMAL -> "Minimal: swipe the cover for the next or previous song, and double-tap it to skip ahead or back (Gestures, below). "
        PlayerLayout.COVER -> "Cover: tap the cover for the controls; they tuck away again after a few seconds. "
        else -> ""
    } + "Tablets and phones turned sideways keep their side-by-side player."
    Group("Layout", info = layoutInfo) {
        ChoiceCards(
            PlayerLayout.entries, s.playerLayout,
            title = { it.label }, detail = { it.note },
            onSelect = { prefs.setPlayerLayout(it) },
        ) { LayoutSample(it) }
        if (s.playerLayout == PlayerLayout.VINYL) Toggle("Tonearm", "Swings onto the record while it plays", s.vinylTonearm) { prefs.setVinylTonearm(it) }
    }
}

/** A tiny dark phone showing where things sit in [layout]. */
@Composable
private fun LayoutSample(layout: PlayerLayout) {
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
private fun SampleScreen(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF2A2140), PlayerDark))), content = content)
}

// ---------------------------------------------------------------------------- Buttons

@Composable
fun PlayerButtonsGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val chosen = s.playerButtons
    Group("Buttons", info = "Pick ${PlayerButton.MIN} to ${PlayerButton.MAX} for the row under the controls. Hold one (or drag its grip) to put them in your order. The rest stay in the player's ⋯ menu.") {
        // The row as it'll be.
        SampleScreen(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(64.dp)) {
            Row(
                Modifier.align(Alignment.Center).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape).padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
            ) { chosen.forEach { Icon(buttonIcon(it), it.label, Modifier.size(22.dp), tint = Color.White.copy(alpha = 0.85f)) } }
        }
        // The picked ones, in their order, to drag; then the rest to pick from.
        com.vinz.music.ui.components.ReorderList(chosen, { it }, { prefs.setPlayerButtons(it) }) { b, i, held, mod ->
            OrderRow(
                b.label, buttonIcon(b), true,
                canCheck = chosen.size > PlayerButton.MIN,
                onCheck = { v -> if (!v) prefs.setPlayerButtons(chosen - b) },
                onUp = if (i > 0) ({ prefs.setPlayerButtons(chosen.toMutableList().apply { add(i - 1, removeAt(i)) }) }) else null,
                onDown = if (i < chosen.lastIndex) ({ prefs.setPlayerButtons(chosen.toMutableList().apply { add(i + 1, removeAt(i)) }) }) else null,
                modifier = mod, grip = Modifier.dragHandle(), held = held,
            )
        }
        PlayerButton.entries.filter { it !in chosen }.forEach { b ->
            OrderRow(
                b.label, buttonIcon(b), false, canCheck = chosen.size < PlayerButton.MAX,
                onCheck = { v -> if (v) prefs.setPlayerButtons(chosen + b) }, onUp = null, onDown = null,
            )
        }
        if (chosen != PlayerButton.DEFAULT) com.vinz.music.ui.components.VinzTextButton({ prefs.setPlayerButtons(PlayerButton.DEFAULT) }, Modifier.padding(start = 12.dp)) {
            Text("Back to Lyrics, Video and Up next")
        }
    }
}

/** The buttons beside play outside Vinz: the notification, the lock screen, Android Auto (Controls & gestures). */
@Composable
fun SessionButtonsGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val chosen = s.sessionButtons
    Group("Notification and car", info = "Your phone's notification and lock screen show the first two beside play; Android Auto shows them all, in this order. Hold one to move it.") {
        // The notification as it'll look: previous, play, next and the first two beside them.
        SampleScreen(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(56.dp)) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                chosen.getOrNull(0)?.let { Icon(sessionIcon(it), it.label, Modifier.size(20.dp), tint = Ink) }
                Icon(VinzIcons.Previous, null, Modifier.size(22.dp), tint = Color.White)
                Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) { Icon(VinzIcons.Pause, null, Modifier.size(16.dp), tint = Color.Black) }
                Icon(VinzIcons.Next, null, Modifier.size(22.dp), tint = Color.White)
                chosen.getOrNull(1)?.let { Icon(sessionIcon(it), it.label, Modifier.size(20.dp), tint = Ink) }
            }
        }
        com.vinz.music.ui.components.ReorderList(chosen, { it }, { prefs.setSessionButtons(it) }) { b, i, held, mod ->
            OrderRow(
                b.label, sessionIcon(b), true, canCheck = true,
                onCheck = { v -> if (!v) prefs.setSessionButtons(chosen - b) },
                onUp = if (i > 0) ({ prefs.setSessionButtons(chosen.toMutableList().apply { add(i - 1, removeAt(i)) }) }) else null,
                onDown = if (i < chosen.lastIndex) ({ prefs.setSessionButtons(chosen.toMutableList().apply { add(i + 1, removeAt(i)) }) }) else null,
                modifier = mod, grip = Modifier.dragHandle(), held = held,
            )
        }
        SessionButton.entries.filter { it !in chosen }.forEach { b ->
            OrderRow(
                b.label, sessionIcon(b), false, canCheck = true,
                onCheck = { v -> if (v) prefs.setSessionButtons(chosen + b) }, onUp = null, onDown = null,
            )
        }
        if (chosen != SessionButton.DEFAULT) com.vinz.music.ui.components.VinzTextButton({ prefs.setSessionButtons(SessionButton.DEFAULT) }, Modifier.padding(start = 12.dp)) {
            Text("Back to Vinz's buttons")
        }
    }
}

/**
 * A tickable row with its icon, and up / down to move it while it's ticked; in a [com.vinz.music.ui.components.ReorderList]
 * it also takes the list's [modifier] (hold to carry it) and shows a [grip] to drag it at once.
 */
@Composable
private fun OrderRow(
    label: String, icon: ImageVector, on: Boolean, canCheck: Boolean,
    onCheck: (Boolean) -> Unit, onUp: (() -> Unit)?, onDown: (() -> Unit)?,
    modifier: Modifier = Modifier, grip: Modifier? = null, held: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        VinzCheck(on, enabled = canCheck, label = label, onChange = onCheck)
        Icon(icon, null, Modifier.size(20.dp), tint = if (on) scheme.onSurface else scheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (on) scheme.onSurface else scheme.onSurfaceVariant)
        if (on && !held) {
            IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) { Icon(VinzIcons.ChevronUp, "Move $label up") }
            IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) { Icon(VinzIcons.ChevronDown, "Move $label down") }
        } else if (held) Spacer(Modifier.width(96.dp))
        if (grip != null) com.vinz.music.ui.components.DragGrip(grip.padding(start = 4.dp, end = 8.dp), "Drag $label")
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

private fun sessionIcon(b: SessionButton): ImageVector = when (b) {
    SessionButton.LIKE -> VinzIcons.Heart
    SessionButton.SHUFFLE -> VinzIcons.Shuffle
    SessionButton.REPEAT -> VinzIcons.Repeat
    SessionButton.CAR_LYRICS -> VinzIcons.Lyrics
    SessionButton.RADIO -> VinzIcons.Radio
    SessionButton.DISLIKE -> VinzIcons.ThumbDown
    SessionButton.BACK_10 -> VinzIcons.Backward
    SessionButton.AHEAD_30 -> VinzIcons.Forward
}

// ---------------------------------------------------------------------------- Arrange

/** The parts under the title, top to bottom: move them, and tick the ones that can be hidden. */
@Composable
fun PlayerArrangeGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val order = s.playerDeck
    Group("Under the title", info = "Top to bottom, as they sit on the player; hold one to move it. The progress bar, controls and buttons are always there.") {
        com.vinz.music.ui.components.ReorderList(order, { it }, { prefs.setPlayerDeck(it) }) { part, i, held, mod ->
            val shown = when (part) { DeckPart.STRIP -> s.lyricsOnPlayer; DeckPart.VOLUME -> !s.hideVolumeBar; else -> true }
            val hideable = part == DeckPart.STRIP || part == DeckPart.VOLUME
            OrderRow(
                part.label, deckIcon(part), shown || !hideable, canCheck = hideable,
                onCheck = { v -> if (part == DeckPart.STRIP) prefs.setLyricsOnPlayer(v) else prefs.setHideVolumeBar(!v) },
                onUp = if (i > 0) ({ prefs.setPlayerDeck(order.toMutableList().apply { add(i - 1, removeAt(i)) }) }) else null,
                onDown = if (i < order.lastIndex) ({ prefs.setPlayerDeck(order.toMutableList().apply { add(i + 1, removeAt(i)) }) }) else null,
                modifier = mod, grip = Modifier.dragHandle(), held = held,
            )
        }
        if (order != DeckPart.entries) com.vinz.music.ui.components.VinzTextButton({ prefs.setPlayerDeck(DeckPart.entries) }, Modifier.padding(start = 12.dp)) {
            Text("Back to Vinz's order")
        }
        // GitHub #116: a volume just for Vinz, for phones whose volume buttons don't work (or to keep other apps' as they are).
        Label(
            "The volume slider changes",
            info = s.volumeControl.label + ": " + if (s.volumeControl == com.vinz.music.data.prefs.VolumeControl.VINZ)
                "only Vinz gets quieter or louder; other apps and the phone's volume stay as they are. The phone's volume buttons still change the phone's volume."
            else "like the phone's volume buttons, every app's music volume.",
        )
        Chips(com.vinz.music.data.prefs.VolumeControl.entries, s.volumeControl, { it.label }) { v ->
            prefs.setVolumeControl(v)
            // Picking Vinz's own volume is picking the slider: show it if it was hidden.
            if (v == com.vinz.music.data.prefs.VolumeControl.VINZ && s.hideVolumeBar) prefs.setHideVolumeBar(false)
        }
    }
}

private fun deckIcon(p: DeckPart): ImageVector = when (p) {
    DeckPart.STRIP -> VinzIcons.Lyrics
    DeckPart.SCRUBBER -> VinzIcons.Timer
    DeckPart.TRANSPORT -> VinzIcons.PlayCircle
    DeckPart.VOLUME -> VinzIcons.VolumeUp
    DeckPart.BUTTONS -> VinzIcons.More
}

// ---------------------------------------------------------------------------- Title, times and the progress bar's color

@Composable
fun PlayerTitleGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group("Title and times") {
        ChoiceCards(
            PlayerTitleAlign.entries, s.playerTitleAlign, title = { it.label }, detail = { if (it == PlayerTitleAlign.START) "Under the cover's left edge" else "In the middle" },
            onSelect = { prefs.setPlayerTitleAlign(it) },
        ) { TitleSample(it) }
        Label("Title size")
        Chips(com.vinz.music.data.prefs.PlayerTitleSize.entries, s.playerTitleSize, { it.label }) { prefs.setPlayerTitleSize(it) }
        Label("Title weight", info = "Light is Vinz's own, and follows Text → Weight. Bold makes the artist's name heavier too.")
        ChoiceCards(
            com.vinz.music.data.prefs.PlayerTitleWeight.entries, s.type.playerTitleWeight, title = { it.label }, detail = { null },
            onSelect = { prefs.setPlayerTitleWeight(it) },
        ) { TitleSample(s.playerTitleAlign, it) }
        Toggle("Scrolling titles", "A long title scrolls across (off: it ends with …)", s.playerTitleScroll) { prefs.setPlayerTitleScroll(it) }
        Label("Times under the progress bar")
        ChoiceCards(
            PlayerTimes.entries, s.playerTimes, title = { it.label }, detail = { null }, onSelect = { prefs.setPlayerTimes(it) },
        ) { TimesSample(it) }
        Label("Progress bar color")
        ChoiceCards(
            ScrubberColor.entries, s.scrubberColor, title = { it.label },
            detail = {
                when (it) {
                    ScrubberColor.SPECTRUM -> "The split light"
                    ScrubberColor.ACCENT -> "Your accent color"
                    ScrubberColor.WHITE -> "Plain white"
                    ScrubberColor.COVER -> "The song's cover's color"
                }
            },
            onSelect = { prefs.setScrubberColor(it) },
        ) { BarSample(it) }
        // Thickness and the knob (1.7.4): the waveform keeps its own height.
        SliderSetting(
            "Progress bar thickness", s.scrubberThickness, 0.6f..2f, { if (it < 0.85f) "Thinner" else if (it <= 1.15f) "As the style" else "Thicker" },
            info = if (s.scrubberStyle == com.vinz.music.data.prefs.ScrubberStyle.WAVE) "The waveform keeps its own height; thickness shows until a song has been scanned." else null,
        ) {
            prefs.setScrubberThickness(if (it in 0.93f..1.07f) 1f else it)
        }
        Label("Knob at the playhead")
        ChoiceCards(
            com.vinz.music.data.prefs.ScrubberThumb.entries, s.scrubberThumb, title = { it.label },
            detail = { if (it == com.vinz.music.data.prefs.ScrubberThumb.AUTO) "A dot with the Thumb style" else null },
            onSelect = { prefs.setScrubberThumb(it) },
        ) { KnobSample(it, s.scrubberThickness) }
    }
}

@Composable
private fun KnobSample(knob: com.vinz.music.data.prefs.ScrubberThumb, thickness: Float) {
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

/**
 * The visualizer (1.7.4): off unless picked, since it's the one part of the player that works on every frame. It moves
 * with Vinz's own sound (no microphone), only while the player is open and playing.
 */
@Composable
fun PlayerVisualizerGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group("Visualizer", info = "It listens to Vinz's own sound (no microphone) and only runs while the player is open and playing. On an older phone, Off keeps the player lightest.") {
        ChoiceCards(
            com.vinz.music.data.prefs.VisualizerStyle.entries, s.visualizer, title = { it.label }, detail = { it.note },
            onSelect = { prefs.setVisualizer(it) },
        ) { VisualizerSample(it, s.visualizerColor) }
        if (s.visualizer != com.vinz.music.data.prefs.VisualizerStyle.OFF) {
            Label("Visualizer color")
            Chips(ScrubberColor.entries, s.visualizerColor, { it.label }) { prefs.setVisualizerColor(it) }
            // Why it doesn't move: a state, so it stays in view.
            if (s.reduceMotion) Note("Reduce motion is on, so it stays still.")
        }
    }
}

@Composable
private fun VisualizerSample(style: com.vinz.music.data.prefs.VisualizerStyle, color: ScrubberColor) {
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
private fun TitleSample(align: PlayerTitleAlign, weight: com.vinz.music.data.prefs.PlayerTitleWeight = LocalAppSettings.current.type.playerTitleWeight) {
    SampleScreen(Modifier.fillMaxWidth().height(56.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (align == PlayerTitleAlign.CENTER) Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f), horizontalAlignment = if (align == PlayerTitleAlign.CENTER) Alignment.CenterHorizontally else Alignment.Start) {
                Text(
                    "Northern Lights", color = Color.White, fontSize = 12.sp, maxLines = 1, textAlign = if (align == PlayerTitleAlign.CENTER) TextAlign.Center else TextAlign.Start,
                    // Vinz's own big titles are light; at this size their own weight wouldn't show it.
                    fontWeight = weight.title ?: androidx.compose.ui.text.font.FontWeight.Light,
                )
                Text("Vinz Sample", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1, fontWeight = weight.artist)
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(Faint))
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(9.dp).clip(CircleShape).background(Faint))
        }
    }
}

@Composable
private fun TimesSample(times: PlayerTimes) {
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

/** The progress bar's styles as cards, each drawn as it looks on the player (1.7.7, with Ribbons, #151). */
@Composable
fun ScrubberStyleCards() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    ChoiceCards(
        com.vinz.music.data.prefs.ScrubberStyle.entries, s.scrubberStyle, title = { it.label },
        detail = {
            when (it) {
                com.vinz.music.data.prefs.ScrubberStyle.HAIRLINE -> "A thin line"
                com.vinz.music.data.prefs.ScrubberStyle.BOLD -> "A thicker bar"
                com.vinz.music.data.prefs.ScrubberStyle.THUMB -> "A line and a knob"
                com.vinz.music.data.prefs.ScrubberStyle.WAVE -> "Each song's own shape"
                com.vinz.music.data.prefs.ScrubberStyle.RIBBONS -> "Strands flow along while it plays"
            }
        },
        onSelect = { prefs.setScrubberStyle(it) },
    ) { ScrubberStyleSample(it, s.scrubberColor) }
}

@Composable
private fun ScrubberStyleSample(style: com.vinz.music.data.prefs.ScrubberStyle, color: ScrubberColor) {
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

@Composable
private fun BarSample(color: ScrubberColor) {
    val fill = com.vinz.music.ui.player.scrubberFill(color, LocalContainer.current.player.currentSong.value?.thumbnail)
        ?: Brush.horizontalGradient(*VinzTokens.spectrumColors(true).toTypedArray())
    SampleScreen(Modifier.fillMaxWidth().height(36.dp)) {
        Box(Modifier.align(Alignment.Center).padding(horizontal = 12.dp).fillMaxWidth().height(5.dp).clip(CircleShape).background(Faint)) {
            Box(Modifier.fillMaxWidth(0.55f).height(5.dp).background(fill))
        }
    }
}

// ---------------------------------------------------------------------------- Background

/** The color mesh's motion and strength, and how soft the blurred cover is (under Look → Background). */
@Composable
fun PlayerBackgroundTuning() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    if (s.playerBackground == com.vinz.music.data.prefs.PlayerBackground.MESH) {
        SliderSetting("Motion", s.meshSpeed, 0f..1f, { if (it < 0.05f) "Still" else if (it < 0.5f) "Slow drift" else "Drifting" }) { prefs.setMeshSpeed(if (it < 0.05f) 0f else it) }
        if (s.reduceMotion && s.meshSpeed > 0f) Note("Reduce motion is on, so it stays still.")
        SliderSetting("Colors", s.meshIntensity, 0.4f..1.6f, { if (it < 0.8f) "Soft" else if (it <= 1.2f) "As the cover" else "Rich" }) { prefs.setMeshIntensity(it) }
    }
    if (s.playerBackground == com.vinz.music.data.prefs.PlayerBackground.MESH || s.playerBackground == com.vinz.music.data.prefs.PlayerBackground.BLUR) {
        SliderSetting("Softness", s.playerBlur, 0.5f..1.5f, { "${(it * 100).roundToInt()}%" }) { prefs.setPlayerBlur(it) }
    }
}

// ---------------------------------------------------------------------------- Gestures

/** What swipes, a double tap and a long press do on the player's cover, and swipes on the mini player. */
@Composable
fun PlayerGesturesGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group("Gestures", info = "On the player's cover. Swiping down always closes the player.") {
        GestureRow("Swipe left", s.coverSwipeLeft, PlayerGesture.choices()) { prefs.setGesture("cover_swipe_left", it) }
        GestureRow("Swipe right", s.coverSwipeRight, PlayerGesture.choices()) { prefs.setGesture("cover_swipe_right", it) }
        GestureRow("Swipe up", s.coverSwipeUp, PlayerGesture.choices()) { prefs.setGesture("cover_swipe_up", it) }
        GestureRow("Double tap", s.coverDoubleTap, PlayerGesture.choices(double = true)) {
            prefs.setGesture("cover_double_tap", it)
            // Seek by side needs a step; it used to be switched off by setting none.
            if (it == PlayerGesture.SEEK_SIDES && s.doubleTapSeekSeconds <= 0) prefs.setDoubleTapSeekSeconds(10)
        }
        if (s.coverDoubleTap == PlayerGesture.SEEK_SIDES) Chips(listOf(3, 5, 10, 15), s.doubleTapSeekSeconds.coerceAtLeast(3), { "$it s a tap" }) { prefs.setDoubleTapSeekSeconds(it) }
        GestureRow("Long press", s.coverLongPress, PlayerGesture.choices()) { prefs.setGesture("cover_long_press", it) }
        Label("Mini player")
        GestureRow("Swipe left", s.miniSwipeLeft, PlayerGesture.choices(mini = true)) { prefs.setGesture("mini_swipe_left", it) }
        GestureRow("Swipe right", s.miniSwipeRight, PlayerGesture.choices(mini = true)) { prefs.setGesture("mini_swipe_right", it) }
        GestureRow("Swipe up", s.miniSwipeUp, PlayerGesture.choices(mini = true)) { prefs.setGesture("mini_swipe_up", it) }
        GestureRow("Pull down", s.miniSwipeDown, PlayerGesture.choices(mini = true)) { prefs.setGesture("mini_swipe_down", it) }
    }
}

/** A gesture and what it does; tapping it lists the choices. */
@Composable
private fun GestureRow(title: String, value: PlayerGesture, choices: List<PlayerGesture>, onPick: (PlayerGesture) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Change what $title does") { open = true }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(value.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(VinzIcons.ChevronDown, null, Modifier.padding(start = 4.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, { open = false }, Modifier.align(Alignment.TopEnd)) {
            choices.forEach { g ->
                DropdownMenuItem(
                    { Text(g.label) }, { open = false; onPick(g) },
                    trailingIcon = if (g == value) ({ Icon(VinzIcons.Check, null) }) else null,
                )
            }
        }
    }
}
