package com.vinz.music

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.CarLyricsLines
import com.vinz.music.data.prefs.CarLyricsStyle
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.playback.carLyricText
import com.vinz.music.ui.Routes
import com.vinz.music.ui.screens.SettingsIndex
import com.vinz.music.ui.screens.settings.SettingsMap
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsRowChanged
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.SettingsTarget
import com.vinz.music.ui.screens.settings.value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.8.4, Settings → Car rebuilt with the kit (plan §2.3, the ledger's Car table): one group, Android Auto, within the
 * budgets; a control for every stored setting the ledger gives the page; the new "Long lines" (PR #400); Style, Keep
 * the song title and Long lines showing with only the notification's lyrics on (#392); nothing left of the old page's
 * Connections list, Sharing, or "On this phone too"; Reset this page; and the page drawn in light, dark, Performance
 * mode and at large text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h4000dp-xhdpi", application = android.app.Application::class)
class CarPageTest : ListeningPageHarness(SettingsPageId.CAR) {
    private fun t(id: Int) = tr(id)

    private val auto = t(R.string.android_auto)
    private val lyrics = t(R.string.lyrics_in_the_car)
    private val style = t(R.string.car_lyrics_style)
    private val keepTitle = t(R.string.keep_the_song_title)
    private val longLines = t(R.string.car_long_lines)
    private val grid = t(R.string.covers_in_a_grid)
    private val carButtons = t(R.string.buttons_in_the_car)

    private fun groupTitles() = compose.onAllNodes(hasTestTag(SettingsTags.GROUP)).fetchSemanticsNodes()
        .map { g -> g.children.first().config[SemanticsProperties.Text].first().text }

    private fun changed(title: String) = row(title).fetchSemanticsNode().config[SettingsRowChanged]

    @Test fun oneGroupWithinTheBudgets() {
        show()
        assertBudgets("defaults")
        // The ledger's five: Lyrics in the car, Style, Long lines, Covers in a grid, Buttons in the car.
        assertEquals(listOf(auto), groupTitles())
        assertEquals(listOf(5), groupSizes())
        assertEquals(0, changedRows())
        assertTrue(groupTitles().none { it == SettingsPageId.CAR.title })
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(lyrics))
        assertEquals(SettingsTags.ROW_PICK, typeOf(style))
        assertEquals(CarLyricsStyle.TEXT.label, valueOf(style))
        assertEquals(SettingsTags.ROW_CHOICE, typeOf(longLines))
        assertEquals(CarLyricsLines.ONE_LINE.label, valueOf(longLines))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(grid))
        assertEquals(SettingsTags.ROW_LINK, typeOf(carButtons))
        // With "Title only" there is no song title to keep.
        assertFalse(hasRow(keepTitle))
        // What the old page also held is other pages' now: the Connections list, Sharing, and "On this phone too".
        for (id in listOf(R.string.connections, R.string.sharing_and_links, R.string.on_this_phone_too, R.string.speakers_tvs_browser, R.string.discord)) {
            assertFalse(t(id), hasRow(t(id)) || t(id) in groupTitles())
        }
        assertFalse("the notification's lyrics have one home, and it isn't here", hasRow(t(R.string.gb_lyrics_in_notification)))
        assertTrue(SettingsIndex.entries.none { it.page == SettingsPageId.CAR && it.title in listOf(t(R.string.on_this_phone_too), t(R.string.gb_lyrics_in_notification)) })
    }

    /** Principle 8: every stored setting the ledger gives Car has its control here, and the control stores it. */
    @Test fun everyStoredSettingHasItsControl() {
        show()
        val d = AppSettings()

        // car_lyrics_style: in its pick sheet, one card a style.
        tap(style)
        assertEquals(CarLyricsStyle.entries.size, compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION)).fetchSemanticsNodes().size)
        swipeSheetAway()
        assertEquals("a sheet swiped away changes nothing", d.carLyricsStyle, stored.carLyricsStyle)
        pick(style, CarLyricsStyle.KARAOKE.label)
        until("car_lyrics_style") { it.carLyricsStyle == CarLyricsStyle.KARAOKE }
        assertEquals(CarLyricsStyle.KARAOKE.label, valueOf(style))
        // car_lyrics_lines (new in 1.8.4)
        choose(longLines, CarLyricsLines.WRAP_TWO.label)
        until("car_lyrics_lines") { it.behavior.carLyricsLines == CarLyricsLines.WRAP_TWO }
        // car_lyrics_keep_title: with a lyric card on the cover.
        tap(keepTitle)
        until("car_lyrics_keep_title") { it.carLyricsKeepTitle }
        // auto_grid
        tap(grid)
        until("auto_grid") { it.autoGrid != d.autoGrid }
        // car_lyrics
        tap(lyrics)
        until("car_lyrics") { !it.carLyrics }
        assertFalse("its rows go with it (the notification's lyrics are off too)", hasRow(style) || hasRow(keepTitle) || hasRow(longLines))
        assertEquals(listOf(3), groupSizes())
        // The root's line is live.
        assertEquals(auto, SettingsPageId.CAR.value(stored))
        tap(lyrics)
        until("car_lyrics on") { it.carLyrics }
        assertTrue(SettingsPageId.CAR.value(stored), CarLyricsStyle.KARAOKE.label in SettingsPageId.CAR.value(stored))
        tap(lyrics)
        until("car_lyrics off") { !it.carLyrics }

        // Reset this page lists each of the five (the hidden ones too) and puts them all back.
        val lines = resetLines(confirm = true)
        assertEquals(lines.joinToString("\n"), 5, lines.size)
        assertTrue(lines.toString(), lines.any { it.startsWith("$longLines: ${CarLyricsLines.WRAP_TWO.label} → ${CarLyricsLines.ONE_LINE.label}") })
        until("the reset") {
            it.carLyrics == d.carLyrics && it.carLyricsStyle == d.carLyricsStyle && it.carLyricsKeepTitle == d.carLyricsKeepTitle &&
                it.behavior.carLyricsLines == d.behavior.carLyricsLines && it.autoGrid == d.autoGrid
        }
        assertEquals(0, changedRows())
        assertEquals(listOf(5), groupSizes())
    }

    /**
     * #392: Style, Keep the song title and Long lines shape the notification's lyrics too, so they show with only
     * "Lyrics in the notification" on (they were nested under "Lyrics in the car").
     */
    @Test fun theLyricRowsShowWithOnlyTheNotificationSwitchOn() {
        container.settings.setCarLyrics(false)
        until("car lyrics off") { !it.carLyrics }
        show()
        assertFalse(hasRow(style) || hasRow(keepTitle) || hasRow(longLines))
        container.settings.setCarLyricsOnPhone(true)
        until("notification lyrics on") { it.carLyricsOnPhone && !it.carLyrics }
        assertTrue("Style", hasRow(style))
        assertTrue("Long lines", hasRow(longLines))
        assertBudgets("only the notification's lyrics")
        assertEquals(listOf(5), groupSizes())
        // And they work from here.
        pick(style, CarLyricsStyle.LINES.label)
        until("car_lyrics_style") { it.carLyricsStyle == CarLyricsStyle.LINES }
        assertTrue("Keep the song title", hasRow(keepTitle))
        tap(keepTitle)
        until("car_lyrics_keep_title") { it.carLyricsKeepTitle }
        assertEquals(listOf(6), groupSizes())

        // Search says what to turn on, and either switch is enough.
        val off = AppSettings(carLyrics = false, carLyricsOnPhone = false)
        for (title in listOf(style, keepTitle, longLines)) {
            val needs = SettingsIndex.entries.single { it.page == SettingsPageId.CAR && it.title == title }.needs
            assertTrue("$title's hint", needs != null && !needs.on(off))
            assertEquals(t(R.string.car_needs_lyrics), needs!!.hint)
            assertTrue("$title: the car's switch", needs.on(off.copy(carLyrics = true)))
            assertTrue("$title: the notification's switch", needs.on(off.copy(carLyricsOnPhone = true)))
        }
        for (title in listOf(lyrics, grid, carButtons)) assertEquals(title, null, SettingsIndex.entries.single { it.page == SettingsPageId.CAR && it.title == title }.needs)
    }

    /** PR #400: "Long lines" is new, One line as Vinz was, and greyed while the song's own title is kept. */
    @Test fun longLinesIsNewAndRestsWhileTheTitleIsKept() {
        show()
        assertEquals("Long lines", longLines)
        assertEquals(CarLyricsLines.ONE_LINE, stored.behavior.carLyricsLines)
        assertEquals(
            "A lyric too long for the car's title carries on into the second line, in place of the song's name.",
            t(R.string.car_long_lines_info),
        )
        // Title only: it works.
        choose(longLines, CarLyricsLines.WRAP_TWO.label)
        until("wrap to two") { it.behavior.carLyricsLines == CarLyricsLines.WRAP_TWO }
        assertTrue(changed(longLines))
        choose(longLines, CarLyricsLines.ONE_LINE.label)
        until("one line") { it.behavior.carLyricsLines == CarLyricsLines.ONE_LINE }
        // A card on the cover with the song's title kept: the title is the song's, so there is nothing to wrap.
        container.settings.setCarLyricsStyle(CarLyricsStyle.KARAOKE)
        container.settings.setCarLyricsKeepTitle(true)
        until("karaoke, title kept") { it.carLyricsStyle == CarLyricsStyle.KARAOKE && it.carLyricsKeepTitle }
        assertTrue("still on the page", hasRow(longLines))
        choose(longLines, CarLyricsLines.WRAP_TWO.label)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals("greyed: a tap stores nothing", CarLyricsLines.ONE_LINE, stored.behavior.carLyricsLines)
        // ...which is what the car does with it then (carLyricText): the same title either way.
        val long = "a lyric line that is much too long for the title of a car's small screen"
        assertEquals(
            carLyricText(long, "Song", "Artist", keepTitle = true, lines = CarLyricsLines.ONE_LINE),
            carLyricText(long, "Song", "Artist", keepTitle = true, lines = CarLyricsLines.WRAP_TWO),
        )
        assertFalse(
            carLyricText(long, "Song", "Artist", keepTitle = false, lines = CarLyricsLines.ONE_LINE) ==
                carLyricText(long, "Song", "Artist", keepTitle = false, lines = CarLyricsLines.WRAP_TWO),
        )
        // Title only with "keep" still stored from before: the car ignores it (CarLyrics.tick), and so does the row.
        container.settings.setCarLyricsStyle(CarLyricsStyle.TEXT)
        until("title only") { it.carLyricsStyle == CarLyricsStyle.TEXT && it.carLyricsKeepTitle }
        choose(longLines, CarLyricsLines.WRAP_TWO.label)
        until("wrap to two again") { it.behavior.carLyricsLines == CarLyricsLines.WRAP_TWO }
        // Found by search, here.
        val e = SettingsIndex.entries.single { it.title == longLines }
        assertEquals(SettingsPageId.CAR, e.page)
        assertEquals(auto, e.group)
        assertTrue(SettingsIndex.search("wrap two lines").any { it.title == longLines })
    }

    /** "Buttons in the car" leads to the buttons' one home, and the old page's links still land. */
    @Test fun theLinkAndTheOldRoutes() {
        // The row's route is written in the page; the group it names is one Gestures & buttons draws.
        val source = File("src/main/java/com/vinz/music/ui/screens/settings/CarPage.kt").readText()
        assertTrue("SettingsPageId.GESTURES_BUTTONS.key, tr(R.string.notification_and_car)" in source)
        assertTrue("DevicesPage(" !in source && "SharingGroup(" !in source)
        val outside = t(R.string.notification_and_car)
        assertEquals(SettingsTarget(SettingsPageId.GESTURES_BUTTONS, outside), SettingsMap.resolve(SettingsPageId.GESTURES_BUTTONS.key, outside))
        assertTrue(SettingsIndex.entries.any { it.page == SettingsPageId.GESTURES_BUTTONS && it.group == outside })
        assertTrue(Routes.settings(SettingsPageId.GESTURES_BUTTONS.key, outside).startsWith("settings/gestures_buttons?find="))
        // Old links: Devices & connections opens Car, its Android Auto group is still called that, Sharing went to Accounts & sharing.
        assertEquals(SettingsTarget(SettingsPageId.CAR), SettingsMap.resolve("devices"))
        assertEquals(SettingsTarget(SettingsPageId.CAR, auto), SettingsMap.resolve("devices", auto))
        assertEquals(SettingsPageId.ACCOUNTS_SHARING, SettingsMap.resolve("devices", t(R.string.sharing_and_links))!!.page)
        // Every line of the page's search names the group the page draws.
        show()
        val mine = SettingsIndex.entries.filter { it.page == SettingsPageId.CAR && !it.isPage }
        assertTrue(mine.all { it.group == auto })
        assertEquals(6, mine.size)
    }

    @Test fun atLargeTextNothingIsCutOff() {
        container.settings.setCarLyricsStyle(CarLyricsStyle.KARAOKE)
        until("karaoke") { it.carLyricsStyle == CarLyricsStyle.KARAOKE }
        show(fontScale = 1.5f)
        assertBudgets("large text")
        assertEquals(listOf(6), groupSizes())
        assertNoTextCutOff("font scale 1.5")
    }

    private fun drawn(name: String, glass: Boolean, over: (AppSettings) -> AppSettings) {
        show(glass = glass, over = over)
        val file = save("car", name)
        assertTrue(file.path, file.length() > 10_000)
        assertBudgets(name)
        assertNoTextCutOff(name)
    }

    @Test @Config(qualifiers = "w360dp-h900dp-xxhdpi") fun drawnLight() = drawn("car-light", true) { it.copy(themeMode = ThemeMode.LIGHT) }
    @Test @Config(qualifiers = "w360dp-h900dp-xxhdpi") fun drawnDark() = drawn("car-dark", true) { it.copy(themeMode = ThemeMode.DARK) }
    @Test @Config(qualifiers = "w360dp-h900dp-xxhdpi") fun drawnPerformance() = drawn("car-performance", false) { it.copy(themeMode = ThemeMode.DARK, performanceMode = true) }

    /** The page's longest state: a card style with the song's title kept (Long lines greyed). */
    @Test @Config(qualifiers = "w360dp-h900dp-xxhdpi") fun drawnWithTheTitleKept() =
        drawn("car-dark-title-kept", true) { it.copy(themeMode = ThemeMode.DARK, carLyricsStyle = CarLyricsStyle.KARAOKE, carLyricsKeepTitle = true) }

    /** The Style sheet's cards (no song playing: the cover's placeholder over what each style does). */
    @Test @Config(qualifiers = "w360dp-h900dp-xxhdpi") fun drawnStyleSheet() {
        show(glass = true) { it.copy(themeMode = ThemeMode.DARK) }
        tap(style)
        assertEquals(1, sheets())
        assertEquals(CarLyricsStyle.entries.size, compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION)).fetchSemanticsNodes().size)
    }
}
