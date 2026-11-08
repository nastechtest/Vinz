package com.vinz.music.data.lyrics

/** A selected credit within one verified gap. All intervals are half-open, [startMs, endMs). */
data class BrandCreditSlot internal constructor(
    val gapStartMs: Long,
    val gapEndMs: Long,
    val startMs: Long,
    val endMs: Long,
) {
    fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs
}

/**
 * Pure, immutable, recording-scoped credit timeline. It never controls a player or starts a timer.
 *
 * Rebuild when song/recording identity, lyrics or their evidence changes. Query using the existing
 * offset-adjusted lyric clock: pauses/buffering freeze it and seeks simply select another position.
 * Replaying a selected interval revisits that interval, not a fourth scheduled appearance.
 *
 * A gap is eligible only with explicit verified provenance and no foreground/background-vocal
 * overlap anywhere in the gap, including its entry/exit margins. Malformed vocal timing invalidates
 * the whole schedule because absence of vocals cannot then be established. Unknown/inferred gaps
 * remain useful for ordinary lyric presentation but can never schedule branding.
 */
class BrandCreditSchedule private constructor(
    val songId: String,
    val slots: List<BrandCreditSlot>,
) {
    /** End-exclusive, with no exit-animation grace period that could cover returning lyrics. */
    fun activeAt(songId: String, positionMs: Long): BrandCreditSlot? =
        if (songId != this.songId || positionMs < 0) null else slots.firstOrNull { it.contains(positionMs) }

    companion object {
        const val MAX_CREDITS = 3
        const val MIN_GAP_MS = 6_000L
        const val BOUNDARY_MARGIN_MS = 500L
        const val CREDIT_DURATION_MS = 4_000L

        fun create(songId: String, lines: List<LyricLine>): BrandCreditSchedule {
            if (songId.isBlank()) return BrandCreditSchedule(songId, emptyList())

            // Snapshot primitive intervals, not caller-owned mutable line/word collections.
            val vocals = mutableListOf<Pair<Long, Long>>()
            for (line in lines) {
                if (!line.isGap || line.text.isNotBlank()) {
                    if (!valid(line.timeMs, line.endMs)) return BrandCreditSchedule(songId, emptyList())
                    vocals += line.timeMs to line.endMs
                }
                for (word in line.words + line.background) {
                    if (!valid(word.startMs, word.endMs)) return BrandCreditSchedule(songId, emptyList())
                    vocals += word.startMs to word.endMs
                }
            }

            val candidates = lines.asSequence()
                .filter { it.isGap && it.gapProvenance == GapProvenance.VERIFIED_INSTRUMENTAL }
                .filter { it.text.isBlank() && it.words.isEmpty() && it.background.isEmpty() }
                .filter { valid(it.timeMs, it.endMs) && it.endMs - it.timeMs >= MIN_GAP_MS }
                .filter { gap -> vocals.none { (start, end) -> overlaps(gap.timeMs, gap.endMs, start, end) } }
                .map { it.timeMs to it.endMs }
                .distinct()
                .sortedWith(compareByDescending<Pair<Long, Long>> { it.second - it.first }
                    .thenBy { it.first }.thenBy { it.second })
                .toList()

            // Prefer longer verified breaks; stable chronological ties avoid source-order dependence.
            // Overlapping duplicate/competing gap annotations cannot produce multiple appearances.
            val selected = mutableListOf<Pair<Long, Long>>()
            for (candidate in candidates) {
                if (selected.none { overlaps(candidate.first, candidate.second, it.first, it.second) }) {
                    selected += candidate
                    if (selected.size == MAX_CREDITS) break
                }
            }
            val slots = selected.sortedBy { it.first }.map { (start, end) ->
                // All arithmetic stays inside the validated non-negative gap, including near Long.MAX_VALUE.
                val spare = end - start - 2 * BOUNDARY_MARGIN_MS - CREDIT_DURATION_MS
                val creditStart = start + BOUNDARY_MARGIN_MS + spare / 2
                BrandCreditSlot(start, end, creditStart, creditStart + CREDIT_DURATION_MS)
            }
            return BrandCreditSchedule(songId, java.util.Collections.unmodifiableList(slots))
        }

        private fun valid(start: Long, end: Long): Boolean = start >= 0 && end > start

        private fun overlaps(start: Long, end: Long, otherStart: Long, otherEnd: Long): Boolean =
            start < otherEnd && otherStart < end
    }
}
