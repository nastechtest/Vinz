package com.vinz.music.ui.screens

import com.vinz.music.R
import com.vinz.music.tr
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinz.music.data.lyrics.LyricLine
import com.vinz.music.data.lyrics.LyricWord
import com.vinz.music.data.prefs.LyricsAhead
import com.vinz.music.data.prefs.LyricsAlign
import com.vinz.music.data.prefs.LyricsBackground
import com.vinz.music.data.prefs.LyricsColor
import com.vinz.music.data.prefs.LyricsExtraPlace
import com.vinz.music.data.prefs.LyricsLookPrefs
import com.vinz.music.data.prefs.LyricsMotion
import com.vinz.music.data.prefs.LyricsPosition
import com.vinz.music.data.prefs.LyricsStyle
import com.vinz.music.data.prefs.LyricsWeight
import com.vinz.music.ui.player.LyricsLook
import com.vinz.music.ui.player.RestingLine
import com.vinz.music.ui.player.drawNote
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import com.vinz.music.ui.player.SungLine
import com.vinz.music.ui.player.cardColor
import com.vinz.music.ui.player.extraStyle
import com.vinz.music.ui.player.lyricsFill
import com.vinz.music.ui.player.lyricsLookOf
import com.vinz.music.ui.player.rememberLyricsLook
import com.vinz.music.ui.player.solid
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import kotlin.math.roundToInt

/*
 * Customize Vinz → Lyrics: how the lyrics look, in four parts (1.8.0, #253), each picked on its own: the Animation (how
 * the sung line moves), the Text, the Background and the Effects. The six Styles of 1.7.5 are where you "Start from": each
 * sets the parts it used to fix, and any part can be changed after. Every choice shows what it does as a small example,
 * drawn by the same code as the lyrics themselves (always dark, white type).
 */

private val SampleDark = Color(0xFF15141B)

/** Vinz's own words for the examples, timed word by word: a line sung over three seconds. */
// The string's own trailing space doesn't survive (resources trim it): "Everycolor" until 1.8.0.
private val SAMPLE_WORDS = listOf(tr(R.string.every).trimEnd() + " ", "color ", "finds ", "its ", "way")
private val SAMPLE_LINE = LyricLine(
    0, SAMPLE_WORDS.joinToString(""), 3000,
    SAMPLE_WORDS.mapIndexed { i, w -> LyricWord(i * 560L, i * 560L + 520L, w) },
)
private val SAMPLE_BEFORE = LyricLine(-1, tr(R.string.somewhere_a_light_is_turning_on))
private val SAMPLE_AFTER = LyricLine(-1, tr(R.string.we_follow_the_line_of_the))
/** Background vocals under the sung line, in Vinz's own words too. */
private val SAMPLE_BACKING = "(" + SAMPLE_WORDS.take(2).joinToString("").trim().lowercase() + ")"

/** Where the examples rest: part-way through the line, a few words sung. */
private const val SAMPLE_REST_MS = 1500L
/** Times round the sample line when the page opens or a setting changes, before the examples rest. */
private const val SAMPLE_LOOPS = 3

/**
 * The examples' clock: [SAMPLE_LOOPS] times round the sample line whenever the page opens or a setting changes, then
 * held part-way through (at once with Reduce motion). Not round and round for as long as the page is open: Vinz's
 * sweep lays its line out again every frame, and while any accessibility service is on, each layout is reported to it,
 * so the page never went quiet (1.7.9's Android 9 check couldn't read the screen there at all).
 */
@Composable
private fun rememberSampleClock(): State<Long> {
    val s = LocalAppSettings.current
    val clock = remember { mutableLongStateOf(SAMPLE_REST_MS) }
    LaunchedEffect(s) {
        // Still with Reduce motion, and with Android's animations turned off (as the infinite transition before it was).
        val moving = !s.reduceMotion && (coroutineContext[androidx.compose.ui.MotionDurationScale]?.scaleFactor ?: 1f) > 0f
        if (moving) repeat(SAMPLE_LOOPS) {
            val start = withInfiniteAnimationFrameMillis { it }
            var now = start
            while (now - start < SAMPLE_LOOP_MS) {
                clock.longValue = SAMPLE_FROM_MS + (now - start)
                now = withInfiniteAnimationFrameMillis { it }
            }
        }
        clock.longValue = SAMPLE_REST_MS
    }
    return clock
}
private const val SAMPLE_FROM_MS = -400L
private const val SAMPLE_LOOP_MS = 4000L

/** The lyrics' typeface on its weight axis (as the lyrics page uses it), for Vinz's sweep in the examples. */
@Composable
private fun sampleAxis(): androidx.compose.ui.text.font.FontFamily? {
    val s = LocalAppSettings.current
    val choice = if (s.lyrics.font.isEmpty()) s.fontChoice else com.vinz.music.data.prefs.FontChoice.entries.firstOrNull { it.name == s.lyrics.font }
    return remember(choice) { choice?.let(com.vinz.music.ui.theme.AppFonts::axis) }
}

/** The look as the settings have it now, for the song playing (its cover's colors). */
@Composable
internal fun currentLook(): LyricsLook {
    val s = LocalAppSettings.current
    return rememberLyricsLook(s, LocalContainer.current.player.currentSong.value?.thumbnail)
}

/** The look [p] would draw, for the song playing: how each choice's example shows it, with the rest of your settings. */
@Composable
internal fun lookFor(p: LyricsLookPrefs): LyricsLook {
    val cover = LocalContainer.current.player.currentSong.value?.thumbnail
    val sung = lyricsFill(p.sungColor, p.sungCustom, cover)
    val others = lyricsFill(p.otherColor, p.otherCustom, cover).solid()
    val extra = lyricsFill(p.extraColor, p.otherCustom, cover).solid()
    val palette = com.vinz.music.ui.components.rememberArtPalette(cover)
    val card = if (p.onCard) cardColor(palette?.vibrant ?: palette?.dominant ?: Color(0xFF6B3FA0)) else null
    return remember(p, sung, others, extra, card) { lyricsLookOf(p, sung, others, extra, card) }
}

/**
 * Three lines as the lyrics page draws them in [look]: the one before, the sung one going through its words, the next.
 * [ground] stands in for the lyrics' background ([look]'s card color when it has one); [backing] adds background vocals
 * under the sung line and [progress] its progress line.
 */
@Composable
fun LyricsSample(
    look: LyricsLook, modifier: Modifier = Modifier, ground: Brush? = null, size: Float = 1f, lines: Int = 3, extra: String? = null,
    backing: Boolean = false, progress: Boolean = false,
) {
    val clock = rememberSampleClock()
    val axis = sampleAxis()
    val s = LocalAppSettings.current
    val centred = s.lyrics.align == LyricsAlign.CENTER
    val font = com.vinz.music.ui.theme.LocalLyricsFont.current ?: MaterialTheme.typography.bodyLarge.fontFamily
    val style = TextStyle(
        fontSize = (17 * size * look.sizeScale).sp, lineHeight = (21 * size * look.sizeScale).sp, fontFamily = font,
        fontWeight = FontWeight(look.weight), textAlign = if (centred) TextAlign.Center else TextAlign.Start,
    )
    val bg = ground ?: look.card?.let { SolidColor(it) } ?: Brush.verticalGradient(listOf(Color(0xFF2A2140), SampleDark))
    // A picture of the style, not text to read: its line changes every frame, and as text it kept accessibility busy
    // for as long as the page was open (Android 9's screen reader never settled there; the card's own title says it).
    Box(modifier.clearAndSetSemantics { }.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy((5 * look.spacing).dp)) {
            if (lines >= 3) RestingLine(look.shown(SAMPLE_BEFORE), style, look, 0.8f * look.othersAlpha, Modifier.fillMaxWidth(), maxLines = 1)
            Column(Modifier.fillMaxWidth()) {
                if (extra != null && look.extraAbove) SampleExtra(extra, style, look)
                SungLine(look.shown(SAMPLE_LINE), clock, style, look, axis, Modifier.fillMaxWidth(), maxLines = 1, onCard = look.card != null)
                if (progress) SampleProgress(clock, centred)
                if (backing) Text(
                    if (look.uppercase) SAMPLE_BACKING.uppercase() else SAMPLE_BACKING,
                    style = style.copy(fontSize = style.fontSize * look.backingSize, lineHeight = style.lineHeight * look.backingSize * 1.07f, fontWeight = FontWeight.Normal, shadow = look.shadow),
                    color = (if (look.card != null) Color.Black else Color.White).copy(alpha = 0.6f), maxLines = 1, modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
                if (extra != null && !look.extraAbove) SampleExtra(extra, style, look)
            }
            if (lines >= 2 && look.ahead > 0) RestingLine(look.shown(SAMPLE_AFTER), style, look, 0.8f * look.othersAlpha, Modifier.fillMaxWidth(), maxLines = 1)
        }
    }
}

@Composable
private fun SampleExtra(text: String, style: TextStyle, look: LyricsLook) {
    Text(
        text, style = look.extraStyle(style, 0.6f, 0.66f), color = (if (look.card != null) Color.Black else look.extraColor).copy(alpha = 0.78f),
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/** The progress line under the sample's sung line, as the lyrics page draws it (read in the draw phase). */
@Composable
private fun SampleProgress(clock: State<Long>, centred: Boolean) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().padding(top = 5.dp).height(2.dp)) {
        val f = (clock.value.toFloat() / SAMPLE_LINE.endMs).coerceIn(0f, 1f)
        if (f <= 0f) return@Canvas
        val full = size.width * 0.7f
        val w = full * f
        val x = if (centred) (size.width - w) / 2 else 0f
        drawRoundRect(
            com.vinz.music.ui.theme.VinzTokens.spectrum(true, x, x + full), Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height),
            androidx.compose.ui.geometry.CornerRadius(size.height / 2),
        )
    }
}

// ---------------------------------------------------------------------------- Text

/** A tall dark screen of lyrics as bars: the sung one bright at [fraction] of the way down, then [ahead] lines to come. */
@Composable
internal fun PositionSample(fraction: Float, ahead: Int) {
    val light = Brush.horizontalGradient(*com.vinz.music.ui.theme.VinzTokens.spectrumColors(true).toTypedArray())
    Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF2A2140), SampleDark)))) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
            val row = 9.dp.toPx()
            val h = 3.5.dp.toPx()
            val at = 4.dp.toPx() + (size.height - 8.dp.toPx()) * fraction * 1.4f
            val before = (at / row).toInt()
            for (k in -before..10) {
                val y = at + k * row
                if (y < 0 || y > size.height - h) continue
                if (k > 0 && k > ahead) continue
                val w = size.width * (if (k % 2 == 0) 0.82f else 0.64f)
                if (k == 0) drawRoundRect(light, androidx.compose.ui.geometry.Offset(0f, y - 1.dp.toPx()), androidx.compose.ui.geometry.Size(w, h + 2.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(h))
                else drawRoundRect(Color.White.copy(alpha = if (k < 0) 0.2f else 0.32f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(h))
            }
        }
    }
}

// ---------------------------------------------------------------------------- Background

/** What each background looks like, drawn small (the cover's colors from the song playing, or Vinz's own). */
@Composable
internal fun backgroundSample(kind: LyricsBackground, immersive: Boolean = false): Brush {
    val s = LocalAppSettings.current
    val cover = LocalContainer.current.player.currentSong.value?.thumbnail
    val palette = com.vinz.music.ui.components.rememberArtPalette(cover)
    val a = palette?.vibrant ?: Color(0xFF8A6BFF)
    val b = palette?.dominant ?: Color(0xFFFF7A59)
    val dim = { c: Color -> androidx.compose.ui.graphics.lerp(c, Color.Black, 0.55f) }
    return when (kind) {
        LyricsBackground.PLAYER -> if (immersive) Brush.linearGradient(listOf(a, b)) else Brush.verticalGradient(listOf(dim(a), Color(0xFF15141B)))
        LyricsBackground.DARK -> SolidColor(Color(0xFF0C0C10))
        LyricsBackground.BLUR -> Brush.radialGradient(listOf(dim(a), dim(b), Color(0xFF101014)))
        LyricsBackground.CANVAS -> Brush.linearGradient(listOf(dim(b), dim(a), dim(b)))
        LyricsBackground.COVER_COLOR -> cardColor(a).let { Brush.verticalGradient(listOf(it, androidx.compose.ui.graphics.lerp(it, Color.Black, 0.35f))) }
        LyricsBackground.COLOR -> cardColor(Color(s.lyricsBgColor)).let { top ->
            Brush.verticalGradient(listOf(top, if (s.lyricsBgColor2 == 0) androidx.compose.ui.graphics.lerp(top, Color.Black, 0.3f) else cardColor(Color(s.lyricsBgColor2))))
        }
    }
}

// ---------------------------------------------------------------------------- Effects

/** An instrumental break as the lyrics page draws it: dots or a note, half lit, where the lines sit. */
@Composable
internal fun GapSample(note: Boolean, centered: Boolean) {
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
        androidx.compose.foundation.Canvas(Modifier.size(width = if (note) 24.dp else 42.dp, height = if (note) 24.dp else 10.dp)) {
            if (note) {
                drawNote(Color.White.copy(alpha = 0.35f))
                clipRect(top = size.height * 0.5f) { drawNote(Color.White) }
            } else {
                val r = size.height / 2
                val stride = (size.width - 2 * r) / 2
                repeat(3) { d -> drawCircle(Color.White.copy(alpha = if (d == 0) 1f else 0.35f), r, Offset(r + d * stride, size.height / 2)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------- On the player

/** The player's foot as bars: the lyric line ([rows] lines, left or [centered]) over the progress bar. */
@Composable
internal fun StripSample(centered: Boolean, rows: Int) {
    val light = Brush.horizontalGradient(*com.vinz.music.ui.theme.VinzTokens.spectrumColors(true).toTypedArray())
    Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF2A2140), SampleDark)))) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp)) {
            val h = 4.dp.toPx()
            val gap = 5.dp.toPx()
            val bar = size.height - 2.dp.toPx()
            // Progress bar: a third played.
            drawRoundRect(Color.White.copy(alpha = 0.25f), androidx.compose.ui.geometry.Offset(0f, bar), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()))
            drawRoundRect(light, androidx.compose.ui.geometry.Offset(0f, bar), androidx.compose.ui.geometry.Size(size.width * 0.34f, 2.dp.toPx()))
            // The line, bottom up; a two-line one wraps its end onto the second.
            val widths = if (rows > 1) listOf(0.86f, 0.46f) else listOf(0.58f)
            widths.forEachIndexed { i, f ->
                val w = size.width * f
                val y = bar - 8.dp.toPx() - (widths.size - i) * (h + gap)
                val x = if (centered) (size.width - w) / 2 else 0f
                drawRoundRect(Color.White.copy(alpha = 0.85f), androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(h))
            }
        }
    }
}
