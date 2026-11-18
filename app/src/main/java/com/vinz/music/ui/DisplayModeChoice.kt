package com.vinz.music.ui

/** Plain display-mode values make the selection rule testable without Android. */
internal data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/** Request the fastest mode at the current resolution, or 0 to leave the system in control. */
internal fun fastestModeId(modes: List<DisplayModeInfo>, currentId: Int, wanted: Boolean): Int {
    if (!wanted) return 0
    val current = modes.firstOrNull { it.id == currentId } ?: return 0
    val same = modes.filter { it.width == current.width && it.height == current.height }
    val fastest = same.maxByOrNull { it.refreshRate } ?: return 0
    // 59.94 and 60 Hz are practically the same speed. Keep the request stable once granted.
    return if (fastest.refreshRate - same.minOf { it.refreshRate } >= 1f) fastest.id else 0
}

internal fun hasFasterMode(modes: List<DisplayModeInfo>, currentId: Int): Boolean =
    fastestModeId(modes, currentId, wanted = true) != 0
