package com.vinz.music.data.lyrics

import com.vinz.music.data.lyrics.roman.JapaneseKanji
import com.vinz.music.data.lyrics.roman.Script
import com.vinz.music.data.model.Song
import java.text.Normalizer
import kotlin.math.abs

/**
 * Whether a lyric source's hit is the song playing (6.1, #301's kind, PR #494 by NeeL21YT): its title, its artist and
 * its length, the one check every source that says those uses. Before, most sources checked the length only (±8 s),
 * so another song of the same length by anyone passed.
 *
 * Lenient where the same song is written another way: case, accents, punctuation, "(feat. …)", "(Remastered)" and
 * " - Live" tails, full-width letters, Chinese and Japanese forms of a kanji. A title in another script (romanized or
 * translated: "Yoru ni Kakeru" for 夜に駆ける) can't be compared: it passes when the artist is the song's and the length
 * isn't off. A different title in the same script is refused, and so is another artist.
 */
object SongMatch {
    /** How far (s) a source's edition may differ in length from the song playing. */
    const val MAX_LENGTH_GAP_SEC = 8.0
    /** A title in another script and an artist that can't be compared either: only this close in length. */
    private const val BLIND_LENGTH_GAP_SEC = 2.0

    enum class Verdict { MATCH, ABSENT, OTHER_SCRIPT, MISMATCH }

    private val bracketed = Regex("[(\\[（【「『〔][^)\\]）】」』〕]*[)\\]）】」』〕]")
    private val featTail = Regex("\\s+(?:feat|ft|featuring|with|prod)\\.?\\s.*$", RegexOption.IGNORE_CASE)
    private val dashTail = Regex("\\s+[-–—~|]\\s+.*$")
    private val notWord = Regex("[^\\p{L}\\p{N}]+")
    private val marks = Regex("\\p{M}+")
    private val artistSplit = Regex("\\s*(?:,|&|、|/|;|\\+|\\bx\\b|\\band\\b|\\bfeat\\.?|\\bft\\.?|\\bwith\\b)\\s*", RegexOption.IGNORE_CASE)

    /**
     * [s] for comparing: one case, no accents, marks or punctuation, full-width letters as ASCII, traditional Chinese
     * characters as simplified ones ([HanForms]) and those as Japanese kanji where Japanese writes them otherwise.
     */
    fun key(s: String): String {
        val folded = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val plain = Normalizer.normalize(folded, Normalizer.Form.NFD).replace(marks, "")
        return JapaneseKanji.fromChinese(HanForms.simplified(Normalizer.normalize(plain, Normalizer.Form.NFC))).replace(notWord, "")
    }

    /** The title without what editions and guests add: brackets, "feat. …", " - Remastered 2011". */
    fun bareTitle(s: String): String = s.replace(bracketed, " ").replace(featTail, "").replace(dashTail, "").trim()

    private fun scripts(s: String) = Script.foreignIn(s)

    private val quoted = Regex("[\"“”„«»]([^\"“”„«»]{3,})[\"“”„«»]")

    /**
     * The ways a title may be written shorter: whole, bare ([bareTitle]), and for a classical work the piece itself
     * ("Turandot, Act 3: "Nessun dorma" (Live)" is "Nessun dorma" elsewhere): what's quoted, or after the last colon.
     */
    private fun forms(t: String): List<String> {
        val bare = bareTitle(t)
        // Outside brackets only: "(from DreamWorks Animation's "TROLLS")" doesn't name the song.
        val open = t.replace(bracketed, " ")
        val piece = listOfNotNull(quoted.find(open)?.groupValues?.get(1), open.substringAfterLast(": ", "").takeIf { it.isNotBlank() })
        return (listOf(t, bare) + piece).map(::key).filter { it.isNotEmpty() }.distinct()
    }

    /** The title [found] against the song's [wanted] one. */
    fun title(found: String?, wanted: String): Verdict {
        if (found.isNullOrBlank() || wanted.isBlank()) return Verdict.ABSENT
        val f = forms(found)
        val w = forms(wanted)
        if (f.isEmpty() || w.isEmpty()) return Verdict.ABSENT
        if (f.any { it in w }) return Verdict.MATCH
        if (scripts(bareTitle(found)) != scripts(bareTitle(wanted))) return Verdict.OTHER_SCRIPT
        for (a in f) for (b in w) if (close(a, b)) return Verdict.MATCH
        return Verdict.MISMATCH
    }

    /** Two keys of one title: nearly the same letters, or one is most of the other ("thriller" / "thriller25"). */
    private fun close(a: String, b: String): Boolean {
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (short.length >= 3 && long.contains(short) && short.length >= long.length * 0.6) return true
        // Chinese characters in their other form (simplified / traditional) beside the same ones.
        if (Script.HAN in scripts(a) && Script.HAN in scripts(b) && abs(a.length - b.length) <= 1) {
            val pool = long.groupingBy { it }.eachCount().toMutableMap()
            var shared = 0
            for (ch in short) { val n = pool[ch] ?: 0; if (n > 0) { shared++; pool[ch] = n - 1 } }
            if (shared >= long.length * 0.5) return true
        }
        return long.length >= 5 && 1.0 - editDistance(a, b).toDouble() / long.length >= 0.8
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** Each name in an artist line ("A & B", "A、B", "A feat. B"), and the whole line. */
    fun names(line: String): List<String> = (listOf(line) + line.split(artistSplit)).map { it.trim() }.filter { it.isNotEmpty() }

    /** The artists a source gives ([found], each may be a joined line) against the song's. */
    fun artist(found: List<String>, song: Song): Verdict {
        val theirs = found.flatMap(::names).filter { key(it).isNotEmpty() }
        val ours = (song.artists.map { it.name } + song.primaryArtist).flatMap(::names).filter { key(it).isNotEmpty() }
        if (theirs.isEmpty() || ours.isEmpty()) return Verdict.ABSENT
        val comparable = theirs.any { t -> ours.any { o -> scripts(t) == scripts(o) } }
        for (t in theirs) for (o in ours) {
            val a = key(t)
            val b = key(o)
            if (a == b) return Verdict.MATCH
            val (short, long) = if (a.length <= b.length) a to b else b to a
            if (short.length >= 3 && long.contains(short)) return Verdict.MATCH
        }
        return if (comparable) Verdict.MISMATCH else Verdict.OTHER_SCRIPT
    }

    /** A length of [sec] seconds against the song's. */
    fun length(song: Song, sec: Double?): Verdict = when {
        song.durationSec <= 0 || sec == null || sec <= 0 -> Verdict.ABSENT
        abs(sec - song.durationSec) <= MAX_LENGTH_GAP_SEC -> Verdict.MATCH
        else -> Verdict.MISMATCH
    }

    /**
     * Whether a hit titled [title] by [artists], [sec] seconds long, is [song]. What the source doesn't say isn't held
     * against it; what it says has to fit (see the class).
     */
    fun fits(song: Song, title: String?, artists: List<String>, sec: Double?): Boolean {
        val t = title(title, song.title)
        val a = artist(artists, song)
        val l = length(song, sec)
        if (t == Verdict.MISMATCH || a == Verdict.MISMATCH || l == Verdict.MISMATCH) return false
        return when (t) {
            Verdict.MATCH, Verdict.ABSENT -> true
            // Romanized or translated: the artist has to be the song's, or the length all but the same.
            else -> a == Verdict.MATCH || (l == Verdict.MATCH && abs(sec!! - song.durationSec) <= BLIND_LENGTH_GAP_SEC)
        }
    }

    fun fits(song: Song, title: String?, artist: String?, sec: Double?): Boolean =
        fits(song, title, listOfNotNull(artist?.takeIf { it.isNotBlank() }), sec)

    /**
     * Untimed lyrics under the song's own title by someone else (a cover's, as Bini and LyricsPlus file "Pretender" by
     * Tokyo Arrow for Official Hige Dandism's): the words are the song's, and without timings the other recording
     * doesn't matter. Taken only when nothing [fits], and only with no length that's off.
     */
    fun sameWordsUntimed(song: Song, title: String?, sec: Double?): Boolean =
        title(title, song.title) == Verdict.MATCH && length(song, sec) != Verdict.MISMATCH

    /**
     * How much of word-timed lyrics [words] the line-timed [reference] of the song has: the share of its lines found
     * there, nearly word for word. Null when they can't be compared (too few lines, or written in other scripts: a
     * romanized copy of the same song).
     */
    fun linesInCommon(words: List<LyricLine>, reference: List<LyricLine>): Double? {
        val mine = words.filter { !it.isGap }.map { key(it.text) }.filter { it.length >= 2 }
        val theirs = reference.filter { !it.isGap }.map { key(it.text) }.filter { it.length >= 2 }
        if (mine.size < MIN_LINES || theirs.size < MIN_LINES) return null
        fun scriptsOf(l: List<LyricLine>) = l.filter { !it.isGap }.flatMapTo(HashSet()) { scripts(it.text) }
        if (scriptsOf(words) != scriptsOf(reference)) return null
        val refBigrams = theirs.map(::bigrams)
        // Sources break lines in different places: a line may be two of the other's run together, or half of one.
        val refLong = theirs.filter { it.length >= 4 }
        val found = mine.count { m ->
            val b = bigrams(m)
            refBigrams.any { dice(b, it) >= SAME_LINE } || (m.length >= 4 && theirs.any { it.contains(m) }) || refLong.any { m.contains(it) }
        }
        return found.toDouble() / mine.size
    }

    /** Word lyrics sharing less than this share of their lines with the song's line-timed lyrics are another song's. */
    const val MIN_LINES_IN_COMMON = 0.4
    private const val MIN_LINES = 4
    private const val SAME_LINE = 0.6

    private fun bigrams(s: String): Map<String, Int> =
        if (s.length < 2) mapOf(s to 1) else s.windowed(2).groupingBy { it }.eachCount()

    private fun dice(a: Map<String, Int>, b: Map<String, Int>): Double {
        val shared = a.entries.sumOf { (k, n) -> minOf(n, b[k] ?: 0) }
        return 2.0 * shared / (a.values.sum() + b.values.sum()).coerceAtLeast(1)
    }
}
