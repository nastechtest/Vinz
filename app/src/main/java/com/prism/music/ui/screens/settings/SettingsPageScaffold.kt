package com.vinz.music.ui.screens.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.tr
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.components.LocalFrosted
import com.vinz.music.ui.components.LocalPageBackdrop
import com.vinz.music.ui.components.VinzChip
import com.vinz.music.ui.components.VinzTextButton
import com.vinz.music.ui.components.RoundAction
import com.vinz.music.ui.components.SUBPAGE_MAX_WIDTH
import com.vinz.music.ui.components.TopScrollEdge
import com.vinz.music.ui.components.pageTitleStyle
import com.vinz.music.ui.theme.LocalUi
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.ui.theme.VinzTokens
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The jump chips' row fades out over its last 24 dp while more chips lie past the end, so a cut chip reads as "scroll
 * for more" rather than a clipped word. Nothing is drawn differently once the row is at its end (or fits).
 */
internal fun Modifier.fadeEndWhile(more: () -> Boolean): Modifier =
    graphicsLayer { compositingStrategy = if (more()) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
        .drawWithContent {
            drawContent()
            if (more()) {
                val w = 24.dp.toPx().coerceAtMost(size.width)
                val brush = if (layoutDirection == LayoutDirection.Rtl) {
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = w)
                } else {
                    Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width)
                }
                drawRect(brush, blendMode = BlendMode.DstIn)
            }
        }

/**
 * "Reset this page" (⋯ in the bar): [changes] is one line per row that would change, as the confirm lists them (build
 * each with [resetLine]); [onReset] puts those rows back. With nothing changed the menu entry is there but can't be
 * tapped.
 */
class PageReset(val changes: List<String>, val onReset: () -> Unit)

/** One more entry under ⋯, after "Reset this page" (Looks' "Reset everything"). */
class PageMenuItem(val label: String, val onClick: () -> Unit)

/** A line for [PageReset.changes]: "Content country: Germany → Automatic". */
fun resetLine(title: String, now: String, default: String): String = tr(R.string.settings_reset_line, title, now, default)

/** What a settings page holds, top to bottom ([SettingsPageScaffold]'s `content`). */
class SettingsPageScope internal constructor() {
    internal class Entry(val key: String, val title: String?, val info: String?, val isGroup: Boolean, val content: @Composable () -> Unit)

    internal val entries = ArrayList<Entry>()

    /**
     * A [SettingsGroup]: [title] above one card of rows ([content]). Titled groups get a jump chip when the page has
     * four or more. [key] names the group for search and links ("find"); it must be unique on the page, and defaults
     * to the title. Budget: at most 6 groups a page.
     */
    fun group(title: String?, key: String = title.orEmpty(), info: String? = null, content: @Composable () -> Unit) {
        entries += Entry(key.ifBlank { "group-${entries.size}" }, title, info, true, content)
    }

    /** Something that isn't a group of rows (the account card, a state note), in its place among the groups. */
    fun item(key: String, content: @Composable () -> Unit) {
        entries += Entry(key, null, null, false, content)
    }
}

/** The pinned preview's height once the page has scrolled. */
val PreviewStripHeight = 72.dp

/** The preview is never taller than this. */
val PreviewMaxHeight = 200.dp

/**
 * A settings page's list. Each group is one item of it (one card), so a fling over groups not yet composed composed a
 * whole group in one frame (1.8.4's A/B on the A16: the Player page's first fling had twice 1.8.3.1's main-thread work
 * and worse tail frames). The list composes and measures the groups below the screen ahead, in the idle time between
 * frames once the page is up, so a fling finds them ready. [scheduler]: where that runs (a test's; null: Compose's own).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun rememberSettingsListState(scheduler: androidx.compose.foundation.lazy.layout.PrefetchScheduler? = null): LazyListState {
    return rememberLazyListState(prefetchStrategy = remember(scheduler) { WholePagePrefetch(ahead = 8, scheduler) })
}

/**
 * The list's prefetch: as Compose's own (the next item in the scroll's way, and nested lists), and once the page has
 * been laid out, the [ahead] entries after the last one on the screen composed and measured in the idle time between
 * frames (a settings page has a handful of groups; a fling then finds them ready instead of composing one in a frame).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal class WholePagePrefetch(
    private val ahead: Int,
    /** Where the prefetch runs: Compose's own (null: the idle time between frames), or a test's. */
    private val scheduler: androidx.compose.foundation.lazy.layout.PrefetchScheduler? = null,
) : androidx.compose.foundation.lazy.LazyListPrefetchStrategy {
    private val own = androidx.compose.foundation.lazy.LazyListPrefetchStrategy()
    private var asked = false

    override val prefetchScheduler: androidx.compose.foundation.lazy.layout.PrefetchScheduler? get() = scheduler

    override fun androidx.compose.foundation.lazy.LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: androidx.compose.foundation.lazy.LazyListLayoutInfo) =
        with(own) { onScroll(delta, layoutInfo) }

    override fun androidx.compose.foundation.lazy.LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: androidx.compose.foundation.lazy.LazyListLayoutInfo) {
        with(own) { onVisibleItemsUpdated(layoutInfo) }
        val last = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return
        if (asked) return
        asked = true
        for (i in last + 1..minOf(last + ahead, layoutInfo.totalItemsCount - 1)) schedulePrefetch(i)
    }

    override fun androidx.compose.foundation.lazy.layout.NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) =
        with(own) { onNestedPrefetch(firstVisibleItemIndex) }
}

private val BAR = 60.dp
private const val TITLE_KEY = "settings:page:title"
private const val PREVIEW_KEY = "settings:page:preview"
private const val JUMP_KEY = "settings:page:jump"

/**
 * A settings page: a large [title] that scrolls away into the top bar (where it shows small), the bar's back, search
 * and ⋯ buttons, an optional pinned preview, jump chips, then the groups.
 *
 * - [onSearch]: shows the search button (every page has one); the caller opens Settings search.
 * - [reset] / [menu]: what ⋯ holds. "Reset this page" asks first, listing [PageReset.changes].
 * - [preview]: a live example pinned at the top, [previewHeight] tall (at most 200 dp), that shrinks to a 72 dp strip
 *   as the page scrolls and stays under the bar; a tap on the strip opens it again until the next scroll. It's told
 *   when it is a strip, to draw itself smaller.
 * - Pages with four or more titled groups get a row of jump chips under the title; a tap scrolls to that group.
 * - [find]: a group's key or title to open the page at (a search result), ringed for a moment.
 * - [showBack] false: no back button (the page beside the list on a wide window). [onBack] replaces the navigator's
 *   back.
 *
 * Motion follows Reduce motion (jumps and the bar's title don't animate); nothing here blurs.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsPageScaffold(
    title: String,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    onBack: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    reset: PageReset? = null,
    menu: List<PageMenuItem> = emptyList(),
    find: String? = null,
    preview: (@Composable (strip: Boolean) -> Unit)? = null,
    previewHeight: Dp = PreviewMaxHeight,
    listState: LazyListState = rememberSettingsListState(),
    content: SettingsPageScope.() -> Unit,
) {
    val entries = SettingsPageScope().apply(content).entries
    val jump = entries.filter { it.isGroup && !it.title.isNullOrBlank() }
    val showJump = jump.size >= 4
    val hasPreview = preview != null
    // The list: the title, (the preview's room), (the jump chips), then the entries.
    val first = 1 + (if (hasPreview) 1 else 0) + (if (showJump) 1 else 0)
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val nav = if (showBack && onBack == null) LocalNavigator.current else null
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val full = previewHeight.coerceIn(PreviewStripHeight, PreviewMaxHeight)
    // A group jumped to lands under the bar, or under the preview's strip.
    val landing = if (hasPreview) -with(density) { (PreviewStripHeight + 14.dp).roundToPx() } else 0
    val goTo: (Int) -> Unit = { entry ->
        scope.launch { if (ui.reduceMotion) listState.scrollToItem(first + entry, landing) else listState.animateScrollToItem(first + entry, landing) }
    }
    val found = find?.let { f -> entries.indexOfFirst { it.key == f || it.title == f } }?.takeIf { it >= 0 }
    // Lands once: back on the page (Back from a screen it opened, a rotation) it stays where it was scrolled to, and
    // the group glows only on that first landing.
    var landed by rememberSaveable(find) { mutableStateOf(false) }
    val mayGlow = remember(find) { !landed }
    if (found != null && !landed) LaunchedEffect(find) { listState.scrollToItem(first + found, landing); landed = true }

    val scrolled by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 60 } }
    val bar by animateFloatAsState(if (scrolled) 1f else 0f, if (ui.reduceMotion) snap() else tween(220), label = "bar")
    // The preview's room in the list: how far below the bar's foot it starts (below 0: passing under the bar); null
    // until the list is laid out.
    val previewOffset = remember(listState, hasPreview) {
        derivedStateOf {
            if (!hasPreview) null
            else listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == PREVIEW_KEY }?.offset
                ?: if (listState.firstVisibleItemIndex > 1) Int.MIN_VALUE / 2 else null
        }
    }
    val pinned by remember(previewOffset) { derivedStateOf { (previewOffset.value ?: 0) < 0 } }
    // The group at the top of the page now (its jump chip is the lit one). Only worked out while the chips are in view.
    val current = remember(listState, first, landing) {
        derivedStateOf {
            val shown = listState.layoutInfo.visibleItemsInfo.filter { it.index >= first }
            (shown.lastOrNull { it.offset <= -landing + 4 } ?: shown.firstOrNull())?.let { it.index - first } ?: 0
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val melts = LocalFrosted.current && LocalPageBackdrop.current != null

    BoxWithConstraints(modifier.fillMaxSize()) {
        // On a wide window the page keeps a readable width in the middle.
        val side = 16.dp + ((maxWidth - SUBPAGE_MAX_WIDTH) / 2).coerceAtLeast(0.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag(SettingsTags.PAGE_LIST),
            contentPadding = PaddingValues(start = side, end = side, top = statusTop + BAR, bottom = bottomPadding + 28.dp),
        ) {
            item(key = TITLE_KEY) {
                Text(
                    title, Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp).testTag(SettingsTags.PAGE_TITLE).semantics { heading() },
                    style = pageTitleStyle(),
                )
            }
            if (hasPreview) item(key = PREVIEW_KEY) { Spacer(Modifier.height(full + 12.dp)) }
            if (showJump) item(key = JUMP_KEY) {
                val chipScroll = rememberScrollState()
                Row(
                    Modifier.padding(bottom = 14.dp).fadeEndWhile { chipScroll.canScrollForward }
                        .horizontalScroll(chipScroll).testTag(SettingsTags.JUMP_CHIPS),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    jump.forEach { e ->
                        val at = entries.indexOf(e)
                        VinzChip(current.value == at, { goTo(at) }, e.title.orEmpty())
                    }
                }
            }
            items(entries.size, key = { "entry:" + entries[it].key }) { i ->
                val e = entries[i]
                Box(Modifier.padding(bottom = 18.dp)) {
                    if (e.isGroup) SettingsGroup(e.title, info = e.info, highlight = mayGlow && found == i, content = e.content) else e.content()
                }
            }
        }
        if (melts) TopScrollEdge({ bar }, BAR)
        if (preview != null) PinnedPreview(previewOffset, listState, full, statusTop + BAR, side, pinned, preview)
        // On the ambient frost the content melts away under the bar; on a plain page the bar turns solid once scrolled
        // (fully, while the preview is pinned under it).
        // (Drawn, not composed: the bar's fade runs while a fling starts, and reading it here recomposed the page on
        // every one of its frames.)
        val barColor = scheme.surface
        Box(
            Modifier.fillMaxWidth()
                .drawBehind { if (pinned) drawRect(barColor) else if (!melts) drawRect(barColor.copy(alpha = 0.92f * bar)) }
                .statusBarsPadding().height(BAR),
        ) {
            if (showBack) RoundAction(VinzIcons.ArrowBack, tr(R.string.back), Modifier.align(Alignment.CenterStart).padding(start = 12.dp), glass = true) {
                if (onBack != null) onBack() else nav?.back()
            }
            // The small title gets all the room the bar's buttons leave (not twice the wider side's).
            val buttons = (if (onSearch != null) 1 else 0) + (if (reset != null || menu.isNotEmpty()) 1 else 0)
            BarTitle(
                title, this@BoxWithConstraints.maxWidth,
                start = if (showBack) 12.dp + 42.dp + 8.dp else 16.dp,
                end = if (buttons == 0) 16.dp else 12.dp + 42.dp * buttons + 8.dp * buttons,
                modifier = Modifier.align(Alignment.CenterStart).graphicsLayer { alpha = bar },
            )
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onSearch != null) RoundAction(VinzIcons.Search, tr(R.string.search_settings), Modifier.testTag(SettingsTags.PAGE_SEARCH), glass = true, onClick = onSearch)
                if (reset != null || menu.isNotEmpty()) Box {
                    RoundAction(VinzIcons.More, tr(R.string.more), Modifier.testTag(SettingsTags.PAGE_MORE), glass = true) { menuOpen = true }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        if (reset != null) DropdownMenuItem(
                            text = { Text(tr(R.string.reset_this_page)) }, enabled = reset.changes.isNotEmpty(),
                            onClick = { menuOpen = false; asking = true }, modifier = Modifier.testTag(SettingsTags.RESET_ITEM),
                        )
                        menu.forEach { m -> DropdownMenuItem(text = { Text(m.label) }, onClick = { menuOpen = false; m.onClick() }) }
                    }
                }
            }
        }
    }
    if (asking && reset != null) ResetPageDialog(title, reset) { asking = false }
}

/**
 * The page's title in the bar, on one line between the buttons ([start] and [end]: what they take of [barWidth]). It
 * sits in the middle of the bar while it fits there, moves aside when a button is in its way, and where even all the
 * room is too little (a long title, large text) its letters shrink to fit, down to 11 dp; only past that is it cut
 * short. ("Tabs & mini player" had 128 dp of a 360 dp bar and read "Tabs & mini play…".)
 */
@Composable
private fun BarTitle(title: String, barWidth: Dp, start: Dp, end: Dp, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.titleMedium
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val room = (barWidth - start - end).coerceAtLeast(0.dp)
    val size = remember(title, style, density, room) {
        with(density) {
            val roomPx = room.toPx()
            fun wide(s: TextUnit) = measurer.measure(title, style.copy(fontSize = s), maxLines = 1, softWrap = false).size.width
            val natural = wide(style.fontSize)
            if (natural <= roomPx || natural == 0) style.fontSize
            else {
                val floor = 11.dp.toSp()
                var s = style.fontSize * (roomPx / natural)
                while (s > floor && wide(s) > roomPx) s *= 0.97f
                if (s < floor) floor else s
            }
        }
    }
    // (The title sits in a box that is placed, so its own bounds are the words' and not the bar's.)
    Box(
        modifier.layout { measurable, constraints ->
            val startPx = start.roundToPx()
            val endPx = end.roundToPx()
            val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = (constraints.maxWidth - startPx - endPx).coerceAtLeast(0)))
            val x = ((constraints.maxWidth - p.width) / 2).coerceIn(startPx, (constraints.maxWidth - endPx - p.width).coerceAtLeast(startPx))
            layout(constraints.maxWidth, p.height) { p.placeRelative(x, 0) }
        },
    ) {
        Text(title, Modifier.testTag(SettingsTags.PAGE_BAR_TITLE), style = style, fontSize = size, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The confirm behind "Reset this page": names the page, lists every row that changes ([PageReset.changes]), and only
 * then resets. Cancel, back or a tap outside changes nothing.
 */
@Composable
fun ResetPageDialog(pageTitle: String, reset: PageReset, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.reset_2, pageTitle)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr(R.string.settings_reset_lists), style = MaterialTheme.typography.bodyMedium)
                reset.changes.forEach { line ->
                    Text(line, Modifier.testTag(SettingsTags.RESET_CHANGE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        },
        confirmButton = {
            // Its Undo bar counts the lines listed here, not the stored values under them.
            VinzTextButton(onClick = { onDismiss(); com.vinz.music.data.looks.LookStore.pageReset(reset.changes.size); reset.onReset() }, Modifier.testTag(SettingsTags.RESET_CONFIRM)) { Text(tr(R.string.reset)) }
        },
        dismissButton = { VinzTextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

/**
 * The preview over the list: it rides with its room in the list until that reaches the bar, then stays under the bar
 * and gives up height as the page scrolls on, down to [PreviewStripHeight]. Heights and places are read while laying
 * out, so scrolling doesn't recompose it.
 */
@Composable
private fun PinnedPreview(
    offset: State<Int?>,
    listState: LazyListState,
    full: Dp,
    top: Dp,
    side: Dp,
    pinned: Boolean,
    content: @Composable (strip: Boolean) -> Unit,
) {
    val ui = LocalUi.current
    val density = LocalDensity.current
    val fullPx = with(density) { full.roundToPx() }
    val stripPx = with(density) { PreviewStripHeight.roundToPx() }
    // Opened again by a tap on the strip, until the page is scrolled.
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(listState) { snapshotFlow { listState.isScrollInProgress }.collect { if (it) expanded = false } }
    val grow by animateFloatAsState(if (expanded) 1f else 0f, if (ui.reduceMotion) snap() else tween(VinzTokens.Motion.STANDARD), label = "preview")
    val strip by remember(offset, fullPx, stripPx) { derivedStateOf { offset.value.let { it != null && -it >= (fullPx - stripPx) / 2 } } }
    val laidOut by remember(offset) { derivedStateOf { offset.value != null } }
    if (!laidOut) return
    Box(
        Modifier.fillMaxWidth().padding(top = top)
            .offset { IntOffset(0, (offset.value ?: 0).coerceAtLeast(0)) }
            // Pinned, it sits on the page's own color edge to edge, so rows passing under it don't show beside it.
            .then(if (pinned) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier)
            // A drag that starts on the preview still scrolls the page.
            .scrollable(listState, Orientation.Vertical, reverseDirection = true)
            .padding(start = side, end = side, bottom = if (pinned) 6.dp else 0.dp),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .layout { measurable, constraints ->
                    val scrolledTo = fullPx - (-(offset.value ?: 0)).coerceIn(0, fullPx - stripPx)
                    val h = (scrolledTo + (fullPx - scrolledTo) * grow).roundToInt()
                    val p = measurable.measure(Constraints.fixed(constraints.maxWidth, h))
                    layout(p.width, h) { p.place(0, 0) }
                }
                .settingsCard(ui.shape(22.dp), SettingsTags.PREVIEW)
                .clickable(enabled = strip && !expanded, onClickLabel = tr(R.string.settings_show_preview)) { expanded = true },
        ) { content(strip && !expanded) }
    }
}
