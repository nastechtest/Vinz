package com.vinz.music.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vinz.music.data.model.Song

/** Returns the first item in the service supplied playback order. */
internal fun <T> firstUpcoming(queue: List<T>, upNext: List<Int>): T? =
    upNext.firstOrNull()?.let(queue::getOrNull)

/** Integration surface for the compact next track view. */
@Composable
internal fun NextTrackPreview(song: Song, modifier: Modifier = Modifier) {
    // The checked in integration script places the real Vinz UI here.
}
