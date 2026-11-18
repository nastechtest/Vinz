package com.vinz.music.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.TagStore
import com.vinz.music.data.model.Song
import com.vinz.music.playback.QueueKind
import com.vinz.music.playback.QueueSource
import com.vinz.music.tr
import com.vinz.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

/** Add or remove this song's local tags. */
@Composable
fun SongTagPicker(song: Song, onDismiss: () -> Unit) {
    val tags = LocalContainer.current.tags
    val all by remember(tags) { tags.summaries }.collectAsState(initial = emptyList())
    val selected by remember(tags, song.id) { tags.tagsOf(song.id) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun update(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Toast.makeText(context, tr(R.string.song_tags_error), Toast.LENGTH_SHORT).show()
            } finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr(R.string.song_tags_title)) }, text = {
        Column {
            Text(song.title)
            Row {
                TextField(typed, { typed = it.take(TagStore.MAX_LENGTH) }, Modifier.weight(1f), singleLine = true, label = { Text(tr(R.string.song_tags_new)) })
                TextButton(enabled = !busy && TagStore.clean(typed) != null, onClick = {
                    val name = typed
                    update { tags.add(name, listOf(song)); typed = "" }
                }) { Text(tr(R.string.song_tags_add)) }
            }
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                all.forEach { tag ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        update { if (tag.tag in selected) tags.remove(tag.tag, song.id) else tags.add(tag.tag, listOf(song)) }
                    }.padding(vertical = 4.dp)) {
                        Checkbox(checked = tag.tag in selected, onCheckedChange = null)
                        Text(tag.tag, Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.done)) } })
}

/** A compact Library browser for local tags and their playable songs. */
@Composable
fun SongTagsBrowser(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val all by remember(c.tags) { c.tags.summaries }.collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<String?>(null) }
    val chosen = selected
    val rows by remember(c.tags, chosen) { c.tags.songs(chosen ?: "") }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var rename by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    fun update(action: suspend () -> Unit) = scope.launch {
        try { action() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Toast.makeText(context, tr(R.string.song_tags_error), Toast.LENGTH_SHORT).show()
        }
    }
    if (rename && chosen != null) {
        AlertDialog(onDismissRequest = { rename = false }, title = { Text(tr(R.string.song_tags_rename)) }, text = {
            TextField(typed, { typed = it.take(TagStore.MAX_LENGTH) }, singleLine = true)
        }, confirmButton = { TextButton(enabled = TagStore.clean(typed) != null, onClick = {
            val name = typed
            update { selected = c.tags.rename(chosen, name); rename = false }
        }) { Text(tr(R.string.done)) } }, dismissButton = { TextButton(onClick = { rename = false }) { Text(tr(R.string.cancel)) } })
    } else if (deleting && chosen != null) {
        AlertDialog(onDismissRequest = { deleting = false }, title = { Text(tr(R.string.song_tags_delete)) }, text = { Text(tr(R.string.song_tags_delete_confirm)) },
            confirmButton = { TextButton(onClick = { update { c.tags.delete(chosen); selected = null; deleting = false } }) { Text(tr(R.string.song_tags_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(tr(R.string.cancel)) } })
    } else {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(chosen ?: tr(R.string.song_tags_title)) }, text = {
            Column {
                if (chosen == null) {
                    Text(if (all.isEmpty()) tr(R.string.song_tags_empty) else tr(R.string.song_tags_local))
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        all.forEach { tag -> Text("${tag.tag} · ${tr(R.string.song_tags_count, tag.songs)}", Modifier.fillMaxWidth().clickable { selected = tag.tag }.padding(12.dp)) }
                    }
                } else {
                    Row {
                        TextButton(enabled = rows.isNotEmpty(), onClick = { c.player.playQueue(rows, 0, QueueSource(QueueKind.LIBRARY, chosen)) }) { Text(tr(R.string.widget_play)) }
                        TextButton(enabled = rows.isNotEmpty(), onClick = { c.player.playQueue(rows, 0, QueueSource(QueueKind.LIBRARY, chosen), shuffle = true) }) { Text(tr(R.string.shuffle)) }
                    }
                    Row {
                        TextButton(onClick = { typed = chosen; rename = true }) { Text(tr(R.string.song_tags_rename)) }
                        TextButton(onClick = { deleting = true }) { Text(tr(R.string.song_tags_delete)) }
                    }
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        rows.forEachIndexed { index, song ->
                            Text("${song.title} · ${song.artistText}", Modifier.fillMaxWidth().clickable { c.player.playQueue(rows, index, QueueSource(QueueKind.LIBRARY, chosen)) }.padding(12.dp))
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { if (chosen == null) onDismiss() else selected = null }) { Text(tr(if (chosen == null) R.string.done else R.string.back)) } })
    }
}
