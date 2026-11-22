package com.vinz.music.ui.screens.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.looks.BuiltInLooks
import com.vinz.music.data.looks.GalleryLook
import com.vinz.music.data.looks.Look
import com.vinz.music.data.looks.LookGallery
import com.vinz.music.data.looks.LookSchedule
import com.vinz.music.data.looks.LookStore
import com.vinz.music.data.looks.Seasons
import com.vinz.music.data.looks.SunClock
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.tr
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.components.VinzButton
import com.vinz.music.ui.components.VinzOutlinedButton
import com.vinz.music.ui.components.VinzSpinner
import com.vinz.music.ui.components.VinzTextButton
import com.vinz.music.ui.screens.InfoMark
import com.vinz.music.ui.screens.InfoText
import com.vinz.music.ui.screens.LookActions
import com.vinz.music.ui.screens.LookGrid
import com.vinz.music.ui.screens.LookPhones
import com.vinz.music.ui.screens.LookTheme
import com.vinz.music.ui.screens.MiniHome
import com.vinz.music.ui.screens.MiniPlayerScreen
import com.vinz.music.ui.screens.NameDialog
import com.vinz.music.ui.screens.PendingLookDialog
import com.vinz.music.ui.screens.PreviewPhone
import com.vinz.music.ui.screens.SeasonCard
import com.vinz.music.ui.screens.SettingEntry
import com.vinz.music.ui.screens.SettingsSection
import com.vinz.music.ui.screens.ShareLookSheet
import com.vinz.music.ui.screens.lookStepLine
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.LocalUi
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.ui.theme.isAppDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val page = SettingsPageId.LOOKS

/** For tests: the card that says which Look is on, and a state line inside a group (an empty list, a download). */
internal const val LOOKS_NOW_TAG = "settings:looks:now"
internal const val LOOKS_STATE_TAG = "settings:looks:state"

/** What the community gallery is showing: nothing asked yet, loading, its Looks, or why it couldn't load. */
private sealed interface Gallery {
    data object Closed : Gallery
    data object Loading : Gallery
    data class Shown(val looks: List<GalleryLook>) : Gallery
    data class Failed(val why: String) : Gallery
}

/** Kept while Vinz runs, so the list doesn't close every time the page is left. */
private val gallery = mutableStateOf<Gallery>(Gallery.Closed)

/**
 * Settings → Looks (1.8.4's map, plan §2.3), rebuilt with the kit. This file is the only place for the page: its
 * composable, its lines in Settings' search ([looksIndex]), its "Reset this page" keys ([looksLookKeys]), its line on
 * the root ([looksValue]) and the old group names it answers to ([looksOldGroups]).
 *
 * Six groups: Vinz's Looks · Your Looks · From the community · Get a Look · Day and night · History and editing,
 * under the pinned preview (Home, the player and the lyrics as they are now) and a card that says which Look is on,
 * with saving and sharing it. The Looks themselves are the page's content, not choices of a setting: their grids stay
 * as cards (`LookGrid` in `LookSettings.kt`) inside the groups. Everything else is one of the five rows; the day's
 * and night's Look are picked in a sheet. "Customize by tapping" (from the old Customize Vinz page) is with History;
 * "Reset everything" (the old "Reset look & layout", with the confirm it had) is under ⋯.
 */
@Composable
fun LooksPage(bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val app = LocalAppSettings.current
    val mine by c.looks.mine.collectAsState()
    val current by c.looks.currentId.collectAsState()
    val pending by c.looks.pending.collectAsState()
    val edited by c.looks.edited.collectAsState()
    val sch by c.looks.schedule.collectAsState()
    val steps by c.looks.steps.collectAsState()
    val customizing by c.customizing.collectAsState()
    var saving by remember { mutableStateOf(false) }
    var acting by remember { mutableStateOf<Look?>(null) }
    var sharing by remember { mutableStateOf<Look?>(null) }
    var renaming by remember { mutableStateOf<Look?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<String?>(null) }
    var shown by gallery

    fun apply(look: Look) = scope.launch { c.looks.apply(look) }
    fun take(look: Look?) {
        if (look == null) Toast.makeText(context, tr(R.string.that_isn_t_a_vinz_look), Toast.LENGTH_SHORT).show() else c.looks.pending.value = look
    }
    fun load(refresh: Boolean) = scope.launch {
        shown = Gallery.Loading
        shown = runCatching { c.lookGallery.looks(refresh) }.fold(
            { Gallery.Shown(it) },
            { Gallery.Failed(if (it is java.net.UnknownHostException) tr(R.string.you_re_offline) else it.message ?: tr(R.string.couldn_t_reach_github)) },
        )
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { file ->
        if (file != null) scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(file)?.use { c.looks.readFile(it) } } }
            r.getOrNull()?.let { c.looks.pending.value = it }
                ?: Toast.makeText(context, r.exceptionOrNull()?.message ?: tr(R.string.that_file_isn_t_a_vinz), Toast.LENGTH_SHORT).show()
        }
    }
    val today = remember { Seasons.today() }
    // The season's Look in its month, on a card of its own above Vinz's.
    val seasonLook = remember(today) { Seasons.current(today)?.let { BuiltInLooks.of(it.lookId) } }
    val all = BuiltInLooks.all + mine
    fun lookName(id: String) = all.firstOrNull { it.id == id }?.name ?: id
    fun set(s: LookSchedule) = c.looks.setSchedule(s)
    fun time(minutes: Int): String = android.text.format.DateFormat.getTimeFormat(context)
        .format(java.util.Date(java.time.LocalDate.now().atTime(minutes / 60, minutes % 60).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
    fun pickTime(at: Int, done: (Int) -> Unit) {
        android.app.TimePickerDialog(context, { _, h, m -> done(h * 60 + m) }, at / 60, at % 60, android.text.format.DateFormat.is24HourFormat(context)).show()
    }

    SettingsPageScaffold(
        page.title, bottomPadding, showBack = showBack, onSearch = onSearch, find = find,
        // A Look is every look setting: there is no "this page" to reset, only everything.
        menu = listOf(PageMenuItem(tr(R.string.tl_reset_everything)) { resetting = true }),
        preview = { strip -> LooksPreview(strip) },
    ) {
        if (app.performanceMode) item("performance") { PerformanceNote() }
        item("now") { LookNow(current?.let(c.looks::find), edited, onSave = { saving = true }, onShare = { sharing = it }, onApply = { apply(it) }) }
        seasonLook?.let { look -> item("season") { SeasonCard(look) { apply(look) } } }

        group(tr(R.string.vinz_s_looks)) {
            // The season's Look has its own card above while it shows: not again in the grid, unless it's the one in use.
            LookGrid(BuiltInLooks.all.filter { (Seasons.shown(it.id, today) && it.id != seasonLook?.id) || it.id == current }, current, onPick = { apply(it) }, onMore = { acting = it })
        }
        group(tr(R.string.your_looks)) {
            if (mine.isEmpty()) StateLine(tr(R.string.looks_you_save_show_here_save))
            else LookGrid(mine, current, onPick = { apply(it) }, onMore = { acting = it })
        }
        group(tr(R.string.from_the_community)) {
            when (val g = shown) {
                Gallery.Closed -> LinkRow(
                    tr(R.string.browse_community_looks), value = tr(R.string.looks_people_shared_on_github), icon = VinzIcons.Globe,
                    info = tr(R.string.the_list_is_downloaded_from_vinz), action = true,
                ) { load(false) }
                Gallery.Loading -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { VinzSpinner(Modifier.size(28.dp)) }
                is Gallery.Failed -> LinkRow(tr(R.string.couldn_t_load_the_gallery), value = tr(R.string.tap_to_try_again, g.why), icon = VinzIcons.Globe, action = true) { load(true) }
                is Gallery.Shown -> {
                    if (g.looks.isEmpty()) StateLine(tr(R.string.no_community_looks_yet_yours_could))
                    else LookGrid(
                        g.looks.map { it.look }, current,
                        onPick = { look ->
                            val picked = g.looks.first { it.look.id == look.id }
                            if (opening == null) scope.launch {
                                opening = picked.entry.id
                                runCatching { c.lookGallery.fetch(picked, c.looks) }
                                    .onSuccess { c.looks.pending.value = it }
                                    .onFailure { Toast.makeText(context, it.message ?: tr(R.string.couldn_t_download_that_look), Toast.LENGTH_SHORT).show() }
                                opening = null
                            }
                        },
                        onMore = null,
                    )
                    if (opening != null) StateLine(tr(R.string.downloading_its_photos))
                    LinkRow(tr(R.string.refresh_the_list), value = tr(R.string.from_github), icon = VinzIcons.Refresh, action = true) { load(true) }
                }
            }
            LinkRow(
                tr(R.string.add_your_look), value = tr(R.string.by_pull_request_on_github), icon = VinzIcons.Add,
                info = tr(R.string.save_your_look_share_it_as), external = true,
            ) { runCatching { uri.openUri(LookGallery.ADD_YOURS) } }
        }
        group(tr(R.string.get_a_look), info = tr(R.string.you_see_a_look_first_and)) {
            LinkRow(tr(R.string.scan_a_look_s_qr_code), value = tr(R.string.from_a_friend_s_vinz), icon = VinzIcons.Camera) { nav.go(Routes.LOOK_SCAN) }
            LinkRow(tr(R.string.paste_a_look_code), value = tr(R.string.a_code_someone_sent_you), icon = VinzIcons.Copy, action = true) { take(c.looks.decode(clipboard.getText()?.text)) }
            LinkRow(tr(R.string.open_a_look_file), value = tr(R.string.a_vinzlook_file_with_its_photos), icon = VinzIcons.Photo, action = true) {
                openFile.launch(arrayOf("application/zip", "application/octet-stream"))
            }
        }
        group(tr(R.string.day_and_night)) {
            // While it's on, the row says what comes next and when.
            val next = if (!sch.on) null else remember(sch) { c.looks.nextTurn(sch) }.let { turn ->
                val at = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(turn.at.toEpochMilli()))
                tr(R.string.next_at, lookName(if (turn.to == SunClock.Part.DAY) sch.day else sch.night), at)
            }
            SwitchRow(
                tr(R.string.day_and_night_looks), sch.on, value = next ?: tr(R.string.one_look_by_day_another_by),
                info = tr(R.string.like_theme_system_for_whole_looks), changed = sch.on,
            ) { set(sch.copy(on = it)) }
            if (sch.on) {
                val ids = all.map { it.id }
                PickCardsRow(tr(R.string.by_day), (ids + sch.day).distinct(), sch.day, ::lookName, onPick = { set(sch.copy(day = it)) }) { id -> LookExample(all.firstOrNull { it.id == id }) }
                PickCardsRow(tr(R.string.by_night), (ids + sch.night).distinct(), sch.night, ::lookName, onPick = { set(sch.copy(night = it)) }) { id -> LookExample(all.firstOrNull { it.id == id }) }
                ChoiceRow(tr(R.string.switch_text), listOf(true, false), sch.sun, { if (it) tr(R.string.sunrise_sunset) else tr(R.string.set_times) }) { set(sch.copy(sun = it)) }
                if (!sch.sun) {
                    LinkRow(tr(R.string.day_starts_at), value = time(sch.dayAt)) { pickTime(sch.dayAt) { set(sch.copy(dayAt = it)) } }
                    LinkRow(tr(R.string.night_starts_at), value = time(sch.nightAt)) { pickTime(sch.nightAt) { set(sch.copy(nightAt = it)) } }
                }
            }
        }
        group(tr(R.string.tl_history_and_editing)) {
            // The last changes to the look, newest first: a tap goes back to before it.
            if (steps.isEmpty()) StateLine(tr(R.string.your_last_changes_to_the_look, LookStore.MAX_STEPS))
            steps.forEachIndexed { i, step ->
                val ago = android.text.format.DateUtils.getRelativeTimeSpanString(step.at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
                LinkRow(step.label, value = lookStepLine(ago, newest = i == 0), icon = VinzIcons.Undo, action = true) { c.looks.goBack(i) }
            }
            // #152: the list can go; the look itself stays as it is.
            if (steps.isNotEmpty()) LinkRow(tr(R.string.clear_history), value = tr(R.string.your_look_stays_as_it_is), icon = VinzIcons.Delete, action = true) { clearing = true }
            // "Customize this" (1.7.3): tap the mini player, tab bar, player or a Home section to change it there.
            SwitchRow(
                tr(R.string.customize_by_tapping), customizing, value = tr(R.string.tap_the_mini_player_tab_bar),
                info = tr(R.string.while_it_s_on_those_parts),
            ) {
                c.customizing.value = it
                if (it) Toast.makeText(context, tr(R.string.tap_anything_outlined_to_change_it), Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (saving) NameDialog(tr(R.string.save_as_a_look), tr(R.string.name), tr(R.string.my_look), onDismiss = { saving = false }) { name ->
        saving = false
        scope.launch {
            val look = c.looks.saveCurrent(name)
            Toast.makeText(context, tr(R.string.saved, look.name), Toast.LENGTH_SHORT).show()
        }
    }
    renaming?.let { look ->
        NameDialog(tr(R.string.rename), tr(R.string.name), look.name, onDismiss = { renaming = null }) { name -> renaming = null; c.looks.rename(look.id, name) }
    }
    acting?.let { look ->
        LookActions(look, onDismiss = { acting = null }, onApply = { apply(look) }, onShare = { sharing = look }, onRename = { renaming = look })
    }
    sharing?.let { look -> ShareLookSheet(look) { sharing = null } }
    pending?.let { look -> PendingLookDialog(look) }
    if (clearing) AlertDialog(
        onDismissRequest = { clearing = false },
        title = { Text(tr(R.string.clear_the_look_s_history)) },
        text = { Text(tr(R.string.you_won_t_be_able_to)) },
        confirmButton = { VinzTextButton({ c.looks.clearHistory(); clearing = false }) { Text(tr(R.string.clear)) } },
        dismissButton = { VinzTextButton({ clearing = false }) { Text(tr(R.string.cancel)) } },
    )
    // "Reset everything": the confirm the old "Reset look & layout" button had, word for word.
    if (resetting) AlertDialog(
        onDismissRequest = { resetting = false },
        title = { Text(tr(R.string.reset_look_layout)) },
        text = { Text(tr(R.string.font_text_size_corners_spacing_navigation)) },
        confirmButton = { VinzTextButton(onClick = { c.settings.resetLook(); resetting = false }) { Text(tr(R.string.reset)) } },
        dismissButton = { VinzTextButton(onClick = { resetting = false }) { Text(tr(R.string.cancel)) } },
    )
}

/** The pinned preview: Home, the player and the lyrics in the app's settings as they are, so it changes as a Look is picked. */
@Composable
private fun LooksPreview(strip: Boolean) {
    val s = LocalAppSettings.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (strip) LookPhones(s, 28.dp, gap = 10.dp) else LookPhones(s, 86.dp, gap = 12.dp)
    }
}

/**
 * Which Look is on ([now]; null: your own mix of settings) and keeping it: Save as a Look, Share, and for one of
 * yours changed since it was saved ([edited]) what changed, Update and the way back. What a Look is sits behind the ⓘ.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookNow(now: Look?, edited: Look?, onSave: () -> Unit, onShare: (Look) -> Unit, onApply: (Look) -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = LocalAppSettings.current
    val title = when {
        edited != null -> tr(R.string.you_re_on_with_changes, edited.name)
        now != null -> tr(R.string.you_re_on, now.name)
        else -> tr(R.string.you_re_on_your_own_mix)
    }
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().settingsCard(LocalUi.current.shape(20.dp), LOOKS_NOW_TAG).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium)
            InfoMark(open, title) { open = !open }
        }
        InfoText(open, tr(R.string.a_look_keeps_colors_type_icons))
        // State, not an explanation: what has changed since this Look was saved.
        edited?.let { e ->
            remember(e, app) { c.looks.edits(e) }?.let {
                Text(tr(R.string.since_you_saved_it_update_it, it, e.name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (edited != null) {
                VinzButton({ scope.launch { c.looks.update(edited) } }) { Icon(VinzIcons.Update, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.update)) }
                VinzOutlinedButton(onSave) { Icon(VinzIcons.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.save_as_new)) }
            } else VinzButton(onSave) { Icon(VinzIcons.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.save_as_a_look)) }
            if (now != null && edited == null) VinzOutlinedButton({ onShare(now) }) { Icon(VinzIcons.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.share)) }
        }
        edited?.let { e ->
            VinzTextButton({ onApply(e) }) {
                Icon(VinzIcons.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(tr(R.string.go_back_to_as_saved, e.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Icons are drawn as Vinz starts: a Look with other icons shows them after a restart.
        if (c.looks.iconsNeedRestart()) VinzTextButton({
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launch != null) {
                context.startActivity(Intent.makeRestartActivityTask(launch.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Runtime.getRuntime().exit(0)
            }
        }) { Icon(VinzIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.restart_vinz_to_see_its_icons)) }
    }
}

/** A Look in the day / night pick sheet: its Home and its player, as its card shows it. */
@Composable
private fun LookExample(look: Look?) {
    val c = LocalContainer.current
    val app = LocalAppSettings.current
    val song by c.player.currentSong.collectAsState()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (look == null) { Spacer(Modifier.size(64.dp, 131.dp)); return@Box }
        val s = remember(look, app) { c.looks.preview(look) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewPhone(64.dp) { LookTheme(s, isAppDark(s)) { colors -> MiniHome(colors, song) } }
            PreviewPhone(64.dp) { LookTheme(s, true) { colors -> MiniPlayerScreen(colors, song) } }
        }
    }
}

/**
 * A line of state inside a group, where there is nothing to list yet or something is under way ("Looks you save show
 * here", "Downloading its photos…"). Not a row: it sets nothing. The kit has no such part yet.
 */
@Composable
private fun StateLine(text: String) {
    Text(
        text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).testTag(LOOKS_STATE_TAG),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
}

/** The root's line for Looks: what the page is for, in one short line (the Look in use isn't in the settings). */
fun looksValue(s: AppSettings, dark: Boolean): String = tr(R.string.root_looks_short)

/** The look settings "Reset this page" puts back: none (a Look is every look setting; "Reset everything" is under ⋯). */
val looksLookKeys: List<String> = emptyList()

/** Old group titles → this page's groups: History is "History and editing" now, with "Customize by tapping" in it. */
val looksOldGroups: Map<String, String> by lazy {
    mapOf(tr(R.string.history) to tr(R.string.tl_history_and_editing), tr(R.string.customize_this) to tr(R.string.tl_history_and_editing))
}

/** The page's lines in Settings' search, each in the group it is drawn in. */
val looksIndex: List<SettingEntry> by lazy {
    val history = tr(R.string.tl_history_and_editing)
    listOf(
        SettingEntry(page, tr(R.string.vinz_s_looks), tr(R.string.vinz_s_looks), tr(R.string.theme_preset_paper_midnight_neon_soft)),
        SettingEntry(page, tr(R.string.your_looks), tr(R.string.your_looks), tr(R.string.saved_theme_preset_save_rename_duplicate)),
        SettingEntry(page, tr(R.string.from_the_community), tr(R.string.browse_community_looks), tr(R.string.gallery_github_themes_shared_download_others)),
        SettingEntry(page, tr(R.string.from_the_community), tr(R.string.couldn_t_load_the_gallery), tr(R.string.gallery_offline)),
        SettingEntry(page, tr(R.string.from_the_community), tr(R.string.refresh_the_list), tr(R.string.gallery_github_themes)),
        SettingEntry(page, tr(R.string.from_the_community), tr(R.string.add_your_look), tr(R.string.gallery_github_pull_request_contribute_share)),
        SettingEntry(page, tr(R.string.get_a_look), tr(R.string.scan_a_look_s_qr_code), tr(R.string.camera_theme_share_friend)),
        SettingEntry(page, tr(R.string.get_a_look), tr(R.string.paste_a_look_code), tr(R.string.clipboard_theme_share)),
        SettingEntry(page, tr(R.string.get_a_look), tr(R.string.open_a_look_file), tr(R.string.vinzlook_file_import_theme)),
        SettingEntry(page, tr(R.string.day_and_night), tr(R.string.day_and_night_looks), tr(R.string.schedule_sunset_sunrise_automatic_dark_mode)),
        // The schedule is the Looks' own (not a stored setting): its rows show once it is on, and are found by their group.
        SettingEntry(page, tr(R.string.day_and_night), tr(R.string.by_day), tr(R.string.schedule_day_look_morning)),
        SettingEntry(page, tr(R.string.day_and_night), tr(R.string.by_night), tr(R.string.schedule_night_look_evening_dark)),
        SettingEntry(page, tr(R.string.day_and_night), tr(R.string.switch_text), tr(R.string.schedule_sunrise_sunset_set_times)),
        SettingEntry(page, tr(R.string.day_and_night), tr(R.string.day_starts_at), tr(R.string.schedule_time)),
        SettingEntry(page, tr(R.string.day_and_night), tr(R.string.night_starts_at), tr(R.string.schedule_time)),
        SettingEntry(page, history, tr(R.string.history), tr(R.string.undo_go_back_changes_revert)),
        SettingEntry(page, history, tr(R.string.clear_history), tr(R.string.delete_forget_look_changes_history)),
        SettingEntry(page, history, tr(R.string.customize_by_tapping), tr(R.string.edit_mode_customise_tap_long_press) + " " + tr(R.string.customize_this)),
        SettingEntry(page, tr(R.string.your_looks), tr(R.string.include_its_photos), tr(R.string.share_file)),
        SettingEntry(page, tr(R.string.your_looks), tr(R.string.include_its_font), tr(R.string.share_file)),
    )
}
