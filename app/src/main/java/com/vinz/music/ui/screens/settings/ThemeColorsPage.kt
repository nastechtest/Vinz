package com.vinz.music.ui.screens.settings

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.asItComes
import com.vinz.music.data.prefs.ArtworkChange
import com.vinz.music.data.prefs.ArtworkSwatch
import com.vinz.music.data.prefs.ColorSource
import com.vinz.music.data.prefs.ContrastLevel
import com.vinz.music.data.prefs.GlassKind
import com.vinz.music.data.prefs.GlassLook
import com.vinz.music.data.prefs.LightStyle
import com.vinz.music.data.prefs.SurfaceTint
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.tr
import com.vinz.music.trp
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.components.ColorPickerSheet
import com.vinz.music.ui.components.VinzSheet
import com.vinz.music.ui.screens.ColorSample
import com.vinz.music.ui.screens.ColorStage
import com.vinz.music.ui.screens.GlassChoice
import com.vinz.music.ui.screens.GlassSample
import com.vinz.music.ui.screens.LightStrip
import com.vinz.music.ui.screens.LookChoices
import com.vinz.music.ui.screens.Needs
import com.vinz.music.ui.screens.PERFORMANCE_PAUSES
import com.vinz.music.ui.screens.PageGround
import com.vinz.music.ui.screens.ScreenChange
import com.vinz.music.ui.screens.SettingEntry
import com.vinz.music.ui.screens.SettingNeeds
import com.vinz.music.ui.screens.ShowOption
import com.vinz.music.ui.screens.StopEditor
import com.vinz.music.ui.screens.WidgetConfigActivity
import com.vinz.music.ui.screens.accentNote
import com.vinz.music.ui.screens.rememberColors
import com.vinz.music.ui.screens.whiteName
import com.vinz.music.ui.screens.worded
import com.vinz.music.ui.theme.Backdrop
import com.vinz.music.ui.theme.BackdropScreen
import com.vinz.music.ui.theme.ColorMath
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.LocalIsDark
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.ui.theme.SpectrumPresets
import com.vinz.music.ui.theme.ThemeColors
import com.vinz.music.ui.theme.glassSnap
import com.vinz.music.ui.theme.isNeutralSeed
import com.vinz.music.ui.theme.PLAYER_BACKDROP
import com.vinz.music.ui.theme.screensOnly
import com.vinz.music.widget.WidgetOptionsStore
import com.vinz.music.widget.WidgetStyle
import kotlin.math.roundToInt

private val page = SettingsPageId.THEME_COLORS

// ---------------------------------------------------------------------------- The page's merges (plan §2.5)

/** **Theme**: the three theme chips and the Pure black switch as one choice. Black is Dark on true black. */
enum class ThemeChoice(val label: String) {
    SYSTEM(tr(R.string.system)), LIGHT(tr(R.string.light)), DARK(tr(R.string.dark)), BLACK(tr(R.string.black)),
}

/** The stored theme ([mode], [pureBlack]) as the one control shows it. Pure black only shows as Black with Dark. */
fun themeToMerged(mode: ThemeMode, pureBlack: Boolean): ThemeChoice = when (mode) {
    ThemeMode.SYSTEM -> ThemeChoice.SYSTEM
    ThemeMode.LIGHT -> ThemeChoice.LIGHT
    ThemeMode.DARK -> if (pureBlack) ThemeChoice.BLACK else ThemeChoice.DARK
}

/**
 * What picking [choice] stores, from what was stored ([was]: mode, pure black). Dark and Black say which black they
 * are; System and Light leave pure black as it was (it only shows on dark pages), so "System, black at night" and a
 * stored "Light + pure black" both stay as they are.
 */
fun themeToStored(choice: ThemeChoice, was: Pair<ThemeMode, Boolean>): Pair<ThemeMode, Boolean> = when (choice) {
    ThemeChoice.SYSTEM -> ThemeMode.SYSTEM to was.second
    ThemeChoice.LIGHT -> ThemeMode.LIGHT to was.second
    ThemeChoice.DARK -> ThemeMode.DARK to false
    ThemeChoice.BLACK -> ThemeMode.DARK to true
}

/** **Glass also on**: "Glass on more of the screen" and "Frosted pages" as chips of one row. */
enum class GlassAlso(val label: String) {
    MORE(tr(R.string.tl_more_of_the_screen)), PAGES(tr(R.string.tl_artist_and_album_pages)),
}

fun glassAlsoToMerged(more: Boolean, frostedPages: Boolean): Set<GlassAlso> =
    setOfNotNull(GlassAlso.MORE.takeIf { more }, GlassAlso.PAGES.takeIf { frostedPages })

/** glass_more, frosted_pages. */
fun glassAlsoToStored(on: Set<GlassAlso>): Pair<Boolean, Boolean> = (GlassAlso.MORE in on) to (GlassAlso.PAGES in on)

// ---------------------------------------------------------------------------- Words

/** The light preset that is the White light ([LightStyle.WHITE]) rather than a split-light preset. */
internal const val THEME_WHITE_LIGHT = "WHITE"
private const val OWN_LIGHT = "CUSTOM"

private fun sourceLabel(v: ColorSource) = when (v) {
    ColorSource.ARTWORK -> tr(R.string.song_s_cover); ColorSource.DYNAMIC -> tr(R.string.wallpaper); ColorSource.CUSTOM -> tr(R.string.your_pick)
}

private fun tintLabel(v: SurfaceTint) = when (v) {
    SurfaceTint.AUTO -> tr(R.string.automatic_2); SurfaceTint.NEUTRAL -> tr(R.string.plain)
    SurfaceTint.ACCENT -> tr(R.string.accent_tint); SurfaceTint.CUSTOM -> tr(R.string.your_color)
}

private fun exactLabel(exact: Boolean) = if (exact) tr(R.string.exactly_as_picked) else tr(R.string.softened)
private fun bandsLabel(bands: Boolean) = if (bands) tr(R.string.bands) else tr(R.string.smooth)
private fun pageColorsLabel(picture: Boolean) = if (picture) tr(R.string.picture_s_color) else tr(R.string.your_accent)
private fun onOff(on: Boolean) = if (on) tr(R.string.tl_on) else tr(R.string.off)
/** Blur and Frost run 30-200% / 0-200% around 100%, which is how the glass is designed: that point says so in words. */
private fun designedOr(percent: String) = if (percent == "100%") tr(R.string.as_designed) else percent
private fun glassPercent(v: Float) = designedOr("${(glassSnap(v) * 100).roundToInt()}%")
private fun glassSliderText(v: Float) = designedOr(percent(v))

private fun strengthText(v: Float) =
    worded(when { v < 0.6f -> tr(R.string.faint); v < 1.4f -> tr(R.string.gentle); v < 2.2f -> tr(R.string.strong); else -> tr(R.string.very_strong) }, v)

/** The light's name as its row says it (White shows as Black on light pages). */
private fun lightLabel(key: String, dark: Boolean) = if (key == THEME_WHITE_LIGHT) whiteName(dark) else SpectrumPresets.label(key)

private fun lightKey(s: AppSettings) = if (s.lightStyle == LightStyle.WHITE) THEME_WHITE_LIGHT else s.spectrumPreset

private fun lightAccentText(s: AppSettings) = if (s.accentLight == 0) tr(R.string.same_as_your_accent) else ColorMath.toHex(s.accentLight)

/** Screen backgrounds' state: the designs picked and on how many screens, with what that screen's own settings are set to. */
private fun backgroundsText(s: AppSettings): String {
    val screens = s.backdrops.screensOnly
    return if (screens.isEmpty()) tr(R.string.designs_or_your_own_photo)
    // (The plural's own leading space is lost when the strings are compiled: the old row read "Auroraon 2 screens".)
    else screens.values.map { Backdrop.parse(it).first.label }.distinct().joinToString(" · ") + " " + trp(R.plurals.on_n_screens, screens.size).trim()
}

/** What a group's ⓘ says: what the part is, then everything its choice changes ([also]: more for the choice made). */
private fun changesInfo(what: String, changes: String, vararg also: String?): String =
    (listOf(tr(R.string.changes, what, changes)) + also.filterNotNull()).joinToString("\n\n")

private fun glassAlsoText(s: AppSettings) =
    glassAlsoToMerged(s.view.glass.more, s.frostedPages).joinToString(" · ") { it.label }.ifBlank { tr(R.string.tl_none) }

/**
 * "Reset this page": one line per row that differs from how Vinz comes ("Theme: Black → System"), as the confirm
 * lists them. [dark]: Vinz is in dark mode now (the White light is named Black on light pages).
 */
fun themeColorsChanges(s: AppSettings, dark: Boolean): List<String> {
    val d = s.asItComes()
    val out = ArrayList<String>()
    fun line(changed: Boolean, title: Int, now: String, default: String) { if (changed) out += resetLine(tr(title), now, default) }
    line(s.themeMode != d.themeMode || s.pureBlack != d.pureBlack, R.string.theme, themeToMerged(s.themeMode, s.pureBlack).label, themeToMerged(d.themeMode, d.pureBlack).label)
    line(LookChoices.screenChange(s) != LookChoices.screenChange(d), R.string.changing_screens, LookChoices.screenChange(s).label, LookChoices.screenChange(d).label)
    line(s.reduceMotion != d.reduceMotion, R.string.reduce_motion, onOff(s.reduceMotion), onOff(d.reduceMotion))
    line(lightKey(s) != lightKey(d) || s.spectrumStops != d.spectrumStops, R.string.tl_preset, lightLabel(lightKey(s), dark), lightLabel(lightKey(d), dark))
    line(s.spectrumBands != d.spectrumBands, R.string.smooth_or_bands, bandsLabel(s.spectrumBands), bandsLabel(d.spectrumBands))
    line(s.colorSource != d.colorSource, R.string.accent_from, sourceLabel(s.colorSource), sourceLabel(d.colorSource))
    line(s.accent != d.accent, R.string.accent_color, ColorMath.toHex(s.accent), ColorMath.toHex(d.accent))
    line(s.accentExact != d.accentExact, R.string.how_it_s_used, exactLabel(s.accentExact), exactLabel(d.accentExact))
    line(s.accentLight != d.accentLight, R.string.light_mode_accent, lightAccentText(s), lightAccentText(d))
    line(s.artworkSwatch != d.artworkSwatch, R.string.which_of_its_colors, s.artworkSwatch.label, d.artworkSwatch.label)
    line(s.artworkVivid != d.artworkVivid, R.string.always_colorful, onOff(s.artworkVivid), onOff(d.artworkVivid))
    line(s.artworkChange != d.artworkChange, R.string.between_songs, s.artworkChange.label, d.artworkChange.label)
    line(s.surfaceTint != d.surfaceTint, R.string.page_background, tintLabel(s.surfaceTint), tintLabel(d.surfaceTint))
    line(s.surfaceColor != d.surfaceColor, R.string.background_color, ColorMath.toHex(s.surfaceColor), ColorMath.toHex(d.surfaceColor))
    line(s.surfaceTintStrength != d.surfaceTintStrength, R.string.tint_strength, strengthText(s.surfaceTintStrength), strengthText(d.surfaceTintStrength))
    line(s.contrast != d.contrast, R.string.text_contrast, s.contrast.label, d.contrast.label)
    line(s.pageColors != d.pageColors, R.string.tl_page_colors, pageColorsLabel(s.pageColors), pageColorsLabel(d.pageColors))
    line(s.view.playingInPageColor != d.view.playingInPageColor, R.string.playing_song_in_the_page_s_color, onOff(s.view.playingInPageColor), onOff(d.view.playingInPageColor))
    // The screen's own settings (how strong the theme's color lies over them, whether they move and fade) go back with it.
    // The player's own background is Player's (its "Which background" row), not this page's: it stays.
    line(
        s.backdrops.screensOnly != d.backdrops.screensOnly || s.backdropDim != d.backdropDim || s.backdropMotion != d.backdropMotion || s.view.backdropFade != d.view.backdropFade,
        R.string.screen_backgrounds, if (s.backdrops.screensOnly.isEmpty()) tr(R.string.tl_none) else backgroundsText(s), tr(R.string.tl_none),
    )
    line(LookChoices.glass(s) != LookChoices.glass(d), R.string.glass, LookChoices.glass(s).label, LookChoices.glass(d).label)
    line(s.view.glass.blur != d.view.glass.blur, R.string.glass_blur, glassPercent(s.view.glass.blur), glassPercent(d.view.glass.blur))
    line(s.view.glass.frost != d.view.glass.frost, R.string.glass_frost, glassPercent(s.view.glass.frost), glassPercent(d.view.glass.frost))
    line(s.view.glass.more != d.view.glass.more || s.frostedPages != d.frostedPages, R.string.tl_glass_also_on, glassAlsoText(s), glassAlsoText(d))
    return out
}

// ---------------------------------------------------------------------------- The page

/** Which color the picker is open for. */
private sealed interface ThemePicking {
    data object Accent : ThemePicking
    data object LightAccent : ThemePicking
    data object Surface : ThemePicking
    /** One color of your own light ([index] past the end adds one). */
    data class Stop(val index: Int) : ThemePicking
}

/**
 * Settings → Theme & colors (1.8.4's map, plan §2.3), rebuilt with the kit. This file is the only place for the page:
 * its composable, its lines in Settings' search ([themeColorsIndex]), its "Reset this page" keys
 * ([themeColorsLookKeys]), its line on the root ([themeColorsValue]) and the old group names it answers to
 * ([themeColorsOldGroups]).
 *
 * Six groups at most: Theme and motion · The light · Accent · Pages · Glass · Home-screen widgets (the last only while
 * a Vinz widget is on the home screen). The pinned preview is the Color page's sample: your Vinz in dark and in
 * light, its three parts numbered. Choices that need an example open in a pick sheet (the light, smooth or bands,
 * glass); choices that are a color are chips, and the preview shows them. Reads and writes what the old Customize
 * Vinz (theme, Motion), Color and Glass & backgrounds pages did.
 */
@Composable
fun ThemeColorsPage(bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val nav = LocalNavigator.current
    val dark = LocalIsDark.current
    val d = s.asItComes()
    val white = s.lightStyle == LightStyle.WHITE
    val widgets = placedWidgets()
    var picking by remember { mutableStateOf<ThemePicking?>(null) }

    SettingsPageScaffold(
        page.title, bottomPadding, showBack = showBack, onSearch = onSearch, find = find,
        reset = PageReset(themeColorsChanges(s, dark)) {
            val player = s.backdrops[PLAYER_BACKDROP]
            prefs.resetLookKeys(themeColorsLookKeys)
            // "backdrops" holds the player's own background too (Player's row): only the screens' go back.
            if (player != null) prefs.setBackdrops(mapOf(PLAYER_BACKDROP to player))
            // How screens change isn't part of a Look: put back by its own setter.
            LookChoices.setScreenChange(prefs, LookChoices.screenChange(d))
        },
        preview = { strip -> ThemePreview(strip) }, previewHeight = 170.dp,
    ) {
        if (s.performanceMode) item("performance") { PerformanceNote() }

        group(tr(R.string.tl_theme_and_motion)) {
            val theme = themeToMerged(s.themeMode, s.pureBlack)
            ChoiceRow(
                tr(R.string.theme), ThemeChoice.entries, theme, { it.label }, info = tr(R.string.tl_theme_info),
                changed = s.themeMode != d.themeMode || s.pureBlack != d.pureBlack,
            ) { picked ->
                val (mode, black) = themeToStored(picked, s.themeMode to s.pureBlack)
                prefs.setThemeMode(mode)
                prefs.setPureBlack(black)
            }
            val change = LookChoices.screenChange(s)
            ChoiceRow(
                tr(R.string.changing_screens), ScreenChange.entries, change, { it.label }, info = tr(R.string.changing_screens_info),
                changed = change != LookChoices.screenChange(d),
            ) { LookChoices.setScreenChange(prefs, it) }
            SwitchRow(
                tr(R.string.reduce_motion), s.reduceMotion, value = tr(R.string.calmer_quicker_animations),
                info = tr(R.string.no_drifting_backgrounds_no_artwork_bounce), changed = s.reduceMotion != d.reduceMotion,
            ) { prefs.setReduceMotion(it) }
        }

        group(
            tr(R.string.the_light),
            info = listOfNotNull(
                tr(R.string.tl_colors_intro),
                changesInfo(
                    tr(R.string.the_light_the_colors_vinz_lights), tr(R.string.the_progress_bar_and_play_ring_all),
                    if (!white) null else tr(
                        R.string.lights_all_of_these_in_in_all, whiteName(dark), whiteName(dark).lowercase(), whiteName(!dark).lowercase(),
                        if (dark) tr(R.string.mode_light) else tr(R.string.mode_dark),
                    ),
                ),
            ).joinToString("\n\n"),
        ) {
            // A seasonal Look's light only in its month (or while it's the one picked); White and your own come last.
            val today = remember { com.vinz.music.data.looks.Seasons.today() }
            val now = lightKey(s)
            val presets = remember(today, now) {
                (SpectrumPresets.all.filter { p -> p.season?.contains(today) ?: true || p.key == now }.map { it.key } +
                    SpectrumPresets.COVER + THEME_WHITE_LIGHT + OWN_LIGHT + now).distinct()
            }
            fun with(key: String) = if (key == THEME_WHITE_LIGHT) s.copy(lightStyle = LightStyle.WHITE) else s.copy(spectrumPreset = key, lightStyle = LightStyle.SPECTRUM)
            PickCardsRow(
                tr(R.string.tl_preset), presets, now, { lightLabel(it, dark) },
                changed = now != lightKey(d), exampleScale = 1f,
                thumbnail = { key -> Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(*rememberColors(with(key), dark).run.toTypedArray()))) },
                onPick = { key ->
                    when (key) {
                        THEME_WHITE_LIGHT -> prefs.setLightStyle(LightStyle.WHITE)
                        OWN_LIGHT -> {
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
                },
            ) { key -> LightSwatch(rememberColors(with(key), dark), empty = key == OWN_LIGHT && s.spectrumStops.size < 2) }
            if (!white && s.spectrumPreset == OWN_LIGHT) {
                var editing by rememberSaveable { mutableStateOf(false) }
                PickRow(
                    tr(R.string.tl_your_own_colors), trp(R.plurals.tl_n_colors, s.spectrumStops.size), changed = s.spectrumStops != d.spectrumStops,
                    thumbnail = { Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(*rememberColors(s, dark).run.toTypedArray()))) },
                ) { editing = true }
                if (editing) VinzSheet({ editing = false }) { _ ->
                    StopEditor(s.spectrumStops, onEdit = { picking = ThemePicking.Stop(it) }) { prefs.setSpectrumStops(s.spectrumStops.reversed()) }
                    Spacer(Modifier.height(20.dp))
                }
            }
            if (!white) PickCardsRow(
                tr(R.string.smooth_or_bands), listOf(false, true), s.spectrumBands, ::bandsLabel,
                changed = s.spectrumBands != d.spectrumBands, onPick = { prefs.setSpectrumBands(it) },
            ) { LightStrip(rememberColors(s.copy(spectrumBands = it), dark)) }
        }

        group(
            tr(R.string.accent),
            info = changesInfo(
                tr(R.string.the_accent_a_second_color_for), tr(R.string.artist_names_and_links_on_album_all),
                if (s.colorSource == ColorSource.DYNAMIC) tr(R.string.wallpaper_taken_from_your_phone_s_all) else null,
                // Picture's color takes over the accent on exactly the pages it shows most on.
                if (s.pageColors) tr(R.string.tl_pages_use_picture) else null,
            ),
        ) {
            val sources = ColorSource.entries.filter { it != ColorSource.DYNAMIC || Build.VERSION.SDK_INT >= 31 || it == s.colorSource }
            ChoiceRow(tr(R.string.accent_from), sources, s.colorSource, ::sourceLabel, changed = s.colorSource != d.colorSource) { prefs.setColorSource(it) }
            when (s.colorSource) {
                ColorSource.CUSTOM -> {
                    PickRow(
                        tr(R.string.accent_color), ColorMath.toHex(s.accent), changed = s.accent != d.accent, showValue = false,
                        thumbnail = { Box(Modifier.fillMaxSize().background(Color(s.accent))) },
                    ) { picking = ThemePicking.Accent }
                    if (!isNeutralSeed(s.accent)) ChoiceRow(
                        tr(R.string.how_it_s_used), listOf(false, true), s.accentExact, ::exactLabel,
                        info = tr(R.string.vinz_can_soften_it_to_suit), changed = s.accentExact != d.accentExact,
                    ) { prefs.setAccentExact(it) }
                    // 0 = the same as the accent; the picker starts from the accent, and "Use the same" in it clears it.
                    PickRow(
                        tr(R.string.light_mode_accent), lightAccentText(s), info = tr(R.string.light_pages_get_an_accent_of),
                        changed = s.accentLight != d.accentLight,
                        thumbnail = { Box(Modifier.fillMaxSize().background(Color(if (s.accentLight == 0) s.accent else s.accentLight))) },
                    ) { picking = ThemePicking.LightAccent }
                }
                ColorSource.ARTWORK -> {
                    ChoiceRow(
                        tr(R.string.which_of_its_colors), ArtworkSwatch.entries, s.artworkSwatch, { it.label },
                        info = tr(R.string.which_of_the_cover_s_colors), changed = s.artworkSwatch != d.artworkSwatch,
                    ) { prefs.setArtworkSwatch(it) }
                    SwitchRow(
                        tr(R.string.always_colorful), s.artworkVivid, value = tr(R.string.grey_or_black_and_white_covers),
                        changed = s.artworkVivid != d.artworkVivid,
                    ) { prefs.setArtworkVivid(it) }
                    ChoiceRow(
                        tr(R.string.between_songs), ArtworkChange.entries, s.artworkChange, { it.label },
                        info = s.artworkChange.label + ": " + when (s.artworkChange) {
                            ArtworkChange.INSTANT -> tr(R.string.the_new_color_appears_the_moment)
                            ArtworkChange.FADE -> tr(R.string.the_color_fades_to_the_new)
                            ArtworkChange.SLOW -> tr(R.string.a_slow_gentle_fade_of_a)
                            ArtworkChange.ALBUM -> tr(R.string.songs_from_the_same_album_keep)
                        },
                        changed = s.artworkChange != d.artworkChange,
                    ) { prefs.setArtworkChange(it) }
                }
                ColorSource.DYNAMIC -> {}
            }
        }

        group(
            tr(R.string.pages),
            info = changesInfo(tr(R.string.the_background_what_vinz_s_own), tr(R.string.the_ambient_frost_behind_home_search_all)),
        ) {
            // In White light, Automatic is already plain grey: Plain would be the same choice twice.
            val tints = SurfaceTint.entries.filter { !(white && it == SurfaceTint.NEUTRAL && s.surfaceTint != SurfaceTint.NEUTRAL) }
            val home = Backdrop.parse(s.backdrops[BackdropScreen.HOME.name]).first
            ChoiceRow(
                tr(R.string.page_background), tints, s.surfaceTint, ::tintLabel,
                // What hides or changes the choice, said where it's missed.
                info = listOfNotNull(
                    if (home != Backdrop.DEFAULT) tr(R.string.tl_home_has_background, home.label) else null,
                    if (s.pureBlack) tr(R.string.tl_black_keeps_dark) else null,
                ).joinToString("\n\n").ifBlank { null },
                changed = s.surfaceTint != d.surfaceTint,
            ) { prefs.setSurfaceTint(it) }
            if (s.surfaceTint == SurfaceTint.CUSTOM) PickRow(
                tr(R.string.background_color), ColorMath.toHex(s.surfaceColor), changed = s.surfaceColor != d.surfaceColor, showValue = false,
                thumbnail = { Box(Modifier.fillMaxSize().background(Color(s.surfaceColor))) },
            ) { picking = ThemePicking.Surface }
            if (s.surfaceTint == SurfaceTint.ACCENT || s.surfaceTint == SurfaceTint.CUSTOM) SliderRow(
                tr(R.string.tint_strength), s.surfaceTintStrength, 0.2f..3f, ::strengthText,
                changed = s.surfaceTintStrength != d.surfaceTintStrength,
            ) { prefs.setSurfaceTintStrength(it) }
            ChoiceRow(
                tr(R.string.text_contrast), ContrastLevel.entries, s.contrast, { it.label },
                info = changesInfo(tr(R.string.how_strong_grey_text_and_lines), tr(R.string.secondary_text_artists_times_notes_dividers_all)),
                changed = s.contrast != d.contrast,
            ) { prefs.setContrast(it) }
            ChoiceRow(
                tr(R.string.tl_page_colors), listOf(false, true), s.pageColors, ::pageColorsLabel,
                info = changesInfo(
                    tr(R.string.their_accent_yours_as_everywhere_else), tr(R.string.the_accent_on_artist_album_and_all), tr(R.string.an_artist_s_page_can_also),
                ),
                changed = s.pageColors != d.pageColors,
            ) { prefs.setPageColors(it) }
            SwitchRow(
                tr(R.string.playing_song_in_the_page_s_color), s.view.playingInPageColor, value = tr(R.string.its_bars_and_title_take_the_album_s_color),
                info = tr(R.string.on_album_playlist_and_artist_pages_the_playing_song), changed = s.view.playingInPageColor != d.view.playingInPageColor,
            ) { prefs.setPlayingInPageColor(it) }
            LinkRow(
                tr(R.string.screen_backgrounds), value = backgroundsText(s), icon = VinzIcons.Photo,
                changed = s.backdrops.screensOnly.isNotEmpty() || s.backdropDim != d.backdropDim || s.backdropMotion != d.backdropMotion || s.view.backdropFade != d.view.backdropFade,
            ) { nav.go(Routes.BACKGROUNDS) }
        }

        group(tr(R.string.glass)) {
            val choice = LookChoices.glass(s)
            val look = s.view.glass
            // The sliders need Android 12's blur: below it the bars are solid, and the row's ⓘ says so.
            val blurs = Build.VERSION.SDK_INT >= 31
            PickCardsRow(
                tr(R.string.glass), GlassChoice.entries, choice, { it.label },
                info = listOfNotNull(tr(R.string.glass_kinds_what_differs), if (blurs) null else tr(R.string.glass_needs_android_12)).joinToString("\n\n"),
                changed = choice != LookChoices.glass(d), exampleScale = 1f,
                onPick = { LookChoices.setGlass(prefs, it) },
            ) { GlassSample(it) }
            if (choice != GlassChoice.OFF && blurs) {
                // Not on Clear: it has next to no blur to set.
                if (choice != GlassChoice.LIQUID_CLEAR) SliderRow(
                    tr(R.string.glass_blur), look.blur, GlassLook.BLUR_RANGE, ::glassSliderText, info = tr(R.string.glass_blur_info),
                    changed = look.blur != d.view.glass.blur,
                ) { prefs.setGlassBlur(glassSnap(it)) }
                SliderRow(
                    tr(R.string.glass_frost), look.frost, GlassLook.FROST_RANGE, ::glassSliderText, info = tr(R.string.glass_frost_info),
                    changed = look.frost != d.view.glass.frost,
                ) { prefs.setGlassFrost(glassSnap(it)) }
            }
            val also = glassAlsoToMerged(look.more, s.frostedPages)
            fun set(part: GlassAlso, on: Boolean) {
                val (more, pages) = glassAlsoToStored(if (on) also + part else also - part)
                if (more != look.more) prefs.setGlassMore(more)
                if (pages != s.frostedPages) prefs.setFrostedPages(pages)
            }
            ShowChipsRow(
                tr(R.string.tl_glass_also_on), GlassAlso.entries.map { part -> ShowOption(part.label, part in also) { set(part, it) } },
                info = GlassAlso.MORE.label + ": " + tr(R.string.glass_on_more_of_the_screen_detail) + ". " + tr(R.string.glass_on_more_of_the_screen_info) +
                    "\n\n" + GlassAlso.PAGES.label + ": " + tr(R.string.artist_album_and_playlist_pages_sit),
                changed = look.more != d.view.glass.more || s.frostedPages != d.frostedPages,
            )
        }

        // Only while a Vinz widget is on the home screen: each opens its own settings (the same screen as holding it).
        if (widgets.isNotEmpty()) group(tr(R.string.home_screen_widgets), info = tr(R.string.each_widget_can_have_its_own)) {
            val context = LocalContext.current
            widgets.forEach { w ->
                LinkRow(w.label, value = if (w.own) tr(R.string.your_own_settings) else tr(R.string.as_designed), changed = w.own) {
                    context.startActivity(
                        Intent(context, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, w.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    picking?.let { p ->
        val (title, initial) = when (p) {
            ThemePicking.Accent -> tr(R.string.accent_color) to s.accent
            ThemePicking.LightAccent -> tr(R.string.light_mode_accent) to (s.accentLight.takeIf { it != 0 } ?: s.accent)
            ThemePicking.Surface -> tr(R.string.background_color) to s.surfaceColor
            is ThemePicking.Stop -> tr(R.string.light_color, p.index + 1) to (s.spectrumStops.getOrNull(p.index) ?: s.spectrumStops.lastOrNull() ?: s.accent)
        }
        fun withColor(c: Int): AppSettings = when (p) {
            ThemePicking.Accent -> s.copy(colorSource = ColorSource.CUSTOM, accent = c)
            ThemePicking.LightAccent -> s.copy(accentLight = c)
            ThemePicking.Surface -> s.copy(surfaceTint = SurfaceTint.CUSTOM, surfaceColor = c)
            is ThemePicking.Stop -> s.copy(
                spectrumPreset = OWN_LIGHT,
                spectrumStops = if (p.index >= s.spectrumStops.size) s.spectrumStops + c else s.spectrumStops.mapIndexed { i, old -> if (i == p.index) c else old },
            )
        }
        val reset: Pair<String, () -> Unit>? = when {
            p is ThemePicking.Stop && p.index < s.spectrumStops.size && s.spectrumStops.size > 2 ->
                tr(R.string.remove_this_color) to { prefs.setSpectrumStops(s.spectrumStops.filterIndexed { i, _ -> i != p.index }) }
            p == ThemePicking.LightAccent && s.accentLight != 0 -> tr(R.string.use_the_same) to { prefs.setAccentLight(0) }
            else -> null
        }
        ColorPickerSheet(
            title, initial,
            onDismiss = { picking = null },
            note = when (p) {
                ThemePicking.Accent, ThemePicking.LightAccent -> { c -> accentNote(c, s.accentExact) }
                else -> null
            },
            reset = reset,
            // While you pick: Vinz with that color, dark and light (light only for light mode's own accent).
            preview = { c ->
                val next = withColor(c)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p != ThemePicking.LightAccent) ColorSample(rememberColors(next, true), Modifier.weight(1f), height = 128.dp)
                    ColorSample(rememberColors(next, false), Modifier.weight(1f), height = 128.dp)
                }
            },
        ) { c ->
            when (p) {
                ThemePicking.Accent -> prefs.setAccent(c)
                ThemePicking.LightAccent -> prefs.setAccentLight(c)
                ThemePicking.Surface -> prefs.setSurfaceColor(c)
                is ThemePicking.Stop -> prefs.setSpectrumStops(withColor(c).spectrumStops)
            }
        }
    }
}

/**
 * The pinned preview: your Vinz as it is now, in dark and in light side by side, the three parts numbered (① the
 * light, ② the accent, ③ the background); as a strip, one row of it in the mode Vinz is in.
 */
@Composable
private fun ThemePreview(strip: Boolean) {
    val s = LocalAppSettings.current
    if (strip) {
        val song by LocalContainer.current.player.currentSong.collectAsState()
        Box(Modifier.fillMaxSize().padding(3.dp), contentAlignment = Alignment.Center) { ColorStage(rememberColors(s, LocalIsDark.current), song, compact = true) }
    } else Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ColorSample(rememberColors(s, true), Modifier.weight(1f), marks = true)
        ColorSample(rememberColors(s, false), Modifier.weight(1f), marks = true)
    }
}

/**
 * A light in the pick sheet: a large ring and strip of it on the page's real background, so lights that look alike
 * as small swatches (Sunset, Ember, Gold; Ocean, Ice, Aurora) can be told apart. [empty]: your own, with no colors yet.
 */
@Composable
private fun LightSwatch(c: ThemeColors, empty: Boolean) {
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().height(76.dp).clip(shape).border(1.dp, c.line, shape)) {
        PageGround(c, Modifier.matchParentSize())
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val outline = Brush.linearGradient(listOf(c.ink3, c.ink3))
            Box(Modifier.size(40.dp).clip(CircleShape).border(4.dp, if (empty) outline else Brush.sweepGradient(c.ring), CircleShape), contentAlignment = Alignment.Center) {
                if (empty) Icon(VinzIcons.Add, null, Modifier.size(18.dp), tint = c.scheme.onBackground)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).height(14.dp).clip(CircleShape).background(if (empty) outline else Brush.horizontalGradient(*c.run.toTypedArray())))
        }
    }
}

/**
 * At the top of a page of the look while Performance mode is pausing some of its effects (as the old pages said it):
 * a card with its switch, which turns Performance mode off. The kit has no such part yet.
 */
@Composable
internal fun PerformanceNote() {
    val prefs = LocalContainer.current.settings
    SettingsGroup(null) {
        SwitchRow(
            tr(R.string.performance_mode_is_on), true, value = tr(R.string.some_of_these_effects_are_paused),
            info = tr(R.string.paused_you_can_still_change_them, PERFORMANCE_PAUSES.lowercase()),
        ) { prefs.setPerformanceMode(it) }
    }
}

/** A Vinz widget on the home screen: its name there, and whether it has settings of its own. */
private class PlacedWidget(val id: Int, val label: String, val own: Boolean)

/** The Vinz widgets on the home screen now, looked at again whenever the page comes back (one added or removed meanwhile). */
@Composable
private fun placedWidgets(): List<PlacedWidget> {
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    var tick by remember { mutableStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.currentStateFlow.collect { if (it == androidx.lifecycle.Lifecycle.State.RESUMED) tick++ } }
    return remember(tick) {
        WidgetStyle.entries.flatMap { style ->
            runCatching { manager.getAppWidgetIds(android.content.ComponentName(context, style.provider)).toList() }.getOrDefault(emptyList()).map { id ->
                val label = runCatching { manager.getAppWidgetInfo(id).loadLabel(context.packageManager) }.getOrNull() ?: style.name
                PlacedWidget(id, label, own = !WidgetOptionsStore.get(context, id).isDefault)
            }
        }
    }
}

// ---------------------------------------------------------------------------- The page's parts for the map

/** The root's line for Theme & colors: the glass and the theme ("Frosted Glass · Black"). */
fun themeColorsValue(s: AppSettings, dark: Boolean): String = listOf(
    when { s.liquidGlass -> tr(R.string.liquid_glass); s.frostedGlass -> tr(R.string.frosted_glass); else -> tr(R.string.solid_bars) },
    themeToMerged(s.themeMode, s.pureBlack).label,
).joinToString(" · ")

/**
 * The look settings "Reset this page" puts back: every look setting the page's rows write, with the backgrounds the
 * Screen backgrounds row leads to. (How screens change isn't a look setting: the page's reset puts it back itself.)
 */
val themeColorsLookKeys: List<String> = listOf(
    "theme_mode", "pure_black", "reduce_motion",
    "light_style", "spectrum_preset", "spectrum_stops", "spectrum_bands",
    "color_source", "accent", "accent_exact", "accent_light", "artwork_swatch", "artwork_vivid", "artwork_change",
    "surface_tint", "surface_color", "surface_tint_strength", "contrast_level", "page_picture_accent", "playing_page_color",
    "backdrops", "backdrop_dim", "backdrop_motion", "backdrop_fade",
    "liquid_glass", "glass_kind", "frosted_glass", "glass_blur", "glass_frost", "glass_more", "frosted_pages",
)

/** Old group titles → this page's groups (their titles are their keys). */
val themeColorsOldGroups: Map<String, String> by lazy {
    val motion = tr(R.string.tl_theme_and_motion)
    val pages = tr(R.string.pages)
    mapOf(
        tr(R.string.theme) to motion, tr(R.string.motion) to motion,
        tr(R.string.spectrum) to tr(R.string.the_light),
        tr(R.string.accent_color) to tr(R.string.accent),
        tr(R.string.page_background) to pages, tr(R.string.text_contrast) to pages, tr(R.string.artist_album_pages) to pages,
        tr(R.string.backgrounds) to pages,
    )
}

/** The page's lines in Settings' search, each in the group it is drawn in. */
val themeColorsIndex: List<SettingEntry> by lazy {
    val motion = tr(R.string.tl_theme_and_motion)
    val light = tr(R.string.the_light)
    val accent = tr(R.string.accent)
    val pages = tr(R.string.pages)
    val glass = tr(R.string.glass)
    val custom = Needs(tr(R.string.pick_your_pick_for_the_accent)) { it.colorSource == ColorSource.CUSTOM }
    val cover = Needs(tr(R.string.pick_song_s_cover_for_the)) { it.colorSource == ColorSource.ARTWORK }
    with(SettingNeeds) {
        listOf(
            SettingEntry(page, motion, tr(R.string.theme), tr(R.string.dark_light_system_mode_night) + " " + tr(R.string.pure_black) + " " + tr(R.string.black) + " " + tr(R.string.amoled_oled)),
            SettingEntry(page, motion, tr(R.string.changing_screens), listOf(R.string.motion, R.string.fades_between_screens_keywords, R.string.slide_between_screens_keywords, R.string.fades_between_screens, R.string.slide_between_screens).joinToString(" ") { tr(it) }),
            SettingEntry(page, motion, tr(R.string.reduce_motion), tr(R.string.animations_accessibility) + " " + tr(R.string.motion)),
            SettingEntry(page, light, tr(R.string.tl_preset), listOf(R.string.spectrum, R.string.split_light_gradient_rainbow_preset_sunset, R.string.white, R.string.white_light_black_monochrome_neutral_grey).joinToString(" ") { tr(it) }),
            SettingEntry(page, light, tr(R.string.tl_your_own_colors), tr(R.string.spectrum) + " " + tr(R.string.your_own),
                needs = Needs(tr(R.string.tl_pick_your_own_light)) { it.lightStyle != LightStyle.WHITE && it.spectrumPreset == OWN_LIGHT }),
            SettingEntry(page, light, tr(R.string.smooth_or_bands), tr(R.string.stripes_hard_spectrum_split_light_rainbow),
                needs = Needs(tr(R.string.tl_pick_a_split_light)) { it.lightStyle != LightStyle.WHITE }),
            SettingEntry(page, accent, tr(R.string.accent_from), tr(R.string.song_cover_wallpaper_your_pick_now)),
            SettingEntry(page, accent, tr(R.string.accent_color), tr(R.string.color_accent_picker_wheel_hex_code), needs = custom),
            SettingEntry(page, accent, tr(R.string.how_it_s_used), tr(R.string.accent_color_exact_softened_as_picked) + " " + tr(R.string.exactly_as_picked) + " " + tr(R.string.accent_color_exact_shade_softened_tone), needs = custom),
            SettingEntry(page, accent, tr(R.string.light_mode_accent), tr(R.string.accent_color_light_theme) + " " + tr(R.string.accent_color_light_theme_another) + " " + tr(R.string.different_color_for_light_mode), needs = custom),
            SettingEntry(page, accent, tr(R.string.which_of_its_colors), tr(R.string.song_cover_vibrant_muted_dominant_swatch) + " " + tr(R.string.cover_color) + " " + tr(R.string.song_cover_now_playing_artwork_accent), needs = cover),
            SettingEntry(page, accent, tr(R.string.always_colorful), tr(R.string.song_cover_now_playing_artwork_accent_2), needs = cover),
            SettingEntry(page, accent, tr(R.string.between_songs), tr(R.string.song_cover_now_playing_artwork_accent_3), needs = cover),
            SettingEntry(page, pages, tr(R.string.page_background), tr(R.string.tint_surface_page_neutral_plain_color)),
            SettingEntry(page, pages, tr(R.string.background_color), tr(R.string.tint_surface_page_color),
                needs = Needs(tr(R.string.pick_your_color_under_page_background)) { it.surfaceTint == SurfaceTint.CUSTOM }),
            SettingEntry(page, pages, tr(R.string.tint_strength), tr(R.string.surface_page_tint),
                needs = Needs(tr(R.string.pick_accent_tint_or_your_color)) { it.surfaceTint == SurfaceTint.ACCENT || it.surfaceTint == SurfaceTint.CUSTOM }),
            SettingEntry(page, pages, tr(R.string.text_contrast), tr(R.string.high_contrast_accessibility_readability_legible)),
            SettingEntry(page, pages, tr(R.string.tl_page_colors), listOf(R.string.artist_album_pages, R.string.photo_s_color, R.string.picture_s_color, R.string.artist_album_playlist_page_color_color).joinToString(" ") { tr(it) }),
            SettingEntry(page, pages, tr(R.string.playing_song_in_the_page_s_color), tr(R.string.playing_indicator_bars_now_playing_album_color)),
            SettingEntry(page, pages, tr(R.string.screen_backgrounds), tr(R.string.backgrounds) + " " + tr(R.string.wallpaper_photo_pattern_design) + " " + tr(R.string.solid_color)),
            // On the Screen backgrounds screen, which the row above opens.
            SettingEntry(page, pages, tr(R.string.now_playing_background_fades), tr(R.string.now_playing_background_fades_keywords)),
            // The old titles stay as search words.
            SettingEntry(page, glass, tr(R.string.glass), tr(R.string.blur_transparent) + " " + tr(R.string.liquid_glass) + " " + tr(R.string.frosted_glass) + " " + tr(R.string.glass_style)),
            SettingEntry(page, glass, tr(R.string.glass_blur), tr(R.string.glass_blur_info), needs = turnOn(tr(R.string.frosted_glass)) { (it.frostedGlass || it.liquidGlass) && !(it.liquidGlass && it.glassKind == GlassKind.CLEAR) }),
            SettingEntry(page, glass, tr(R.string.glass_frost), tr(R.string.glass_frost_info), needs = turnOn(tr(R.string.frosted_glass)) { it.frostedGlass || it.liquidGlass }),
            SettingEntry(page, glass, tr(R.string.tl_glass_also_on), listOf(R.string.glass_on_more_of_the_screen, R.string.glass_on_more_of_the_screen_detail, R.string.frosted_pages, R.string.blur_artist_album_playlist).joinToString(" ") { tr(it) }),
            SettingEntry(page, tr(R.string.home_screen_widgets), tr(R.string.home_screen_widgets), tr(R.string.widget_background_transparent_see_through_color)),
        )
    }
}
