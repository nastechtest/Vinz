package com.vinz.music.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ButtonShape
import com.vinz.music.data.prefs.CoverShape
import com.vinz.music.data.prefs.FontChoice
import com.vinz.music.data.prefs.TypeLook
import com.vinz.music.tr
import com.vinz.music.ui.components.KeepUpwardScroll
import com.vinz.music.ui.components.VinzSheet
import com.vinz.music.ui.components.VinzSlider
import com.vinz.music.ui.components.VinzSwitch
import com.vinz.music.ui.components.frostFill
import com.vinz.music.ui.screens.WholeWords
import com.vinz.music.ui.theme.AppFonts
import com.vinz.music.ui.theme.AppIcon
import com.vinz.music.ui.theme.AppIcons
import com.vinz.music.ui.theme.CoverShapes
import com.vinz.music.ui.theme.IconFill
import com.vinz.music.ui.theme.IconPack
import com.vinz.music.ui.theme.IconStyle
import com.vinz.music.ui.theme.IconWeight
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalUi
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.ui.theme.VinzTokens
import com.vinz.music.ui.theme.VinzUi
import com.vinz.music.ui.theme.appTypography
import com.vinz.music.ui.theme.hairline

/*
 * Settings → Text & icons (1.8.4): what the page's rows draw. The examples are the old Text and Icons & shapes pages'
 * (`OldTextIcons.kt`), drawn the same; they sit in pick sheets now.
 */

// ---------------------------------------------------------------------------- The icon style (kept apart from the settings)

/**
 * The icon style as saved ([IconStyle], plain preferences read as Vinz starts) and the launcher icon, held for the
 * page: a change is saved for the next start, and [restart] says when what's saved isn't what's drawn.
 */
internal class IconChoices(private val context: Context) {
    var line by mutableStateOf(IconStyle.saved(context))
        private set
    var pack by mutableStateOf(IconStyle.savedPack(context))
        private set
    var likeStar by mutableStateOf(IconStyle.savedLikeStar(context))
        private set
    var fill by mutableStateOf(IconStyle.savedFill(context))
        private set
    var appIcon by mutableStateOf(AppIcons.current(context))
        private set

    val restart: Boolean
        get() = line != (IconStyle.weight to IconStyle.rounded) || pack != IconStyle.pack || likeStar != IconStyle.likeStar || fill != IconStyle.fill

    fun saveLine(v: Pair<IconWeight, Boolean>) { line = v; IconStyle.save(context, v.first, v.second) }
    fun savePack(v: IconPack) { pack = v; IconStyle.savePack(context, v) }
    fun saveLikeStar(v: Boolean) { likeStar = v; IconStyle.saveLikeStar(context, v) }
    fun saveFill(v: IconFill) { fill = v; IconStyle.saveFill(context, v) }
    fun saveAppIcon(v: AppIcon) { if (v != appIcon) { AppIcons.set(context, v); appIcon = v } }

    /** Back to how Vinz comes (the launcher icon is left alone, as the old page's reset left it). */
    fun reset() {
        saveLine(IconWeight.REGULAR to true)
        savePack(IconPack.VINZ)
        saveLikeStar(false)
        saveFill(IconFill.OUTLINE)
    }
}

/**
 * Vinz's icons in [fill] and [pack]: four of them, or with [fills] the three that show a fill best. (The drawings' own
 * names, never words on the screen: translated, "Inicio" had no drawing, #255.)
 */
@Composable
internal fun rememberIconSamples(fill: IconFill, pack: IconPack, fills: Boolean = false) = remember(fill, pack, fills) {
    when {
        // Cupertino is drawn by hand for its own icons; the others come from the drawings.
        pack == IconPack.CUPERTINO -> VinzIcons.cupertinoPreview()
        fills -> listOf("Home", "Library", "Heart").map { VinzIcons.preview(it, fill, pack) }
        else -> listOf("Home", "Search", "Heart", "Play").map { VinzIcons.preview(it, fill, pack) }
    }
}

@Composable
internal fun IconsSample(fill: IconFill, pack: IconPack, fills: Boolean = false) {
    val icons = rememberIconSamples(fill, pack, fills)
    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        icons.forEach { Icon(it, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface) }
    }
}

/** A launcher icon as its own two layers in a rounded square, [size] across. */
@Composable
internal fun AppIconPicture(icon: AppIcon, size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4))) {
        // An adaptive icon's layers are 108 units with the visible 72 in the middle: draw them 1.5× and crop.
        Image(painterResource(icon.background), null, Modifier.fillMaxSize().scale(1.5f))
        Image(painterResource(icon.foreground), null, Modifier.fillMaxSize().scale(1.5f))
    }
}

// ---------------------------------------------------------------------------- Examples

/** A page title and a line of text in [t], on the app's font. */
@Composable
internal fun TypeSample(s: AppSettings, t: TypeLook) {
    val scheme = MaterialTheme.colorScheme
    val type = remember(s.fontChoice, s.fontStamp, t) { appTypography(s.copy(type = t)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(tr(R.string.songs), style = type.headlineSmall, color = scheme.onSurface, maxLines = 1)
        Text(tr(R.string.played_today), style = type.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}

/** [shape] drawn as a cover would be ([round]: as an artist's picture). */
@Composable
internal fun CoverShapeExample(shape: CoverShape, round: Boolean) {
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth(0.82f).aspectRatio(1f).clip(CoverShapes.of(shape, ui.r(14.dp)))
                .background(Brush.linearGradient(listOf(scheme.primary, VinzTokens.spectrumAt(if (round) 0.75f else 0.3f)))),
            contentAlignment = Alignment.Center,
        ) { Icon(if (round) VinzIcons.Person else VinzIcons.Song, null, tint = Color.White.copy(alpha = 0.85f)) }
    }
}

@Composable
internal fun ButtonShapeSample(b: ButtonShape) {
    val scheme = MaterialTheme.colorScheme
    val shape = LocalUi.current.copy(buttonShape = b).button
    Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(30.dp).clip(shape).background(scheme.onSurface), contentAlignment = Alignment.Center) {
            Text(tr(R.string.widget_play), style = MaterialTheme.typography.labelMedium, color = scheme.surface)
        }
    }
}

@Composable
internal fun PlayButtonSample(outlined: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val shape = LocalUi.current.button
    Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).clip(shape)
                .then(if (outlined) Modifier.border(1.5.dp, scheme.onSurface, shape) else Modifier.background(scheme.onSurface)),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(VinzIcons.Play, null, Modifier.size(16.dp), tint = if (outlined) scheme.onSurface else scheme.surface)
            Spacer(Modifier.width(4.dp))
            Text(tr(R.string.widget_play), style = MaterialTheme.typography.labelMedium, color = if (outlined) scheme.onSurface else scheme.surface)
        }
    }
}

/** A slider and a switch drawn Vinz's way or Vinz 1.6's. */
@Composable
internal fun ControlsSample(classic: Boolean) {
    CompositionLocalProvider(LocalUi provides LocalUi.current.copy(classicControls = classic)) {
        Box {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                VinzSlider(0.6f, {}, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                VinzSwitch(true, null)
            }
            // A picture of the controls, not controls: a tap on the slider is a tap on the card it's drawn in (the
            // slider would keep the tap, and the choice wouldn't be picked).
            Box(Modifier.matchParentSize().pointerInput(Unit) {})
        }
    }
}

// ---------------------------------------------------------------------------- The pinned preview

/**
 * The page's live example: a title and a line in the app's type, four icons in the saved fill and pack, a button in
 * its shape and a cover in the albums' shape with the corners as set. As a strip: the title and three icons.
 */
@Composable
internal fun TextIconsPreview(s: AppSettings, icons: IconChoices, strip: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val type = remember(s.fontChoice, s.fontStamp, s.type) { appTypography(s) }
    val ui = remember(s.shapes, s.corners) { VinzUi.from(s) }
    val drawn = rememberIconSamples(icons.fill, icons.pack)
    if (strip) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.songs), Modifier.weight(1f), style = type.titleLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            drawn.take(3).forEach { Icon(it, null, Modifier.padding(start = 14.dp).size(22.dp), tint = scheme.onSurface) }
        }
        return
    }
    Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr(R.string.songs), style = type.headlineSmall, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tr(R.string.played_today), style = type.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                drawn.forEach { Icon(it, null, Modifier.size(22.dp), tint = scheme.onSurface) }
            }
            Spacer(Modifier.height(12.dp))
            val outlined = s.shapes.playOutlined
            Row(
                Modifier.height(30.dp).clip(ui.button)
                    .then(if (outlined) Modifier.border(1.5.dp, scheme.onSurface, ui.button) else Modifier.background(scheme.onSurface))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(VinzIcons.Play, null, Modifier.size(14.dp), tint = if (outlined) scheme.onSurface else scheme.surface)
                Spacer(Modifier.width(4.dp))
                Text(tr(R.string.widget_play), style = type.labelMedium, color = if (outlined) scheme.onSurface else scheme.surface, maxLines = 1)
            }
        }
        Spacer(Modifier.width(16.dp))
        Box(
            Modifier.size(88.dp).clip(ui.albumArt).background(Brush.linearGradient(listOf(scheme.primary, VinzTokens.spectrumAt(0.3f)))),
            contentAlignment = Alignment.Center,
        ) { Icon(VinzIcons.Song, null, tint = Color.White.copy(alpha = 0.85f)) }
    }
}

// ---------------------------------------------------------------------------- The font's sheet

/**
 * The font's pick sheet: a card per font (the old font picker's cards: "Aa" in the font, its name, the typeface's own
 * name), and under them what belongs to the font and nothing else: a font file from the phone, the phone's installed
 * fonts, and taking the file away again. A tap on a font applies it and closes the sheet; "Your font" without a file
 * asks for one ([onFile]).
 *
 * The kit's `PickSheet` holds cards only; this one also holds rows, so it is built here (a kit gap, see the report).
 */
@Composable
internal fun FontSheet(
    current: FontChoice,
    customName: String?,
    stamp: Long,
    onPick: (FontChoice) -> Unit,
    onFile: () -> Unit,
    onInstalled: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val hasFile = customName != null
    val tall = LocalConfiguration.current.screenHeightDp.dp * 0.62f
    val haptics = LocalHapticFeedback.current
    val tick = LocalAppSettings.current.haptics
    VinzSheet(onDismiss, containerColor = scheme.surfaceContainerLow, contentColor = scheme.onSurface, dragHandle = null) { close ->
        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp, 4.dp).clip(CircleShape).background(scheme.onSurface.copy(alpha = 0.3f)))
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = tall).testTag(SettingsTags.SHEET).nestedScroll(KeepUpwardScroll)
                .verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        ) {
            Text(
                tr(R.string.font), Modifier.padding(start = 20.dp, end = 16.dp, bottom = 10.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            FontCards(current, customName, stamp) { f ->
                if (tick) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                // "Your font" is always pickable: without a file yet, picking it asks for one.
                if (f == FontChoice.CUSTOM && !hasFile) close { onFile() }
                else { if (f != current) onPick(f); close {} }
            }
            Spacer(Modifier.height(8.dp))
            LinkRow(
                if (hasFile) tr(R.string.your_font, customName.orEmpty()) else tr(R.string.use_a_font_from_your_phone),
                value = if (hasFile) tr(R.string.tap_to_pick_a_different_ttf) else tr(R.string.pick_a_ttf_or_otf_file),
            ) { close { onFile() } }
            // #318 (1.8.1): a font installed on the phone, without a file to pick (Android 10 and later list them).
            if (android.os.Build.VERSION.SDK_INT >= 29) LinkRow(
                tr(R.string.installed_fonts), value = tr(R.string.installed_fonts_detail), info = tr(R.string.installed_fonts_info),
            ) { close { onInstalled() } }
            if (hasFile) LinkRow(tr(R.string.remove_your_font), value = tr(R.string.back_to_a_built_in_font), action = true) { onRemove(); close {} }
        }
    }
}

/** The fonts as cards, as many across as fit at about 100 dp, every card in a row as tall as the tallest (#117). */
@Composable
private fun FontCards(current: FontChoice, customName: String?, stamp: Long, onPick: (FontChoice) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ui = LocalUi.current
    val missing = remember(stamp) { FontChoice.entries.filterNot { it == FontChoice.CUSTOM || AppFonts.available(it) } }
    BoxWithConstraints(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        // Wider with larger text, so names like "Geometric" still fit.
        val card = 100.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val columns = ((maxWidth + 8.dp) / (card + 8.dp)).toInt().coerceIn(2, 5)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FontChoice.entries.chunked(columns).forEach { rowFonts ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowFonts.forEach { f ->
                        val on = f == current
                        val usable = f !in missing
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(ui.tile).background(frostFill(if (on) 2.6f else 1f))
                                .then(if (on) Modifier.border(2.dp, VinzTokens.spectrumRing(), ui.tile) else Modifier.border(1.dp, scheme.hairline, ui.tile))
                                .testTag(SettingsTags.SHEET_OPTION).semantics { role = Role.RadioButton; selected = on }
                                .clickable(enabled = usable) { onPick(f) }
                                .then(if (usable) Modifier else Modifier.alpha(0.38f))
                                .padding(10.dp),
                        ) {
                            Text(tr(R.string.aa), style = TextStyle(fontFamily = AppFonts.family(f), fontWeight = FontWeight.Medium, fontSize = MaterialTheme.typography.headlineSmall.fontSize), color = scheme.onSurface)
                            // The name gets smaller until no word is split ("Dyslexia-friendly" in OpenDyslexic's wide letters).
                            WholeWords(
                                if (f == FontChoice.CUSTOM) customName ?: tr(R.string.your_font_3) else f.label,
                                MaterialTheme.typography.labelMedium.copy(fontFamily = AppFonts.family(f)),
                                if (on) scheme.onSurface else scheme.onSurfaceVariant,
                            )
                            // The typeface's own name, quietly, so it can be looked up (and matched to a file for Your font).
                            f.face?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    repeat(columns - rowFonts.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
