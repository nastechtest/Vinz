package com.vinz.music.ui.screens

import com.vinz.music.ui.components.VinzButton
import com.vinz.music.ui.components.VinzOutlinedButton
import com.vinz.music.ui.components.VinzSwitch
import com.vinz.music.ui.components.VinzTextButton
import com.vinz.music.ui.theme.VinzIcons
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vinz.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch
import java.io.File

private fun backupName() = "Vinz backup " + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".zip"

private fun sizeText(bytes: Long) = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    else -> "%.0f MB".format(maxOf(bytes, 1_048_576L) / 1_048_576.0)
}

/**
 * Backing up: asks whether the downloaded audio goes in too, then where to save the file.
 * [onDone] gets whether the backup was written.
 */
@Composable
fun rememberBackupFlow(onDone: (Boolean) -> Unit = {}): () -> Unit {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var withDownloads by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) { onDone(false); return@rememberLauncherForActivityResult }
        busy = true
        scope.launch {
            val r = runCatching { c.backup.export(uri, withDownloads) }
            busy = false
            Toast.makeText(context, r.exceptionOrNull()?.let { "Backup failed: ${it.message}" } ?: "Backed up", Toast.LENGTH_SHORT).show()
            onDone(r.isSuccess)
        }
    }
    if (asking) {
        var small by remember { mutableLongStateOf(0L) }
        var full by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) { small = c.backup.estimate(false); full = c.backup.estimate(true) }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Back up Vinz") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Your library, Replay history, settings, sign-in, covers and backgrounds go into one file. Keep it private: it can sign in as you.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Include downloaded songs", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (withDownloads) "About ${sizeText(full)}" else "About ${sizeText(small)}; downloads are fetched again after restoring",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        VinzSwitch(withDownloads, { withDownloads = it })
                    }
                }
            },
            confirmButton = { VinzTextButton(onClick = { asking = false; save.launch(backupName()) }) { Text("Choose where") } },
            dismissButton = { VinzTextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
    if (busy) WorkingDialog("Backing up…")
    return { asking = true }
}

/** Restoring: pick a backup, confirm, and Vinz restarts with it. */
@Composable
fun rememberRestoreFlow(): () -> Unit {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<android.net.Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked = it }
    picked?.let { uri ->
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text("Restore this backup?") },
            text = { Text("Vinz's library, history and settings on this phone are replaced with the backup's, then Vinz restarts.") },
            confirmButton = {
                VinzTextButton(onClick = {
                    picked = null
                    busy = true
                    scope.launch {
                        // Only comes back if it failed; on success Vinz restarts.
                        runCatching { c.backup.restore(uri) }.onFailure {
                            busy = false
                            Toast.makeText(context, "Couldn't restore: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { VinzTextButton(onClick = { picked = null }) { Text("Cancel") } },
        )
    }
    if (busy) WorkingDialog("Restoring…")
    return { open.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
}

@Composable
private fun WorkingDialog(text: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.vinz.music.ui.components.VinzSpinner(Modifier.size(24.dp), stroke = 2.5.dp)
                Spacer(Modifier.width(16.dp))
                Text(text)
            }
        },
    )
}

/** Settings → About: back up and restore. */
@Composable
fun BackupGroup() {
    val backup = rememberBackupFlow()
    val restore = rememberRestoreFlow()
    Group("Backup") {
        Item("Back up", "Library, Replay, settings and sign-in, to a file", onClick = backup)
        Item("Restore from a backup", "Replaces what's here, then restarts", onClick = restore)
    }
}

/** On the welcome screen, for a fresh install coming from a backup. */
@Composable
fun WelcomeRestoreButton() {
    val restore = rememberRestoreFlow()
    VinzTextButton(onClick = restore, Modifier.fillMaxWidth()) { Text("Restore from a backup", color = Color.White.copy(alpha = 0.8f)) }
}

/**
 * The new Vinz is signed with a new key, so Android can't install it over this one. Three steps:
 * back up, keep the new Vinz somewhere safe, uninstall this one; then open the saved file and restore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSignatureSheet(version: String, apk: File, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backedUp by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val backup = rememberBackupFlow { if (it) backedUp = true }
    val saveApk = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        if (uri != null) scope.launch {
            runCatching { c.updates.saveApk(apk, uri) }
                .onSuccess { saved = true }
                .onFailure { Toast.makeText(context, "Couldn't save it: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Moving to Vinz $version", style = MaterialTheme.typography.titleLarge)
            Text(
                "From $version, Vinz is signed with its own key instead of a shared test key, so Android stops flagging it. " +
                    "Android can't update across that change, so this once Vinz is reinstalled. Your things come with you:",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Step(1, "Back up", "Save a backup (Downloads is a good place)", backedUp) { backup() }
            Step(2, "Save the new Vinz", "Keep Vinz-$version.apk next to it", saved) { saveApk.launch("Vinz-$version.apk") }
            Step(3, "Uninstall this Vinz", "Then open Vinz-$version.apk from your files", false, enabled = backedUp && saved) {
                runCatching { context.startActivity(c.updates.uninstallIntent()) }.onFailure {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
                }
            }
            Text(
                "When the new Vinz opens, tap \"Restore from a backup\" and pick the backup.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Step(n: Int, title: String, detail: String, done: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$n. $title", style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        if (done) Icon(VinzIcons.Check, "Done", tint = MaterialTheme.colorScheme.primary)
        else if (n == 3) VinzButton(onClick = onClick, enabled = enabled) { Text("Uninstall") }
        else VinzOutlinedButton(onClick = onClick, enabled = enabled) { Text(if (n == 1) "Back up" else "Save") }
    }
}
