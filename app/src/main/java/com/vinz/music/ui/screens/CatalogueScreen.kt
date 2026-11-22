package com.vinz.music.ui.screens

import com.vinz.music.R
import com.vinz.music.tr
import androidx.compose.ui.platform.testTag
import com.vinz.music.data.model.quickPicks
import com.vinz.music.ui.theme.VinzIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.data.model.PlaylistItem
import com.vinz.music.data.prefs.HomeSectionConfig
import com.vinz.music.data.prefs.HomeSections
import com.vinz.music.data.prefs.HomeShortcut
import com.vinz.music.data.prefs.PinnedPlaylist
import com.vinz.music.data.prefs.SectionStyle
import com.vinz.music.ui.components.VinzChip
import com.vinz.music.ui.components.RoundAction
import com.vinz.music.ui.components.SubPage
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import androidx.compose.ui.semantics.semantics
import com.vinz.music.data.prefs.HomeHeader
import com.vinz.music.data.prefs.HomeHeaderHeight
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.screens.settings.ChoiceRow
import com.vinz.music.ui.screens.settings.LinkRow
import com.vinz.music.ui.screens.settings.ListeningResets
import com.vinz.music.ui.screens.settings.PickCardsRow
import com.vinz.music.ui.screens.settings.PickListRow
import com.vinz.music.ui.screens.settings.SettingsPageScaffold
import com.vinz.music.ui.screens.settings.ShowChipsRow
import com.vinz.music.ui.screens.settings.SliderRow
import com.vinz.music.ui.screens.settings.SwitchRow
import com.vinz.music.ui.screens.settings.asVinzComes
import com.vinz.music.ui.screens.settings.settingsRowChanged
import com.vinz.music.ui.screens.settings.settingsRowValue
import kotlin.math.roundToInt
private fun SectionStyle.icon(): ImageVector = when (this) {
    SectionStyle.CAROUSEL -> VinzIcons.Carousel
    SectionStyle.GRID, SectionStyle.DIAL -> VinzIcons.Customize
    SectionStyle.LIST -> VinzIcons.Queue
    SectionStyle.HERO -> VinzIcons.Rows
}

/**
 * Customize Home: the top of Home, the sections it shows (in order: hold and drag to move, each with a look), the
 * shortcut tiles, and Home's options. A screen on its own route that counts as a settings page (1.8.4, plan §2.6): it
 * is built with the settings kit's groups and rows around its sections editor, within the same budgets, and its ⋯ has
 * "Reset this page".
 *
 * [find]: the group to open it at (a Settings search result). Left out, it is the `find` argument of the route the
 * screen was opened with, where its destination has one ([routeFind]).
 */
@Composable
fun CatalogueScreen(bottomPadding: Dp, find: String? = null) {
    val at = find ?: routeFind()
    StoredSettings { CataloguePage(bottomPadding, at) }
}

/**
 * The `find` argument of this screen's destination, read once as it opens. Settings' search opens the screen with
 * `catalogue?find=<group>`. A destination registered as plain "catalogue" opens for that route too, but drops the
 * query (the page then starts at its top); registered as "catalogue?find={find}" it keeps it, and the page opens at
 * that group.
 */
@Composable
private fun routeFind(): String? {
    val nav = LocalNavigator.current.nav
    return remember {
        val entry = nav.currentBackStackEntry?.takeIf { it.destination.route?.substringBefore('?') == Routes.CATALOGUE }
        entry?.arguments?.getString("find")?.takeIf { it.isNotBlank() }
    }
}

/** The tag of the list of sections: a list in a group, which the settings kit has no row type for (it counts as one row). */
internal const val CUSTOMIZE_HOME_SECTIONS = "customizeHome:sections"

@Composable
private fun CataloguePage(bottomPadding: Dp, find: String?) {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val prefs = c.settings
    val d = remember { com.vinz.music.data.prefs.AppSettings() }
    val ytShelves by homeShelfCache.collectAsState()
    val layout = settings.home.layout
    // Every section Vinz can show (built-in and every shelf YouTube Music has offered) that isn't on Home yet.
    val picks = ytShelves.quickPicks(settings.accountName)
    val available = (HomeSections.defaults + ytShelves.filterNot { it === picks }.map { HomeSectionConfig(shelfKey(it.title), it.title) })
        .filter { cfg -> layout.none { it.key == cfg.key } }
        .distinctBy { it.key }
    var adding by remember { mutableStateOf(false) }
    var pinning by remember { mutableStateOf(false) }
    // The tiles only show while their section is on Home.
    val tilesOn = layout.any { it.key == HomeSections.GREETING && it.visible }
    val header = settings.home.header
    val pictured = header == HomeHeader.PICTURE || header == HomeHeader.NOW_PLAYING

    fun save(list: List<HomeSectionConfig>) = prefs.setHomeLayout(list)

    // What differs from how Vinz comes, for the rows' dots and for Reset this page (top to bottom, as the page).
    val reset = ListeningResets()
    val onOff: (Boolean) -> String = { ListeningResets.onOff(it) }
    val greeting = tr(R.string.good_morning_afternoon_evening)
    val tilesCount: (Int) -> String = { if (it == 0) tr(R.string.off) else "$it" }
    val headerChanged = reset.add(tr(R.string.style), header, d.home.header, { it.label }) { prefs.setHomeHeader(it) }
    val headlineChanged = reset.add(tr(R.string.headline), settings.greetingText, d.greetingText, { it.ifBlank { greeting } }) { prefs.setGreetingText(it) }
    val showNameChanged = reset.add(tr(R.string.show_your_name), settings.greetingShowName, d.greetingShowName, onOff) { prefs.setGreetingShowName(it) }
    val heightChanged = reset.add(tr(R.string.height), settings.home.headerHeight, d.home.headerHeight, { it.label }) { prefs.setHomeHeaderHeight(it) }
    val pictureChanged = reset.add(tr(R.string.picture), settings.home.headerPicture, d.home.headerPicture, ::headerPictureLabel) { prefs.setHomeHeaderPicture(it) }
    val sectionsChanged = reset.add(tr(R.string.sections), layout, d.home.layout, asVinzComes(d.home.layout)) { save(it) }
    val shortcutsChanged = reset.add(tr(R.string.section_shortcut_tiles), settings.home.shortcuts, d.home.shortcuts, { l -> l.joinToString(" · ") { it.label }.ifEmpty { tr(R.string.none) } }) { prefs.setHomeShortcuts(it) }
    val pinnedChanged = reset.add(tr(R.string.pinned_playlists), settings.home.playlists, d.home.playlists, { l -> l.joinToString(", ") { it.title }.ifEmpty { tr(R.string.none) } }) { prefs.setHomePlaylists(it) }
    val recentChanged = reset.add(tr(R.string.recently_played_albums), settings.home.recentTiles, d.home.recentTiles, tilesCount) { prefs.setHomeRecentTiles(it) }
    val autoAddChanged = reset.add(tr(R.string.add_new_sections_automatically), settings.autoAddSections, d.autoAddSections, onOff) { prefs.setAutoAddSections(it) }
    val hidden = hideKindsToMerged(settings.home.hideKinds)
    val hiddenWords: (Set<HomeHiddenKind>) -> String = { kinds -> kinds.sorted().joinToString(" · ") { it.label }.ifEmpty { tr(R.string.none) } }
    val hiddenChanged = reset.add(tr(R.string.ch_hide_from_home), hidden, hideKindsToMerged(d.home.hideKinds), hiddenWords) { kinds ->
        HomeHiddenKind.entries.forEach { prefs.setHomeHideKind(it.key, it in kinds) }
    }
    val picksFromChanged = reset.add(tr(R.string.quick_picks_from), settings.behavior.quickPicksSource, d.behavior.quickPicksSource, { it.label }) { prefs.setQuickPicksSource(it) }
    val picksRadioChanged = reset.add(tr(R.string.quick_picks_start_a_radio), settings.quickPicksRadio, d.quickPicksRadio, onOff) { prefs.setQuickPicksRadio(it) }
    val youTubeOnlyChanged = reset.add(tr(R.string.speed_dial_youtube_music_s_only), settings.speedDialYouTubeOnly, d.speedDialYouTubeOnly, onOff) { prefs.setSpeedDialYouTubeOnly(it) }
    val yoursFirstChanged = reset.add(tr(R.string.speed_dial_your_plays_first), settings.behavior.speedDialYoursFirst, d.behavior.speedDialYoursFirst, onOff) { prefs.setSpeedDialYoursFirst(it) }
    val buttonChanged = reset.add(tr(R.string.customize_button_on_home), settings.home.customizeButton, d.home.customizeButton, onOff) { prefs.setHomeCustomizeButton(it) }

    SettingsPageScaffold(tr(R.string.customize_home), bottomPadding, find = find, reset = reset.pageReset()) {
        group(tr(R.string.top_of_home)) {
            // 1.7.6: the greeting as before, just the buttons, the greeting over a picture or over the playing cover.
            PickCardsRow(
                tr(R.string.style), HomeHeader.entries, header, { it.label }, changed = headerChanged, exampleScale = 1f,
                onPick = { prefs.setHomeHeader(it) },
            ) { kind ->
                Column {
                    HeaderSample(kind)
                    Spacer(Modifier.height(4.dp))
                    Text(kind.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // The greeting's own rows show with every top that has a greeting (all but "Just the buttons").
            if (header != HomeHeader.NONE) {
                HeadlineRow(headlineChanged)
                SwitchRow(tr(R.string.show_your_name), settings.greetingShowName, changed = showNameChanged) { prefs.setGreetingShowName(it) }
            }
            if (pictured) ChoiceRow(tr(R.string.height), HomeHeaderHeight.entries, settings.home.headerHeight, { it.label }, changed = heightChanged) { prefs.setHomeHeaderHeight(it) }
            if (header == HomeHeader.PICTURE) HeaderPictureRow(pictureChanged)
        }
        group(tr(R.string.sections), info = tr(R.string.hold_a_section_or_drag_its)) {
            SectionsList(layout, sectionsChanged, ::save) { adding = true }
        }
        // Faint (still editable) while the Shortcut tiles section is hidden or removed; the group's ⓘ says why.
        val tilesInfo = tr(R.string.shortcuts_appear_in_the_order_you) +
            if (tilesOn) "" else " " + tr(R.string.show_the_section_to_see_these, tr(R.string.section_shortcut_tiles)) + "."
        group(tr(R.string.section_shortcut_tiles), info = tilesInfo) {
            val faint = if (tilesOn) Modifier else Modifier.alpha(0.45f)
            val showTitle = tr(R.string.show)
            ShowChipsRow(
                showTitle,
                HomeShortcut.entries.map { sc ->
                    val on = sc in settings.home.shortcuts
                    // Numbered in the order they were picked, which is the order they show in.
                    ShowOption(if (on) "${settings.home.shortcuts.indexOf(sc) + 1}  ${sc.label}" else sc.label, on) {
                        prefs.setHomeShortcuts(if (on) settings.home.shortcuts - sc else settings.home.shortcuts + sc)
                    }
                },
                modifier = faint, changed = shortcutsChanged,
            )
            LinkRow(
                tr(R.string.pinned_playlists), faint, value = settings.home.playlists.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.title } ?: tr(R.string.none),
                info = tr(R.string.shown_as_tiles_after_the_shortcuts), changed = pinnedChanged,
            ) { pinning = true }
            SliderRow(
                tr(R.string.recently_played_albums), settings.home.recentTiles.toFloat(), 0f..6f, { tilesCount(it.roundToInt()) }, faint,
                steps = 5, changed = recentChanged,
            ) { prefs.setHomeRecentTiles(it.roundToInt()) }
        }
        group(tr(R.string.options)) {
            SwitchRow(
                tr(R.string.add_new_sections_automatically), settings.autoAddSections,
                info = tr(R.string.new_shelves_from_youtube_music_appear) + ".", changed = autoAddChanged,
            ) { prefs.setAutoAddSections(it) }
            // YouTube Music's own sections of a kind, left off Home (1.7.6): two switches before 1.8.4, the same stored set.
            ShowChipsRow(
                tr(R.string.ch_hide_from_home),
                HomeHiddenKind.entries.map { kind -> ShowOption(kind.label, kind in hidden) { prefs.setHomeHideKind(kind.key, it) } },
                info = tr(R.string.ch_hide_from_home_info), changed = hiddenChanged, tickHides = true,
            )
            // #349: Vinz's, made around your artists, or YouTube Music's own as they come.
            ChoiceRow(
                tr(R.string.quick_picks_from), com.vinz.music.data.prefs.QuickPicksSource.entries, settings.behavior.quickPicksSource, { it.label },
                info = tr(R.string.quick_picks_from_info), changed = picksFromChanged,
            ) { prefs.setQuickPicksSource(it) }
            SwitchRow(
                tr(R.string.quick_picks_start_a_radio), settings.quickPicksRadio,
                info = (if (settings.quickPicksRadio) tr(R.string.tapping_a_pick_plays_it_then) else tr(R.string.tapping_a_pick_plays_quick_picks)) + ".",
                changed = picksRadioChanged,
            ) { prefs.setQuickPicksRadio(it) }
            SwitchRow(
                tr(R.string.speed_dial_youtube_music_s_only), settings.speedDialYouTubeOnly,
                info = tr(R.string.speed_dial_starts_with_youtube_music) + " " + tr(R.string.speed_dial_only_short_page), changed = youTubeOnlyChanged,
            ) { prefs.setSpeedDialYouTubeOnly(it) }
            // #372: tiles that change as you listen. Under "YouTube Music's only" there are none of yours to put first.
            if (!settings.speedDialYouTubeOnly) SwitchRow(
                tr(R.string.speed_dial_your_plays_first), settings.behavior.speedDialYoursFirst,
                info = tr(R.string.speed_dial_your_plays_first_info), changed = yoursFirstChanged,
            ) { prefs.setSpeedDialYoursFirst(it) }
            // The same name here and wherever else it is spoken of (plan §2.6).
            SwitchRow(tr(R.string.customize_button_on_home), settings.home.customizeButton, changed = buttonChanged) { prefs.setHomeCustomizeButton(it) }
        }
    }

    if (adding) AddSectionSheet(available, ytShelves.isEmpty(), { adding = false }) { cfg ->
        // Speed dial goes in at the top, where YouTube Music has it (#53); everything else at the bottom. Either can be dragged.
        save(if (cfg.key == HomeSections.SPEED_DIAL) listOf(cfg.copy(visible = true)) + layout else layout + cfg.copy(visible = true))
    }
    if (pinning) PinPlaylistsSheet { pinning = false }
}

// ---------------------------------------------------------------------------- One control instead of two (plan §2.11)

/** A kind of YouTube Music's sections that "Hide from Home" leaves off Home, by the name it is stored under. */
enum class HomeHiddenKind(val key: String) {
    VIDEOS(HomeSections.KIND_VIDEOS), PODCASTS(HomeSections.KIND_PODCASTS);

    val label: String get() = if (this == VIDEOS) tr(R.string.videos) else tr(R.string.podcasts)
}

/** The stored set ([com.vinz.music.data.prefs.AppSettings.homeHideKinds]) as the chips that are ticked. */
fun hideKindsToMerged(stored: Set<String>): Set<HomeHiddenKind> = HomeHiddenKind.entries.filter { it.key in stored }.toSet()

/** What the chips are stored as; anything else already in the stored set ([before]) stays in it. */
fun hideKindsToStored(kinds: Set<HomeHiddenKind>, before: Set<String> = emptySet()): Set<String> =
    before - HomeHiddenKind.entries.map { it.key }.toSet() + kinds.map { it.key }

// ---------------------------------------------------------------------------- The rows the kit has no type for

/**
 * Home's sections, in order, each with its look and its ⋮ menu, then "Add a section": the editor as it was. A list in
 * a group: it carries its own tag, and says how many of its sections show as its value.
 */
@Composable
private fun SectionsList(layout: List<HomeSectionConfig>, changed: Boolean, save: (List<HomeSectionConfig>) -> Unit, onAdd: () -> Unit) {
    val shown = tr(R.string.hl_shown_of, layout.count { it.visible }, layout.size)
    Column(Modifier.fillMaxWidth().testTag(CUSTOMIZE_HOME_SECTIONS).semantics { settingsRowValue = shown; settingsRowChanged = changed }) {
        com.vinz.music.ui.components.ReorderList(layout, { it.key }, { save(it) }) { cfg, i, held, mod ->
            Column(mod) {
                if (i > 0 && !held) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SectionRow(
                    cfg,
                    canUp = i > 0,
                    canDown = i < layout.lastIndex,
                    onMove = { by -> save(layout.toMutableList().apply { add(i + by, removeAt(i)) }) },
                    onChange = { new -> save(layout.map { if (it.key == cfg.key) new else it }) },
                    onRemove = { save(layout.filterNot { it.key == cfg.key }) },
                    held = held,
                    grip = Modifier.dragHandle(),
                )
            }
        }
        if (layout.isNotEmpty()) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(VinzIcons.Add, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Text(tr(R.string.add_a_section), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Home's headline: the row says it (or the greeting it stands in for), and a tap opens a sheet to type it in. Saved
 * empty, Home follows the time of day again. Swiping the sheet down, Back or Cancel changes nothing.
 */
@Composable
private fun HeadlineRow(changed: Boolean) {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val title = tr(R.string.headline)
    val info = tr(R.string.ch_headline_info)
    var editing by remember { mutableStateOf(false) }
    LinkRow(title, value = settings.greetingText.ifBlank { tr(R.string.good_morning_afternoon_evening) }, info = info, changed = changed) { editing = true }
    if (editing) {
        val scheme = MaterialTheme.colorScheme
        var text by remember { mutableStateOf(settings.greetingText) }
        var explained by remember { mutableStateOf(false) }
        com.vinz.music.ui.components.VinzSheet({ editing = false }, containerColor = scheme.surfaceContainerLow, contentColor = scheme.onSurface, dragHandle = null) { close ->
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp, 4.dp).clip(androidx.compose.foundation.shape.CircleShape).background(scheme.onSurface.copy(alpha = 0.3f)))
            }
            Column(Modifier.fillMaxWidth().testTag(com.vinz.music.ui.screens.settings.SettingsTags.SHEET).padding(start = 20.dp, end = 16.dp, bottom = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleLarge)
                    InfoMark(explained, title) { explained = !explained }
                }
                InfoText(explained, info, Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(10.dp))
                com.vinz.music.ui.components.VinzTextField(
                    text, { text = it.replace('\n', ' ') }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(tr(R.string.good_morning_afternoon_evening)) },
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    com.vinz.music.ui.components.VinzTextButton(onClick = { close {} }) { Text(tr(R.string.cancel)) }
                    com.vinz.music.ui.components.VinzTextButton(onClick = { c.settings.setGreetingText(text.trim()); close {} }) { Text(tr(R.string.save)) }
                }
            }
        }
    }
}

/** What the header's picture is, in words: "Your photo" or the design's name. */
private fun headerPictureLabel(stored: String): String =
    com.vinz.music.ui.theme.Backdrop.parse(stored).first.let { if (it == com.vinz.music.ui.theme.Backdrop.PHOTO) tr(R.string.your_photo) else it.label }

/** One line of the picture's list: a design, or your photo ([design] null). */
private data class HeaderPicture(val design: com.vinz.music.ui.theme.Backdrop?)

/** The picture behind the greeting: one of the background designs, or a photo of your own (the phone's picker). */
@Composable
private fun HeaderPictureRow(changed: Boolean) {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { savePhoto(context, uri, "home-header") }
            if (file != null) c.settings.setHomeHeaderPicture(com.vinz.music.ui.theme.Backdrop.photoValue(file.absolutePath))
        }
    }
    val current = com.vinz.music.ui.theme.Backdrop.parse(settings.home.headerPicture).first
    val photo = current == com.vinz.music.ui.theme.Backdrop.PHOTO
    val designs = com.vinz.music.ui.theme.Backdrop.entries.filter {
        (it.group == com.vinz.music.ui.theme.BackdropGroup.COLOR || it.group == com.vinz.music.ui.theme.BackdropGroup.DESIGNS) &&
            (it.offeredOn(com.vinz.music.data.looks.Seasons.today()) || it == current)
    }
    val choosePhoto = { picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val options = listOf(HeaderPicture(null)) + designs.map { HeaderPicture(it) }
    PickListRow(
        tr(R.string.picture), options, if (photo) HeaderPicture(null) else HeaderPicture(current),
        { o -> o.design?.label ?: tr(R.string.your_photo) },
        changed = changed,
        // With a photo set, its line says a tap chooses another, and a tap on it (the current pick) does.
        detail = { o -> if (o.design == null && photo) tr(R.string.ch_another_photo) else null },
        onRepick = { if (photo) choosePhoto() },
    ) { o ->
        if (o.design == null) choosePhoto()
        else c.settings.setHomeHeaderPicture(o.design.name)
    }
}

/** One section on Home: tap to show or hide it; ⋮ moves, restyles or removes it. */
@Composable
private fun SectionRow(
    cfg: HomeSectionConfig,
    canUp: Boolean,
    canDown: Boolean,
    onMove: (Int) -> Unit,
    onChange: (HomeSectionConfig) -> Unit,
    onRemove: () -> Unit,
    held: Boolean = false,
    modifier: Modifier = Modifier,
    /** Dragging the grip moves the section at once (the row itself after a hold). */
    grip: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    if (renaming) SectionNameDialog(cfg, { renaming = false }) { onChange(cfg.copy(name = it)); renaming = false }
    val fg = if (cfg.visible) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.6f)
    Row(
        // No tap while held, so letting go of a drag doesn't also hide the section.
        modifier.fillMaxWidth().clickable(enabled = !held, onClickLabel = if (cfg.visible) tr(R.string.hide) else tr(R.string.show)) { onChange(cfg.copy(visible = !cfg.visible)) }
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (cfg.visible) cfg.style.icon() else VinzIcons.Hidden, null, Modifier.size(22.dp), tint = if (cfg.visible) scheme.primary else fg)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            // No line limit: at large text a long name wraps and the row grows (1.8.4).
            Text(cfg.titled(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = fg)
            Text(
                listOfNotNull(
                    if (!cfg.visible) tr(R.string.hidden) else if (cfg.tile) tr(R.string.one_tile) else cfg.style.label,
                    cfg.title.takeIf { cfg.name != null }?.let { tr(R.string.was, it) },
                    tr(R.string.pinned).takeIf { cfg.key.startsWith(HomeSections.PIN_PREFIX) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
        com.vinz.music.ui.components.DragGrip(grip, tr(R.string.drag, cfg.titled()))
        IconButton(onClick = { menu = true }) {
            Icon(VinzIcons.MoreVertical, tr(R.string.options_for, cfg.title), tint = scheme.onSurfaceVariant)
            DropdownMenu(menu, { menu = false }) {
                SectionStyle.entries.forEach { st ->
                    DropdownMenuItem(
                        { Text(st.label) }, { onChange(cfg.copy(style = st, visible = true, tile = false)); menu = false },
                        leadingIcon = { Icon(st.icon(), null) },
                        trailingIcon = { if (st == cfg.style && !cfg.tile) Icon(VinzIcons.Check, null) },
                    )
                }
                // A pinned album or playlist as one tile (1.8.3, D48).
                if (OwnSections.canTile(cfg.key)) DropdownMenuItem(
                    { Text(tr(R.string.one_tile)) }, { onChange(cfg.copy(tile = true, visible = true)); menu = false },
                    leadingIcon = { Icon(VinzIcons.Album, null) },
                    trailingIcon = { if (cfg.tile) Icon(VinzIcons.Check, null) },
                    modifier = Modifier.testTag("oneTile"),
                )
                HorizontalDivider()
                DropdownMenuItem({ Text(tr(R.string.rename)) }, { renaming = true; menu = false }, leadingIcon = { Icon(VinzIcons.Edit, null) })
                if (canUp) DropdownMenuItem({ Text(tr(R.string.move_up)) }, { onMove(-1); menu = false }, leadingIcon = { Icon(VinzIcons.ChevronUp, null) })
                if (canDown) DropdownMenuItem({ Text(tr(R.string.move_down)) }, { onMove(1); menu = false }, leadingIcon = { Icon(VinzIcons.ChevronDown, null) })
                DropdownMenuItem(
                    { Text(if (cfg.visible) tr(R.string.hide) else tr(R.string.show)) }, { onChange(cfg.copy(visible = !cfg.visible)); menu = false },
                    leadingIcon = { Icon(if (cfg.visible) VinzIcons.Hidden else VinzIcons.Visible, null) },
                )
                DropdownMenuItem({ Text(tr(R.string.remove)) }, { onRemove(); menu = false }, leadingIcon = { Icon(VinzIcons.Delete, null) })
            }
        }
    }
}

/** A small example of each kind of top of Home: the picture (if any), the greeting as bars, the round buttons. */
@Composable
private fun HeaderSample(kind: com.vinz.music.data.prefs.HomeHeader) {
    val scheme = MaterialTheme.colorScheme
    val dark = com.vinz.music.ui.theme.LocalIsDark.current
    val ground = when (kind) {
        com.vinz.music.data.prefs.HomeHeader.PICTURE -> androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF2EE59D), Color(0xFF7C5CFF), Color(0xFF00C2FF)))
        com.vinz.music.data.prefs.HomeHeader.NOW_PLAYING -> androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFFE76F51), Color(0xFF264653)))
        else -> androidx.compose.ui.graphics.SolidColor(scheme.surfaceContainerHigh)
    }
    Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerHigh)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val picture = kind == com.vinz.music.data.prefs.HomeHeader.PICTURE || kind == com.vinz.music.data.prefs.HomeHeader.NOW_PLAYING
            if (picture) drawRect(ground, alpha = 0.9f)
            if (picture) drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(0.4f to Color.Transparent, 1f to scheme.surfaceContainerHigh))
            val ink = if (dark || picture) Color.White else scheme.onSurface
            // The round buttons, top right.
            for (i in 0..2) drawCircle(ink.copy(alpha = 0.55f), 5.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width - (12 + i * 14).dp.toPx(), 14.dp.toPx()))
            if (kind != com.vinz.music.data.prefs.HomeHeader.NONE) {
                val y = size.height - 22.dp.toPx()
                drawRoundRect(ink.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(10.dp.toPx(), y - 9.dp.toPx()), androidx.compose.ui.geometry.Size(size.width * 0.25f, 3.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                drawRoundRect(ink.copy(alpha = 0.9f), androidx.compose.ui.geometry.Offset(10.dp.toPx(), y), androidx.compose.ui.geometry.Size(size.width * 0.55f, 7.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            }
        }
    }
}

/** A section's own name on Home (1.7.6); blank goes back to its own. */
@Composable
private fun SectionNameDialog(cfg: HomeSectionConfig, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(cfg.name ?: cfg.title) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.rename_section)) },
        text = {
            com.vinz.music.ui.components.VinzTextField(
                text, { text = it.replace('\n', ' ').take(40) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(cfg.title) },
            )
        },
        confirmButton = {
            com.vinz.music.ui.components.VinzTextButton(onClick = { onSave(text.trim().takeIf { it.isNotEmpty() && it != cfg.title }) }) { Text(tr(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (cfg.name != null) com.vinz.music.ui.components.VinzTextButton(onClick = { onSave(null) }) { Text(tr(R.string.use_2, cfg.title)) }
                com.vinz.music.ui.components.VinzTextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSectionSheet(available: List<HomeSectionConfig>, notLoaded: Boolean, onDismiss: () -> Unit, onAdd: (HomeSectionConfig) -> Unit) {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    // Sections from your own listening (1.7.6), not on Home yet.
    val rules by androidx.compose.runtime.produceState(emptyList<HomeSectionConfig>()) {
        value = OwnSections.rules(c).filter { r -> settings.home.layout.none { it.key == r.key } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(tr(R.string.add_a_section), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
        val (vinz, youtube) = available.partition { !it.key.startsWith(HomeSections.YT_PREFIX) }
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            item { SheetLabel(tr(R.string.your_own)) }
            items(rules, key = { it.key }) { cfg -> SheetRow(cfg.title) { onAdd(cfg); onDismiss() } }
            item {
                Text(
                    tr(R.string.to_pin_an_album_playlist_artist),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            if (vinz.isNotEmpty()) item { SheetLabel(tr(R.string.from_vinz)) }
            items(vinz, key = { it.key }) { cfg -> SheetRow(cfg.title) { onAdd(cfg); onDismiss() } }
            item { SheetLabel(tr(R.string.from_youtube_music)) }
            if (youtube.isEmpty()) item {
                Text(
                    if (notLoaded) tr(R.string.open_home_once_to_load_youtube) else tr(R.string.everything_s_already_on_home),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            items(youtube, key = { it.key }) { cfg -> SheetRow(cfg.title) { onAdd(cfg); onDismiss() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinPlaylistsSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val library by c.library.playlists.collectAsState()
    val vinzLists by c.vinzLibrary.lists.collectAsState()
    // Vinz's own playlists (kept on the phone) can be pinned too, as well as the library's.
    val mine = remember(vinzLists) { vinzLists.map { it.item() } }
    val playlists = remember(library, mine) { mine + library.filterIsInstance<PlaylistItem>().filterNot { l -> mine.any { it.id == l.id } } }
    // Pinned ones first (kept even if they've left the library), then Vinz's playlists, then the rest of the library.
    val pinnedIds = settings.home.playlists.map { it.id }
    val pinned = settings.home.playlists.map { it.id to it.title }
    val vinzOptions = mine.filter { it.id !in pinnedIds }.map { it.id to it.title }
    val youtubeOptions = playlists.filter { it.id !in pinnedIds && mine.none { m -> m.id == it.id } }.map { it.id to it.title }
    val options = pinned + vinzOptions + youtubeOptions
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(tr(R.string.pinned_playlists), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Text(
            tr(R.string.shown_as_tiles_after_the_shortcuts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            if (options.isEmpty()) item {
                Text(
                    tr(R.string.your_playlists_show_up_here_once_2), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            fun row(scope: androidx.compose.foundation.lazy.LazyListScope, list: List<Pair<String, String>>) = scope.items(list, key = { it.first }) { (id, title) ->
                val on = id in pinnedIds
                SheetRow(title, checked = on) {
                    c.settings.setHomePlaylists(
                        if (on) settings.home.playlists.filterNot { it.id == id }
                        else settings.home.playlists + playlists.first { it.id == id }.let { PinnedPlaylist(it.id, it.title, it.thumbnail) }
                    )
                }
            }
            if (pinned.isNotEmpty()) { item { SheetLabel(tr(R.string.pinned)) }; row(this, pinned) }
            if (vinzOptions.isNotEmpty()) { item { SheetLabel(tr(R.string.vinz_playlists)) }; row(this, vinzOptions) }
            if (youtubeOptions.isNotEmpty()) { item { SheetLabel(tr(R.string.youtube_music_playlists)) }; row(this, youtubeOptions) }
        }
    }
}

@Composable
private fun SheetLabel(text: String) = com.vinz.music.ui.components.Eyebrow(text, Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp))

@Composable
private fun SheetRow(title: String, checked: Boolean? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (checked) {
            null -> Icon(VinzIcons.Add, tr(R.string.add), tint = MaterialTheme.colorScheme.primary)
            true -> Icon(VinzIcons.Check, tr(R.string.pinned), tint = MaterialTheme.colorScheme.primary)
            false -> {}
        }
    }
}
