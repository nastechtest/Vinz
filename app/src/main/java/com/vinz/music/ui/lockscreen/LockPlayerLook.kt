package com.vinz.music.ui.lockscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ColorSource
import com.vinz.music.data.prefs.PlayerBackground
import com.vinz.music.data.prefs.ScrubberStyle
import com.vinz.music.data.prefs.ScrubberThumb
import com.vinz.music.ui.player.AppearanceBackdrop
import com.vinz.music.ui.player.ArtworkMesh
import com.vinz.music.ui.player.ArtworkMeshBackdrop
import com.vinz.music.ui.player.CoverGradient
import com.vinz.music.ui.player.CoverGradientBackdrop
import com.vinz.music.ui.player.FullArtworkBlurBackdrop
import com.vinz.music.ui.player.ThemeBackdrop
import com.vinz.music.ui.player.backdropBlackout
import com.vinz.music.ui.player.barHeights
import com.vinz.music.ui.player.decodeSmall
import com.vinz.music.ui.player.drawRibbonStrands
import com.vinz.music.ui.player.rememberArtworkMesh
import com.vinz.music.ui.player.rememberCoverGradient
import com.vinz.music.ui.player.rememberFullArtworkBlur
import com.vinz.music.ui.player.scrubberFill
import com.vinz.music.ui.theme.Backdrop
import com.vinz.music.ui.theme.BackdropScreen
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.PLAYER_BACKDROP
import com.vinz.music.ui.theme.ambientFrostShown
import com.vinz.music.ui.theme.VinzTokens
import com.vinz.music.ui.theme.pickSwatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 1.8.4, D53 (Reddit): "Same look as the player". With [com.vinz.music.data.prefs.ViewLook.lockScreenPlayerLook] on, the
 * lock screen takes the in-app player's background, its progress bar's style and color, and its accent.
 *
 * Everything here is the player's look held still. The lock screen is up while the phone is locked, so nothing on it may
 * run on its own: the color mesh never drifts and has no live blur (the form a TV uses), a moving Appearance background is
 * drawn without its motion, Ribbons' strands stand still, and the animated cover, the music video and the visualizer are
 * not brought over at all. What's left changes only when the song does, as the cover's frost did.
 */

/** The player's progress bar as the lock screen draws it: its style, thickness, color and knob. */
internal class LockBarLook(
    val style: ScrubberStyle,
    val thick: Float = 1f,
    /** The played part's own fill (Player → progress bar color); null for the split light. */
    val fill: Brush? = null,
    val thumb: ScrubberThumb = ScrubberThumb.AUTO,
    /** The song's outline for the Waveform style, once it has been scanned. */
    val wave: FloatArray? = null,
) {
    /** "With the style" is a dot on the styles that have a knob, as on the player. */
    val knob: ScrubberThumb
        get() = when (thumb) {
            ScrubberThumb.AUTO -> if (style == ScrubberStyle.THUMB || style == ScrubberStyle.RIBBONS) ScrubberThumb.DOT else ScrubberThumb.NONE
            else -> thumb
        }
}

/** The player's look for the lock screen: what's drawn behind every layout, and the progress bar. */
internal class LockPlayerLook(val ground: @Composable () -> Unit, val bar: LockBarLook)

/**
 * The player's look for the lock screen, or null with the setting off (the lock screen then draws as it always has).
 * [hidden]: the song is hidden, so nothing made from its cover is shown (black instead, as the frost gives way to black).
 */
@Composable
internal fun rememberLockPlayerLook(s: AppSettings, art: String?, hidden: Boolean, wave: FloatArray? = null): LockPlayerLook? {
    if (!s.view.lockScreenPlayerLook) return null
    val cover = art.takeUnless { hidden }
    val bg = s.player.background
    // Only the one that's up is worked out, as on the player.
    val mesh = rememberArtworkMesh(cover.takeIf { bg == PlayerBackground.MESH || bg == PlayerBackground.WHITE })
    val gradient = rememberCoverGradient(cover.takeIf { bg == PlayerBackground.GRADIENT })
    val blur = rememberFullArtworkBlur(cover.takeIf { bg == PlayerBackground.BLUR || (bg == PlayerBackground.APPEARANCE && appearanceFromCover(s)) }, s.player.blur)
    val fill = scrubberFill(s.scrubberColor, cover)
    return LockPlayerLook(
        ground = { LockPlayerGround(s, mesh, blur, gradient, hidden) },
        bar = LockBarLook(s.scrubberStyle, s.scrubberThickness, fill, s.scrubberThumb, wave.takeIf { s.scrubberStyle == ScrubberStyle.WAVE && !hidden }),
    )
}

/**
 * The player's background ([AppSettings.playerBackground]) behind a lock screen layout, still. White is the color mesh
 * here, as on the wide player: the lock screen's type, clock and system bars are drawn for a dark ground.
 */
@Composable
internal fun LockPlayerGround(s: AppSettings, mesh: ArtworkMesh?, blur: ImageBitmap?, gradient: CoverGradient?, hidden: Boolean = false) {
    val show = s.player.backdropShow
    // Clipped: the blurred cover and the drawn backgrounds reach a little past their edges.
    Box(Modifier.fillMaxSize().clipToBounds().background(Color.Black)) {
        when (s.player.background) {
            PlayerBackground.MESH, PlayerBackground.WHITE ->
                if (!hidden) ArtworkMeshBackdrop(mesh, speed = 0f, intensity = s.meshIntensity, blur = s.player.blur, liveBlur = false)
            PlayerBackground.BLUR -> if (!hidden) {
                FullArtworkBlurBackdrop(blur, scrim = 1.3f - 0.95f * show.coerceAtLeast(0f))
                Blackout(show)
            }
            // "Now playing" and the frost from what's playing are made from the cover, the first with a blur redone as
            // it's drawn: here they're the cover blurred once, and nothing at all behind a hidden song.
            PlayerBackground.APPEARANCE -> if (appearanceFromCover(s)) {
                if (!hidden) {
                    FullArtworkBlurBackdrop(blur, scrim = 1.3f - 0.95f * show.coerceAtLeast(0f))
                    Blackout(show)
                }
            } else {
                val still = remember(s) { s.copy(backdropMotion = false) }
                CompositionLocalProvider(LocalAppSettings provides still) { AppearanceBackdrop(show.coerceAtLeast(0f)) }
                Blackout(show)
            }
            PlayerBackground.THEME -> ThemeBackdrop()
            PlayerBackground.BLACK -> {}
            PlayerBackground.GRADIENT -> if (!hidden) CoverGradientBackdrop(gradient, s.view.gradientDepth)
        }
    }
}

/** Whether the Appearance background the player shows (its own pick, else Home's) is made from the song's cover. */
internal fun appearanceFromCover(s: AppSettings): Boolean {
    val (style, _) = Backdrop.parse(s.backdrops[PLAYER_BACKDROP] ?: s.backdrops[BackdropScreen.HOME.name])
    // The player draws it in its dark version whatever the app's theme.
    return style == Backdrop.ARTWORK || (style == Backdrop.DEFAULT && ambientFrostShown(s, dark = true))
}

/** #309: "Show background" below 0 darkens on, to black. */
@Composable
private fun Blackout(show: Float) {
    val veil = backdropBlackout(show)
    if (veil > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = veil)))
}

/**
 * The accent the player has when Vinz's color comes from the cover: the same swatch of the same cover, for the lock
 * screen's theme. Null (the stored accent, as always) with the look off, another color source, or a hidden song.
 */
@Composable
internal fun rememberLockAccent(s: AppSettings, art: String?): Color? {
    val url = art.takeIf { s.view.lockScreenPlayerLook && s.colorSource == ColorSource.ARTWORK && !s.behavior.lockScreenHideSong }
    val context = LocalContext.current
    var color by remember { mutableStateOf<Color?>(null) }
    val swatch = s.artworkSwatch
    val vivid = s.artworkVivid
    LaunchedEffect(url, swatch, vivid) {
        if (url == null) { color = null; return@LaunchedEffect }
        val picked = withContext(Dispatchers.IO) {
            runCatching { decodeSmall(context, url, 160)?.let { pickSwatch(Palette.from(it).generate(), swatch, vivid) } }.getOrNull()
        }
        // The last cover's color stays until the new one is known, as in the app.
        picked?.let { color = Color(it) }
    }
    return color
}

/** The room the Waveform style's bars take, as on the player. */
private val WAVE_HEIGHT = 22.dp

/** How many strands [drawRibbonStrands] draws. */
internal const val LOCK_RIBBON_STRANDS = 3

/**
 * The player's progress bar ([com.vinz.music.ui.player.ThinSlider] at rest) in the middle of the lock screen's line:
 * the style's own height, the played part in its color, the waveform's bars, Ribbons' strands standing still, the knob.
 */
internal fun DrawScope.drawPlayerBar(look: LockBarLook, fraction: Float, strands: List<Path>) {
    val filled = size.width * fraction.coerceIn(0f, 1f)
    val brush = look.fill ?: Brush.horizontalGradient(*VinzTokens.spectrumColors(true).toTypedArray(), startX = 0f, endX = size.width)
    val wave = look.wave?.takeIf { it.isNotEmpty() }
    val h = (if (wave != null) WAVE_HEIGHT else barHeights(look.style, look.thick).first).toPx().coerceAtMost(size.height)
    val top = (size.height - h) / 2
    if (wave != null) {
        val step = size.width / wave.size
        val w = (step * 0.6f).coerceAtLeast(1f)
        val round = CornerRadius(w / 2)
        wave.forEachIndexed { i, v ->
            val bar = h * (0.18f + 0.82f * v.coerceIn(0f, 1f))
            val x = step * i + (step - w) / 2
            val at = Offset(x, top + (h - bar) / 2)
            if (x + w / 2 <= filled) drawRoundRect(brush, at, Size(w, bar), round)
            else drawRoundRect(Color.White.copy(alpha = 0.22f), at, Size(w, bar), round)
        }
    } else {
        val round = CornerRadius(h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.20f), Offset(0f, top), Size(size.width, h), round)
        if (filled > 0f) {
            drawRoundRect(brush, Offset(0f, top), Size(filled.coerceAtLeast(h).coerceAtMost(size.width), h), round)
            if (look.style == ScrubberStyle.RIBBONS) translate(top = top) { drawRibbonStrands(strands, filled, 0f, brush, barHeight = h) }
        }
    }
    val knob = look.knob
    if (knob == ScrubberThumb.NONE) return
    val radius = 7.dp.toPx()
    val x = filled.coerceIn(radius, (size.width - radius).coerceAtLeast(radius))
    val y = size.height / 2
    when (knob) {
        ScrubberThumb.LINE -> {
            val half = maxOf(h / 2 + 5.dp.toPx(), radius).coerceAtMost(size.height / 2)
            val w = 3.dp.toPx()
            drawLine(Color.Black.copy(alpha = 0.18f), Offset(x, y - half + 1f), Offset(x, y + half + 1f), w + 1.5f, StrokeCap.Round)
            drawLine(Color.White, Offset(x, y - half), Offset(x, y + half), w, StrokeCap.Round)
        }
        ScrubberThumb.RING -> {
            drawCircle(Color.Black.copy(alpha = 0.18f), radius + 1.5f, Offset(x, y + 1f))
            drawCircle(Color.Black.copy(alpha = 0.55f), radius, Offset(x, y))
            drawCircle(Color.White, radius, Offset(x, y), style = Stroke(2.dp.toPx()))
        }
        else -> {
            drawCircle(Color.Black.copy(alpha = 0.18f), radius + 1.5f, Offset(x, y + 1f))
            drawCircle(Color.White, radius, Offset(x, y))
        }
    }
}
