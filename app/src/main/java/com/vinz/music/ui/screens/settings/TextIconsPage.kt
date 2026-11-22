package com.vinz.music.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ButtonShape
import com.vinz.music.data.prefs.Corners
import com.vinz.music.data.prefs.CoverShape
import com.vinz.music.data.prefs.FontChoice
import com.vinz.music.data.prefs.TypeSpacing
import com.vinz.music.data.prefs.TypeWeight
import com.vinz.music.tr
import com.vinz.music.ui.screens.InstalledFontsSheet
import com.vinz.music.ui.screens.SettingEntry
import com.vinz.music.ui.screens.SettingsSection
import com.vinz.music.ui.theme.AppFonts
import com.vinz.music.ui.theme.AppIcon
import com.vinz.music.ui.theme.IconFill
import com.vinz.music.ui.theme.IconPack
import com.vinz.music.ui.theme.IconWeight
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val page = SettingsPageId.TEXT_ICONS

// ---------------------------------------------------------------------------- §2.5: icon weight + rounded, one row

/**
 * The icons' lines as one choice (plan §2.5: "icon weight + rounded" is one row): how heavy they are drawn and
 * whether their ends and corners are round. Underneath it is still the two values [com.vinz.music.ui.theme.IconStyle]
 * saves, a weight and a flag.
 */
data class IconLine(val weight: IconWeight, val rounded: Boolean)

/** Every icon line there is, as the row's sheet lists them: each weight, rounded then square. */
val iconLines: List<IconLine> = IconWeight.entries.flatMap { w -> listOf(IconLine(w, true), IconLine(w, false)) }

/** The saved weight and rounded flag as the one choice the row shows. */
fun iconLineToMerged(stored: Pair<IconWeight, Boolean>): IconLine = IconLine(stored.first, stored.second)

/** The choice back as the two saved values. */
fun iconLineToStored(line: IconLine): Pair<IconWeight, Boolean> = line.weight to line.rounded

/** "Regular, rounded ends". */
fun iconLineLabel(line: IconLine): String = tr(if (line.rounded) R.string.icon_line_rounded else R.string.icon_line_square, line.weight.label)

// ---------------------------------------------------------------------------- The page

/** The corners' value in words: a step's name when it is (nearly) on one, else a percentage. */
private fun cornerText(v: Float): String =
    Corners.entries.minByOrNull { abs(it.scale - v) }!!.let { if (abs(it.scale - v) < 0.08f) it.label else "${(v * 100).roundToInt()}%" }

private fun onOff(on: Boolean) = if (on) tr(R.string.on_2) else tr(R.string.off)
private fun typeName(classic: Boolean) = if (classic) tr(R.string.classic) else tr(R.string.vinz_s_own)
private fun playName(outlined: Boolean) = if (outlined) tr(R.string.outlined) else tr(R.string.filled)
private fun titleFontName(f: FontChoice?) = f?.label ?: tr(R.string.same_as_the_text)

/** The font row's value: the font's name, or the name of the listener's own font file. */
private fun fontName(s: AppSettings, hasFile: Boolean): String =
    if (s.fontChoice == FontChoice.CUSTOM && hasFile && s.appFontName.isNotBlank()) tr(R.string.your_font, s.appFontName) else s.fontChoice.label

/**
 * Settings → Text & icons (1.8.4's map, plan §2.3), rebuilt with the kit. This file is the only place for the page:
 * its composable, its lines in Settings' search ([textIconsIndex]), its "Reset this page" keys ([textIconsLookKeys]),
 * its line on the root ([textIconsValue]) and the old group names it answers to ([textIconsOldGroups]). What its rows
 * draw (the examples, the font's sheet, the pinned preview) is in `TextIconsParts.kt`.
 *
 * Four groups, as the ledger proposes: **Text** (font, app text size, type, weight, letter spacing, titles), **Icons**
 * (fill, pack, weight with rounded as one row, likes as stars, app icon), **Shapes** (corners, the three cover shapes,
 * square video covers) and **Buttons** (shape, Play on album pages, switches). Every choice the old pages showed as
 * example cards opens in a pick sheet with the same examples. Not here any more: the lyrics font (Lyrics & Sing's) and
 * the player's cover shape (Player's).
 */
@Composable
fun TextIconsPage(bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val s = LocalAppSettings.current
    val prefs = c.settings
    val d = remember { AppSettings() }
    val icons = remember { IconChoices(context.applicationContext) }
    val hasFile = remember(s.fontStamp) { AppFonts.customFile(AppFonts.FontSlot.APP) != null }
    val corner = s.shapes.cornerScale ?: s.corners.scale
    val defaultLine = IconLine(IconWeight.REGULAR, true)

    // Every row with its value now and as Vinz comes: "Reset this page" lists the ones that differ.
    val rows = listOf(
        Triple(tr(R.string.font), fontName(s, hasFile), d.fontChoice.label),
        Triple(tr(R.string.app_text_size), percent(s.textScale), percent(d.textScale)),
        Triple(tr(R.string.type), typeName(s.type.classic), typeName(d.type.classic)),
        Triple(tr(R.string.weight), s.type.weight.label, d.type.weight.label),
        Triple(tr(R.string.letter_spacing), s.type.spacing.label, d.type.spacing.label),
        Triple(tr(R.string.titles), titleFontName(s.type.titleFont), titleFontName(d.type.titleFont)),
        Triple(tr(R.string.icon_style), icons.fill.label, IconFill.OUTLINE.label),
        Triple(tr(R.string.pack), icons.pack.label, IconPack.VINZ.label),
        Triple(tr(R.string.icon_weight), iconLineLabel(iconLineToMerged(icons.line)), iconLineLabel(defaultLine)),
        Triple(tr(R.string.likes_as_stars), onOff(icons.likeStar), onOff(false)),
        Triple(tr(R.string.card_and_cover_corners), cornerText(corner), cornerText(d.corners.scale)),
        Triple(tr(R.string.songs_covers), s.shapes.songs.label, d.shapes.songs.label),
        Triple(tr(R.string.albums_covers), s.shapes.albums.label, d.shapes.albums.label),
        Triple(tr(R.string.artists_pictures), s.shapes.artists.label, d.shapes.artists.label),
        Triple(tr(R.string.square_video_covers), onOff(s.squareVideoCovers), onOff(d.squareVideoCovers)),
        Triple(tr(R.string.shape), s.shapes.button.label, d.shapes.button.label),
        Triple(tr(R.string.play_on_album_and_playlist_pages), playName(s.shapes.playOutlined), playName(d.shapes.playOutlined)),
        Triple(tr(R.string.switches_sliders_and_buttons), typeName(s.shapes.classicControls), typeName(d.shapes.classicControls)),
    )
    val reset = PageReset(rows.filter { it.second != it.third }.map { resetLine(it.first, it.second, it.third) }) {
        prefs.resetLookKeys(textIconsLookKeys)
        prefs.setSquareVideoCovers(d.squareVideoCovers)
        // The icon style lives apart from the settings.
        icons.reset()
        c.looks.iconsChanged()
    }

    SettingsPageScaffold(
        page.title, bottomPadding, showBack = showBack, onSearch = onSearch, reset = reset, find = find,
        preview = { strip -> TextIconsPreview(s, icons, strip) }, previewHeight = 164.dp,
    ) {
        group(tr(R.string.text)) {
            FontRow(s, hasFile, changed = s.fontChoice != d.fontChoice)
            SliderRow(
                tr(R.string.app_text_size), s.textScale, 0.85f..1.25f, ::percent, steps = 7,
                info = tr(R.string.on_top_of_your_phone_s), changed = s.textScale != d.textScale,
            ) { prefs.setTextScale(it) }
            // Classic type (1.7.8, #155): Vinz 1.6's bold headings, or today's light ones.
            PickCardsRow(
                tr(R.string.type), listOf(false, true), s.type.classic, ::typeName, info = tr(R.string.vinz_1_7_writes_big_titles),
                changed = s.type.classic != d.type.classic, onPick = { prefs.setTypeClassic(it) },
            ) { TypeSample(s, s.type.copy(classic = it)) }
            PickCardsRow(
                tr(R.string.weight), TypeWeight.entries, s.type.weight, { it.label }, info = tr(R.string.how_heavy_all_of_vinz_s),
                changed = s.type.weight != d.type.weight, columns = 3, onPick = { prefs.setTypeWeight(it) },
            ) { TypeSample(s, s.type.copy(weight = it)) }
            PickCardsRow(
                tr(R.string.letter_spacing), TypeSpacing.entries, s.type.spacing, { it.label },
                changed = s.type.spacing != d.type.spacing, columns = 3, onPick = { prefs.setTypeSpacing(it) },
            ) { TypeSample(s, s.type.copy(spacing = it)) }
            // Titles in a font of their own: every built-in font but the text's.
            val titleFonts: List<FontChoice?> = listOf(null) + FontChoice.entries.filter { it != FontChoice.CUSTOM && it != s.fontChoice }
            PickCardsRow(
                tr(R.string.titles), titleFonts, s.type.titleFont, ::titleFontName, info = tr(R.string.page_section_and_album_titles_can),
                changed = s.type.titleFont != d.type.titleFont, onPick = { prefs.setTitleFont(it) },
            ) { TypeSample(s, s.type.copy(titleFont = it)) }
        }
        group(tr(R.string.icons)) {
            // Vinz's icons are built as it starts: a change is saved for the next start.
            fun changedIcons() = c.looks.iconsChanged()
            PickCardsRow(
                tr(R.string.icon_style), IconFill.entries, icons.fill, { it.label }, changed = icons.fill != IconFill.OUTLINE, columns = 3,
                onPick = { icons.saveFill(it); changedIcons() },
            ) { IconsSample(it, icons.pack, fills = true) }
            PickCardsRow(
                tr(R.string.pack), IconPack.entries, icons.pack, { it.label }, changed = icons.pack != IconPack.VINZ,
                onPick = { icons.savePack(it); changedIcons() },
            ) { IconsSample(IconFill.OUTLINE, it) }
            PickListRow(
                tr(R.string.icon_weight), iconLines, iconLineToMerged(icons.line), ::iconLineLabel,
                changed = iconLineToMerged(icons.line) != defaultLine, searchable = false,
                onPick = { icons.saveLine(iconLineToStored(it)); changedIcons() },
            )
            SwitchRow(tr(R.string.likes_as_stars), icons.likeStar, info = tr(R.string.a_star_instead_of_a_heart), changed = icons.likeStar) {
                icons.saveLikeStar(it); changedIcons()
            }
            PickCardsRow(
                tr(R.string.app_icon), AppIcon.entries, icons.appIcon, { it.label }, info = tr(R.string.your_launcher_may_take_a_moment),
                changed = icons.appIcon != AppIcon.CLASSIC, columns = 3, thumbnail = { AppIconPicture(it, 30.dp) },
                onPick = { icons.saveAppIcon(it) },
            ) { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AppIconPicture(it, 60.dp) } }
            if (icons.restart) LinkRow(tr(R.string.restart_vinz_to_see_them), value = tr(R.string.playback_stops_for_a_moment), action = true) {
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launch != null) {
                    context.startActivity(android.content.Intent.makeRestartActivityTask(launch.component).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    Runtime.getRuntime().exit(0)
                }
            }
        }
        group(tr(R.string.text_icons_shapes)) { TextIconsShapesRows() }
        group(tr(R.string.buttons)) { TextIconsButtonsRows() }
    }
}

/** The Shapes group's rows: on the page, and in "Customize this" for a Home section. */
@Composable
internal fun TextIconsShapesRows() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val d = remember { AppSettings() }
    val corner = s.shapes.cornerScale ?: s.corners.scale
    // Corners as a slider (1.7.7): the old four steps are its marks, and a Look that names a step still works.
    SliderRow(
        tr(R.string.card_and_cover_corners), corner, 0f..2f, ::cornerText, info = tr(R.string.card_and_cover_corners_info),
        changed = cornerText(corner) != cornerText(d.corners.scale),
    ) { v ->
        // Close to a step: that step, so it reads by name.
        Corners.entries.firstOrNull { abs(it.scale - v) < 0.06f }?.let { prefs.setCorners(it) } ?: prefs.setCornerScale(v)
    }
    // Each kind of picture has its own shape (the player's cover has its own on the Player page).
    CoverShapeRow(tr(R.string.songs_covers), "songs", s.shapes.songs, d.shapes.songs)
    CoverShapeRow(tr(R.string.albums_covers), "albums", s.shapes.albums, d.shapes.albums)
    CoverShapeRow(tr(R.string.artists_pictures), "artists", s.shapes.artists, d.shapes.artists)
    // It squares video pictures everywhere, not only on the player (#92).
    SwitchRow(
        tr(R.string.square_video_covers), s.squareVideoCovers, info = tr(R.string.youtube_s_video_pictures_are_wide),
        changed = s.squareVideoCovers != d.squareVideoCovers,
    ) { prefs.setSquareVideoCovers(it) }
}

/** The Buttons group's rows: on the page, and in "Customize this" for a Home section. */
@Composable
internal fun TextIconsButtonsRows() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val d = remember { AppSettings() }
    PickCardsRow(
        tr(R.string.shape), ButtonShape.entries, s.shapes.button, { it.label }, changed = s.shapes.button != d.shapes.button, columns = 3,
        onPick = { prefs.setButtonShape(it) },
    ) { ButtonShapeSample(it) }
    PickCardsRow(
        tr(R.string.play_on_album_and_playlist_pages), listOf(false, true), s.shapes.playOutlined, ::playName,
        changed = s.shapes.playOutlined != d.shapes.playOutlined, onPick = { prefs.setPlayOutlined(it) },
    ) { PlayButtonSample(it) }
    // Classic controls (1.7.8, #155): each card draws a switch and a slider its way.
    PickCardsRow(
        tr(R.string.switches_sliders_and_buttons), listOf(false, true), s.shapes.classicControls, ::typeName,
        info = tr(R.string.vinz_s_own_lit_with_your), changed = s.shapes.classicControls != d.shapes.classicControls,
        onPick = { prefs.setControlsClassic(it) },
    ) { ControlsSample(it) }
}

/** One kind of cover's shape: a Pick row whose sheet draws each shape as that kind of cover. */
@Composable
private fun CoverShapeRow(title: String, which: String, now: CoverShape, default: CoverShape) {
    val prefs = LocalContainer.current.settings
    PickCardsRow(
        title, CoverShape.entries, now, { it.label }, info = tr(R.string.each_kind_of_picture_can_have), changed = now != default, columns = 3,
        onPick = { prefs.setCoverShape(which, it) },
    ) { CoverShapeExample(it, round = which == "artists") }
}

/**
 * The app's font: a Pick row whose sheet ([FontSheet]) holds the fonts and, under them, a font file from the phone
 * (#12, copied into Vinz), the phone's installed fonts (#318) and the file's removal.
 */
@Composable
private fun FontRow(s: AppSettings, hasFile: Boolean, changed: Boolean) {
    val prefs = LocalContainer.current.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fontTypes = arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream")
    // The launcher stays with the row: the sheet is gone by the time the file comes back.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { AppFonts.import(context, uri, AppFonts.FontSlot.APP) } }
            r.onSuccess { name -> prefs.setAppFont(name); prefs.setFontChoice(FontChoice.CUSTOM) }
                .onFailure { Toast.makeText(context, it.message ?: tr(R.string.that_font_couldn_t_be_used), Toast.LENGTH_LONG).show() }
        }
    }
    var open by rememberSaveable { mutableStateOf(false) }
    var installed by rememberSaveable { mutableStateOf(false) }
    // "Aa" in the font, at a size that doesn't follow the text size (its frame doesn't either).
    val small = with(LocalDensity.current) { 15.dp.toSp() }
    PickRow(
        tr(R.string.font), fontName(s, hasFile), changed = changed,
        thumbnail = { Text(tr(R.string.aa), style = TextStyle(fontFamily = AppFonts.family(s.fontChoice), fontWeight = FontWeight.Medium, fontSize = small), color = MaterialTheme.colorScheme.onSurface, maxLines = 1) },
    ) { open = true }
    if (open) FontSheet(
        s.fontChoice, customName = s.appFontName.takeIf { hasFile }, stamp = s.fontStamp,
        onPick = { prefs.setFontChoice(it) },
        onFile = { pickFile.launch(fontTypes) },
        onInstalled = { installed = true },
        onRemove = {
            AppFonts.remove(AppFonts.FontSlot.APP)
            if (s.fontChoice == FontChoice.CUSTOM) prefs.setFontChoice(FontChoice.VINZ)
            prefs.setAppFont("")
        },
        onDismiss = { open = false },
    )
    if (installed) InstalledFontsSheet(AppFonts.FontSlot.APP, onPicked = { name -> prefs.setAppFont(name); prefs.setFontChoice(FontChoice.CUSTOM) }) { installed = false }
}

/** The root's line for Text & icons: the font, and the text size when it isn't the usual. */
fun textIconsValue(s: AppSettings, dark: Boolean): String = SettingsSection.TEXT.summary(s, dark)

/**
 * The look settings "Reset this page" puts back (the icon style, kept apart from the settings, goes back with them, and
 * so does Square video covers, which isn't a look setting). The lyrics font is Lyrics & Sing's to reset and the
 * player's cover shape is Player's: they left with their rows.
 */
val textIconsLookKeys: List<String> = listOf(
    "font_choice", "text_scale", "type_classic", "type_weight", "type_spacing", "title_font",
    "corners", "corner_scale", "cover_shape_songs", "cover_shape_albums", "cover_shape_artists",
    "button_shape", "play_outlined", "controls_classic",
)

/** Old group titles → this page's group keys (a group's key is its title): the nine old groups are four now. */
val textIconsOldGroups: Map<String, String> by lazy {
    mapOf(
        tr(R.string.type) to tr(R.string.text), tr(R.string.weight_and_spacing) to tr(R.string.text), tr(R.string.titles) to tr(R.string.text),
        tr(R.string.app_icon) to tr(R.string.icons),
        tr(R.string.shape) to tr(R.string.text_icons_shapes), tr(R.string.covers_shapes) to tr(R.string.text_icons_shapes),
    )
}

/**
 * The page's lines in Settings' search. A renamed row is listed by its new name, with the old one among its search
 * words ("text size", "corners"); the old Rounded switch, part of Icon weight now, keeps a line that says so.
 */
val textIconsIndex: List<SettingEntry> by lazy {
    val text = tr(R.string.text)
    val icons = tr(R.string.icons)
    val shapes = tr(R.string.text_icons_shapes)
    val buttons = tr(R.string.buttons)
    val covers = tr(R.string.cover_shape_circle_squircle_flower_cookie) + " " + tr(R.string.covers_shapes)
    listOf(
        SettingEntry(page, text, tr(R.string.font), tr(R.string.typeface_custom_ttf_otf_outfit_vinz)),
        // In the font's sheet.
        SettingEntry(page, text, tr(R.string.use_a_font_from_your_phone), tr(R.string.typeface_custom_ttf_otf_outfit_vinz)),
        SettingEntry(page, text, tr(R.string.installed_fonts), tr(R.string.installed_fonts_keywords)),
        SettingEntry(page, text, tr(R.string.remove_your_font)),
        SettingEntry(page, text, tr(R.string.app_text_size), tr(R.string.font_size_scale_bigger_smaller) + " " + tr(R.string.text_size)),
        SettingEntry(page, text, tr(R.string.type), tr(R.string.classic_1_6_bold_headings_titles) + " " + tr(R.string.classic_type)),
        SettingEntry(page, text, tr(R.string.weight), tr(R.string.bold_light_heavy_text_weight) + " " + tr(R.string.weight_and_spacing)),
        SettingEntry(page, text, tr(R.string.letter_spacing), tr(R.string.tracking_wide_tight_letters)),
        SettingEntry(page, text, tr(R.string.titles), tr(R.string.title_font_headings_different_font)),
        SettingEntry(page, icons, tr(R.string.icon_style), tr(R.string.fill) + " " + tr(R.string.outline) + " " + tr(R.string.duotone) + " " + tr(R.string.filled)),
        SettingEntry(page, icons, tr(R.string.pack), tr(R.string.icon_style_cupertino_apple)),
        SettingEntry(page, icons, tr(R.string.icon_weight), tr(R.string.icon_thin_bold)),
        // The old Rounded switch is part of Icon weight.
        SettingEntry(page, icons, tr(R.string.rounded), tr(R.string.icon_corners), shown = tr(R.string.search_shown_as, tr(R.string.icon_weight), tr(R.string.rounded))),
        SettingEntry(page, icons, tr(R.string.likes_as_stars), tr(R.string.heart_star_like_favourite_favorite_icon)),
        SettingEntry(page, icons, tr(R.string.app_icon), tr(R.string.launcher_home_screen_icon_logo)),
        SettingEntry(page, icons, tr(R.string.restart_vinz_to_see_them)),
        SettingEntry(page, shapes, tr(R.string.card_and_cover_corners), tr(R.string.rounded_square_shape_radius_slider) + " " + tr(R.string.corners)),
        SettingEntry(page, shapes, tr(R.string.songs_covers), covers),
        SettingEntry(page, shapes, tr(R.string.albums_covers), covers),
        SettingEntry(page, shapes, tr(R.string.artists_pictures), covers),
        SettingEntry(page, shapes, tr(R.string.square_video_covers), tr(R.string.video_thumbnail_crop_black_bars_letterbox)),
        SettingEntry(page, buttons, tr(R.string.shape), tr(R.string.button_shape_pill_rounded_square) + " " + buttons),
        SettingEntry(page, buttons, tr(R.string.play_on_album_and_playlist_pages), tr(R.string.play_button_outlined_filled)),
        SettingEntry(page, buttons, tr(R.string.switches_sliders_and_buttons), tr(R.string.classic_controls_1_6_material_switch)),
    )
}
