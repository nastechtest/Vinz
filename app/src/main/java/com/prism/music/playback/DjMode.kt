package com.prism.music.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * DJ mode's state (switched on in the queue, or by "Play as a DJ set"): while it's on, the playback service scans the
 * songs ahead for their key and tempo and puts them in an order that blends ([DjSequencer]), with Crossfade and beat
 * matching on. It's for the queue it was turned on in: a new queue starts with it off.
 */
class DjMode {
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on
    @Volatile var onNow = false
        private set

    /** How the songs ahead are getting on (null while off). */
    private val _progress = MutableStateFlow<DjProgress?>(null)
    val progress: StateFlow<DjProgress?> = _progress

    /** Bumped as songs' keys and tempos come in (the queue's badges show them). */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    /** Bumped to look at the songs ahead again (after mobile data was allowed for it). */
    private val _rescan = MutableStateFlow(0)
    val rescan: StateFlow<Int> = _rescan

    fun setOn(v: Boolean) {
        onNow = v
        _on.value = v
        if (!v) _progress.value = null
    }

    /**
     * Mobile data allowed for its scans from the queue's "Use mobile data", until Prism closes. The setting ("Measure
     * songs on mobile data") isn't changed by it.
     */
    @Volatile var dataAllowed = false
        private set

    /** Looks at the songs ahead again; [allowData]: mobile data was just allowed for it. */
    fun rescan(allowData: Boolean = false) {
        if (allowData) dataAllowed = true
        _rescan.update { it + 1 }
    }

    internal fun setProgress(p: DjProgress?) { _progress.value = p }
    internal fun bump() = _revision.update { it + 1 }
}

/**
 * The songs DJ mode is looking at: [looked] of [all] done, [keyed] with a key and tempo read, [needNetwork] left
 * because they'd have to be downloaded and that isn't allowed now (mobile data).
 */
data class DjProgress(val looked: Int, val all: Int, val keyed: Int, val needNetwork: Int = 0)

/** What the queue says about DJ mode, honestly: it only claims an order it has. */
object DjStatus {
    /** The line, and whether to offer "Use mobile data" beside it. */
    fun of(p: DjProgress?): Pair<String, Boolean> = when {
        p == null || p.all == 0 -> "DJ mode" to false
        // Songs waiting for Wi-Fi aren't being read: they're said apart (with the chip at once), not counted as done.
        p.looked < p.all -> {
            val reading = "DJ mode: reading keys and tempos… ${p.looked - p.needNetwork} of ${p.all - p.needNetwork}"
            if (p.needNetwork > 0) "$reading, ${p.needNetwork} wait for Wi-Fi" to true else reading to false
        }
        p.needNetwork > 0 && p.keyed < MIN_ORDERED ->
            "DJ mode: keys are read on Wi-Fi (${p.needNetwork} ${if (p.needNetwork == 1) "song" else "songs"} waiting)" to true
        p.keyed < MIN_ORDERED -> "DJ mode: no clear keys to order these by" to false
        p.keyed < p.all ->
            "DJ mode: ${p.keyed} of ${p.all} songs in key order" + (if (p.needNetwork > 0) ", the rest wait for Wi-Fi" else ", the rest stay put") to (p.needNetwork > 0)
        else -> "DJ mode: in key order, beat-matched" to false
    }

    /** The next song plus two to order after it. */
    private const val MIN_ORDERED = 3
}
