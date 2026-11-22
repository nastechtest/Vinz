package com.vinz.music.playback

/**
 * For the effects that keep their state across a gapless change of song (plan item 10): the player plays the chain out
 * to the last song's end ([mark]) and flushes it for the next one at once. Only that flush is the sound going on
 * ([goesOn]); one that comes later (the queue ended and something else is played after a while) starts afresh, so the
 * end of one sitting never sounds into the start of the next.
 */
class PlayedOut(private val now: () -> Long = System::nanoTime, private val withinNanos: Long = 2_000_000_000L) {
    private var marked = false
    private var at = 0L

    fun mark() { marked = true; at = now() }

    fun goesOn(): Boolean = marked && now() - at < withinNanos

    fun clear() { marked = false }
}