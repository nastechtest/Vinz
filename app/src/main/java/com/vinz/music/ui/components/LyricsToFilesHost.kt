package com.vinz.music.ui.components

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.vinz.music.data.local.LyricsToFiles
import com.vinz.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

/**
 * Carries out requests to save lyrics into music files (#16; [LyricsToFiles.request]): finds the files and their lyrics,
 * asks Android's permission to change them, and writes them. At the app's root, so the permission dialog's answer
 * still lands after the menu that asked has closed.
 */
@Composable
fun LyricsToFilesHost() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<LyricsToFiles.Item>>(emptyList()) }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun write(items: List<LyricsToFiles.Item>) = scope.launch {
        val n = c.lyricsToFiles.write(items)
        toast(
            when {
                n == items.size -> if (n == 1) "Lyrics saved into the file" else "Lyrics saved into $n files"
                n == 0 -> "Couldn't save the lyrics into the files"
                else -> "Lyrics saved into $n of ${items.size} files"
            }
        )
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) write(pending) else toast("Nothing was changed")
    }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) write(pending) else toast("Vinz needs permission to change your music files")
    }
    LaunchedEffect(Unit) {
        c.lyricsToFiles.requests.collect { songs ->
            val targets = c.lyricsToFiles.targets(songs)
            if (targets.isEmpty()) { toast("No FLAC or MP3 files to save lyrics into"); return@collect }
            if (targets.size > 1) toast("Getting lyrics for ${targets.size} files…")
            val items = c.lyricsToFiles.prepare(targets).take(MAX_FILES)
            if (items.isEmpty()) { toast(if (targets.size == 1) "No lyrics found for this song" else "No lyrics found for these songs"); return@collect }
            pending = items
            when {
                Build.VERSION.SDK_INT >= 30 -> c.lyricsToFiles.writeRequest(items)?.let { consent.launch(IntentSenderRequest.Builder(it).build()) }
                Build.VERSION.SDK_INT <= 28 && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ->
                    legacy.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else -> write(items)
            }
        }
    }
}

/** At most this many files are changed at once (Android's permission request lists them all). */
private const val MAX_FILES = 1000
