package com.vinz.music.ui.screens

import com.vinz.music.R
import com.vinz.music.tr
import com.vinz.music.data.CloudBackupPhase
import com.vinz.music.data.CloudBackupRecord
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.components.VinzButton
import com.vinz.music.ui.components.VinzTextButton
import com.vinz.music.ui.theme.LocalContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

private fun cloudBackupSize(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))

private fun cloudBackupDate(value: String): String =
    value.takeIf { it.isNotBlank() }?.take(16)?.replace('T', ' ') ?: tr(R.string.cloud_backup_unknown_date)

/** Cloud backup controls alongside the existing, independent local backup and restore flows. */
@Composable
fun CloudBackupGroup() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val sessionRevision by container.vinzCloudAccount.sessionChanges.collectAsState()
    val session = remember(sessionRevision) { container.vinzCloudAccount.session }
    val scope = rememberCoroutineScope()
    val progress by container.vinzCloudBackup.progress.collectAsState()

    var records by remember { mutableStateOf<List<CloudBackupRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var selectedPreview by remember { mutableStateOf<Pair<CloudBackupRecord, com.vinz.music.data.BackupPreview>?>(null) }
    var selectedDelete by remember { mutableStateOf<CloudBackupRecord?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var refreshGeneration by remember { mutableStateOf(0) }

    LaunchedEffect(session?.userId) {
        activeJob?.cancel()
        records = emptyList()
        selectedPreview = null
        selectedDelete = null
        error = null
        status = null
    }

    LaunchedEffect(session?.userId, refreshGeneration) {
        val expectedUserId = session?.userId ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            val latest = container.vinzCloudBackup.list()
            if (container.vinzCloudAccount.session?.userId == expectedUserId) records = latest
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (container.vinzCloudAccount.session?.userId == expectedUserId) {
                error = failure.message?.takeIf { it.isNotBlank() } ?: tr(R.string.cloud_backup_load_failed)
            }
        } finally {
            loading = false
        }
    }

    fun runOperation(action: suspend () -> Unit) {
        if (busy || loading || session == null) return
        busy = true
        error = null
        status = null
        activeJob = scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message?.takeIf { it.isNotBlank() } ?: tr(R.string.cloud_backup_failed)
            } finally {
                busy = false
                activeJob = null
            }
        }
    }

    Group(tr(R.string.cloud_backup_title), info = tr(R.string.cloud_backup_description)) {
        if (session == null) {
            Text(
                tr(R.string.cloud_backup_sign_in_required),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VinzButton(
                onClick = { navigator.go(Routes.VINZ_CLOUD_ACCOUNT) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(tr(R.string.cloud_backup_sign_in)) }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VinzButton(
                    onClick = {
                        val expectedUserId = session.userId
                        runOperation {
                            val created = container.vinzCloudBackup.create()
                            if (container.vinzCloudAccount.session?.userId == expectedUserId) {
                                records = listOf(created) + records
                                status = tr(R.string.cloud_backup_created)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy && !loading,
                ) { Text(tr(R.string.cloud_backup_create)) }
                VinzTextButton(
                    onClick = { refreshGeneration++ },
                    enabled = !busy && !loading,
                ) { Text(tr(R.string.cloud_backup_refresh)) }
            }

            if (loading || busy) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Column(Modifier.weight(1f)) {
                        val phaseText = when (progress?.phase) {
                            CloudBackupPhase.PREPARING -> R.string.cloud_backup_preparing
                            CloudBackupPhase.UPLOADING -> R.string.cloud_backup_uploading
                            CloudBackupPhase.DOWNLOADING -> R.string.cloud_backup_downloading
                            CloudBackupPhase.VALIDATING -> R.string.cloud_backup_validating
                            CloudBackupPhase.RESTORING -> R.string.cloud_backup_restoring
                            CloudBackupPhase.DELETING -> R.string.cloud_backup_deleting
                            null -> R.string.cloud_backup_loading
                        }
                        Text(tr(phaseText), style = MaterialTheme.typography.bodyMedium)
                        progress?.takeIf { it.totalBytes > 0 }?.let {
                            Text(
                                tr(R.string.cloud_backup_progress, (it.fraction * 100).toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (busy) VinzTextButton(onClick = { activeJob?.cancel() }) {
                        Text(tr(R.string.cloud_backup_cancel))
                    }
                }
            }
            error?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!loading && !busy) VinzTextButton(onClick = { refreshGeneration++ }) {
                    Text(tr(R.string.cloud_backup_retry))
                }
            }
            status?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!loading && records.isEmpty() && error == null) {
                Text(
                    tr(R.string.cloud_backup_empty),
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            records.forEach { record ->
                Item(
                    title = cloudBackupDate(record.createdAt),
                    subtitle = tr(R.string.cloud_backup_item_summary, cloudBackupSize(record.byteSize), record.appVersion),
                    onClick = {
                        runOperation {
                            val expectedUserId = session.userId
                            val preview = container.vinzCloudBackup.preview(record)
                            if (container.vinzCloudAccount.session?.userId == expectedUserId) {
                                selectedPreview = record to preview
                            }
                        }
                    },
                )
            }
        }
    }

    selectedPreview?.let { (record, preview) ->
        AlertDialog(
            onDismissRequest = { if (!busy) selectedPreview = null },
            title = { Text(tr(R.string.cloud_backup_preview_title)) },
            text = {
                Column {
                    Text(
                        tr(
                            R.string.cloud_backup_preview_meta,
                            cloudBackupSize(record.byteSize),
                            record.formatVersion,
                            record.appVersion,
                            cloudBackupDate(record.createdAt),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(tr(R.string.cloud_backup_preview_contains), style = MaterialTheme.typography.titleSmall)
                    preview.parts.sortedBy { it.ordinal }.forEach { part ->
                        Text("• ${part.label}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr(R.string.cloud_backup_preview_excludes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                VinzButton(
                    onClick = {
                        val target = record
                        selectedPreview = null
                        runOperation {
                            container.vinzCloudBackup.restore(target)
                            status = tr(R.string.cloud_backup_restored)
                        }
                    },
                    enabled = !busy,
                ) { Text(tr(R.string.cloud_backup_restore)) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    VinzTextButton(onClick = {
                        selectedPreview = null
                        selectedDelete = record
                    }, enabled = !busy) { Text(tr(R.string.cloud_backup_delete)) }
                    VinzTextButton(onClick = { selectedPreview = null }, enabled = !busy) {
                        Text(tr(R.string.cancel))
                    }
                }
            },
        )
    }

    selectedDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { if (!busy) selectedDelete = null },
            title = { Text(tr(R.string.cloud_backup_delete_title)) },
            text = { Text(tr(R.string.cloud_backup_delete_confirmation)) },
            confirmButton = {
                VinzButton(
                    onClick = {
                        selectedDelete = null
                        runOperation {
                            container.vinzCloudBackup.delete(record.id)
                            records = records.filterNot { it.id == record.id }
                            status = tr(R.string.cloud_backup_deleted)
                        }
                    },
                    enabled = !busy,
                ) { Text(tr(R.string.cloud_backup_delete)) }
            },
            dismissButton = {
                VinzTextButton(onClick = { selectedDelete = null }, enabled = !busy) {
                    Text(tr(R.string.cancel))
                }
            },
        )
    }
}
