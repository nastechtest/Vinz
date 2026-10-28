package com.prism.music.ui.screens

import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismOutlinedButton
import com.prism.music.ui.components.PrismTextButton
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.prism.music.PrismActivity
import com.prism.music.container
import com.prism.music.data.recognize.Recognizer
import com.prism.music.ui.components.ActionSheet
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTheme

/**
 * Song recognition as a card over whatever is on screen, for the Recognize widget and the Quick Settings tile: it
 * listens straight away (asking for the microphone the first time), then shows the song with Play and Open. The
 * microphone is only used while this card is on screen; leaving it stops listening.
 */
class RecognizeActivity : ComponentActivity() {
    private val c by lazy { container }
    private val denied = mutableStateOf(false)
    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied.value = !ok
        if (ok) c.recognizer.start(pausePrism = true)
    }

    private fun granted() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun listen() {
        if (granted()) { denied.value = false; c.recognizer.start(pausePrism = true) } else ask.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            c.recognizer.reset()
            listen()
        }
        setContent {
            val settings by c.settings.flow.collectAsState()
            CompositionLocalProvider(LocalContainer provides c) {
                PrismTheme(settings, null) {
                    RecognizeCard(
                        denied = denied.value,
                        onListen = ::listen,
                        onOpen = { uri ->
                            startActivity(Intent(this, PrismActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        onDone = ::finish,
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Never listening unseen: leaving the card (Home, another app) stops it.
        if (c.recognizer.state.value is Recognizer.State.Listening) c.recognizer.cancel()
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && c.recognizer.state.value !is Recognizer.State.Listening) c.recognizer.reset()
    }
}

@Composable
private fun RecognizeCard(denied: Boolean, onListen: () -> Unit, onOpen: (String) -> Unit, onDone: () -> Unit) {
    val c = LocalContainer.current
    val state by c.recognizer.state.collectAsState()
    val found = state as? Recognizer.State.Found
    ActionSheet(found?.recognition?.cover ?: found?.song?.thumbnail, onDismiss = {
        if (c.recognizer.state.value is Recognizer.State.Listening) c.recognizer.cancel()
        onDone()
    }) { close ->
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                is Recognizer.State.Found -> {
                    val r = s.recognition
                    val song = s.song
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(r.cover ?: song?.thumbnail, Modifier.size(88.dp), LocalUi.current.art, placeholderIcon = PrismIcons.Song)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Found it", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(r.title ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(r.artist ?: "", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (song != null) PrismButton({ close { c.player.playSingle(song) } }, Modifier.weight(1f)) {
                            Icon(PrismIcons.Play, null); Spacer(Modifier.width(6.dp)); Text("Play")
                        }
                        // With a song: it plays and Prism opens on the player; without one, on the Recognized list.
                        PrismOutlinedButton({
                            close {
                                if (song != null) c.player.playSingle(song)
                                onOpen(if (song != null) "prism://player" else "prism://recognized")
                            }
                        }, Modifier.weight(1f)) {
                            Text(if (song != null) "Open in Vinz" else "See in Vinz")
                        }
                    }
                    if (song == null) Text(
                        "Not found in Vinz Music", Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrismTextButton({ c.recognizer.reset(); onListen() }) { Text("Listen again") }
                }
                else -> {
                    val listening = s as? Recognizer.State.Listening
                    Text("Recognize", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                    ListenButton(listening != null, listening?.level ?: 0f, size = 190.dp) {
                        if (listening != null) c.recognizer.cancel() else onListen()
                    }
                    Text(
                        when {
                            listening != null -> "Listening… ${listening.heardMs / 1000} s"
                            denied -> "Vinz needs the microphone to listen"
                            s == Recognizer.State.NotFound -> "Couldn't recognize that one"
                            s == Recognizer.State.Saved -> "Saved, will check when you're online"
                            s == Recognizer.State.RateLimited -> "Shazam asks to wait a little"
                            s is Recognizer.State.Failed -> s.message
                            else -> "Tap to listen"
                        },
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    )
                    Text(
                        when {
                            listening != null -> "Hold your phone near the music"
                            denied -> "Tap the button to allow it. Only a fingerprint of the sound leaves your phone."
                            s == Recognizer.State.NotFound -> "Try again closer to the music, or where it's louder."
                            s == Recognizer.State.Saved -> "You'll get a notification when it's found."
                            s == Recognizer.State.RateLimited -> "Try again in a minute."
                            else -> "Vinz listens for up to 12 seconds. Only a fingerprint of the sound goes to Shazam."
                        },
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
