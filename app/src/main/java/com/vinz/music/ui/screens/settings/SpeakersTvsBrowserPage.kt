package com.vinz.music.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vinz.music.R
import com.vinz.music.connect.PairedBrowser
import com.vinz.music.connect.ShownCode
import com.vinz.music.connect.TvInstall
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.tr
import com.vinz.music.ui.components.tickWhileShown
import com.vinz.music.ui.screens.SettingEntry
import com.vinz.music.ui.screens.SettingNeeds
import com.vinz.music.ui.screens.SettingsSection
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzIcons
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val page = SettingsPageId.SPEAKERS_TVS_BROWSER

/**
 * Test tags of what the two Connections pages show that isn't one of the kit's five rows: the lines inside a sheet
 * (an address, a pairing code, a paired browser or device, a step), which are content, not rows, and count towards no
 * budget.
 */
internal object ConnectTags {
    /** A line of content in a sheet ([ConnectSheetLine]). */
    const val LINE = "settings:connect:line"

    /** A paragraph in a sheet (a step of "How to connect"). */
    const val TEXT = "settings:connect:text"
    const val SPOTIFY_CLIENT_ID = "settings:connect:spotify-client-id"
    const val SPOTIFY_CONNECT = "settings:connect:spotify-connect"
}

/**
 * Settings → Speakers, TVs & browser (1.8.4's map, plan §2.3), built with the kit: Vinz on a TV, the browser remote
 * and handoff. This file is the only place for the page: its composable, its lines in Settings' search
 * ([speakersTvsBrowserIndex]), its "Reset this page" keys ([speakersTvsBrowserLookKeys]), its line on the root
 * ([speakersTvsBrowserValue]) and the old group names it answers to ([speakersTvsBrowserOldGroups]).
 *
 * What grows or changes by itself (the remote's address, its pairing code, the paired browsers; handoff's code and its
 * paired devices) is content of a sheet behind one row each, so the groups stay within the budgets however many are
 * paired. "Play on a speaker or TV" (a row that did nothing, §2.7) is gone: casting is picked on the player.
 */
@Composable
fun SpeakersTvsBrowserPage(bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val d = remember { AppSettings() }
    val reset = ListeningResets()
    val onOff: (Boolean) -> String = { ListeningResets.onOff(it) }
    val remoteChanged = reset.add(tr(R.string.control_from_a_browser), s.webRemote, d.webRemote, onOff) { c.settings.setWebRemote(it) }
    val handoffChanged = reset.add(tr(R.string.available_for_handoff), s.handoffAvailable, d.handoffAvailable, onOff) { c.settings.setHandoffAvailable(it) }
    // Held by the page, not by its group: the list is lazy, and sharing mustn't stop because a card scrolled away.
    val tv = rememberTvShare()

    SettingsPageScaffold(page.title, bottomPadding, showBack = showBack, onSearch = onSearch, find = find, reset = reset.pageReset()) {
        group(tr(R.string.vinz_on_a_tv)) { TvRows(tv) }
        group(tr(R.string.cn_browser_remote)) {
            SwitchRow(
                tr(R.string.control_from_a_browser), s.webRemote, info = tr(R.string.play_search_and_see_the_queue), changed = remoteChanged,
            ) { c.settings.setWebRemote(it) }
            if (s.webRemote) BrowserRemoteRow()
        }
        group(tr(R.string.handoff)) {
            SwitchRow(
                tr(R.string.available_for_handoff), s.handoffAvailable, info = tr(R.string.other_devices_with_vinz_on_this), changed = handoffChanged,
            ) { c.settings.setHandoffAvailable(it) }
            val shown by c.handoff.shownCode.collectAsState()
            val paired by c.handoff.pairedDevices.collectAsState()
            if (shown != null || paired.isNotEmpty()) PairedDevicesRow(shown, paired) { c.handoff.forget(it) }
        }
    }
}

// ---------------------------------------------------------------------------- Vinz on a TV

/** "Share Vinz with a TV": on only while the page is open (the switch isn't kept), see [rememberTvShare]. */
private class TvShare(val share: TvInstall) {
    var on by mutableStateOf(false)
    var failed by mutableStateOf(false)

    fun set(want: Boolean) {
        if (want) { failed = !share.start(); on = !failed } else { share.stop(); on = false }
    }
}

/**
 * Vinz on a TV (1.8.0): off whenever the page opens, and sharing stops when it goes, so nothing listens on the network
 * unless someone is putting Vinz on a TV right now. Vinz put away with the page still open (another app, the screen
 * off): sharing stops a little later, not at once (a TV may be part-way through taking it, the phone's screen gone dark
 * meanwhile), and not if Vinz comes back.
 */
@Composable
private fun rememberTvShare(): TvShare {
    val share = LocalContainer.current.tvInstall
    val tv = remember(share) { TvShare(share) }
    DisposableEffect(share) { onDispose { share.stop() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycle, share) {
        var later: Job? = null
        val o = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> if (tv.on) later = scope.launch {
                    delay(SHARE_AWAY_MS)
                    share.stop()
                    tv.on = false
                }
                Lifecycle.Event.ON_START -> { later?.cancel(); later = null }
                else -> Unit
            }
        }
        lifecycle.addObserver(o)
        onDispose { later?.cancel(); lifecycle.removeObserver(o) }
    }
    return tv
}

/** How long "Share Vinz with a TV" goes on with Vinz put away (a TV part-way through taking it finishes). */
private const val SHARE_AWAY_MS = 2 * 60_000L

/** Copies a text and says so. */
@Composable
internal fun copier(): (String) -> Unit {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    return { text ->
        clipboard.setText(AnnotatedString(text))
        android.widget.Toast.makeText(context, tr(R.string.copied), android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** The switch (its line says how it's going), the address to type on the TV while it's on, and the Downloader code. */
@Composable
private fun TvRows(tv: TvShare) {
    val copy = copier()
    val sent by tv.share.sent.collectAsState()
    SwitchRow(
        tr(R.string.share_vinz_with_a_tv), tv.on,
        value = when {
            tv.failed -> tr(R.string.this_copy_can_t_be_shared)
            tv.on && sent > 0 -> tr(R.string.sent_finish_installing_on_the_tv)
            else -> null
        },
        info = tr(R.string.tv_share_info) + "\n\n" + tr(R.string.tv_share_steps),
    ) { tv.set(it) }
    if (tv.on) {
        // The address follows the network (another Wi-Fi), so it's read again every few seconds.
        var tick by remember { mutableIntStateOf(0) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle) { lifecycle.tickWhileShown(2_000) { tick++ } }
        val address = remember(tick) { tv.share.address }
        // A tap copies it.
        LinkRow(tr(R.string.address), value = address ?: tr(R.string.connect_to_wi_fi_to_use), action = true) { address?.let(copy) }
    }
    val code = TvInstall.DOWNLOADER_CODE
    if (code.isNotBlank()) LinkRow(
        tr(R.string.downloader_code), value = tr(R.string.downloader_code_detail, code), info = tr(R.string.downloader_code_info), action = true,
    ) { copy(code) }
}

// ---------------------------------------------------------------------------- Browser remote

/**
 * The browser remote's one row while it's on: it says the address to type into a browser, and opens a sheet with the
 * address (to copy), the pairing code (and a new one), and the paired browsers (to unpair, one or all).
 */
@Composable
private fun BrowserRemoteRow() {
    val remote = LocalContainer.current.webRemote
    val copy = copier()
    val running by remote.running.collectAsState()
    // The address and code can change (another network, a pairing): looked at again every few seconds.
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.tickWhileShown(2_000) { tick++ } }
    val address = remember(tick, running) { remote.address }
    val title = tr(R.string.cn_address_and_code)
    var open by rememberSaveable(title, "browser-remote") { mutableStateOf(false) }
    val addressLine = address ?: if (running) tr(R.string.connect_to_wi_fi_to_use) else tr(R.string.couldn_t_start_try_switching_it)
    PickRow(title, addressLine) { open = true }
    if (open) RowsSheet(title, { open = false }) {
        val code = remember(tick) { remote.auth.code }
        val browsers: List<PairedBrowser> = remember(tick) { remote.auth.browsers() }
        // A tap copies it (as the page's own address row does).
        LinkRow(tr(R.string.address), value = addressLine, action = true) { address?.let(copy) }
        // Content with its own button: a tap on the code gives a new one (the ⟳ says so).
        ConnectSheetLine(
            tr(R.string.pairing_code), tr(R.string.one_browser_per_code, code.take(3), code.drop(3)),
            onClick = { remote.auth.newCode(); tick++ }, icon = VinzIcons.Refresh, iconLabel = tr(R.string.new_code),
        )
        ConnectSheetHeader(tr(R.string.cn_paired_browsers))
        if (browsers.isEmpty()) ConnectSheetText(tr(R.string.cn_no_browsers))
        browsers.forEach { b ->
            ConnectSheetLine(
                b.name, tr(R.string.paired_last_used, day(b.pairedAt), day(b.lastSeen)),
                icon = VinzIcons.Close, iconLabel = tr(R.string.unpair, b.name), onIcon = { remote.auth.forget(b.id); tick++ },
            )
        }
        if (browsers.size > 1) LinkRow(tr(R.string.unpair_all_browsers), value = tr(R.string.they_ll_need_a_new_code), action = true) {
            remote.auth.forgetAll(); tick++
        }
    }
}

private fun day(ms: Long): String {
    if (ms <= 0) return tr(R.string.never).lowercase()
    val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    return when (d) {
        today -> tr(R.string.today).lowercase()
        today.minusDays(1) -> tr(R.string.yesterday).lowercase()
        else -> com.vinz.music.localDates("dMMM").format(d)
    }
}

// ---------------------------------------------------------------------------- Handoff

/** What handoff's "Paired devices" row says: the code to type while one is showing, else who is paired. */
internal fun pairedDevicesValue(shown: ShownCode?, paired: List<Pair<String, String>>): String =
    shown?.let { tr(R.string.type_it_on, it.code.take(3), it.code.drop(3), it.from) } ?: paired.joinToString(", ") { it.second }

/**
 * Handoff's paired devices, and the pairing code while this device shows one: one row (it's only there with either),
 * and a sheet with the code and each device (to forget).
 */
@Composable
private fun PairedDevicesRow(shown: ShownCode?, paired: List<Pair<String, String>>, forget: (String) -> Unit) {
    val title = tr(R.string.cn_paired_devices)
    var open by rememberSaveable(title, "paired-devices") { mutableStateOf(false) }
    PickRow(title, pairedDevicesValue(shown, paired)) { open = true }
    if (open) RowsSheet(title, { open = false }) {
        shown?.let { ConnectSheetLine(tr(R.string.pairing_code), tr(R.string.type_it_on, it.code.take(3), it.code.drop(3), it.from)) }
        paired.forEach { (id, name) ->
            ConnectSheetLine(name, tr(R.string.paired), icon = VinzIcons.Close, iconLabel = tr(R.string.forget, name), onIcon = { forget(id) })
        }
    }
}

// ---------------------------------------------------------------------------- What the sheets hold besides rows

/**
 * A line of content in a [RowsSheet] (not a row): a [title] over its [value], and an [icon] at the end. [onClick]: what
 * a tap on the line does (a new code: the icon says so); with [onIcon] only the icon is tapped (unpair this one).
 * Actions with nothing to show but their name and line (copy the address, unpair all) are the kit's action rows.
 */
@Composable
internal fun ConnectSheetLine(
    title: String,
    value: String?,
    onClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
    iconLabel: String? = null,
    onIcon: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().testTag(ConnectTags.LINE)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = iconLabel, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp).padding(start = 16.dp, end = if (onIcon != null) 4.dp else 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface.copy(alpha = 0.7f))
        }
        if (icon != null) {
            Spacer(Modifier.width(12.dp))
            if (onIcon != null) IconButton(onClick = onIcon) { Icon(icon, iconLabel, tint = scheme.onSurfaceVariant) }
            // The line itself is the button; its label names what a tap does.
            else Icon(icon, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
        }
    }
}

/** A small heading inside a sheet of the Connections pages. */
@Composable
internal fun ConnectSheetHeader(text: String) {
    Text(
        text, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A paragraph inside a sheet of the Connections pages. */
@Composable
internal fun ConnectSheetText(text: String) {
    Text(
        text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).testTag(ConnectTags.TEXT),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
    )
}

// ---------------------------------------------------------------------------- The page's other parts

/**
 * The root's line for Speakers, TVs & browser: what's on of the browser remote and handoff, or what the page is for
 * when neither is.
 */
fun speakersTvsBrowserValue(s: AppSettings, dark: Boolean): String =
    listOfNotNull(if (s.webRemote) tr(R.string.browser_remote_on) else null, if (s.handoffAvailable) tr(R.string.root_handoff_on) else null)
        .joinToString(" · ").ifEmpty { tr(R.string.root_connect_short) }

/** The look settings "Reset this page" puts back: none (connections aren't part of a look; the page resets its rows itself). */
val speakersTvsBrowserLookKeys: List<String> = emptyList()

/**
 * Old group titles → this page's groups: "Control from a browser" is "Browser remote" (the title repeated its row).
 * "Speakers & TVs" held only the row that did nothing, so it opens the page at its top.
 */
val speakersTvsBrowserOldGroups: Map<String, String> by lazy { mapOf(tr(R.string.control_from_a_browser) to tr(R.string.cn_browser_remote)) }

/** The page's lines in Settings' search (each names the group it is drawn in). */
val speakersTvsBrowserIndex: List<SettingEntry> by lazy {
    val tvGroup = tr(R.string.vinz_on_a_tv)
    val browser = tr(R.string.cn_browser_remote)
    val handoff = tr(R.string.handoff)
    with(SettingNeeds) {
        listOf(
            SettingEntry(page, tvGroup, tr(R.string.share_vinz_with_a_tv), tr(R.string.tv_install_keywords) + " " + tr(R.string.from_this_phone_over_your_wi_fi)),
            SettingEntry(page, tvGroup, tr(R.string.downloader_code), tr(R.string.tv_install_keywords)),
            // "From a computer or tablet on this Wi-Fi" was the switch's second line.
            SettingEntry(
                page, browser, tr(R.string.control_from_a_browser),
                tr(R.string.web_remote_computer_laptop_tablet_browser) + " " + tr(R.string.from_a_computer_or_tablet_on),
            ),
            SettingEntry(page, browser, tr(R.string.cn_address_and_code), tr(R.string.web_remote_computer_laptop_tablet_browser), needs = webRemote),
            // In that row's sheet.
            SettingEntry(page, browser, tr(R.string.address), tr(R.string.web_remote_url_link_ip), needs = webRemote),
            SettingEntry(page, browser, tr(R.string.pairing_code), tr(R.string.web_remote_pair_code_browser), shown = tr(R.string.browser_pairing_code), needs = webRemote),
            SettingEntry(page, browser, tr(R.string.cn_paired_browsers), tr(R.string.cn_paired_keywords), needs = webRemote),
            SettingEntry(page, browser, tr(R.string.unpair_all_browsers), tr(R.string.web_remote_forget_browsers), needs = webRemote),
            // "Move music between your Vinz devices" was the switch's second line.
            SettingEntry(
                page, handoff, tr(R.string.available_for_handoff),
                tr(R.string.move_playback_another_device_tablet_phone) + " " + tr(R.string.move_music_between_your_vinz_devices),
            ),
            SettingEntry(page, handoff, tr(R.string.pairing_code), tr(R.string.handoff_pair_code), shown = tr(R.string.handoff_pairing_code)),
            SettingEntry(page, handoff, tr(R.string.cn_paired_devices), tr(R.string.cn_paired_keywords)),
        )
    }
}
