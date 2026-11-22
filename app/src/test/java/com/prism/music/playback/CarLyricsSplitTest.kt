package com.vinz.music.playback

import com.vinz.music.data.prefs.CarLyricsLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Car lyrics wrapping a long line onto the second line (PR #400 by henrynvn09; the first four cases are the PR's own).
 * Lengths are counted in cells: a Chinese, Japanese or Korean character or an emoji takes two.
 */
class CarLyricsSplitTest {
    @Test
    fun shortLineKeepsSongFallback() {
        val (top, bottom) = CarLyrics.splitLyric("Short line", "Title · Artist")
        assertEquals("Short line", top)
        assertEquals("Title · Artist", bottom)
    }

    @Test
    fun longEnglishLineSplitsAtWordBoundary() {
        val line = "Yesterday all my troubles seemed so far away"
        val (top, bottom) = CarLyrics.splitLyric(line, "Title · Artist", maxTitle = 24)
        assertEquals("Yesterday all my", top)
        assertEquals("troubles seemed so far away", bottom)
    }

    @Test
    fun longRemainderIsNotTruncated() {
        val line = "First line is pretty short here but second line continues on and on and on and on and on and never gets truncated"
        val (top, bottom) = CarLyrics.splitLyric(line, "Title · Artist", maxTitle = 30)
        assertEquals("First line is pretty short", top)
        assertEquals("here but second line continues on and on and on and on and on and never gets truncated", bottom)
    }

    @Test
    fun longWordWithoutSpacesSplitsAtLimit() {
        val line = "SupercalifragilisticexpialidociousAndMore"
        val (top, bottom) = CarLyrics.splitLyric(line, "Title · Artist", maxTitle = 20)
        assertEquals("Supercalifragilistic", top)
        assertEquals("expialidociousAndMore", bottom)
    }

    @Test
    fun longWordAtTheDefaultLength() {
        val (top, bottom) = CarLyrics.splitLyric("Pneumonoultramicroscopicsilicovolcanoconiosis", "Title · Artist")
        assertEquals("Pneumonoultramicroscopic", top)
        assertEquals("silicovolcanoconiosis", bottom)
    }

    @Test
    fun aShortFirstWordDoesNotLeaveTheTitleNearlyEmpty() {
        // The only space comes too early (under a third of the line): the long word is cut instead.
        val (top, bottom) = CarLyrics.splitLyric("Oh Supercalifragilisticexpialidocious", "Title · Artist", maxTitle = 24)
        assertEquals("Oh Supercalifragilistice", top)
        assertEquals("xpialidocious", bottom)
    }

    @Test
    fun cjkSplitsAtLimitWhenNoSpaces() {
        // The PR's case, with its 14 characters as 28 cells.
        val line = "原谅我这一生不羁放纵爱自由也会怕有一天会跌倒"
        val (top, bottom) = CarLyrics.splitLyric(line, "Title · Artist", maxTitle = 28)
        assertEquals("原谅我这一生不羁放纵爱自由也", top)
        assertEquals("会怕有一天会跌倒", bottom)
    }

    @Test
    fun cjkTakesTwoCellsACharacter() {
        // 24 cells hold 12 characters, not 24: a title of 24 would run off the car's screen.
        val (top, bottom) = CarLyrics.splitLyric("原谅我这一生不羁放纵爱自由也会怕有一天会跌倒", "Title · Artist")
        assertEquals("原谅我这一生不羁放纵爱自", top)
        assertEquals("由也会怕有一天会跌倒", bottom)
        // A line of 12 fits and is left alone.
        assertEquals("原谅我这一生不羁放纵爱自" to "Title · Artist", CarLyrics.splitLyric("原谅我这一生不羁放纵爱自", "Title · Artist"))
    }

    @Test
    fun cjkSecondLineDoesNotStartWithClosingPunctuation() {
        val (top, bottom) = CarLyrics.splitLyric("原谅我这一生不羁放纵爱自。由也会怕有一天会跌倒", "Title · Artist")
        assertEquals("原谅我这一生不羁放纵爱", top)
        assertEquals("自。由也会怕有一天会跌倒", bottom)
    }

    @Test
    fun japaneseAndKorean() {
        val (jTop, jBottom) = CarLyrics.splitLyric("夜に駆けるように二人で沈むように溶けてゆくように", "Title · Artist")
        assertEquals("夜に駆けるように二人で沈", jTop)
        assertEquals("むように溶けてゆくように", jBottom)
        // Korean has spaces: its words stay whole.
        val (kTop, kBottom) = CarLyrics.splitLyric("오늘 밤 주인공은 나야 나 너만을 기다려 온 나야 나", "Title · Artist")
        assertEquals("오늘 밤 주인공은 나야 나", kTop)
        assertEquals("너만을 기다려 온 나야 나", kBottom)
    }

    @Test
    fun emojiIsNeverCutInHalf() {
        // 23 letters, then emoji: counting UTF-16 chars cut the first one between its two halves.
        val line = "a".repeat(23) + "😀😀😀😀"
        val (top, bottom) = CarLyrics.splitLyric(line, "Title · Artist", maxTitle = 24)
        assertEquals("a".repeat(23), top)
        assertEquals("😀😀😀😀", bottom)
        whole(top); whole(bottom)
    }

    @Test
    fun zwjFamilyEmojiStaysTogether() {
        val family = "👨‍👩‍👧‍👦"
        // It fits at the end of the first line: kept there, whole.
        val (top, bottom) = CarLyrics.splitLyric("a".repeat(22) + family + " together", "Title · Artist", maxTitle = 24)
        assertEquals("a".repeat(22) + family, top)
        assertEquals("together", bottom)
        // It doesn't fit: it goes to the second line, whole.
        val (top2, bottom2) = CarLyrics.splitLyric("a".repeat(23) + family + "x", "Title · Artist", maxTitle = 24)
        assertEquals("a".repeat(23), top2)
        assertEquals(family + "x", bottom2)
        // At every length, on either side of it.
        for (max in 1..40) {
            val (a, b) = CarLyrics.splitLyric("We are $family family $family forever $family", "Title · Artist", maxTitle = max)
            for (part in listOf(a, b)) {
                whole(part)
                assertFalse("max $max: \"$part\"", part.startsWith("‍") || part.endsWith("‍"))
                assertEquals("max $max: \"$part\"", 0, part.replace(family, "").count { it == '‍' || Character.isSurrogate(it) })
            }
        }
    }

    @Test
    fun flagsSkinTonesAndAccentsStayTogether() {
        for (max in 1..30) {
            val flags = CarLyrics.splitLyric("🇩🇪🇫🇷🇯🇵🇰🇷🇧🇷🇨🇦🇮🇹🇪🇸🇸🇪🇳🇴🇫🇮🇩🇰", "T", maxTitle = max)
            for (part in flags.toList().filter { it != "T" }) assertEquals("max $max: $part", 0, part.codePointCount(0, part.length) % 2)
            val thumbs = CarLyrics.splitLyric("👍🏽".repeat(12), "T", maxTitle = max)
            for (part in thumbs.toList().filter { it != "T" }) assertEquals("max $max: $part", "", part.replace("👍🏽", ""))
            val keycapsAndHearts = CarLyrics.splitLyric("❤️".repeat(30), "T", maxTitle = max)
            for (part in keycapsAndHearts.toList().filter { it != "T" }) assertEquals("max $max: $part", "", part.replace("❤️", ""))
            // e + a combining acute accent, written apart.
            val accents = CarLyrics.splitLyric("é".repeat(30), "T", maxTitle = max)
            for (part in accents.toList().filter { it != "T" }) assertEquals("max $max: $part", "", part.replace("é", ""))
        }
    }

    @Test
    fun rightToLeftBreaksAtAWordInReadingOrder() {
        for (line in listOf(
            "كل عام وأنت بخير يا صديقي العزيز في هذا اليوم الجميل",
            "אני רוצה לשיר לך שיר אהבה ישן וחדש בלילה הזה",
        )) {
            val (top, bottom) = CarLyrics.splitLyric(line, "Title · Artist")
            // The first line is the start of the sentence as it's read, up to a space.
            assertTrue(line.startsWith("$top "))
            assertEquals(line, "$top $bottom")
            assertTrue(top.length in 8..24)
        }
    }

    @Test
    fun thaiWithoutSpacesKeepsItsMarks() {
        val line = "ฉันรักเธอมากที่สุดในโลกใบนี้ตลอดไปและตลอดกาล"
        for (max in 1..40) {
            val (top, bottom) = CarLyrics.splitLyric(line, "T", maxTitle = max)
            if (bottom == "T") { assertEquals(line, top); continue }
            assertEquals(line, top + bottom)
            assertFalse("max $max", Character.getType(bottom.codePointAt(0)) == Character.NON_SPACING_MARK.toInt())
        }
    }

    @Test
    fun nothingIsLostAtAnyLength() {
        val lines = listOf(
            "Yesterday all my troubles seemed so far away",
            "  leading and trailing spaces around a long enough line  ",
            "one-word-with-hyphens-and-no-spaces-at-all-anywhere",
            "原谅我这一生不羁放纵爱自由，也会怕有一天会跌倒。",
            "Mixed 日本語 and English と 絵文字 😀🎵 in one line",
            "كل عام وأنت بخير يا صديقي العزيز",
            "😀", "", " ", "a", "\uD83D", "ab\uDE00cd\uD83Def",
        )
        for (line in lines) for (max in listOf(-3, 0) + (1..60)) {
            val (top, bottom) = CarLyrics.splitLyric(line, "T", maxTitle = max)
            if (bottom == "T" && top == line) continue
            assertTrue("\"$line\" at $max", top.isNotBlank() && bottom.isNotBlank())
            assertEquals("\"$line\" at $max", line.filterNot { it.isWhitespace() }, (top + bottom).filterNot { it.isWhitespace() })
            assertEquals(top, top.trim()); assertEquals(bottom, bottom.trim())
        }
    }

    @Test
    fun oneLineIsExactlyTodaysOutput() {
        // Before 1.8.4: Overlay(id, line.text, "${title} · ${artist}", art), or the song's own with "Keep the song title".
        val lines = listOf(
            "Short", "Yesterday all my troubles seemed so far away", "原谅我这一生不羁放纵爱自由也会怕有一天会跌倒",
            "a".repeat(23) + "😀😀😀😀", "  spaces kept as they are  ", "👨‍👩‍👧‍👦".repeat(9),
        )
        for (line in lines) {
            val t = carLyricText(line, "Song", "Singer", keepTitle = false, lines = CarLyricsLines.ONE_LINE)
            assertEquals(line, t.title)
            assertEquals("Song · Singer", t.artist)
            assertNull("the session's subtitle is left as the song's", t.subtitle)
            for (mode in CarLyricsLines.entries) {
                val kept = carLyricText(line, "Song", "Singer", keepTitle = true, lines = mode)
                assertEquals(CarLyricText("Song", "Singer", null), kept)
            }
        }
    }

    @Test
    fun wrapToTwo() {
        // A line that fits keeps the song's name under it.
        assertEquals(
            CarLyricText("Short line", "Song · Singer", "Song · Singer"),
            carLyricText("Short line", "Song", "Singer", keepTitle = false, lines = CarLyricsLines.WRAP_TWO),
        )
        // A long one carries on into the second line.
        assertEquals(
            CarLyricText("Yesterday all my", "troubles seemed so far away", "troubles seemed so far away"),
            carLyricText("Yesterday all my troubles seemed so far away", "Song", "Singer", keepTitle = false, lines = CarLyricsLines.WRAP_TWO),
        )
    }

    @Test
    fun karaokeWordsStayWhole() {
        // The lyric card lights words up from the line's own words (it isn't wrapped here: StaticLayout wraps it).
        // The title's two lines keep each of those words whole and in order, so title and card never disagree.
        val words = listOf("Yesterday ", "all ", "my ", "troubles ", "seemed ", "so ", "far ", "away")
        val (top, bottom) = CarLyrics.splitLyric(words.joinToString("").trimEnd(), "Title · Artist")
        assertEquals(words.map { it.trim() }, top.split(' ') + bottom.split(' '))
    }

    /** No half of a surrogate pair on its own. */
    private fun whole(s: String) {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (Character.isHighSurrogate(ch)) {
                assertTrue("lone high surrogate in \"$s\"", i + 1 < s.length && Character.isLowSurrogate(s[i + 1])); i += 2
            } else {
                assertFalse("lone low surrogate in \"$s\"", Character.isLowSurrogate(ch)); i++
            }
        }
    }
}
