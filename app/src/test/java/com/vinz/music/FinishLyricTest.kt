package com.vinz.music

import com.vinz.music.data.FinishLyric
import com.vinz.music.data.FinishLyric.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** "Finish the lyric" (1.8.5, plan §3.8): which lines can be asked, the choices, the round. Lyrics are made up. */
class FinishLyricTest {

    private fun song(n: Int, lines: List<String>) = FinishLyric.SongLyrics("id$n", "Song $n", "Artist $n", null, lines)

    /** Made-up songs: each line ends in its own word, so every song has plenty to ask. */
    private fun songs(count: Int, linesEach: Int = 6) = (1..count).map { n ->
        song(n, (1..linesEach).map { l -> "we walk along the road number $n$l toward word${n}x$l" })
    }

    @Test fun theLastWordIsTheEnding() {
        val b = FinishLyric.blank("I keep on walking down the open road.")!!
        assertEquals("I keep on walking down the open", b.prompt)
        assertEquals("road", b.ending)
        assertEquals(Mode.WORD, b.mode)
        // Quotes and closing punctuation go; an apostrophe inside the word stays.
        assertEquals("home", FinishLyric.blank("and she said take me \"home!\"")!!.ending)
        assertEquals("can't", FinishLyric.blank("all the things that I can't,")!!.ending)
        // Background vocals at the end aren't the ending.
        assertEquals("tonight", FinishLyric.blank("we are dancing all tonight (tonight)")!!.ending)
    }

    @Test fun shortLinesMarkersAndJunkAreNeverAsked() {
        for (line in listOf(
            "", "   ", "Oh oh oh", "Yeah", "Na na",
            "[Chorus]", "(Instrumental)", "[Instrumental break here now]", "♪ ♪ ♪", "♪ la la la la la ♪",
            "Instrumental", "Chorus:", "Verse 1:",
            "Written by: Some One And Another", "Produced by Some Body Else here", "作词 : 某某某某", "作曲：某某某",
            "123 ContributorsSong Name Lyrics", "You might also likeEmbed",
            "I don't give a f**k about it", "this is so ***** right now",
            "count them one two three 4",
            "x".repeat(120) + " a b c",
        )) assertNull("asked: '$line'", FinishLyric.blank(line))
    }

    @Test fun repeatedChorusIsAskedOnce() {
        val chorus = "We will be together till the end"
        val lines = listOf(chorus, "Here comes another line of song", "WE WILL be together, till the END!", chorus, "And one more line to go")
        val u = FinishLyric.usable(lines)
        assertEquals(3, u.size)
        assertEquals(1, u.count { it.ending.equals("end", ignoreCase = true) })
    }

    @Test fun aStartSungWithTwoEndingsIsNeverAsked() {
        val u = FinishLyric.usable(listOf("I want you to stay", "I want you to go", "Another line that is fine"))
        assertEquals(listOf("fine"), u.map { it.ending })
    }

    @Test fun chineseAndJapaneseLoseTheirLastCharacters() {
        val zh = FinishLyric.blank("我们一起走过的日子")!!
        assertEquals(Mode.CHARS, zh.mode)
        assertEquals("日子", zh.ending)
        assertEquals("我们一起走过的", zh.prompt)
        val ja = FinishLyric.blank("君の声が聞こえる。")!!
        assertEquals("える", ja.ending)
        assertEquals("CJK", ja.script)
        // Too short to guess from.
        assertNull(FinishLyric.blank("我爱你"))
    }

    @Test fun otherScriptsGoByWords() {
        val ko = FinishLyric.blank("너와 함께 걷던 그 길을 기억해")!!
        assertEquals("기억해", ko.ending)
        assertEquals("HANGUL", ko.script)
        // Hindi: the vowel sign at the end stays with its word.
        val hi = FinishLyric.blank("तुम ही हो अब तुम ही है")!!
        assertEquals("है", hi.ending)
        // Thai without spaces between words: one long phrase is no word.
        assertNull(FinishLyric.blank("ฉันรักเธอมากกว่าใครในโลกนี้ทั้งหมด"))
    }

    @Test fun rightToLeftLinesKeepTheirLastWordInReadingOrder() {
        val ar = FinishLyric.blank("أنا أحبك أكثر من كل الدنيا")!!
        assertEquals("الدنيا", ar.ending)
        assertEquals("أنا أحبك أكثر من كل", ar.prompt)
        assertEquals("ARABIC", ar.script)
        assertTrue(FinishLyric.isRtl(ar.prompt))
        val he = FinishLyric.blank("אני הולך לאט לאט הביתה")!!
        assertEquals("הביתה", he.ending)
        assertTrue(FinishLyric.isRtl(he.prompt))
        assertFalse(FinishLyric.isRtl("I walk home"))
    }

    @Test fun choicesNeverRepeatAndAlwaysHoldTheAnswer() {
        // Endings that differ only by case or accents count as the same.
        val tricky = song(99, listOf(
            "we walk along the road to Cafe", "we walk along the road to café", "we walk along the road to CAFE!",
            "we ran along the street to cafe", "here is something else for later",
        ))
        val all = songs(6) + tricky
        repeat(50) { seed ->
            val round = FinishLyric.round(all, Random(seed))
            assertEquals(FinishLyric.ROUND, round.size)
            assertEquals(FinishLyric.ROUND, round.map { it.song.songId }.toSet().size)
            for (q in round) {
                assertEquals(FinishLyric.CHOICES, q.choices.size)
                assertEquals(q.choices.size, q.choices.map { FinishLyric.norm(it) }.toSet().size)
                assertTrue(q.answerIndex in q.choices.indices)
                assertEquals(q.answer, q.choices[q.answerIndex])
            }
        }
    }

    @Test fun choicesStayInTheAnswersScript() {
        val latin = songs(5)
        val arabic = (1..2).map { n ->
            song(50 + n, listOf("أنا أحبك أكثر من كل الدنيا", "يا قلبي لا تنسى هذا الليل", "نمشي معا في طريق القمر", "وأغني لك كل يوم أغنية"))
        }
        assertEquals(7, FinishLyric.playableSongs(latin + arabic))
        var arabicAsked = false
        repeat(30) { seed ->
            for (q in FinishLyric.round(latin + arabic, Random(seed))) {
                val s = FinishLyric.scriptOf(q.answer)
                assertTrue(q.choices.toString(), q.choices.all { FinishLyric.scriptOf(it) == s })
                if (s == "ARABIC") arabicAsked = true
            }
        }
        assertTrue(arabicAsked)
    }

    @Test fun theSameSeedGivesTheSameRound() {
        val all = songs(8)
        assertEquals(FinishLyric.round(all, Random(42)), FinishLyric.round(all, Random(42)))
        assertFalse((0..10).map { FinishLyric.round(all, Random(it)) }.toSet().size == 1)
    }

    @Test fun tooFewSongsMeansNoGame() {
        assertFalse(FinishLyric.canPlay(songs(FinishLyric.ROUND - 1)))
        assertTrue(FinishLyric.round(songs(FinishLyric.ROUND - 1), Random(1)).isEmpty())
        assertTrue(FinishLyric.canPlay(songs(FinishLyric.ROUND)))
        // Songs whose lines are all junk don't count.
        val junk = (1..6).map { song(100 + it, listOf("[Chorus]", "Oh oh", "(Instrumental)")) }
        assertFalse(FinishLyric.canPlay(songs(FinishLyric.ROUND - 1) + junk))
        // Not enough other endings to choose from: no question either.
        assertFalse(FinishLyric.canPlay((1..6).map { song(it, listOf("the very same line in every song")) }))
    }

    @Test fun theScoreCountsRightPicks() {
        val round = FinishLyric.round(songs(6), Random(3))
        val allRight = round.map { it.answerIndex }
        assertEquals(round.size, FinishLyric.score(round, allRight))
        val oneWrong = allRight.toMutableList<Int?>().also { it[0] = (it[0]!! + 1) % FinishLyric.CHOICES; it[1] = null }
        assertEquals(round.size - 2, FinishLyric.score(round, oneWrong))
        assertNotNull(round.first().prompt)
    }
}
