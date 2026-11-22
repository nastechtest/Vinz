package com.vinz.music.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.tr
import com.vinz.music.ui.components.VinzSlider
import com.vinz.music.ui.components.VinzSwitch
import com.vinz.music.ui.components.Segmented
import com.vinz.music.ui.components.glassEdge
import com.vinz.music.ui.components.glassFill
import com.vinz.music.ui.components.spectrumRing
import com.vinz.music.ui.screens.InfoMark
import com.vinz.music.ui.screens.InfoText
import com.vinz.music.ui.screens.ShowOption
import com.vinz.music.ui.theme.LocalUi
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.ui.theme.VinzTokens
import com.vinz.music.ui.theme.hairline

/**
 * The settings kit of 1.8.4 ("Settings, made simple"). A page is a [SettingsPageScaffold] of [SettingsGroup] cards, and a
 * card holds only the five row types: [SwitchRow], [PickRow], [SliderRow], [ChoiceRow] (and its many-at-once form,
 * [ShowChipsRow]) and [LinkRow].
 *
 * What every row does by itself: 64 dp tall with a value line and 56 dp without (it grows, never cuts text off, when
 * the text is large), 16 dp gutters, the title in titleMedium and the value in bodyMedium at 70%, an optional ⓘ
 * (`info`) that opens the explanation under the row, and a dot after the value when `changed` is true. Rows draw no
 * icon (calmer), except a [LinkRow] to another screen. Nothing in a row moves or blurs while it's at rest.
 *
 * For tests: each row carries one of [SettingsTags.rowTypes] as its test tag, its value in words as
 * [SettingsRowValue] and its `changed` flag as [SettingsRowChanged].
 */
object SettingsTags {
    const val ROW_SWITCH = "settings:row:switch"
    const val ROW_PICK = "settings:row:pick"
    const val ROW_SLIDER = "settings:row:slider"
    const val ROW_CHOICE = "settings:row:choice"
    const val ROW_LINK = "settings:row:link"

    /** The five row types, and nothing else. */
    val rowTypes = setOf(ROW_SWITCH, ROW_PICK, ROW_SLIDER, ROW_CHOICE, ROW_LINK)

    /** A [SettingsGroup] (its header and card), and the card alone (it carries [SettingsCardGlass]). */
    const val GROUP = "settings:group"
    const val GROUP_CARD = "settings:group:card"

    /** The dot after a changed row's value. */
    const val CHANGED_DOT = "settings:changed"

    const val PAGE_LIST = "settings:page:list"
    const val PAGE_TITLE = "settings:page:title"

    /** The small title in the bar (seen once the page has scrolled). */
    const val PAGE_BAR_TITLE = "settings:page:bartitle"
    const val PAGE_SEARCH = "settings:page:search"
    const val PAGE_MORE = "settings:page:more"
    const val JUMP_CHIPS = "settings:jump"
    const val PREVIEW = "settings:preview"

    const val RESET_ITEM = "settings:reset"
    const val RESET_CHANGE = "settings:reset:change"
    const val RESET_CONFIRM = "settings:reset:confirm"

    const val SHEET = "settings:sheet"
    const val SHEET_OPTION = "settings:sheet:option"
    const val SHEET_SEARCH = "settings:sheet:search"
}

/** A row's value in words, as it shows it ("" for a switch or link without a value line). */
val SettingsRowValue = SemanticsPropertyKey<String>("settings_row_value")
var SemanticsPropertyReceiver.settingsRowValue by SettingsRowValue

/** Whether a row was given `changed = true` (it shows the dot). */
val SettingsRowChanged = SemanticsPropertyKey<Boolean>("settings_row_changed")
var SemanticsPropertyReceiver.settingsRowChanged by SettingsRowChanged

/** How far in from the card's start edge the hairline above a row begins ([SettingsGroup] reads it). */
internal data class HairlineInset(val start: Dp) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@HairlineInset
}

/** The small dot that marks a value that differs from how Vinz comes. */
@Composable
fun ChangedDot(modifier: Modifier = Modifier) {
    Box(
        modifier.padding(start = 6.dp).size(6.dp).clip(CircleShape).background(VinzTokens.spectrumRing())
            .testTag(SettingsTags.CHANGED_DOT).semantics { contentDescription = tr(R.string.changed) },
    )
}

/** What the five rows share: the title and value lines, ⓘ, the dot, the heights, the tag. */
@Composable
private fun RowFrame(
    type: String,
    title: String,
    /** The line under the title, or null for none (the row is then 56 dp). */
    value: String?,
    /** The value for [SettingsRowValue] (what a chip or a slider shows isn't on a value line). */
    spoken: String,
    info: String?,
    changed: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    /** What a tap on the whole row does (clickable, toggleable), or nothing for rows with their own controls. */
    interaction: Modifier = Modifier,
    icon: ImageVector? = null,
    /** False where the row places the dot itself (a slider's, beside its value). */
    dot: Boolean = changed,
    /** A row with controls under its title line is as tall as they make it. */
    stacked: Boolean = false,
    /** The title's color: the accent for a row that acts at once ([LinkRow]'s `action`), else the page's ink. */
    accentTitle: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    var open by rememberSaveable(title, type) { mutableStateOf(false) }
    // The page-spacing setting still scales rows; never under a finger's reach.
    val min = if (stacked) 44.dp else ui.gap(if (value != null) 64.dp else 56.dp).coerceAtLeast(48.dp)
    Column(
        modifier.fillMaxWidth().then(HairlineInset(if (icon != null) 56.dp else 16.dp))
            .testTag(type).semantics { settingsRowValue = spoken; settingsRowChanged = changed }
            .then(interaction).then(if (enabled) Modifier else Modifier.alpha(0.38f)),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = min).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(24.dp), tint = scheme.onSurfaceVariant); Spacer(Modifier.width(16.dp)) }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // No line limit: at large text the title wraps and the row grows.
                    Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, color = if (accentTitle) scheme.primary else scheme.onSurface)
                    if (dot && value == null) ChangedDot()
                    if (info != null) InfoMark(open, title) { open = !open }
                }
                if (value != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(value, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface.copy(alpha = 0.7f))
                    if (dot) ChangedDot()
                }
            }
            if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
        }
        InfoText(open && info != null, info, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        below?.invoke(this)
    }
}

/**
 * Row type 1, **Switch**: on or off; the whole row is the switch. [value] is an optional line under the title (the
 * state in words where on/off alone doesn't say it). [icon]: a small mark before the title (1.8.7.6: the heart beside
 * "I've supported Vinz" while it is on).
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    value: String? = null,
    info: String? = null,
    changed: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onChange: (Boolean) -> Unit,
) {
    RowFrame(
        SettingsTags.ROW_SWITCH, title, value, value.orEmpty(), info, changed, enabled, modifier,
        interaction = Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange), icon = icon,
        trailing = { VinzSwitch(checked, null, enabled = enabled) },
    )
}

/**
 * Row type 2, **Pick**: the current [value] in words and a chevron; a tap opens the choices ([onClick]), normally a
 * [PickSheet] or [PickListSheet] ([PickCardsRow] and [PickListRow] do both parts). [thumbnail]: a small picture of the
 * current pick for looks, drawn in a 44 × 30 dp frame before the chevron.
 */
@Composable
fun PickRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    info: String? = null,
    changed: Boolean = false,
    enabled: Boolean = true,
    thumbnail: (@Composable () -> Unit)? = null,
    /** False where the [thumbnail] says it all (a color's swatch): no value line, though TalkBack still reads [value]. */
    showValue: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    RowFrame(
        SettingsTags.ROW_PICK, title, if (showValue) value else null, value, info, changed, enabled, modifier,
        interaction = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        trailing = {
            if (thumbnail != null) {
                val frame = RoundedCornerShape(8.dp)
                Box(Modifier.size(44.dp, 30.dp).clip(frame).border(1.dp, scheme.hairline, frame), contentAlignment = Alignment.Center) { thumbnail() }
                Spacer(Modifier.width(8.dp))
            }
            Icon(VinzIcons.ChevronRight, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
        },
    )
}

/** How long a [SliderRow] gives its save to land before it shows the stored value again. */
internal const val SLIDER_SETTLE_MS = 400L

/**
 * Row type 3, **Slider**: the title, the value as [valueText] writes it, and the slider under them. It follows the
 * finger and saves ([onSave]) when let go.
 */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: (Float) -> String,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    info: String? = null,
    changed: Boolean = false,
    enabled: Boolean = true,
    onSave: (Float) -> Unit,
) {
    var v by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { v = value }
    // A save may store what was there already (a value snapped to a step: progress bar thickness, corners), and then
    // `value` doesn't change. Once the save has had time to land, the slider shows what is stored, unless dragged again.
    val stored by rememberUpdatedState(value)
    var dragging by remember { mutableStateOf(false) }
    var saves by remember { mutableIntStateOf(0) }
    LaunchedEffect(saves) {
        if (saves == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(SLIDER_SETTLE_MS)
        if (!dragging) v = stored
    }
    val shown = valueText(v)
    RowFrame(
        SettingsTags.ROW_SLIDER, title, null, shown, info, changed, enabled, modifier, dot = false, stacked = true,
        trailing = {
            Text(shown, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            if (changed) ChangedDot()
        },
        below = {
            VinzSlider(
                v, { v = it; dragging = true }, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp), enabled = enabled,
                valueRange = range, steps = steps, onValueChangeFinished = { dragging = false; onSave(v); saves++ },
            )
        },
    )
}

/**
 * Row type 4, **Choice chips**: one of two to four short words, picked in place under the title. For more or longer
 * choices, or ones that need an example to tell apart, use a Pick row.
 */
@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    info: String? = null,
    changed: Boolean = false,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    // One capsule while the words fit in it; at large text (or long words) pills that wrap, so no word is cut off.
    val density = LocalDensity.current
    val short = density.fontScale < 1.3f && options.size in 2..4 && options.all { label(it).length <= 12 } && options.sumOf { label(it).length } <= 30
    val pick: (T) -> Unit = { if (enabled) onSelect(it) }
    val words = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    RowFrame(
        SettingsTags.ROW_CHOICE, title, null, label(selected), info, changed, enabled, modifier, stacked = true,
        below = {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                // Measured, not guessed: every word must fit its share of the capsule on one line (the capsule's 3 dp
                // rim, 6 dp beside each word), or the row falls back to chips that wrap.
                val labels = options.map(label)
                val capsule = short && remember(labels, maxWidth, words, density) {
                    val share = with(density) { ((maxWidth - 6.dp) / labels.size - 12.dp).toPx() }
                    labels.all { measurer.measure(it, words, maxLines = 1, softWrap = false).size.width + 1 <= share }
                }
                if (capsule) Segmented(options, selected, label, Modifier, pick)
                else KitChips { options.forEach { o -> KitChip(o == selected, label(o)) { pick(o) } } }
            }
        },
    )
}

/** The kit's chips, wrapping onto as many lines as they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KitChips(modifier: Modifier = Modifier, chips: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
}

/**
 * A chip of [ChoiceRow] and [ShowChipsRow]: the app's chip (`VinzChip`: a glass pill, lifted and ringed in split light
 * when picked), but never cut off: 36 dp tall on one line, and where the words don't fit the row's width (large text, a
 * long translation) it wraps them and grows.
 */
@Composable
private fun KitChip(selected: Boolean, label: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // The pill's round ends at one line; a rounded box when it grows.
    val shape = RoundedCornerShape(18.dp)
    val bg by animateColorAsState(glassFill(if (selected) 2.6f else 1.2f), tween(180), label = "chipBg")
    val fg by animateColorAsState(if (selected) scheme.onSurface else scheme.onSurfaceVariant, tween(180), label = "chipFg")
    Row(
        modifier.heightIn(min = 36.dp).clip(shape).background(bg)
            .then(if (selected) Modifier.spectrumRing(shape) else Modifier.border(1.dp, glassEdge(), shape))
            .semantics { this.selected = selected }
            .clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = fg); Spacer(Modifier.width(6.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/**
 * Row type 4, many at once (**Show chips**): several things to show or hide as chips that tick on and off, in place of
 * a run of switches ("In the queue: Show"). Each [ShowOption] carries its own state and setter, so the stored values
 * stay as they are. [tickHides]: a ticked chip means hidden ("Hide from Home"), and TalkBack says so.
 */
@Composable
fun ShowChipsRow(
    title: String,
    options: List<ShowOption>,
    modifier: Modifier = Modifier,
    info: String? = null,
    changed: Boolean = false,
    tickHides: Boolean = false,
) {
    val shown = options.filter { it.on }
    // With nothing ticked the row's value is "None" (for TalkBack and tests), but no line says it: the chips under the
    // title already show that none is on, and "None" above them read like one more setting.
    val none = if (shown.isEmpty()) tr(R.string.none) else null
    RowFrame(
        SettingsTags.ROW_CHOICE, title, null, none ?: shown.joinToString(" · ") { it.label }, info, changed, true, modifier, stacked = true,
        below = {
            val on = tr(if (tickHides) R.string.hidden else R.string.shown)
            val off = tr(if (tickHides) R.string.shown else R.string.hidden)
            KitChips(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                options.forEach { o ->
                    KitChip(
                        o.on, o.label, Modifier.semantics { role = Role.Checkbox; stateDescription = if (o.on) on else off },
                        icon = if (o.on) VinzIcons.Check else null,
                    ) { o.onChange(!o.on) }
                }
            }
            Spacer(Modifier.heightIn(min = 4.dp))
        },
    )
}

/**
 * Row type 5, **Link**: opens another screen (or, with [external], something outside Vinz). The only row that may
 * carry an [icon]. [value]: an optional line under the title (the screen's state in words).
 *
 * [action]: the row does something here and now and opens nothing ("Back up now", "Clear the cache"): a button, with
 * no chevron (and no "opens outside" mark) after it, its title in the accent color so it reads as tappable.
 */
@Composable
fun LinkRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    icon: ImageVector? = null,
    info: String? = null,
    changed: Boolean = false,
    enabled: Boolean = true,
    external: Boolean = false,
    action: Boolean = false,
    onClick: () -> Unit,
) {
    val mark: (@Composable RowScope.() -> Unit)? = if (action) null else ({
        Icon(if (external) VinzIcons.OpenInNew else VinzIcons.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    })
    RowFrame(
        SettingsTags.ROW_LINK, title, value, value.orEmpty(), info, changed, enabled, modifier,
        interaction = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick), icon = icon,
        accentTitle = action, trailing = mark,
    )
}
