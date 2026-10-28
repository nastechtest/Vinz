package com.prism.music.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.prism.music.data.model.hiRes
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ArtworkChange
import com.prism.music.data.prefs.ArtworkSwatch
import com.prism.music.data.prefs.ColorSource
import com.prism.music.data.prefs.ContrastLevel
import com.prism.music.data.prefs.LightStyle
import com.prism.music.data.prefs.SurfaceTint
import com.prism.music.ui.components.ColorPickerSheet
import com.prism.music.ui.components.PrismSlider
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.components.Segmented
import com.prism.music.ui.components.frostFill
import com.prism.music.ui.theme.ColorMath
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalIsDark
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTokens
import com.prism.music.ui.theme.SpectrumPresets
import com.prism.music.ui.theme.ThemeColors
import com.prism.music.ui.theme.isNeutralSeed
import com.prism.music.ui.theme.pickSwatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Which color the picker is open for. */
private sealed interface Picking {
    data object Accent : Picking
    data object LightAccent : Picking
    data object Surface : Picking
    /** One color of your own light ([index] past the end adds one). */
    data class Stop(val index: Int) : Picking
}

/**
 * Appearance → Color (1.7.2): a preview of your Prism (dark and light, its three parts numbered), pinned under the bar
 * once scrolled past ([COLOR_PREVIEW_KEY], see SubPage's pinned); below it every choice is a card showing what picking
 * it looks like.
 */
fun androidx.compose.foundation.lazy.LazyListScope.colorPage() {
    item(key = COLOR_PREVIEW_KEY) { ColorPreviewBar() }
    item(key = "color") { ColorSettings() }
}

const val COLOR_PREVIEW_KEY = "color-preview"

/** Which mode the preview shows (null = the app's own); shared by the page's preview and its pinned copy. */
private val previewDark = mutableStateOf<Boolean?>(null)

/**
 * Your Prism as it is now: its Now playing in dark or light (a switch above it), with the three parts numbered on it and
 * named in a key underneath, each with a swatch of what it is. [pinned]: the one-row copy held under the bar while the
 * page scrolls.
 */
@Composable
fun ColorPreviewBar(pinned: Boolean = false) {
    val s = LocalAppSettings.current
    val scheme = MaterialTheme.colorScheme
    val dark = previewDark.value ?: LocalIsDark.current
    val c = rememberColors(s, dark)
    val song by LocalContainer.current.player.currentSong.collectAsState()
    if (pinned) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                .background(frostFill(4f)).background(scheme.surface.copy(alpha = 0.82f))
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        ) { ColorStage(c, song, compact = true) }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your Vinz", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Light)
                Text("Changes the moment you pick", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Segmented(listOf(true, false), dark, { if (it) "Dark" else "Light" }, Modifier.width(164.dp)) { previewDark.value = it }
        }
        ColorStage(c, song)
        PartsKey(c)
    }
}

/** The three parts, numbered as on the preview, each with a swatch of itself in [c]. */
@Composable
private fun PartsKey(c: ThemeColors) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorPart.entries.forEach { p ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(frostFill())
                    .border(1.dp, scheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(16.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.number}", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    when (p) {
                        ColorPart.LIGHT -> Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Brush.horizontalGradient(*c.run.toTypedArray())))
                        ColorPart.ACCENT -> Box(Modifier.size(14.dp).clip(CircleShape).background(c.scheme.primary))
                        ColorPart.BACKGROUND -> Box(
                            Modifier.size(width = 26.dp, height = 14.dp).clip(RoundedCornerShape(4.dp)).background(c.scheme.background)
                                .border(1.dp, scheme.onSurface.copy(alpha = 0.2f), RoundedCornerShape(4.dp)),
                        )
                    }
                }
                var open by androidx.compose.runtime.saveable.rememberSaveable(p.name) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                    InfoMark(open, p.title) { open = !open }
                }
                InfoText(open, p.detail)
            }
        }
    }
}

@Composable
private fun ColorSettings() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val dark = LocalIsDark.current
    var picking by remember { mutableStateOf<Picking?>(null) }
    val white = s.lightStyle == LightStyle.WHITE
    val cover = rememberCoverPalette()
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        // How the page works, behind an ⓘ like every other explanation.
        var introOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        Column(Modifier.padding(horizontal = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Three parts, numbered in the preview: ① the light, ② the accent and ③ the background.",
                    Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium,
                )
                InfoMark(introOpen, "the color page") { introOpen = !introOpen }
            }
            InfoText(
                introOpen,
                "Each group's ⓘ says everything it changes in the app, and each choice shows what it looks like. Tap one and the preview above changes at once. Reset colors, at the bottom, puts everything back.",
                Modifier.padding(top = 4.dp),
            )
        }

        // White light is one of the Spectrum's choices (1.7.2): the light before the prism splits it.
        Group(
            "Spectrum",
            info = changes(
                "① The light: the colors Vinz lights its highlights with.",
                "The progress bar and Play ring in the player and mini player, the song that's playing in every list (and its " +
                    "bars), switches, the tab and chip you're on, Settings' icons, Home's Replay card and Continue line, the Liked " +
                    "songs cover, the lyrics' progress line and the Best part tag. Home-screen widgets keep Vinz's own colors.",
                also = if (!white) null else
                    "${whiteName(dark)} lights all of these in ${whiteName(dark).lowercase()} (${whiteName(!dark).lowercase()} in " +
                        "${if (dark) "light" else "dark"} mode). It also turns the frosted backgrounds and Vinz's own colored tiles " +
                        "(moods, genres, the Replay card) grey, and greys the home-screen widgets. Covers and photos keep their color, and " +
                        "your accent (②) stays on its own things.",
            ),
        ) {
            LightPresets(s, dark) { key ->
                when (key) {
                    WHITE_KEY -> prefs.setLightStyle(LightStyle.WHITE)
                    "CUSTOM" -> {
                        // Start your own from whatever is showing now.
                        val from = when {
                            s.spectrumStops.size >= 2 -> s.spectrumStops
                            s.spectrumPreset == SpectrumPresets.COVER && SpectrumPresets.coverStops.size >= 2 -> SpectrumPresets.coverStops
                            else -> SpectrumPresets.colorsFor(s.spectrumPreset, emptyList()).map { it.toArgb() }
                        }
                        prefs.setSpectrumStops(from)
                        prefs.setLightStyle(LightStyle.SPECTRUM)
                    }
                    else -> { prefs.setSpectrumPreset(key); prefs.setLightStyle(LightStyle.SPECTRUM) }
                }
            }
            if (!white && s.spectrumPreset == "CUSTOM") StopEditor(s.spectrumStops, onEdit = { picking = Picking.Stop(it) }) { prefs.setSpectrumStops(s.spectrumStops.reversed()) }
            if (!white) ChoiceCards(
                listOf(false, true), s.spectrumBands,
                title = { if (it) "Bands" else "Smooth" },
                detail = { if (it) "Stripes of each color, like the logo" else "Each color blends into the next" },
                onSelect = { prefs.setSpectrumBands(it) },
            ) { LightStrip(rememberColors(s.copy(spectrumBands = it), dark)) }
        }

        Group(
            "Accent color",
            info = changes(
                "② The accent: a second color, for details.",
                "Artist names and links on album and playlist pages; the star, More and New release on artist pages; ticks and " +
                    "picked rows in lists and menus; Replay's top three and its bars; the text cursor; the Simple pattern backgrounds; " +
                    "and the page tint when Page background is Accent tint. Song lists and the player's text don't use it (the " +
                    "player only does with its Theme or Your background backgrounds, in Player settings).",
                also = if (s.colorSource != ColorSource.DYNAMIC) null else
                    "Wallpaper: taken from your phone's wallpaper, as other apps do, and it changes when you change the wallpaper. " +
                        "With Page background on Automatic, sheets and plain pages take the wallpaper's tint too.",
            ),
        ) {
            // Picture's color takes over the accent on exactly the pages it shows most on: say so here, where it's missed.
            if (s.pageColors) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Album, playlist and artist pages use their picture's color instead (Artist & album pages, below)",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                )
                PrismTextButton({ prefs.setPageColors(false) }) { Text("Use mine") }
            }
            val sources = ColorSource.entries.filter { it != ColorSource.DYNAMIC || Build.VERSION.SDK_INT >= 31 }
            ChoiceCards(
                sources, s.colorSource,
                title = { when (it) { ColorSource.ARTWORK -> "Song's cover"; ColorSource.DYNAMIC -> "Wallpaper"; ColorSource.CUSTOM -> "Your pick" } },
                detail = {
                    when (it) {
                        ColorSource.ARTWORK -> "Follows the song that's playing"
                        ColorSource.DYNAMIC -> "Your phone wallpaper's colors"
                        ColorSource.CUSTOM -> "One color you choose"
                    }
                },
                columns = 3,
                onSelect = { prefs.setColorSource(it) },
            ) { AccentSample(rememberColors(s.copy(colorSource = it), dark)) }
            when (s.colorSource) {
                ColorSource.CUSTOM -> {
                    Item("Accent color", "${ColorMath.toHex(s.accent)} · tap to change", onClick = { picking = Picking.Accent }) { Swatch(s.accent) }
                    if (!isNeutralSeed(s.accent)) {
                        Label("How it's used", info = "Vinz can soften it to suit dark and light pages, or use it exactly.")
                        ChoiceCards(
                            listOf(false, true), s.accentExact,
                            title = { if (it) "Exactly as picked" else "Softened" },
                            detail = { if (it) "Your color as it is, only changed where text wouldn't read" else "Lighter on dark pages, deeper on light ones" },
                            onSelect = { prefs.setAccentExact(it) },
                        ) { exact -> AccentPair(s.copy(accentExact = exact)) }
                    }
                    Toggle("Different color for light mode", "Light pages get an accent of their own", s.accentLight != 0) {
                        prefs.setAccentLight(if (it) s.accent else 0)
                    }
                    if (s.accentLight != 0) Item("Light mode accent", "${ColorMath.toHex(s.accentLight)} · tap to change", onClick = { picking = Picking.LightAccent }) { Swatch(s.accentLight) }
                }
                ColorSource.ARTWORK -> {
                    Label(
                        "Which of its colors",
                        info = if (cover == null) "Which of the cover's colors becomes the accent. Play a song to see its own colors here." else "Which of this cover's colors becomes the accent.",
                    )
                    ChoiceCards(
                        ArtworkSwatch.entries, s.artworkSwatch,
                        title = { it.label },
                        detail = { when (it) { ArtworkSwatch.VIBRANT -> "Its brightest color"; ArtworkSwatch.MUTED -> "A softer, dusty one"; ArtworkSwatch.DOMINANT -> "The color it has most of" } },
                        columns = 3,
                        onSelect = { prefs.setArtworkSwatch(it) },
                    ) { sw ->
                        val col = cover?.let { pickSwatch(it, sw, s.artworkVivid) }?.let { Color(it) } ?: SAMPLE_COVER[sw]!!
                        AccentSample(rememberColors(s.copy(colorSource = ColorSource.CUSTOM, accent = col.toArgb(), accentLight = 0, accentExact = false), dark))
                    }
                    Toggle("Always colorful", "Grey or black-and-white covers still give a color", s.artworkVivid) { prefs.setArtworkVivid(it) }
                    Label(
                        "Between songs",
                        info = s.artworkChange.label + ": " + when (s.artworkChange) {
                            ArtworkChange.INSTANT -> "the new color appears the moment a song starts."
                            ArtworkChange.FADE -> "the color fades to the new one over about a second."
                            ArtworkChange.SLOW -> "a slow, gentle fade of a couple of seconds."
                            ArtworkChange.ALBUM -> "songs from the same album keep one color."
                        },
                    )
                    Chips(ArtworkChange.entries, s.artworkChange, { it.label }) { prefs.setArtworkChange(it) }
                }
                ColorSource.DYNAMIC -> {}
            }
        }

        Group(
            "Page background",
            info = changes(
                "③ The background: what Vinz's own screens sit on.",
                "The Ambient frost behind Home, Search, Library, Replay and Settings (Automatic keeps the cover's colors in it, " +
                    "Plain makes it grey, a tint colors it), those pages when frosted glass is off, and sheets and dialogs. " +
                    "Not the player, nor album, artist and playlist pages, which take their picture's colors.",
            ),
        ) {
            val homeBackdrop = com.prism.music.ui.theme.Backdrop.parse(s.backdrops[com.prism.music.ui.theme.BackdropScreen.HOME.name]).first
            if (homeBackdrop != com.prism.music.ui.theme.Backdrop.DEFAULT) Note(
                "Home has the ${homeBackdrop.label} background (Glass & backgrounds), drawn over this color, so it only shows a " +
                    "little there. Pick Ambient there to see it fully"
            )
            // In White light, Automatic is already plain grey: Plain would be the same choice twice.
            val tints = SurfaceTint.entries.filter { !(white && it == SurfaceTint.NEUTRAL && s.surfaceTint != SurfaceTint.NEUTRAL) }
            ChoiceCards(
                tints, s.surfaceTint,
                title = { when (it) { SurfaceTint.AUTO -> "Automatic"; SurfaceTint.NEUTRAL -> "Plain"; SurfaceTint.ACCENT -> "Accent tint"; SurfaceTint.CUSTOM -> "Your color" } },
                detail = {
                    when (it) {
                        SurfaceTint.AUTO -> if (white) "Grey, the frost in greys too" else "The cover's colors in the frost; a hint of the accent on plain pages"
                        SurfaceTint.NEUTRAL -> "Grey: the frost loses its color too"
                        SurfaceTint.ACCENT -> if (s.colorSource == ColorSource.ARTWORK) "The accent's color, frost included (changes with each song)" else "The accent's color, frost included"
                        SurfaceTint.CUSTOM -> "A color you pick, frost included"
                    }
                },
                onSelect = { prefs.setSurfaceTint(it) },
            ) { ColorSample(rememberColors(s.copy(surfaceTint = it), dark)) }
            if (s.surfaceTint == SurfaceTint.CUSTOM) Item("Background color", "${ColorMath.toHex(s.surfaceColor)} · tap to change", onClick = { picking = Picking.Surface }) { Swatch(s.surfaceColor) }
            if (s.surfaceTint == SurfaceTint.ACCENT || s.surfaceTint == SurfaceTint.CUSTOM) StrengthSlider(s) { prefs.setSurfaceTintStrength(it) }
            if (s.pureBlack) Note("Pure black (under Theme) keeps dark pages black; the tint then shows on cards and in light mode")
        }

        Group(
            "Text contrast",
            info = changes(
                "How strong grey text and lines are against the page.",
                "Secondary text (artists, times, notes), dividers and outlines on pages, sheets and menus. Medium and High also " +
                    "firm up the accent so it reads more easily. The player's own white text over the cover stays as it is.",
            ),
        ) {
            ChoiceCards(
                ContrastLevel.entries, s.contrast,
                title = { it.label },
                detail = { when (it) { ContrastLevel.STANDARD -> "Soft grey text"; ContrastLevel.MEDIUM -> "Firmer"; ContrastLevel.HIGH -> "Strongest, easiest to read" } },
                columns = 3,
                onSelect = { prefs.setContrast(it) },
            ) { TextSample(rememberColors(s.copy(contrast = it), dark)) }
        }

        Group(
            "Artist & album pages",
            info = changes(
                "Their accent: yours (②), as everywhere else, or a color taken from each page's picture.",
                "The accent on artist, album and playlist pages, and in the menus you open from a song, album or artist (long " +
                    "press or ⋯). Their frosted backgrounds take the picture's colors either way (Glass & backgrounds).",
                also = "An artist's page can also have a color of its own: ⋯ → Page color on their page.",
            ),
        ) {
            // The playing cover's page accent, worked out exactly as its page does (none for a black-and-white cover).
            val art = cover?.let { p ->
                val dominant = (p.dominantSwatch ?: p.mutedSwatch ?: p.vibrantSwatch)?.rgb?.let { Color(it) }
                val vibrant = (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.darkVibrantSwatch)?.rgb?.let { Color(it) } ?: dominant
                if (dominant != null && vibrant != null) com.prism.music.ui.components.ArtPalette(vibrant, dominant) else null
            } ?: com.prism.music.ui.components.ArtPalette(SAMPLE_COVER[ArtworkSwatch.VIBRANT]!!, SAMPLE_COVER[ArtworkSwatch.DOMINANT]!!)
            val c = rememberColors(s, dark)
            val pictureAccent = art.accent(dark)?.let { com.prism.music.ui.theme.readableOn(it, c.scheme.background) }
            ChoiceCards(
                listOf(false, true), s.pageColors,
                title = { if (it) "Picture's color" else "Your accent" },
                detail = {
                    if (it) "Each page and menu takes a color from its picture instead of yours (black-and-white ones keep yours)"
                    else "Your accent on every page and menu"
                },
                onSelect = { prefs.setPageColors(it) },
            ) { on -> ColorSample(c, pageAccent = if (on) pictureAccent else null) }
        }
        ResetColors()
    }

    picking?.let { p ->
        val (title, initial) = when (p) {
            Picking.Accent -> "Accent color" to s.accent
            Picking.LightAccent -> "Light mode accent" to s.accentLight.takeIf { it != 0 }.let { it ?: s.accent }
            Picking.Surface -> "Background color" to s.surfaceColor
            is Picking.Stop -> "Light color ${p.index + 1}" to (s.spectrumStops.getOrNull(p.index) ?: s.spectrumStops.lastOrNull() ?: s.accent)
        }
        val removable = p is Picking.Stop && p.index < s.spectrumStops.size && s.spectrumStops.size > 2
        fun withColor(c: Int): AppSettings = when (p) {
            Picking.Accent -> s.copy(colorSource = ColorSource.CUSTOM, accent = c)
            Picking.LightAccent -> s.copy(accentLight = c)
            Picking.Surface -> s.copy(surfaceTint = SurfaceTint.CUSTOM, surfaceColor = c)
            is Picking.Stop -> s.copy(
                spectrumPreset = "CUSTOM",
                spectrumStops = if (p.index >= s.spectrumStops.size) s.spectrumStops + c else s.spectrumStops.mapIndexed { i, old -> if (i == p.index) c else old },
            )
        }
        ColorPickerSheet(
            title, initial,
            onDismiss = { picking = null },
            note = when (p) {
                Picking.Accent, Picking.LightAccent -> { c -> accentNote(c, s.accentExact) }
                else -> null
            },
            reset = if (removable) "Remove this color" to { prefs.setSpectrumStops(s.spectrumStops.filterIndexed { i, _ -> i != (p as Picking.Stop).index }) } else null,
            // While you pick: Prism with that color, dark and light (light only for light mode's own accent).
            preview = { c ->
                val next = withColor(c)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p != Picking.LightAccent) ColorSample(rememberColors(next, true), Modifier.weight(1f), height = 128.dp)
                    ColorSample(rememberColors(next, false), Modifier.weight(1f), height = 128.dp)
                }
            },
        ) { c ->
            when (p) {
                Picking.Accent -> prefs.setAccent(c)
                Picking.LightAccent -> prefs.setAccentLight(c)
                Picking.Surface -> prefs.setSurfaceColor(c)
                is Picking.Stop -> prefs.setSpectrumStops(withColor(c).spectrumStops)
            }
        }
    }
}

/**
 * A group's ⓘ: what the part is, then everything its choice changes in the app ([also]: more for the choice made), so
 * nothing it touches is a surprise.
 */
private fun changes(what: String, changes: String, also: String? = null): String =
    "$what\n\nChanges: $changes" + (also?.let { "\n\n$it" } ?: "")

/** Stand-ins for a cover's colors while nothing is playing. */
private val SAMPLE_COVER = mapOf(
    ArtworkSwatch.VIBRANT to Color(0xFFE0735A), ArtworkSwatch.MUTED to Color(0xFF9C7F8E), ArtworkSwatch.DOMINANT to Color(0xFF2B1F4A),
)

/** The playing song's cover colors, for the "Song's cover" examples; null when nothing is playing. */
@Composable
private fun rememberCoverPalette(): Palette? {
    val c = LocalContainer.current
    val context = LocalContext.current
    val song by c.player.currentSong.collectAsState()
    val palette by produceState<Palette?>(null, song?.thumbnail) {
        val url = song?.thumbnail ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context).data(hiRes(url, 160) ?: url).allowHardware(false).build()
                (SingletonImageLoader.get(context).execute(req) as? SuccessResult)?.image?.toBitmap()?.let { Palette.from(it).generate() }
            }.getOrNull()
        }
    }
    return palette
}

/** The accent alone, small: an artist's name, a star and a link on the page. */
@Composable
private fun AccentSample(c: ThemeColors) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().height(64.dp).clip(shape).background(c.scheme.background).border(1.dp, c.line, shape).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(width = 30.dp, height = 8.dp).clip(CircleShape).background(c.scheme.primary))
        Text("The Weeknd", color = c.scheme.primary, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PrismIcons.StarFilled, null, Modifier.size(11.dp), tint = c.scheme.primary)
            Spacer(Modifier.width(4.dp))
            Text("MORE", color = c.scheme.primary, fontSize = 8.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The accent as it comes out on a dark page and a light page, side by side. */
@Composable
private fun AccentPair(s: AppSettings) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(true, false).forEach { d ->
            val c = rememberColors(s, d)
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier.weight(1f).height(52.dp).clip(shape).background(c.scheme.background).border(1.dp, c.line, shape).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Artist", color = c.scheme.primary, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(c.scheme.primary))
            }
        }
    }
}

/** The light alone: a progress line and a switch. */
@Composable
private fun LightStrip(c: ThemeColors) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(shape).background(c.scheme.background).border(1.dp, c.line, shape).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Brush.horizontalGradient(*c.run.toTypedArray())))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(18.dp).clip(CircleShape).border(2.dp, Brush.sweepGradient(c.ring), CircleShape))
    }
}

/** What Prism does with an accent, when it doesn't use it exactly as picked. */
private fun accentNote(c: Int, exact: Boolean): String? = when {
    isNeutralSeed(c) -> "White, black and greys give Vinz's plain look: white on dark pages, black on light ones"
    !exact -> "Shown softened (lighter on dark pages, deeper on light ones). Pick \"Exactly as picked\" on the Color page to use it as it is"
    ColorMath.contrast(c, 0xFF0E0E12.toInt()) < 4.5 || ColorMath.contrast(c, 0xFFFAFAFB.toInt()) < 4.5 ->
        "Made a little lighter or darker where text in it wouldn't read"
    else -> null
}

/** A filled circle of [color], for a row's end. */
@Composable
private fun Swatch(color: Int) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(Color(color))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f), CircleShape),
    )
}

/** Tint strength: the example follows the slider as it moves; it's kept when you let go. */
@Composable
private fun StrengthSlider(s: AppSettings, onSave: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(s.surfaceTintStrength) }
    LaunchedEffect(s.surfaceTintStrength) { v = s.surfaceTintStrength }
    val dark = LocalIsDark.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tint strength", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(
                when { v < 0.6f -> "Faint"; v < 1.4f -> "Gentle"; v < 2.2f -> "Strong"; else -> "Very strong" },
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PrismSlider(v, { v = it }, valueRange = 0.2f..3f, onValueChangeFinished = { onSave(v) })
        ColorSample(rememberColors(s.copy(surfaceTintStrength = v), dark), height = 128.dp)
    }
}

/** Each light as a ring and a strip, with its name; the picked one ringed. The last is your own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightPresets(s: AppSettings, dark: Boolean, onPick: (String) -> Unit) {
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val white = s.lightStyle == LightStyle.WHITE
    // White is a preset like the others: white light on dark pages, black on light ones (named for what it shows).
    val keys = SpectrumPresets.all.map { it.key to it.label } + (SpectrumPresets.COVER to SpectrumPresets.COVER_LABEL) +
        (WHITE_KEY to whiteName(dark)) + ("CUSTOM" to "Your own")
    // Three to a row, every tile the same width (a short last row keeps its tiles' size, it doesn't stretch them).
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      keys.chunked(3).forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { (key, label) ->
            val on = if (key == WHITE_KEY) white else !white && key == s.spectrumPreset
            val empty = key == "CUSTOM" && s.spectrumStops.size < 2
            val c = rememberColors(
                if (key == WHITE_KEY) s.copy(lightStyle = LightStyle.WHITE) else s.copy(spectrumPreset = key, lightStyle = LightStyle.SPECTRUM),
                dark,
            )
            Column(
                Modifier.weight(1f).clip(ui.tile)
                    .background(frostFill(if (on) 2.6f else 1f))
                    .then(if (on) Modifier.border(2.dp, PrismTokens.spectrum, ui.tile) else Modifier)
                    .clickable { onPick(key) }.padding(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape)
                            .border(2.5.dp, if (empty) Brush.linearGradient(listOf(scheme.outline, scheme.outline)) else Brush.sweepGradient(c.ring), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (empty) Icon(PrismIcons.Add, null, Modifier.size(12.dp)) }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.weight(1f).height(8.dp).clip(CircleShape)
                            .background(if (empty) Brush.linearGradient(listOf(scheme.outlineVariant, scheme.outlineVariant)) else Brush.horizontalGradient(*c.run.toTypedArray())),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 2)
            }
        }
        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
      }
      }
    }
}

/** The Spectrum tile that turns on the White preset ([LightStyle.WHITE]) rather than naming a split-light preset. */
private const val WHITE_KEY = "WHITE"

/** The White preset's name as it shows: white light on dark pages, black on light ones. */
fun whiteName(dark: Boolean) = if (dark) "White" else "Black"

/** Your own light's colors in order: tap one to change (or remove) it, + to add one, and a way to flip the order. */
@Composable
private fun StopEditor(stops: List<Int>, onEdit: (Int) -> Unit, onReverse: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("Your own light's colors, left to right", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            stops.forEachIndexed { i, c ->
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Color(c)).border(1.dp, scheme.onSurface.copy(alpha = 0.18f), CircleShape)
                        .semantics { contentDescription = "Color ${i + 1}, ${ColorMath.toHex(c)}" }
                        .clickable { onEdit(i) },
                )
            }
            if (stops.size < 7) Box(
                Modifier.size(38.dp).clip(CircleShape).border(1.5.dp, scheme.onSurface.copy(alpha = 0.3f), CircleShape).clickable { onEdit(stops.size) },
                contentAlignment = Alignment.Center,
            ) { Icon(PrismIcons.Add, "Add a color", Modifier.size(20.dp)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tap a color to change or remove it, + to add one (2 to 7).", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            PrismTextButton(onReverse) { Text("Reverse") }
        }
    }
}

@Composable
private fun ResetColors() {
    val prefs = LocalContainer.current.settings
    var ask by remember { mutableStateOf(false) }
    PrismTextButton(onClick = { ask = true }, Modifier.fillMaxWidth()) {
        Icon(PrismIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Reset colors to defaults")
    }
    if (ask) androidx.compose.material3.AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("Reset colors?") },
        text = { Text("Style, light, accent, background, contrast and page colors go back to how Vinz ships. Your saved colors stay.") },
        confirmButton = { PrismTextButton(onClick = { prefs.resetColors(); ask = false }) { Text("Reset") } },
        dismissButton = { PrismTextButton(onClick = { ask = false }) { Text("Cancel") } },
    )
}
