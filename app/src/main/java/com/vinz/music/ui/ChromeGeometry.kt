package com.vinz.music.ui

import kotlin.math.roundToInt

/** Platform-free state and bounds for the phone navigation/player morph. */
internal class ChromeScrollAccumulator(travelPx: Float) {
    private val safeTravelPx = travelPx.takeIf { it.isFinite() && it > 0f } ?: 1f
    private var accumulatedPx = 0f

    /** Feed only vertical scroll the child actually consumed. */
    fun consume(consumedY: Float, enabled: Boolean): Float {
        if (!enabled) return reset()
        if (!consumedY.isFinite()) return progress
        accumulatedPx = (accumulatedPx - consumedY).coerceIn(0f, safeTravelPx)
        return progress
    }

    fun reset(): Float { accumulatedPx = 0f; return progress }
    val progress: Float get() = (accumulatedPx / safeTravelPx).coerceIn(0f, 1f)
}

internal data class ChromeRect(val x: Int, val y: Int, val width: Int, val height: Int)
internal data class SplitChromeBounds(val hostHeight: Int, val left: ChromeRect, val player: ChromeRect, val right: ChromeRect)

/** Fitting policy and interpolated bounds for one player plus two tab groups. */
internal data class SplitChromeGeometry(
    val enabled: Boolean,
    val leftTabs: Int = 0,
    val rightTabs: Int = 0,
    /** Fractions keep [bounds]' pixel inputs in one unit system. */
    val sideFootprintFraction: Float = 0f,
    val compactPlayerWidthFraction: Float = 0f,
) {
    /**
     * The three children remain composed at every progress value; only these pixel bounds change.
     *
     * A compact side is deliberately sized for the larger tab group. For an odd tab count the
     * shorter group must keep its tab targets at 48 dp and use the remaining space as group
     * padding; distributing its tabs across the full footprint would make them wider than 48 dp.
     */
    fun bounds(width: Int, playerHeight: Int, tabHeight: Int, compact: Float, gap: Int): SplitChromeBounds {
        val safeWidth = width.coerceAtLeast(0)
        val safePlayerHeight = playerHeight.coerceAtLeast(0)
        val safeTabHeight = tabHeight.coerceAtLeast(0)
        val safeGap = gap.coerceAtLeast(0)
        val t = compact.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        fun mix(a: Int, b: Int) = (a + (b - a) * t).roundToInt()

        // These are pixels: the fit-time dp values are converted by preserving their share of the
        // actual available width, rather than mixing raw dp values into pixel bounds.
        val requestedSide = (safeWidth * sideFootprintFraction).roundToInt().coerceIn(0, safeWidth / 2)
        val requestedPlayer = (safeWidth * compactPlayerWidthFraction).roundToInt().coerceAtLeast(0)
        // A stale geometry during a resize may no longer fit. Prefer a non-overlapping result to
        // negative/overlapping targets; from() will disable the split at the new width.
        val compactPlayerWidth = requestedPlayer.coerceAtMost((safeWidth - requestedSide * 2).coerceAtLeast(0))

        val tabCount = leftTabs + rightTabs
        val expandedLeftWidth = if (tabCount > 0) {
            (safeWidth.toLong() * leftTabs / tabCount).toInt()
        } else 0
        val expandedRightWidth = safeWidth - expandedLeftWidth
        val compactPlayerX = (safeWidth - compactPlayerWidth) / 2
        val compactRightX = safeWidth - requestedSide

        // In the expanded state the player and tab row occupy distinct vertical bands. The compact
        // state shares the tab band's height, relying on the disjoint horizontal footprints above.
        val expandedHost = safePlayerHeight + safeGap + safeTabHeight
        val compactHost = safeTabHeight
        val compactPlayerHeight = safeTabHeight
        val host = mix(expandedHost, compactHost)
        val playerHeightPx = mix(safePlayerHeight, compactPlayerHeight)
        val playerY = mix(0, compactHost - compactPlayerHeight)
        val tabsY = (host - safeTabHeight).coerceAtLeast(0)

        return SplitChromeBounds(
            host,
            ChromeRect(0, tabsY, mix(expandedLeftWidth, requestedSide), safeTabHeight),
            ChromeRect(mix(0, compactPlayerX), playerY, mix(safeWidth, compactPlayerWidth), playerHeightPx),
            ChromeRect(mix(expandedLeftWidth, compactRightX), tabsY, mix(expandedRightWidth, requestedSide), safeTabHeight),
        )
    }

    companion object {
        internal const val MIN_TOUCH_DP = 48f
        private const val COMPACT_PLAYER_DP = 64f
        private const val GAP_DP = 4f
        private const val MAX_TABS = 6
        private const val MAX_SAFE_FONT_SCALE = 1.3f

        fun from(widthDp: Float, fontScale: Float, tabCount: Int): SplitChromeGeometry {
            if (!widthDp.isFinite() || widthDp <= 0f ||
                !fontScale.isFinite() || fontScale <= 0f || fontScale > MAX_SAFE_FONT_SCALE ||
                tabCount !in 2..MAX_TABS
            ) return SplitChromeGeometry(false)

            val left = tabCount / 2
            val right = tabCount - left
            // Equal footprints centre the player for odd counts. The spare unit on the shorter
            // group is explicit group padding, not a wider touch target.
            val sideDp = maxOf(left, right) * MIN_TOUCH_DP
            val requiredDp = sideDp * 2 + COMPACT_PLAYER_DP + GAP_DP * 2
            if (widthDp < requiredDp) return SplitChromeGeometry(false)
            return SplitChromeGeometry(
                enabled = true,
                leftTabs = left,
                rightTabs = right,
                sideFootprintFraction = sideDp / widthDp,
                compactPlayerWidthFraction = COMPACT_PLAYER_DP / widthDp,
            )
        }
    }
}
