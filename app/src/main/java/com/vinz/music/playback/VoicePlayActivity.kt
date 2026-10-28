package com.vinz.music.playback

import android.app.Activity
import android.app.SearchManager
import android.content.ComponentName
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * "Hey Google, play … on Vinz" from the phone (Assistant / Gemini send `MEDIA_PLAY_FROM_SEARCH` to an
 * activity, and only list apps that declare it as music sources). No screen: the query goes to the
 * playback service as a search request — `onSetMediaItems` plays the top match, or the last queue when
 * there's no query — and this closes at once. In the car, Android Auto asks the session directly.
 */
class VoicePlayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val query = intent?.getStringExtra(SearchManager.QUERY)?.trim().orEmpty()
            .ifEmpty { listOfNotNull(intent?.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE), intent?.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST)).joinToString(" ") }
        val app = applicationContext
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackServices.target(app)))).buildAsync()
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull() ?: return@addListener
            val item = MediaItem.Builder()
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build())
                .build()
            controller.setMediaItem(item)
            controller.prepare()
            controller.play()
            // Let the service pick up the request before this controller goes away.
            controller.addListener(object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) controller.release() }
            })
            android.os.Handler(app.mainLooper).postDelayed({ controller.release() }, 20_000)
        }, ContextCompat.getMainExecutor(app))
        finish()
    }
}
