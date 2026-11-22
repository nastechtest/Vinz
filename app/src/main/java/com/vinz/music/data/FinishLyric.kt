package com.vinz.music.data

import com.vinz.music.data.lyrics.Uncensor
import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

/**
 * "Finish the lyric" (1.8.5, plan §3.8): a small game on Replay. A line from one of the listener's most played songs
 * shows with its last word missing (with Chinese or Japanese, its last two characters), and four endings to pick from:
 * the real one and endings of other lines, from the same song first, then from the other songs. A round is [ROUND]
 * questions, each from a different song.
 *
 * Only lyrics already saved on the phone go in (the screen loads them with no lyric service asked); this part is the
 * pure game: which lines can be asked, the choices and the round, all from a seeded [Random] so a round can be rebuilt
 * the same after the screen turns.
 */
object FinishLyric {
    /** Questions in a round, each from its own song: fewer songs than this with lines that can be asked, no game. */
    const val ROUND = 5
    /** Choices per question: the right ending and three others. */
    const val CHOICES = 4
    /** Fewest words a line needs (in languages written with spaces), so there's something to go on. */
    const val MIN_WORDS = 4
    /** Fewest letters a line needs in Chinese or Japanese, where the ending is its last [CJK_ENDING] characters. */
    const val MIN_CJK_LETTERS = 6
    const val CJK_ENDING = 2
    /** Longer lines are run-ons (a whole verse on one line) or not lyrics at all. */
    const val MAX_LINE = 100
    /** A "word" longer than this is a phrase in a script written without spaces (Thai), not a word. */
    const val MAX_WORD = 14

    /** One played song with its saved lyrics, as plain lines. */
    data class SongLyrics(
        val songId: String,
        val title: String,
        val artist: String,
        val thumbnail: String?,
        val lines: List<String>,
    )

    /** How a line's ending was cut: the last word, or (no spaces between words) the last characters. */
    enum class Mode { WORD, CHARS }

    /** A line that can be asked: [prompt] shown, [ending] to find. */
    data class Blank(val prompt: String, val ending: String, val mode: Mode, val script: String) {
        /** The ending as compared: no case, accents or punctuation. */
        val key: String = norm(ending)
    }

    data class Question(val song: SongLyrics, val prompt: String, val answer: String, val choices: List<String>) {
        val answerIndex: Int get() = choices.indexOf(answer)
    }

    private val censoredWord = Regex("[*#]{2,}")
    private val notes = Regex("[♪♫♬♩🎵🎶]")
    private val bracketed = Regex("^[\\[(（【{<].*[\\])）】}>]$")
    private val trailingAside = Regex("\\s*[(（][^()（）]*[)）]\\s*$")
    private val instrumental = Regex(
        "^\\W*(instrumental|interlude|intro|outro|solo|guitar solo|music|musica|música|musique|break|instrumental break|间奏|間奏|前奏|尾奏)\\W*$",
        RegexOption.IGNORE_CASE,
    )
    /** Credit lines some sources put on top ("作词 : …", "Written by: …", "Produced by …"). */
    private val credit = Regex(
        "^\\s*(written|lyrics|lyricist|words|music|composer|composed|producer|produced|arranged|arranger|mixed|mastered|vocals)" +
            "(\\s+by)?\\s*[:：]|^\\s*(written|lyrics|music|composed|produced|arranged|mixed|mastered)\\s+by\\b",
        RegexOption.IGNORE_CASE,
    )
    private val cjkCredit = Regex("^\\s*(作词|作詞|作曲|编曲|編曲|制作|製作|监制|監製|混音|母带|和声|吉他|贝斯|鼓|演唱|原唱|出品|词|詞|曲|작사|작곡|편곡)[^:：]{0,12}[:：]")
    /** Leftovers of web pages in plain lyrics (Genius): "123 Contributors", "…Embed", "You might also like". */
    private val pageJunk = Regex("\\d+\\s*Contributors|Embed$|^You might also like|^See .* Live$|Lyrics$", RegexOption.IGNORE_CASE)

    /** A word or ending as compared: lower case, no accents or marks, letters and digits only. */
    fun norm(s: String): String = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").filter { it.isLetterOrDigit() }

    /** A whole line as compared (for repeats): as [norm], with single spaces between the words kept. */
    fun normLine(s: String): String = s.split(Regex("\\s+")).map(::norm).filter { it.isNotEmpty() }.joinToString(" ")

    private fun isMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    /** Part of a word: a letter, a digit, or a mark on a letter (Hindi's vowel signs, Arabic's short vowels). */
    private fun wordy(cp: Int): Boolean = Character.isLetterOrDigit(cp) || isMark(cp)

    private fun isCjk(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
        else -> false
    }

    /** The script a word is written in ("LATIN", "ARABIC", "CJK"…), so choices are never in another one. */
    fun scriptOf(word: String): String {
        val cp = word.codePoints().filter { Character.isLetter(it) }.findFirst().orElse(-1)
        if (cp < 0) return "NONE"
        return if (isCjk(cp)) "CJK" else Character.UnicodeScript.of(cp).name
    }

    /** Whether [text] reads right to left (its first strong letter is Hebrew, Arabic and the like). */
    fun isRtl(text: String): Boolean {
        for (ch in text) when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
        return false
    }

    /** [line] as a question, or null when it can't fairly be one (too short, a marker, a credit, starred out…). */
    fun blank(line: String): Blank? {
        var t = line.trim()
        if (t.isEmpty() || t.length > MAX_LINE) return null
        if (notes.containsMatchIn(t) || bracketed.matches(t) || instrumental.matches(t)) return null
        if (t.endsWith(":") || t.endsWith("：") || credit.containsMatchIn(t) || cjkCredit.containsMatchIn(t) || pageJunk.containsMatchIn(t)) return null
        if (censoredWord.containsMatchIn(t) || Uncensor.isStarred(t)) return null
        // Background vocals at the end ("…tonight (tonight)") aren't the line's ending.
        t = t.replace(trailingAside, "").trim()
        if (t.isEmpty()) return null
        // The end of the line without its closing punctuation.
        var end = t.length
        while (end > 0 && !wordy(t.codePointBefore(end))) end -= Character.charCount(t.codePointBefore(end))
        if (end == 0) return null
        val body = t.substring(0, end)
        val lastCp = body.codePointBefore(body.length)
        if (isCjk(lastCp)) {
            // Chinese and Japanese have no spaces between words: the last characters, when they're all CJK letters.
            if (body.codePoints().filter { Character.isLetter(it) }.count() < MIN_CJK_LETTERS) return null
            var cut = body.length
            repeat(CJK_ENDING) {
                if (cut == 0) return null
                val cp = body.codePointBefore(cut)
                if (!isCjk(cp) || !Character.isLetter(cp)) return null
                cut -= Character.charCount(cp)
            }
            val prompt = body.substring(0, cut).trimEnd()
            if (prompt.none { it.isLetter() }) return null
            return Blank(prompt, body.substring(cut), Mode.CHARS, "CJK")
        }
        val words = body.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < MIN_WORDS) return null
        // The last word without the punctuation around it ("\"home" → "home"); apostrophes inside stay ("lovin'" keeps
        // its own end, which went with the closing punctuation, so it's "lovin").
        val raw = words.last()
        var s = 0
        while (s < raw.length && !Character.isLetterOrDigit(raw.codePointAt(s))) s += Character.charCount(raw.codePointAt(s))
        val ending = raw.substring(s)
        // Two letters at least (a vowel sign in Hindi or a mark in Arabic counts with its letter); not a whole phrase.
        if (ending.codePoints().filter { Character.isLetter(it) || isMark(it) }.count() < 2 || ending.none { it.isLetter() }) return null
        if (ending.codePointCount(0, ending.length) > MAX_WORD) return null
        val prompt = body.substring(0, body.length - ending.length).trimEnd()
        if (prompt.count { it.isLetter() } < 2) return null
        return Blank(prompt, ending, Mode.WORD, scriptOf(ending))
    }

    /**
     * The lines of one song that can be asked: each line once however often it's sung (a chorus is asked once), and
     * never a line whose start is sung with another ending elsewhere in the song (both endings would be right).
     */
    fun usable(lines: List<String>): List<Blank> {
        val all = lines.mapNotNull { l -> blank(l)?.let { normLine(l.trim()) to it } }
        val endingsByPrompt = all.groupBy({ normLine(it.second.prompt) }, { it.second.key })
        val seen = HashSet<String>()
        return all.filter { (lineKey, b) ->
            endingsByPrompt[normLine(b.prompt)].orEmpty().toSet().size == 1 && b.key.isNotEmpty() && seen.add(lineKey)
        }.map { it.second }
    }

    private fun group(b: Blank) = "${b.mode}|${b.script}"

    private class Pool(songs: List<SongLyrics>) {
        val blanks: List<Pair<SongLyrics, List<Blank>>> = songs.map { it to usable(it.lines) }
        /** Every distinct ending by script and kind, with the songs it comes from, in a fixed order. */
        val endings: Map<String, List<Pair<String, Blank>>> = LinkedHashMap<String, MutableList<Pair<String, Blank>>>().also { m ->
            val seen = HashSet<String>()
            for ((song, bs) in blanks) for (b in bs) if (seen.add(group(b) + "|" + b.key)) m.getOrPut(group(b)) { mutableListOf() }.add(song.songId to b)
        }

        /** The other endings [b] could be given, the same song's first, in [random] order within each; distinct. */
        fun others(song: SongLyrics, b: Blank, random: Random): List<String> {
            // [endings] holds each ending once already, so these differ from each other and from the answer.
            val same = endings[group(b)].orEmpty().filter { it.second.key != b.key }
            val own = same.filter { it.first == song.songId }.map { it.second.ending }
            val rest = same.filter { it.first != song.songId }.map { it.second.ending }
            return own.shuffled(random) + rest.shuffled(random)
        }

        /** [bs] that have enough other endings in their script to choose from (theirs is in the list once too). */
        fun askable(bs: List<Blank>) = bs.filter { endings[group(it)].orEmpty().size - 1 >= CHOICES - 1 }
    }

    /** How many of [songs] have a line that can be asked with enough other endings to choose from. */
    fun playableSongs(songs: List<SongLyrics>): Int = Pool(songs).let { p -> p.blanks.count { (_, bs) -> p.askable(bs).isNotEmpty() } }

    /** Whether there's enough for a whole round (the card and the game show only then). */
    fun canPlay(songs: List<SongLyrics>): Boolean = playableSongs(songs) >= ROUND

    /**
     * A round: [size] questions from as many different songs, picked and shuffled with [random]. Empty when there
     * aren't enough songs ([canPlay]). The same [random] seed gives the same round.
     */
    fun round(songs: List<SongLyrics>, random: Random, size: Int = ROUND): List<Question> {
        val pool = Pool(songs)
        val playable = pool.blanks.mapNotNull { (s, bs) -> pool.askable(bs).takeIf { it.isNotEmpty() }?.let { s to it } }
        if (playable.size < size) return emptyList()
        return playable.shuffled(random).take(size).map { (song, bs) ->
            val b = bs[random.nextInt(bs.size)]
            val choices = (pool.others(song, b, random).take(CHOICES - 1) + b.ending).shuffled(random)
            Question(song, b.prompt, b.ending, choices)
        }
    }

    /** Right answers in [picks] (the chosen choice per question; null for none). */
    fun score(questions: List<Question>, picks: List<Int?>): Int =
        questions.indices.count { i -> picks.getOrNull(i) != null && picks[i] == questions[i].answerIndex }
}
