package com.vinz.music.playback

/**
 * A cast speaker's volume (0–100) as the phone's volume keys and Android's volume panel see it (#534, 1.8.7.6): in
 * [STEPS] steps, one per key press. Before, the session said 100 steps and a press moved 4 of them: Android's panel
 * showed one step up, then jumped to where the speaker really was, and a held key ran to the end.
 */
object RemoteVolume {
    const val STEPS = 25
    private const val PER_STEP = 100 / STEPS
    /** A held key's repeats come every 50 ms; one step is taken per this long, about 2.5 s from silent to full. */
    const val HELD_GAP_MS = 100L

    /** The step shown for a speaker at [level] (0–100). */
    fun shown(level: Int): Int = (level.coerceIn(0, 100) + PER_STEP / 2) / PER_STEP

    /** The speaker's level (0–100) for the step [shown]. */
    fun level(shown: Int): Int = shown.coerceIn(0, STEPS) * PER_STEP

    /** The level one key press up ([by] 1) or down (-1) from [level]: the next step, so the panel and speaker agree. */
    fun stepped(level: Int, by: Int): Int = level(shown(level) + by)

    /** Whether a volume key event is taken: every first press, and a held key's repeats [HELD_GAP_MS] apart. */
    fun takesKey(repeat: Int, now: Long, lastAt: Long): Boolean = repeat == 0 || now - lastAt >= HELD_GAP_MS
}
