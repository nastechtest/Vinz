package com.vinz.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vinz.music.data.Habits
import com.vinz.music.data.Personality
import com.vinz.music.data.VinzYear
import com.vinz.music.data.RewindMilestone
import com.vinz.music.data.StoryPart
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.MonthTotal
import com.prism.music.data.db.SongPlays
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.screens.LocalStoryFont
import com.vinz.music.ui.screens.Period
import com.vinz.music.ui.screens.ReplayStory
import com.vinz.music.ui.screens.StorySlide
import com.vinz.music.ui.screens.calendarShades
import com.vinz.music.ui.screens.monthLetters
import com.vinz.music.ui.screens.monthName
import com.vinz.music.ui.screens.weekdayLetters
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * Rewind 2026's new slides (1.8.5) and the months slide, as a shared slide's picture has them, at a phone's size: in
 * Vinz's poster type and the app's own font, in English, German (long words) and Arabic (right to left), and at large
 * text. No text runs into another or off the slide. Drawn to app/build/rewind-slides/<case>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1840dp-h820dp-hdpi")
class RewindSlidesRenderTest {
    @get:Rule val compose = createComposeRule()

    // Strings keeps the first test's resources (and so its language) for the JVM's life: each test reads its own.
    @org.junit.Before fun freshLanguage() { Strings.resources = null }

    private val kinds = listOf(StoryPart.MONTHS, StoryPart.CALENDAR, StoryPart.CLOCK, StoryPart.FOUND, StoryPart.MILESTONES)
    private val long = "An Extraordinarily Long Song Title (Extended Club Remix featuring Somebody Else)"

    private fun song(i: Int, title: String = "Song $i") = SongPlays("song$i", 40 - i, (40 - i) * 200_000L, title, "Artist with a rather long name $i", null, null, 200, null, "Artist $i")

    private fun story(includes: String? = "Includes Spotify, Last.fm, YouTube Music"): ReplayStory {
        val songs = (0 until 12).map { if (it == 0) song(0, long) else song(it) }
        // A year that leans to evenings and weekends, with a quiet summer.
        val days = List(365) { d -> if (d % 9 == 4 || d in 180..200) 0L else (d % 37 + 1) * 120_000L }
        val week = List(168) { i -> val h = i % 24; val wd = i / 24; if (h in 2..6) 0L else (h * 3 + wd * 5) * 60_000L }
        return ReplayStory(
            period = Period.year(2026), title = "2026", totalMs = days.sum(), previousMs = null, previousLabel = null, plays = 4000,
            activeDays = days.count { it > 0 }, songCount = 900, artistCount = 300, songs = songs,
            artists = listOf(ArtistPlays("Artist 0", 100, 9_000_000, null, null)), genres = emptyList(), genreSongs = emptyMap(),
            habits = Habits(LongArray(24) { h -> (0 until 7).sumOf { week[it * 24 + h] } }, LongArray(7) { 1 }, 12, null, 0),
            newArtists = null, personality = Personality("", ""), topArtistImage = null, topArtistType = com.vinz.music.ui.theme.ArtistTypography.standard,
            rewind = true, months = (1..12).map { MonthTotal("2026-%02d".format(it), it * 1_000_000L % 7_000_000L + 500_000L) }, vinz = VinzYear(radarFinds = 14),
            includes = includes, days = days, weekHours = week, hourSongs = listOf(song(0, long)),
            milestones = listOf(
                RewindMilestone("STREAK", 30, 1_770_000_000_000),
                RewindMilestone("SONG_PLAYS", 250, 1_775_000_000_000, "song0", long, null),
                RewindMilestone("HOURS", 1000, 1_780_000_000_000),
                RewindMilestone("SONGS", 2500, 1_785_000_000_000),
                RewindMilestone("SONG_PLAYS", 100, 1_790_000_000_000, "song1", "Song 1", null),
            ),
            finds = listOf(song(0, long), song(3), song(5)), findCount = 23,
        )
    }

    private fun shoot(name: String, ownFont: Boolean = false, fontScale: Float = 1f, rtl: Boolean = false, still: Boolean = true, inBounds: Boolean = true) {
        val s = story()
        compose.setContent {
            VinzTheme(AppSettings(themeMode = ThemeMode.DARK, reduceMotion = still), artworkColor = null) {
                val d = LocalDensity.current
                CompositionLocalProvider(
                    LocalStoryFont provides if (ownFont) FontFamily.SansSerif else null,
                    LocalDensity provides Density(d.density, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Row(Modifier.background(Color.Black)) {
                        kinds.forEach { k -> Box(Modifier.size(360.dp, 780.dp).testTag("slide-$k")) { StorySlide(k, s) } }
                    }
                }
            }
        }
        compose.waitForIdle()
        val dir = File("build/rewind-slides").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // Every piece of text: inside its slide, and clear of every other.
        val slides = kinds.associateWith { k -> compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TestTag, "slide-$k")).fetchSemanticsNodes().single().boundsInRoot }
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        val problems = ArrayList<String>()
        // Cut off by what holds it (a card or the slide's own edge): its shown part is smaller than the text.
        for (n in nodes) {
            val t = n.config[SemanticsProperties.Text].joinToString { it.text }
            if (n.boundsInRoot.height < n.size.height - 1f || n.boundsInRoot.width < n.size.width - 1f) problems += "\"$t\" is cut off: ${n.boundsInRoot} of ${n.size}"
            // Or squeezed: given less height than its lines need.
            val layouts = ArrayList<androidx.compose.ui.text.TextLayoutResult>()
            n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { null }?.action?.invoke(layouts)
            if (layouts.any { it.size.height < it.multiParagraph.height - 1f }) problems += "\"$t\" is squeezed: ${n.size}"
        }
        val texts = nodes.map { it.config[SemanticsProperties.Text].joinToString { t -> t.text } to it.boundsInRoot }
            .filter { it.second.width > 0 && it.second.height > 0 }
        for ((t, r) in texts) {
            val home = slides.entries.firstOrNull { it.value.contains(r.center) } ?: run { problems += "\"$t\" is on no slide: $r"; continue }
            if (inBounds && !home.value.inflate(1f).let { b -> r.left >= b.left && r.right <= b.right && r.top >= b.top && r.bottom <= b.bottom }) problems += "\"$t\" runs off ${home.key}: $r in ${home.value}"
        }
        for (i in texts.indices) for (j in i + 1 until texts.size) {
            val (a, ra) = texts[i]
            val (b, rb) = texts[j]
            val o = ra.intersect(rb)
            if (o.width > 1f && o.height > 1f) problems += "\"$a\" overlaps \"$b\": $ra / $rb"
        }
        assertTrue("$name:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun inEnglish() = shoot("rewind-slides-en")
    @Test fun inTheAppsFont() = shoot("rewind-slides-own-font", ownFont = true)
    @Test @Config(qualifiers = "de-w1840dp-h820dp-hdpi") fun inGerman() = shoot("rewind-slides-de")
    @Test @Config(qualifiers = "de-w1840dp-h820dp-hdpi") fun inGermanInTheAppsFont() = shoot("rewind-slides-de-own-font", ownFont = true)
    @Test @Config(qualifiers = "ar-ldrtl-w1840dp-h820dp-hdpi") fun inArabicRightToLeft() = shoot("rewind-slides-ar", rtl = true)
    @Test fun atLargeText() = shoot("rewind-slides-text-130", fontScale = 1.3f)
    @Test fun atLargeTextInTheAppsFont() = shoot("rewind-slides-text-130-own-font", ownFont = true, fontScale = 1.3f)
    @Test fun atTheLargestText() = shoot("rewind-slides-text-200", fontScale = 2f)

    /** Reduce motion and Performance mode: the slides are drawn still, the same a moment and three seconds in. */
    @Test fun stillWithReduceMotion() {
        compose.mainClock.autoAdvance = false
        val s = story()
        compose.setContent {
            VinzTheme(AppSettings(themeMode = ThemeMode.DARK, reduceMotion = true), artworkColor = null) {
                Row { kinds.forEach { k -> Box(Modifier.size(360.dp, 780.dp)) { StorySlide(k, s) } } }
            }
        }
        compose.mainClock.advanceTimeBy(50)
        val a = pixels()
        compose.mainClock.advanceTimeBy(3_000)
        val b = pixels()
        assertArrayEquals("the slides moved with Reduce motion on", a, b)
    }

    /** The same slides with motion on do move (so the test above can tell). */
    @Test fun movingWithoutReduceMotion() {
        compose.mainClock.autoAdvance = false
        val s = story()
        compose.setContent {
            VinzTheme(AppSettings(themeMode = ThemeMode.DARK, reduceMotion = false), artworkColor = null) {
                Row { kinds.forEach { k -> Box(Modifier.size(360.dp, 780.dp)) { StorySlide(k, s) } } }
            }
        }
        compose.mainClock.advanceTimeBy(50)
        val a = pixels()
        compose.mainClock.advanceTimeBy(3_000)
        assertFalse(a.contentEquals(pixels()))
    }

    private fun pixels(): IntArray {
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        return IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    }

    @Test fun monthsAndDaysInTheLanguageShown() {
        assertEquals(listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D"), monthLetters(Locale.ENGLISH))
        val ru = Locale.forLanguageTag("ru")
        assertEquals("Я", monthLetters(ru).first())
        // A month's name on its own (the old slide said "ЯНВАРЯ", the form for "1 января").
        assertEquals("январь", monthName(1, ru).lowercase(ru))
        assertEquals("styczeń", monthName(1, Locale.forLanguageTag("pl")).lowercase())
        assertEquals("January", monthName(1, Locale.ENGLISH))
        assertEquals(12, monthLetters(Locale.forLanguageTag("ar")).size)
        assertTrue(monthLetters(Locale.forLanguageTag("ar")).all { it.isNotBlank() })
        assertEquals(7, weekdayLetters(Locale.GERMAN).size)
        assertEquals("M", weekdayLetters(Locale.ENGLISH).first())
    }

    @Test fun theCalendarsShades() {
        val days = listOf(0L, 10, 20, 30, 40, 50, 60, 70, 80, 1_000_000)
        val shades = calendarShades(days)
        assertEquals(0, shades[0])
        assertEquals(4, shades.last())
        assertTrue(shades.drop(1).zipWithNext().all { (a, b) -> a <= b })
        assertEquals(setOf(1, 2, 3, 4), shades.drop(1).toSet())
        assertTrue(calendarShades(List(365) { 0L }).all { it == 0 })
        assertTrue(calendarShades(emptyList()).isEmpty())
    }
}
