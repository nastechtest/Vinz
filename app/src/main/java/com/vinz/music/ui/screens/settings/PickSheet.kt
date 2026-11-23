package com.vinz.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.tr
import com.vinz.music.ui.components.KeepUpwardScroll
import com.vinz.music.ui.components.VinzSheet
import com.vinz.music.ui.components.SearchField
import com.vinz.music.ui.components.frostFill
import com.vinz.music.ui.screens.InfoMark
import com.vinz.music.ui.screens.InfoText
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalUi
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.ui.theme.VinzTokens
import com.vinz.music.ui.theme.hairline

/**
 * A choice among examples, in a sheet (plan §2.4): half the screen tall, a grab handle, the setting's [title] and ⓘ
 * ([info]), then one card per option, [columns] (2 or 3) across: its [example] drawn at [exampleScale] of its size and
 * a one-line caption ([label]). The current pick is ringed in split light.
 *
 * A tap applies the option ([onPick], at once, so a preview behind the sheet shows it), gives a haptic tick and closes
 * the sheet. Swiping it down, Back or a tap outside changes nothing. [onDismiss] is called once the sheet is gone,
 * either way: clear the state that shows it there. A tap on the current pick only closes the sheet, unless [onRepick]
 * is given: it is then called first (a pick that opens an editor, or one that is applied again).
 *
 * [example] takes what `ChoiceCards` took for the same choice, so a page's example drawings move over as they are.
 */
@Composable
fun <T> PickSheet(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    info: String? = null,
    columns: Int = 2,
    exampleScale: Float = 0.8f,
    onRepick: (() -> Unit)? = null,
    example: @Composable (T) -> Unit,
) {
    SettingsSheet(onDismiss) { close ->
        PickCards(title, options, selected, label, info, columns, exampleScale, example) { picked ->
            if (picked != selected) onPick(picked) else onRepick?.invoke()
            close {}
        }
    }
}

/**
 * The same sheet as a list, for choices that are words (a gesture's action, the language, a country): one row per
 * option with [label] and an optional second line ([detail]), the current one ticked. [searchable] adds a search field
 * over the list (it looks in both lines); the sheet then keeps its height while the list narrows.
 *
 * Tap, swipe-down, [onRepick] and [onDismiss] behave as in [PickSheet].
 */
@Composable
fun <T> PickListSheet(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    info: String? = null,
    detail: (T) -> String? = { null },
    searchable: Boolean = options.size > 12,
    onRepick: (() -> Unit)? = null,
) {
    SettingsSheet(onDismiss) { close ->
        PickList(title, options, selected, label, info, detail, searchable) { picked ->
            if (picked != selected) onPick(picked) else onRepick?.invoke()
            close {}
        }
    }
}

/**
 * A [PickRow] that opens its own [PickSheet]: the row shows the current pick in words ([label] of [selected]) and,
 * with [thumbnail], a small picture of it. [value]: what the row says where that isn't the pick's label ("Custom", the
 * pick with a detail). [onRepick]: as [PickSheet]'s.
 */
@Composable
fun <T> PickCardsRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    info: String? = null,
    changed: Boolean = false,
    enabled: Boolean = true,
    columns: Int = 2,
    exampleScale: Float = 0.8f,
    thumbnail: (@Composable (T) -> Unit)? = null,
    value: String? = null,
    onRepick: (() -> Unit)? = null,
    onPick: (T) -> Unit,
    example: @Composable (T) -> Unit,
) {
    var open by rememberSaveable(title, "pick-cards") { mutableStateOf(false) }
    val thumb: (@Composable () -> Unit)? = if (thumbnail != null) ({ thumbnail(selected) }) else null
    PickRow(title, value ?: label(selected), modifier, info, changed, enabled, thumb) { open = true }
    if (open) PickSheet(title, options, selected, label, onPick, { open = false }, info, columns, exampleScale, onRepick, example)
}

/** A [PickRow] that opens its own [PickListSheet]. [value]: what the row says (the picked option's [label] by default). */
@Composable
fun <T> PickListRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    value: String = label(selected),
    info: String? = null,
    changed: Boolean = false,
    enabled: Boolean = true,
    detail: (T) -> String? = { null },
    searchable: Boolean = options.size > 12,
    onRepick: (() -> Unit)? = null,
    onPick: (T) -> Unit,
) {
    var open by rememberSaveable(title, "pick-list") { mutableStateOf(false) }
    PickRow(title, value, modifier, info, changed, enabled) { open = true }
    if (open) PickListSheet(title, options, selected, label, onPick, { open = false }, info, detail, searchable, onRepick)
}

/**
 * A sheet that holds rows, not one choice: what a row of the page opens when there is more behind it than fits a row
 * (the lock screen's options, a device's codes, the two streaming qualities). The pick sheets' look (grab handle, the
 * [title] and its ⓘ, [info]), then [content]: the kit's rows, top to bottom, scrolling when they're taller than the
 * sheet (at most 85% of the screen). Tagged [SettingsTags.SHEET]. Rows apply at once; swiping the sheet down, Back or
 * a tap outside closes it, and [onDismiss] is called once it's gone: clear the state that shows it there.
 */
@Composable
fun RowsSheet(title: String, onDismiss: () -> Unit, info: String? = null, content: @Composable ColumnScope.() -> Unit) {
    SettingsSheet(onDismiss) { _ -> SheetRows(title, info, content) }
}

/** What a [RowsSheet] shows under its handle (apart, so it's tested and drawn without a sheet's window). */
@Composable
internal fun SheetRows(title: String, info: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f).testTag(SettingsTags.SHEET)) {
        SheetHeader(title, info)
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).nestedScroll(KeepUpwardScroll).verticalScroll(rememberScrollState()).padding(bottom = 22.dp),
            content = content,
        )
    }
}

/** The sheet both kinds share: [VinzSheet] with the app's grab handle, at most half the screen tall. */
@Composable
private fun SettingsSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.(close: (then: () -> Unit) -> Unit) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    VinzSheet(onDismiss, containerColor = scheme.surfaceContainerLow, contentColor = scheme.onSurface, dragHandle = null) { close ->
        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp, 4.dp).clip(CircleShape).background(scheme.onSurface.copy(alpha = 0.3f)))
        }
        content(close)
    }
}

/** Half the screen: what a pick sheet's content may take under its handle. */
@Composable
private fun halfScreen() = LocalConfiguration.current.screenHeightDp.dp * 0.5f - 26.dp

/** The setting's name and its ⓘ, at the head of a sheet. */
@Composable
private fun SheetHeader(title: String, info: String?) {
    var open by rememberSaveable(title, "sheet-info") { mutableStateOf(false) }
    Column(Modifier.padding(start = 20.dp, end = 16.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f, fill = false).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            if (info != null) InfoMark(open, title) { open = !open }
        }
        InfoText(open && info != null, info, Modifier.padding(top = 4.dp))
    }
}

/** A haptic tick as a pick lands (not with Haptics turned off). */
@Composable
private fun <T> ticking(onPick: (T) -> Unit): (T) -> Unit {
    val haptics = LocalHapticFeedback.current
    val on = LocalAppSettings.current.haptics
    return { picked -> if (on) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPick(picked) }
}

/** What a [PickSheet] shows under its handle (apart, so it's tested without a sheet's window). */
@Composable
internal fun <T> PickCards(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    info: String?,
    columns: Int,
    exampleScale: Float,
    example: @Composable (T) -> Unit,
    onPick: (T) -> Unit,
) {
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val across = columns.coerceIn(2, 3)
    val pick = ticking(onPick)
    val density = LocalDensity.current
    val small = remember(density, exampleScale) { Density(density.density * exampleScale, density.fontScale) }
    Column(Modifier.fillMaxWidth().heightIn(max = halfScreen()).testTag(SettingsTags.SHEET)) {
        SheetHeader(title, info)
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).nestedScroll(KeepUpwardScroll).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            options.chunked(across).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { o ->
                        val on = o == selected
                        Column(
                            Modifier.weight(1f).clip(ui.tile).background(frostFill(if (on) 2.6f else 1f))
                                .then(if (on) Modifier.border(2.dp, VinzTokens.spectrumRing(), ui.tile) else Modifier.border(1.dp, scheme.hairline, ui.tile))
                                .testTag(SettingsTags.SHEET_OPTION).semantics { role = Role.RadioButton; this.selected = on }
                                .clickable { pick(o) }.padding(10.dp),
                        ) {
                            // The example is a picture of the option: whatever it draws (a switch, a button, a
                            // slider) takes no tap and isn't read out on its own. The card takes the tap.
                            Box {
                                Box(Modifier.clearAndSetSemantics {}) { CompositionLocalProvider(LocalDensity provides small) { example(o) } }
                                Box(Modifier.matchParentSize().pointerInput(Unit) {})
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(label(o), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (on) Icon(VinzIcons.CheckCircle, tr(R.string.picked), Modifier.size(18.dp), tint = scheme.onSurface)
                            }
                        }
                    }
                    repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** What a [PickListSheet] shows under its handle. */
@Composable
internal fun <T> PickList(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    info: String?,
    detail: (T) -> String?,
    searchable: Boolean,
    onPick: (T) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val pick = ticking(onPick)
    var query by rememberSaveable(title, "sheet-query") { mutableStateOf("") }
    val shown = remember(options, query) {
        val q = query.trim()
        if (q.isEmpty()) options else options.filter { label(it).contains(q, ignoreCase = true) || detail(it)?.contains(q, ignoreCase = true) == true }
    }
    // Opens at the top while the current pick shows there whole; else scrolled to two rows above it.
    val list = rememberLazyListState()
    LaunchedEffect(Unit) {
        val at = options.indexOf(selected)
        if (at <= 0 || query.isNotBlank()) return@LaunchedEffect
        val laid = snapshotFlow { list.layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
        val seen = laid.visibleItemsInfo.firstOrNull { it.index == at }
        if (seen == null || seen.offset + seen.size > laid.viewportEndOffset) list.scrollToItem((at - 2).coerceAtLeast(0))
    }
    // With a search field the sheet keeps its height while the list narrows; without one it's as tall as its rows.
    val height = if (searchable) Modifier.height(halfScreen()) else Modifier.heightIn(max = halfScreen())
    Column(Modifier.fillMaxWidth().then(height).testTag(SettingsTags.SHEET)) {
        SheetHeader(title, info)
        if (searchable) SearchField(query, tr(R.string.search), { query = it }, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag(SettingsTags.SHEET_SEARCH))
        if (shown.isEmpty()) Text(
            tr(R.string.nothing_found), Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).nestedScroll(KeepUpwardScroll).fadeTopWhile { list.canScrollBackward }, state = list) {
            items(shown.size) { i ->
                val o = shown[i]
                val on = o == selected
                val second = detail(o)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = if (second != null) 64.dp else 56.dp)
                        .testTag(SettingsTags.SHEET_OPTION).semantics { role = Role.RadioButton; this.selected = on }
                        .clickable { pick(o) }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(label(o), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                        if (second != null) Text(second, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface.copy(alpha = 0.7f))
                    }
                    if (on) Icon(VinzIcons.Check, tr(R.string.picked), Modifier.size(20.dp), tint = scheme.onSurface)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/**
 * Rows that have scrolled part-way off the top of a list fade out (clear for the first 8 dp, then in over 16 dp), so a
 * sliver of a row (the feet of its letters) doesn't sit as a stray mark under what's above the list. Nothing is drawn
 * differently while [scrolled] is false (the list at its top).
 */
private fun Modifier.fadeTopWhile(scrolled: () -> Boolean): Modifier =
    graphicsLayer { compositingStrategy = if (scrolled()) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
        .drawWithContent {
            drawContent()
            if (scrolled()) {
                val clear = 8.dp.toPx()
                val end = clear + 16.dp.toPx()
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = clear, endY = end), blendMode = BlendMode.DstIn)
            }
        }
