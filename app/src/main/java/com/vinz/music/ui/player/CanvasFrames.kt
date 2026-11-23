package com.vinz.music.ui.player

/**
 * 1.8.7.6 (#528): when a canvas's frames reached the screen and when its loop went round, so a test can see whether the
 * picture stands still at the restart. Off unless a test switches it on ([on]); then each frame costs one clock read
 * and an array write. Main thread only.
 */
internal object CanvasFrames {
    @Volatile var on = false
    private const val FRAMES = 4096
    private const val ROUNDS = 128
    // Made only once a test switches the record on.
    private val frames by lazy { LongArray(FRAMES) }
    private var frameCount = 0
    private val rounds by lazy { LongArray(ROUNDS) }
    private var roundCount = 0

    fun frame(nowNs: Long) { frames[frameCount % FRAMES] = nowNs; frameCount++ }
    fun round(nowNs: Long) { rounds[roundCount % ROUNDS] = nowNs; roundCount++ }

    /** The kept first frame going up over the video (true) and down again, as the view was changed. */
    private val covers = ArrayList<Pair<Long, Boolean>>()
    fun cover(shown: Boolean, nowNs: Long) { if (covers.size < 4096) covers += nowNs to shown }
    /** How many times the kept first frame went up over the video. */
    fun coversUp(): Int = covers.count { it.second }

    /**
     * When what the viewer sees changed: a frame drawn while the kept first frame is not over the video, and the kept
     * frame going up or down. A frame drawn under the kept frame changes nothing on screen.
     */
    fun visibleNs(): LongArray = visibleChanges(framesNs(), covers.toList())

    fun clear() { frameCount = 0; roundCount = 0; covers.clear(); synchronized(wraps) { wraps.clear() } }

    /**
     * What the decoder handed out around each restart, from the player's own thread: the last frame's time in the loop
     * before it went back, the first one after, and how long apart they were released, in ms ("old>new+apart").
     */
    val wraps = ArrayList<String>()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun releases() = object : androidx.media3.exoplayer.video.VideoFrameMetadataListener {
        private var lastPtsUs = Long.MIN_VALUE
        private var lastReleaseNs = 0L
        override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: androidx.media3.common.Format, mediaFormat: android.media.MediaFormat?) {
            // Each time the loop goes back, and any frame released long after the one before it.
            if (lastPtsUs != Long.MIN_VALUE && (presentationTimeUs < lastPtsUs || releaseTimeNs - lastReleaseNs > 80_000_000L)) synchronized(wraps) {
                // Numbers only (milliseconds): no words, so nothing here is text to translate.
                wraps += "${lastPtsUs / 1000.0}>${presentationTimeUs / 1000.0}+${(releaseTimeNs - lastReleaseNs) / 1e6}"
            }
            lastPtsUs = presentationTimeUs; lastReleaseNs = releaseTimeNs
        }
    }

    /** The frames and rounds kept (the latest [FRAMES] and [ROUNDS]), oldest first. */
    fun framesNs(): LongArray = last(frames, frameCount)
    fun roundsNs(): LongArray = last(rounds, roundCount)

    private fun last(ring: LongArray, count: Int): LongArray {
        val n = minOf(count, ring.size)
        return LongArray(n) { ring[(count - n + it) % ring.size] }
    }
}

/**
 * How long the picture stood still at each restart of a loop, against how often frames come inside it: for each round
 * in [roundsNs], the longest gap between two frames that comes within [aroundMs] of it; [typicalMs] is the median gap
 * between frames away from every round, [longestElsewhereMs] the longest of those.
 */
internal data class WrapGaps(val atWrapsMs: List<Double>, val typicalMs: Double, val longestElsewhereMs: Double)

/** [CanvasFrames.visibleNs], given the frames and the kept frame's ups and downs (each with its time), all oldest first. */
internal fun visibleChanges(framesNs: LongArray, covers: List<Pair<Long, Boolean>>): LongArray {
    val out = ArrayList<Long>(framesNs.size + covers.size)
    var c = 0
    var shown = false
    for (f in framesNs) {
        while (c < covers.size && covers[c].first <= f) { if (covers[c].second != shown) out += covers[c].first; shown = covers[c].second; c++ }
        if (!shown) out += f
    }
    while (c < covers.size) { if (covers[c].second != shown) out += covers[c].first; shown = covers[c].second; c++ }
    return out.toLongArray()
}

internal fun wrapGaps(framesNs: LongArray, roundsNs: LongArray, aroundMs: Long = 1_000): WrapGaps {
    val around = aroundMs * 1_000_000
    val atWraps = ArrayList<Double>()
    // Only rounds with frames on both sides of their window are counted: a gap cut off by the record's ends isn't known.
    for (r in roundsNs) {
        if (framesNs.isEmpty() || framesNs.first() > r - around || framesNs.last() < r + around) continue
        var longest = 0L
        for (i in 1 until framesNs.size) {
            val a = framesNs[i - 1]; val b = framesNs[i]
            if (b >= r - around && a <= r + around) longest = maxOf(longest, b - a)
        }
        atWraps += longest / 1e6
    }
    val elsewhere = ArrayList<Long>()
    for (i in 1 until framesNs.size) {
        val a = framesNs[i - 1]; val b = framesNs[i]
        if (roundsNs.none { b >= it - around && a <= it + around }) elsewhere += b - a
    }
    elsewhere.sort()
    val typical = if (elsewhere.isEmpty()) 0.0 else elsewhere[elsewhere.size / 2] / 1e6
    return WrapGaps(atWraps, typical, (elsewhere.lastOrNull() ?: 0L) / 1e6)
}
