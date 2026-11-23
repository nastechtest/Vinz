package com.vinz.music.ui.screens

import com.vinz.music.R
import com.vinz.music.tr
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vinz.music.data.looks.BuiltInLooks
import com.vinz.music.data.looks.Look
import com.vinz.music.data.looks.LookCode
import com.vinz.music.data.looks.LookSchedule
import com.vinz.music.data.looks.LookStore
import com.vinz.music.data.looks.SunClock
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.components.ActionGroup
import com.vinz.music.ui.components.ActionItem
import com.vinz.music.ui.components.ActionSheet
import com.vinz.music.ui.components.VinzButton
import com.vinz.music.ui.components.VinzOutlinedButton
import com.vinz.music.ui.components.VinzTextButton
import com.vinz.music.ui.components.frostFill
import com.vinz.music.ui.components.runAction
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.LocalUi
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.ui.theme.VinzTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** [looks] two to a row, each a card with the Look on two small phones; [current] is ringed. */
@Composable
internal fun LookGrid(looks: List<Look>, current: String?, onPick: (Look) -> Unit, onMore: ((Look) -> Unit)?) {
    val edited by LocalContainer.current.looks.edited.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        looks.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { look ->
                    LookCard(look, look.id == current, Modifier.weight(1f), { onPick(look) }, onMore?.let { m -> { m(look) } }, changed = look.id == edited?.id)
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A Look as a card: its Home and player, its name and what it is, a ⋯ for more. Tapping it switches to it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LookCard(look: Look, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit, onMore: (() -> Unit)? = null, changed: Boolean = false) {
    val c = LocalContainer.current
    val app = LocalAppSettings.current
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val s = remember(look, app) { c.looks.preview(look) }
    val song by c.player.currentSong.collectAsState()
    Column(
        modifier.clip(ui.tile).background(frostFill(if (selected) 2.6f else 1f))
            .then(if (selected) Modifier.border(2.dp, VinzTokens.spectrum, ui.tile) else Modifier.border(1.dp, scheme.onSurface.copy(alpha = 0.08f), ui.tile))
            .semantics { role = Role.RadioButton; this.selected = selected }
            // A long press opens the card's ⋯ too, as every other card in Vinz does.
            .combinedClickable(onClickLabel = tr(R.string.use, look.name), onLongClickLabel = onMore?.let { tr(R.string.more_for, look.name) }, onLongClick = onMore, onClick = onClick)
            .padding(8.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val w = ((maxWidth - 8.dp) / 2).coerceAtMost(80.dp)
            val dark = com.vinz.music.ui.theme.isAppDark(s)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewPhone(w) { LookTheme(s, dark) { colors -> MiniHome(colors, song) } }
                PreviewPhone(w) { LookTheme(s, true) { colors -> MiniPlayerScreen(colors, song) } }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(look.name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selected) Icon(VinzIcons.CheckCircle, tr(R.string.in_use), Modifier.size(18.dp))
            // Yours, in use with changes not saved into it yet.
            if (changed) Icon(VinzIcons.Edit, tr(R.string.in_use_changed_since_saved), Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
            if (onMore != null) Icon(
                VinzIcons.More, tr(R.string.more_for, look.name),
                Modifier.padding(start = 2.dp).size(32.dp).clip(RoundedCornerShape(50)).clickable(onClick = onMore).padding(6.dp),
                tint = scheme.onSurfaceVariant,
            )
        }
        if (look.note.isNotBlank()) Text(look.note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, minLines = 2, maxLines = 3)
    }
}

/** A Look's ⋯: switch to it, share it, and (yours) rename, copy or delete it; Vinz's own can be copied to make your own. */
@Composable
internal fun LookActions(look: Look, onDismiss: () -> Unit, onApply: () -> Unit, onShare: () -> Unit, onRename: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    ActionSheet(null, onDismiss) { close ->
        Text(look.name, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium))
        if (look.note.isNotBlank()) Text(look.note, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val edited by c.looks.edited.collectAsState()
        val changed = edited?.id == look.id
        val changes = remember(look, changed) { if (changed) c.looks.edits(look)?.let { tr(R.string.since_you_saved_it, it) } else c.looks.changes(look) }
        Text(
            changes ?: tr(R.string.you_re_on_it_now), Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
        )
        val items = listOfNotNull(
            // Yours: save the app's look as it is now into it (your changes since, or any settings you've made since).
            if (!look.builtIn && changes != null) ActionItem(
                VinzIcons.Update, if (changed) tr(R.string.update_with_your_changes) else tr(R.string.update_to_your_settings_now),
                onClick = { scope.launch { c.looks.update(look) } },
            ) else null,
            ActionItem(VinzIcons.Check, if (changed) tr(R.string.go_back_to_it_as_saved) else tr(R.string.use_this_look), onClick = onApply),
            ActionItem(VinzIcons.Share, tr(R.string.share), onClick = onShare),
            if (!look.builtIn) ActionItem(VinzIcons.Edit, tr(R.string.rename), onClick = onRename) else null,
            ActionItem(VinzIcons.Copy, if (look.builtIn) tr(R.string.save_a_copy_to_change) else tr(R.string.duplicate), onClick = {
                scope.launch { c.looks.keep(look, if (look.builtIn) look.name else tr(R.string.copy_2, look.name)) }
            }),
            if (!look.builtIn) ActionItem(VinzIcons.Delete, tr(R.string.delete), danger = true, onClick = { c.looks.delete(look.id) }) else null,
        )
        ActionGroup(null, items) { runAction(it, close) }
    }
}

/** A name to type ([initial] picked), for saving or renaming a Look. */
@Composable
internal fun NameDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            com.vinz.music.ui.components.VinzTextField(
                name, { name = it.take(Look.MAX_NAME) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
            )
        },
        confirmButton = { VinzTextButton({ onDone(name) }, enabled = name.isNotBlank()) { Text(tr(R.string.save)) } },
        dismissButton = { VinzTextButton(onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

/**
 * Sharing a Look: its QR code to scan, its code as text, or a .vinzlook file. Codes leave out photos and your own font;
 * a file can carry them, if you tick them.
 */
@Composable
internal fun ShareLookSheet(look: Look, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val code = remember(look) { c.looks.code(look) }
    val hasPhotos = look.photoAssets.isNotEmpty()
    val hasFont = look.font != null
    var withPhotos by remember { mutableStateOf(hasPhotos) }
    var withFont by remember { mutableStateOf(false) }
    ActionSheet(null, onDismiss) { close ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr(R.string.share_2, look.name), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(12.dp))
            if (code.length <= LookCode.MAX_QR_CHARS) {
                Box(Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(10.dp)) {
                    QrCode(code, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(8.dp))
                Text(tr(R.string.scan_it_in_vinz_settings_customize), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            } else Text(tr(R.string.too_much_for_a_qr_code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hasPhotos || hasFont) Text(
                tr(R.string.codes_leave_out_photos_and_your),
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VinzButton({
                    val text = tr(R.string.here_s_my_vinz_look_share, look.name, code)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), tr(R.string.send, look.name)))
                }) { Text(tr(R.string.send_as_text)) }
                VinzOutlinedButton({
                    scope.launch {
                        val r = runCatching {
                            withContext(Dispatchers.IO) {
                                val name = look.name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().ifBlank { tr(R.string.look) }
                                val file = File(context.cacheDir, "share").apply { mkdirs() }.resolve("$name.${LookStore.FILE_EXTENSION}")
                                file.outputStream().use { c.looks.writeFile(look, it, withPhotos, withFont) }
                                file
                            }
                        }
                        r.onSuccess { file ->
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.share", file)
                            val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            send.clipData = android.content.ClipData.newRawUri(null, uri)
                            context.startActivity(Intent.createChooser(send, tr(R.string.send, look.name)))
                        }.onFailure { Toast.makeText(context, it.message ?: tr(R.string.couldn_t_make_the_file), Toast.LENGTH_LONG).show() }
                    }
                }) { Text(tr(R.string.send_as_a_file)) }
            }
        }
        if (hasPhotos || hasFont) Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            if (hasPhotos) Toggle(tr(R.string.include_its_photos), tr(R.string.your_background_photos_go_in_the), withPhotos) { withPhotos = it }
            if (hasFont) Toggle(tr(R.string.include_its_font), tr(R.string.only_if_the_font_s_license), withFont) { withFont = it }
        }
    }
}

/** A Look someone shared, shown before anything changes: try it, keep it, or not. */
@Composable
internal fun PendingLookDialog(look: Look) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = remember(look) { c.looks.preview(look) }
    val changes = remember(look) { c.looks.changes(look) }
    fun done() { c.looks.pending.value = null }
    AlertDialog(
        onDismissRequest = ::done,
        title = { Text(look.name) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LookPhones(s, 72.dp, gap = 8.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    (changes?.let { "$it. " } ?: tr(R.string.it_looks_just_like_yours_now)) + tr(R.string.a_look_only_changes_how_vinz),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Row {
                VinzTextButton({
                    done()
                    scope.launch { c.looks.keep(look); Toast.makeText(context, tr(R.string.saved_to_your_looks), Toast.LENGTH_SHORT).show() }
                }) { Text(tr(R.string.save)) }
                VinzTextButton({ done(); scope.launch { c.looks.apply(look) } }) { Text(tr(R.string.use_it)) }
            }
        },
        dismissButton = { VinzTextButton(::done) { Text(tr(R.string.not_now)) } },
    )
}

/**
 * The season's Look, offered in its month (1.8.0): a quiet card with the Look on two small phones, what it is, and Use it;
 * the ⓘ says it's only for the month and what happens when it ends.
 */
@Composable
internal fun SeasonCard(look: Look, onUse: () -> Unit) {
    val c = LocalContainer.current
    val app = LocalAppSettings.current
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val s = remember(look, app) { c.looks.preview(look) }
    val song by c.player.currentSong.collectAsState()
    var info by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(ui.tile).background(frostFill(1.4f))
            .border(1.dp, scheme.onSurface.copy(alpha = 0.08f), ui.tile).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewPhone(56.dp) { LookTheme(s, com.vinz.music.ui.theme.isAppDark(s)) { colors -> MiniHome(colors, song) } }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.for_the_season, look.name), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                InfoMark(info, look.name) { info = !info }
            }
            Text(look.note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            InfoText(info, tr(R.string.season_info))
            VinzButton(onUse) { Text(tr(R.string.use_it)) }
        }
    }
}

/**
 * Holiday Mode (1.8.0): asked once a season, the first time Vinz opens in its month ([com.vinz.music.data.looks.SeasonPrompt]).
 * The season's background, small and moving, what changes, and that it ends with the month. [onAnswer]: true to turn it
 * on; false for No thanks or closing it (asked either way).
 */
@Composable
fun HolidayModeDialog(season: com.vinz.music.data.looks.Season, onAnswer: (Boolean) -> Unit) {
    val s = LocalAppSettings.current
    val style = com.vinz.music.ui.theme.Backdrop.entries.first { it.name == season.backdrop }
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(tr(R.string.holiday_mode)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp))) {
                    com.vinz.music.ui.theme.BackdropLayer(style, null, 0f, s.backdropMotion && !s.reduceMotion, Modifier.fillMaxSize())
                }
                Text(
                    tr(if (season == com.vinz.music.data.looks.Season.HARVEST) R.string.holiday_mode_harvest else R.string.holiday_mode_holiday),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { VinzTextButton({ onAnswer(true) }) { Text(tr(R.string.turn_on)) } },
        dismissButton = { VinzTextButton({ onAnswer(false) }) { Text(tr(R.string.no_thanks)) } },
    )
}

/** A history step's line under its name: how long [ago], and what a tap does (the newest one is an undo). */
internal fun lookStepLine(ago: String, newest: Boolean): String =
    if (newest) tr(R.string.look_step_tap_to_undo, ago) else tr(R.string.look_step_tap_to_go_back, ago)
