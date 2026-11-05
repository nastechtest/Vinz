package com.vinz.music.playback

import androidx.media3.common.C
import androidx.media3.common.Timeline

/**
 * Preview natural playback, not the skip-button target: repeat-one previews the current
 * track and repeat-all wraps in the player's actual shuffle order. No queue arithmetic.
 */
internal fun nextPreviewWindow(timeline: Timeline, current: Int, repeatMode: Int, shuffle: Boolean): Int {
    if (timeline.isEmpty || current !in 0 until timeline.windowCount) return C.INDEX_UNSET
    return timeline.getNextWindowIndex(current, repeatMode, shuffle)
}
