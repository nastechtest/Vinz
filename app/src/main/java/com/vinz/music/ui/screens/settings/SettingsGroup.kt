package com.vinz.music.ui.screens.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vinz.music.ui.components.LocalFrosted
import com.vinz.music.ui.components.pane
import com.vinz.music.ui.screens.InfoMark
import com.vinz.music.ui.screens.InfoText
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalUi
import com.vinz.music.ui.theme.VinzTokens
import com.vinz.music.ui.theme.hairline

/** Whether a settings card is drawn as glass (true) or as a plain, solid surface (false: Performance mode, plain pages). */
val SettingsCardGlass = SemanticsPropertyKey<Boolean>("settings_card_glass")
var SemanticsPropertyReceiver.settingsCardGlass by SettingsCardGlass

/**
 * The surface of a settings card: the app's glass ([pane]) on a frosted page, and in Performance mode a plain, solid
 * surface with a flat hairline edge (no see-through fill, no lit edge, nothing blurred). Settings pages are given the
 * settings as stored, so Performance mode is read here and not left to the theme.
 */
@Composable
fun Modifier.settingsCard(shape: Shape, tag: String = SettingsTags.GROUP_CARD): Modifier {
    val performance = LocalAppSettings.current.performanceMode
    val glass = LocalFrosted.current && !performance
    val scheme = MaterialTheme.colorScheme
    val surface = if (performance) clip(shape).background(scheme.surfaceContainerHigh).border(1.dp, scheme.hairline, shape) else pane(shape)
    return surface.testTag(tag).semantics { settingsCardGlass = glass }
}

/**
 * One group of a settings page: a small sentence-case [title] above one rounded card (the app's corner setting, 20 dp
 * at "Round") that holds the group's rows, with hairlines between them, inset to where the rows' text starts (past
 * the icon of a [LinkRow] that has one).
 *
 * [title] null or blank: no header (a page with a single group doesn't repeat its own name). [info]: what the whole
 * group is about, behind an ⓘ beside the title. [highlight]: ring the card in split light for a moment (a search result
 * landed on this group). [content] is the rows, top to bottom; budget: at most 7 with default settings.
 */
@Composable
fun SettingsGroup(
    title: String?,
    modifier: Modifier = Modifier,
    info: String? = null,
    highlight: Boolean = false,
    content: @Composable () -> Unit,
) {
    val ui = LocalUi.current
    val shape = ui.shape(20.dp)
    val scheme = MaterialTheme.colorScheme
    var open by rememberSaveable(title, "settings-group") { mutableStateOf(false) }
    val glow = remember { Animatable(0f) }
    if (highlight) LaunchedEffect(Unit) {
        if (ui.reduceMotion) glow.snapTo(1f) else glow.animateTo(1f, tween(220))
        kotlinx.coroutines.delay(1100)
        if (ui.reduceMotion) glow.snapTo(0f) else glow.animateTo(0f, tween(800))
    }
    Column(modifier.fillMaxWidth().testTag(SettingsTags.GROUP)) {
        if (!title.isNullOrBlank()) Row(Modifier.padding(start = 8.dp, end = 8.dp, bottom = if (info != null) 1.dp else 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f, fill = false).semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            if (info != null) InfoMark(open, title) { open = !open }
        }
        Column(
            Modifier.fillMaxWidth().settingsCard(shape)
                .then(if (glow.value > 0.02f) Modifier.border((2.5f * glow.value).dp, VinzTokens.spectrum, shape) else Modifier),
        ) {
            InfoText(open && info != null, info, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            HairlineColumn(content)
        }
    }
}

/** [content]'s children top to bottom, with a hairline between each two that take room. */
@Composable
private fun HairlineColumn(content: @Composable () -> Unit) {
    val color = MaterialTheme.colorScheme.hairline
    // Where the lines go (x: the inset from the start edge, y: the line), found while placing the rows.
    var lines by remember { mutableStateOf<List<Offset>>(emptyList()) }
    Layout(
        content,
        Modifier.fillMaxWidth().drawWithContent {
            drawContent()
            val rtl = layoutDirection == LayoutDirection.Rtl
            lines.forEach { (inset, y) ->
                drawLine(color, Offset(if (rtl) 0f else inset, y), Offset(if (rtl) size.width - inset else size.width, y), 1.dp.toPx())
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val tops = IntArray(placeables.size)
        val found = ArrayList<Offset>()
        var y = 0
        var any = false
        placeables.forEachIndexed { i, p ->
            tops[i] = y
            // A sheet or dialog a row keeps beside itself takes no room, and gets no line.
            if (p.height > 0) {
                if (any) found += Offset(((measurables[i].parentData as? HairlineInset)?.start ?: 16.dp).toPx(), y.toFloat())
                any = true
            }
            y += p.height
        }
        if (found != lines) lines = found
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeables.maxOfOrNull { it.width } ?: 0
        layout(width, y.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeables.forEachIndexed { i, p -> p.placeRelative(0, tops[i]) }
        }
    }
}
