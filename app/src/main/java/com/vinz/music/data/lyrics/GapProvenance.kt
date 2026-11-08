package com.vinz.music.data.lyrics

/**
 * Where a gap row came from, not how accurate its timestamps happen to look.
 *
 * Neither empty LRC rows nor space between timed words proves that vocals are absent. Existing
 * parsers must never promote those rows to [VERIFIED_INSTRUMENTAL]. That value is reserved for an
 * explicitly verified instrumental annotation tied to the same recording/timeline. No current
 * provider supplies that evidence; until one does, production lyrics correctly yield no credits.
 * Optional audio analysis may support an annotation, but must not manufacture trust from silence
 * estimates alone or require changes to playback.
 */
enum class GapProvenance {
    UNKNOWN,
    LRC_BLANK,
    INFERRED_INTRO,
    INFERRED_TIMING,
    VERIFIED_INSTRUMENTAL,
}
