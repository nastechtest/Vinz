package com.vinz.music

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ArtworkChange
import com.vinz.music.data.prefs.ArtworkSwatch
import com.vinz.music.data.prefs.ColorSource
import com.vinz.music.data.prefs.ContrastLevel
import com.vinz.music.data.prefs.GlassKind
import com.vinz.music.data.prefs.LightStyle
import com.vinz.music.data.prefs.SurfaceTint
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.Routes
import com.vinz.music.ui.screens.GlassChoice
import com.vinz.music.ui.screens.SettingsIndex
import com.vinz.music.ui.screens.settings.SettingsMap
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsRowChanged
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.SettingsTarget
import com.vinz.music.ui.screens.settings.ThemeColorsPage
import com.vinz.music.ui.screens.settings.themeColorsChanges
import com.vinz.music.ui.screens.settings.themeColorsLookKeys
import com.vinz.music.ui.screens.settings.themeColorsValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.8.4, Settings → Theme & colors rebuilt with the kit (plan §2.3–2.7, the ledger's rows for the page): it draws in
 * light, dark, Performance mode and at large text; it keeps to the budgets; every stored setting the ledger gives the
 * page has a row that changes it; Reset this page lists and puts back what changed. The window is tall so the whole
 * (lazy) page is there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h4000dp-xhdpi")
class ThemeColorsPageTest : ThemeLooksPageHarness() {
    private val prefs get() = container.settings

    private fun page() = show { ThemeColorsPage(0.dp, onSearch = {}) }

    private val motion = "Theme and motion"
    private val defaultGroups = listOf(motion, "The light", "Accent", "Pages", "Glass")

    // ------------------------------------------------------------------ Drawn

    @Test fun withDefaultsItIsFiveGroupsOfKitRows() {
        page()
        assertEquals("Theme & colors", compose.onNodeWithTag(SettingsTags.PAGE_TITLE).fetchSemanticsNode().config[SemanticsProperties.Text].first().text)
        val groups = groups()
        // The sixth, Home-screen widgets, only shows while a Vinz widget is on the home screen.
        assertEquals(defaultGroups, groups.map { it.first })
        assertEquals(
            listOf(
                listOf("Theme", "Changing screens", "Reduce motion"),
                listOf("Preset", "Smooth or bands"),
                listOf("Accent from", "Accent color", "How it's used", "Light mode accent"),
                listOf("Page background", "Text contrast", "Page colors", "Playing song in the page's color", "Screen backgrounds"),
                listOf("Glass", "Blur", "Frost", "Glass also on"),
            ),
            groups.map { g -> g.second.map { titleOf(it) } },
        )
        assertBudgets()
        // Each row is one of the five types, and says where it stands.
        assertEquals(
            listOf(SettingsTags.ROW_CHOICE, SettingsTags.ROW_CHOICE, SettingsTags.ROW_SWITCH),
            groups[0].second.map { tagOf(it) },
        )
        assertEquals("System", valueOf("Theme"))
        assertEquals("Fade", valueOf("Changing screens"))
        assertEquals("Vinz", valueOf("Preset"))
        assertEquals("Smooth", valueOf("Smooth or bands"))
        assertEquals("Your pick", valueOf("Accent from"))
        // W17 (1.8.7.6): the swatch says it; the hex code is read out, and is in the picker, but isn't drawn on the row.
        assertEquals("#3A86FF", valueOf("Accent color"))
        assertFalse("#3A86FF" in textsOf(compose.onNodeWithTag(SettingsTags.PAGE_LIST, useUnmergedTree = true).fetchSemanticsNode()))
        assertEquals("Softened", valueOf("How it's used"))
        assertEquals("The same as your accent", valueOf("Light mode accent"))
        assertEquals("Automatic", valueOf("Page background"))
        assertEquals("Standard", valueOf("Text contrast"))
        assertEquals("Your accent", valueOf("Page colors"))
        assertEquals("Designs or your own photo", valueOf("Screen backgrounds"))
        assertEquals("Frosted glass", valueOf("Glass"))
        // W21 (1.8.7.6): 100% is the middle of both sliders, how the glass is designed.
        assertEquals("As designed", valueOf("Blur"))
        assertEquals("As designed", valueOf("Frost"))
        assertEquals("Artist and album pages", valueOf("Glass also on"))
        // Nothing differs from how Vinz comes: no dot anywhere, and nothing for Reset this page to do.
        assertTrue(rows().none { it.config[SettingsRowChanged] })
        assertEquals(emptyList<String>(), themeColorsChanges(stored, dark = true))
        // The pinned preview and, with five groups, the jump chips.
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsTags.PREVIEW)).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsTags.JUMP_CHIPS)).fetchSemanticsNodes().size)
    }

    /** §2.7 and the brief: no paragraphs on the page, and the old hub's link lists and Looks' rows are gone from it. */
    @Test fun thePageIsQuiet() {
        page()
        val drawn = textsOf(compose.onNodeWithTag(SettingsTags.PAGE_LIST, useUnmergedTree = true).fetchSemanticsNode())
        for (gone in listOf(
            R.string.three_parts_numbered_in_the_preview, R.string.tl_colors_intro, R.string.changes_the_moment_you_pick, R.string.your_vinz,
            R.string.the_whole_look, R.string.each_part, R.string.customize_by_tapping, R.string.reset_colors_to_defaults, R.string.reset_look_layout_to_defaults,
            R.string.reset_this_page, R.string.pure_black, R.string.artist_album_pages, R.string.frosted_pages,
        )) assertFalse("\"${tr(gone)}\" is on the page", tr(gone) in drawn)
        // "Artist pages" is Home & Library's group; the colors of pages are "Page colors" here.
        assertTrue("Page colors" in drawn)
        // The Color intro is behind the light's ⓘ.
        assertFalse(drawn.any { it.startsWith("Three parts") })
    }

    @Test fun inDarkItDrawsTheSameRows() {
        prefs.setThemeMode(ThemeMode.DARK)
        await { stored.themeMode == ThemeMode.DARK }
        page()
        assertEquals(defaultGroups, groups().map { it.first })
        assertEquals(18, rows().size)
        assertEquals("Dark", valueOf("Theme"))
        assertBudgets()
    }

    @Test fun inLightItDrawsTheSameRows() {
        prefs.setThemeMode(ThemeMode.LIGHT)
        await { stored.themeMode == ThemeMode.LIGHT }
        page()
        assertEquals(defaultGroups, groups().map { it.first })
        assertEquals(18, rows().size)
        assertEquals("Light", valueOf("Theme"))
        assertBudgets()
    }

    @Test fun inPerformanceModeItSaysSoAndKeepsEveryRow() {
        prefs.setPerformanceMode(true)
        await { stored.performanceMode }
        page()
        // The settings shown are the stored ones, not what Performance mode draws.
        assertEquals("Frosted glass", valueOf("Glass"))
        assertEquals(listOf("") + defaultGroups, groups().map { it.first })
        assertEquals("Some of these effects are paused for now", valueOf("Performance mode is on"))
        assertEquals(19, rows().size)
        tap("Performance mode is on")
        await { !stored.performanceMode }
        assertFalse(hasRow("Performance mode is on"))
    }

    @Test fun atLargeTextNothingIsCutOff() {
        show(fontScale = 1.5f) { ThemeColorsPage(0.dp, onSearch = {}) }
        assertEquals(18, rows().size)
        assertNoRowTextIsCutOff()
    }

    // ------------------------------------------------------------------ Nothing missing (the ledger's keys)

    /** theme_mode, pure_black, page_slide, screen_fades, reduce_motion. */
    @Test fun themeAndMotionRowsChangeTheirSettings() {
        page()
        chip("Theme", "Black")
        await { stored.themeMode == ThemeMode.DARK && stored.pureBlack }
        assertEquals("Black", valueOf("Theme"))
        chip("Theme", "Light")
        await { stored.themeMode == ThemeMode.LIGHT }
        assertTrue("Light keeps the black picked last", stored.pureBlack)
        chip("Theme", "Dark")
        await { stored.themeMode == ThemeMode.DARK && !stored.pureBlack }
        chip("Theme", "System")
        await { stored.themeMode == ThemeMode.SYSTEM }

        chip("Changing screens", "Slide")
        await { stored.behavior.pageSlide && stored.behavior.screenFades }
        chip("Changing screens", "None")
        await { !stored.behavior.screenFades }
        assertEquals("None", valueOf("Changing screens"))

        tap("Reduce motion")
        await { stored.reduceMotion }
    }

    /** light_style, spectrum_preset, spectrum_stops, spectrum_bands. */
    @Test fun theLightRowsChangeTheirSettings() {
        prefs.setThemeMode(ThemeMode.DARK)
        await { stored.themeMode == ThemeMode.DARK }
        page()
        tap("Preset")
        // The lights that look alike are all there to tell apart, and White and your own come last.
        val options = sheetOptions()
        for (name in listOf("Vinz", "Sunset", "Ember", "Gold", "Ocean", "Ice", "Aurora")) assertTrue("$name in $options", name in options)
        assertEquals(listOf("White", "Your own"), options.takeLast(2))
        compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText("Sunset")).performClick()
        await { stored.spectrumPreset == "SUNSET" }
        sheetGone()
        assertEquals("Sunset", valueOf("Preset"))

        pick("Smooth or bands", "Bands")
        await { stored.spectrumBands }
        sheetGone()

        pick("Preset", "White")
        await { stored.lightStyle == LightStyle.WHITE }
        sheetGone()
        // White light has no bands to pick.
        assertFalse(hasRow("Smooth or bands"))

        assertFalse(hasRow("Your own colors"))
        pick("Preset", "Your own")
        await { stored.lightStyle == LightStyle.SPECTRUM && stored.spectrumPreset == "CUSTOM" && stored.spectrumStops.size >= 2 }
        sheetGone()
        val stops = stored.spectrumStops
        assertEquals("${stops.size} colors", valueOf("Your own colors"))
        tap("Your own colors")
        compose.onNode(hasText(tr(R.string.reverse)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        await { stored.spectrumStops == stops.reversed() }
    }

    private fun pickColor(title: String, hex: String) {
        tap(title)
        compose.onNode(hasSetTextAction()).performTextReplacement(hex)
        compose.waitForIdle()
        compose.onNode(hasText(tr(R.string.use_this_color)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    /** color_source, accent, accent_exact, accent_light, artwork_swatch, artwork_vivid, artwork_change. */
    @Test fun accentRowsChangeTheirSettings() {
        page()
        pickColor("Accent color", "#112233")
        await { stored.accent == 0xFF112233.toInt() }
        assertEquals("#112233", valueOf("Accent color"))
        chip("How it's used", "Exactly as picked")
        await { stored.accentExact }
        pickColor("Light mode accent", "#445566")
        await { stored.accentLight == 0xFF445566.toInt() }
        assertEquals("#445566", valueOf("Light mode accent"))
        // "Use the same", in the picker, clears it again.
        tap("Light mode accent")
        compose.onNode(hasText(tr(R.string.use_the_same)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        await { stored.accentLight == 0 }

        chip("Accent from", "Song's cover")
        await { stored.colorSource == ColorSource.ARTWORK }
        // The cover's rows take the place of your pick's.
        assertEquals(listOf("Accent from", "Which of its colors", "Always colorful", "Between songs"), groups()[2].second.map { titleOf(it) })
        chip("Which of its colors", ArtworkSwatch.MUTED.label)
        await { stored.artworkSwatch == ArtworkSwatch.MUTED }
        tap("Always colorful")
        await { stored.artworkVivid }
        chip("Between songs", ArtworkChange.SLOW.label)
        await { stored.artworkChange == ArtworkChange.SLOW }
        chip("Accent from", "Your pick")
        await { stored.colorSource == ColorSource.CUSTOM }
    }

    /** surface_tint, surface_color, surface_tint_strength, contrast_level, page_picture_accent, playing_page_color. */
    @Test fun pagesRowsChangeTheirSettings() {
        page()
        assertFalse(hasRow("Background color") || hasRow("Tint strength"))
        chip("Page background", "Your color")
        await { stored.surfaceTint == SurfaceTint.CUSTOM }
        pickColor("Background color", "#223344")
        await { stored.surfaceColor == 0xFF223344.toInt() }
        slide("Tint strength", 2f)
        await { stored.surfaceTintStrength == 2f }
        assertEquals("Strong · 200%", valueOf("Tint strength"))
        chip("Text contrast", "High")
        await { stored.contrast == ContrastLevel.HIGH }
        chip("Page colors", "Picture's color")
        await { stored.pageColors }
        tap("Playing song in the page's color")
        await { stored.view.playingInPageColor }
        // With everything showing, the group is at the budget, not over it.
        assertEquals(7, groups()[3].second.size)
    }

    /** backdrops, backdrop_dim, backdrop_motion, backdrop_fade: on the Screen backgrounds screen, one Link from here. */
    @Test fun screenBackgroundsOpensItsScreenAndSaysItsState() {
        prefs.setBackdrops(mapOf("HOME" to "COLOR|#FF0000", "SEARCH" to "AURORA", "PLAYER" to "SILK"))
        await { stored.backdrops.size == 3 }
        show(routes = true) { ThemeColorsPage(0.dp, onSearch = {}) }
        // The screens' backgrounds (the player's is Player's own).
        assertEquals("Solid color · Aurora on 2 screens", valueOf("Screen backgrounds"))
        assertTrue(row("Screen backgrounds").fetchSemanticsNode().config[SettingsRowChanged])
        tap("Screen backgrounds")
        await("the Screen backgrounds screen") { nav?.currentDestination?.route == Routes.BACKGROUNDS }
    }

    /** liquid_glass, glass_kind, frosted_glass, glass_blur, glass_frost, glass_more, frosted_pages. */
    @Test fun glassRowsChangeTheirSettings() {
        page()
        slide("Blur", 1.5f)
        await { stored.view.glass.blur == 1.5f }
        assertEquals("150%", valueOf("Blur"))
        slide("Frost", 0.5f)
        await { stored.view.glass.frost == 0.5f }

        chip("Glass also on", "More of the screen")
        await { stored.view.glass.more }
        assertEquals("More of the screen · Artist and album pages", valueOf("Glass also on"))
        chip("Glass also on", "Artist and album pages")
        await { !stored.frostedPages }
        assertTrue("the other chip stays as it was", stored.view.glass.more)

        tap("Glass")
        assertEquals(GlassChoice.entries.map { it.label }, sheetOptions())
        compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText(GlassChoice.LIQUID_CLEAR.label)).performClick()
        await { stored.liquidGlass && stored.glassKind == GlassKind.CLEAR }
        sheetGone()
        // Clear has next to no blur to set.
        assertFalse(hasRow("Blur"))
        assertTrue(hasRow("Frost"))
        pick("Glass", GlassChoice.OFF.label)
        await { !stored.liquidGlass && !stored.frostedGlass }
        sheetGone()
        assertEquals("Off", valueOf("Glass"))
        assertFalse(hasRow("Blur") || hasRow("Frost"))
        assertTrue(hasRow("Glass also on"))
    }

    /** The ledger's rows for the page: each key is written by one of the tests above, and is in the page's reset or its own. */
    @Test fun everyLedgerKeyIsThePages() {
        val ledger = listOf(
            "theme_mode", "pure_black", "reduce_motion", "page_slide", "screen_fades",
            "light_style", "spectrum_preset", "spectrum_stops", "spectrum_bands",
            "color_source", "accent", "accent_exact", "accent_light", "artwork_swatch", "artwork_vivid", "artwork_change",
            "surface_tint", "surface_color", "surface_tint_strength", "contrast_level", "page_picture_accent", "playing_page_color",
            "backdrops", "backdrop_dim", "backdrop_motion", "backdrop_fade",
            "liquid_glass", "glass_kind", "frosted_glass", "glass_blur", "glass_frost", "glass_more", "frosted_pages",
        )
        assertEquals(33, ledger.size)
        // All but the two that aren't look settings (how screens change), which the page's reset puts back by their setter.
        assertEquals((ledger - setOf("page_slide", "screen_fades")).toSet(), themeColorsLookKeys.toSet())
        assertTrue(ledger.all { it in prefs.allKeyNames })
    }

    // ------------------------------------------------------------------ Reset, the root's line, old links

    @Test fun resetThisPageListsWhatChangedAndPutsItBack() {
        prefs.setThemeMode(ThemeMode.DARK); prefs.setPureBlack(true); prefs.setContrast(ContrastLevel.HIGH)
        prefs.setPageSlide(true); prefs.setGlassMore(true)
        await { stored.pureBlack && stored.contrast == ContrastLevel.HIGH && stored.behavior.pageSlide && stored.view.glass.more }
        page()
        assertEquals(listOf("Theme", "Changing screens", "Text contrast", "Glass also on"), rows().filter { it.config[SettingsRowChanged] }.map { titleOf(it) })
        compose.onNodeWithTag(SettingsTags.PAGE_MORE).performClick()
        compose.onNodeWithTag(SettingsTags.RESET_ITEM).performClick()
        val listed = compose.onAllNodes(hasTestTag(SettingsTags.RESET_CHANGE)).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString { t -> t.text } }
        assertEquals(
            listOf(
                "Theme: Black → System", "Changing screens: Slide → Fade", "Text contrast: High → Standard",
                "Glass also on: More of the screen · Artist and album pages → Artist and album pages",
            ),
            listed,
        )
        assertTrue("nothing is reset before the confirm", stored.pureBlack)
        compose.onNodeWithTag(SettingsTags.RESET_CONFIRM).performClick()
        val d = AppSettings()
        await { stored.themeMode == d.themeMode && !stored.pureBlack && stored.contrast == d.contrast && !stored.behavior.pageSlide && !stored.view.glass.more }
        assertTrue(rows().none { it.config[SettingsRowChanged] })
    }

    /**
     * 1.8.4 review: "backdrops" also holds the player's own background (Player → Which background). Set only that, this
     * page has nothing to reset; with a screen's set too, Reset puts back the screens' and keeps the player's.
     */
    @Test fun resetKeepsThePlayersOwnBackground() {
        val d = AppSettings()
        assertEquals(emptyList<String>(), themeColorsChanges(d.copy(backdrops = mapOf("PLAYER" to "SILK")), dark = true))
        assertEquals(
            listOf("Screen backgrounds: Aurora on 1 screen → None"),
            themeColorsChanges(d.copy(backdrops = mapOf("PLAYER" to "SILK", "HOME" to "AURORA")), dark = true),
        )

        prefs.setBackdrops(mapOf("PLAYER" to "SILK", "HOME" to "AURORA"))
        prefs.setContrast(ContrastLevel.HIGH)
        await { stored.backdrops.size == 2 && stored.contrast == ContrastLevel.HIGH }
        page()
        compose.onNodeWithTag(SettingsTags.PAGE_MORE).performClick()
        compose.onNodeWithTag(SettingsTags.RESET_ITEM).performClick()
        compose.onNodeWithTag(SettingsTags.RESET_CONFIRM).performClick()
        await { stored.contrast == d.contrast }
        await("the player's own background stays") { stored.backdrops == mapOf("PLAYER" to "SILK") }
        assertTrue(rows().none { it.config[SettingsRowChanged] })
    }

    @Test fun everyChangeIsListedForTheReset() {
        val d = AppSettings()
        fun titles(s: AppSettings) = themeColorsChanges(s, dark = true).map { it.substringBefore(":") }
        assertEquals(emptyList<String>(), titles(d))
        assertEquals(listOf("Theme"), titles(d.copy(pureBlack = true)))
        assertEquals(listOf("Reduce motion"), titles(d.copy(reduceMotion = true)))
        assertEquals(listOf("Preset"), titles(d.copy(lightStyle = LightStyle.WHITE)))
        assertEquals(listOf("Preset"), titles(d.copy(spectrumStops = listOf(1, 2))))
        assertEquals(listOf("Smooth or bands"), titles(d.copy(spectrumBands = true)))
        assertEquals(listOf("Accent from"), titles(d.copy(colorSource = ColorSource.ARTWORK)))
        assertEquals(listOf("Accent color", "How it's used", "Light mode accent"), titles(d.copy(accent = 1, accentExact = true, accentLight = 2)))
        assertEquals(listOf("Which of its colors", "Always colorful", "Between songs"), titles(d.copy(artworkSwatch = ArtworkSwatch.MUTED, artworkVivid = true, artworkChange = ArtworkChange.SLOW)))
        assertEquals(listOf("Page background", "Background color", "Tint strength"), titles(d.copy(surfaceTint = SurfaceTint.CUSTOM, surfaceColor = 3, surfaceTintStrength = 2f)))
        assertEquals(listOf("Page colors", "Playing song in the page's color"), titles(d.copy(pageColors = true, view = d.view.copy(playingInPageColor = true))))
        for (s in listOf(d.copy(backdrops = mapOf("HOME" to "AURORA")), d.copy(backdropDim = 0.9f), d.copy(backdropMotion = false), d.copy(view = d.view.copy(backdropFade = true)))) {
            assertEquals(listOf("Screen backgrounds"), titles(s))
        }
        assertEquals(listOf("Glass"), titles(d.copy(liquidGlass = true)))
        assertEquals(listOf("Blur", "Frost"), titles(d.copy(view = d.view.copy(glass = d.view.glass.copy(blur = 1.5f, frost = 0.5f)))))
        assertEquals(listOf("Glass also on"), titles(d.copy(frostedPages = false)))
        assertEquals("Glass also on: None → Artist and album pages", themeColorsChanges(d.copy(frostedPages = false), true).single())
    }

    @Test fun theRootsLineNamesTheGlassAndTheTheme() {
        assertEquals("Frosted glass · System", themeColorsValue(AppSettings(), true))
        assertEquals("Liquid glass · Black", themeColorsValue(AppSettings(liquidGlass = true, themeMode = ThemeMode.DARK, pureBlack = true), true))
        assertEquals("Solid bars · Light", themeColorsValue(AppSettings(frostedGlass = false, themeMode = ThemeMode.LIGHT, pureBlack = true), true))
    }

    @Test fun oldLinksLandOnTheGroupsThatHoldThemNow() {
        fun to(key: String, find: String? = null) = SettingsMap.resolve(key, find)
        val p = SettingsPageId.THEME_COLORS
        assertEquals(SettingsTarget(p), to("appearance"))
        assertEquals(SettingsTarget(p, motion), to("appearance", tr(R.string.theme)))
        assertEquals(SettingsTarget(p, motion), to("appearance", tr(R.string.motion)))
        assertEquals(SettingsTarget(p, "The light"), to("color"))
        assertEquals(SettingsTarget(p, "Accent"), to("color", tr(R.string.accent_color)))
        for (old in listOf(R.string.page_background, R.string.text_contrast, R.string.artist_album_pages)) assertEquals(SettingsTarget(p, "Pages"), to("color", tr(old)))
        assertEquals(SettingsTarget(p, "Pages"), to("glass"))
        assertEquals(SettingsTarget(p, "Glass"), to("glass", tr(R.string.glass)))
        assertEquals(SettingsTarget(p, "Home-screen widgets"), to("icons", tr(R.string.home_screen_widgets)))
        // Search opens each line at a group the page draws (or would, for the widgets).
        val groups = defaultGroups + "Home-screen widgets"
        val lines = SettingsIndex.entries.filter { it.page == p && !it.isPage }
        assertTrue(lines.size >= 25)
        for (e in lines) assertTrue("${e.title} › ${e.group}", e.group in groups)
        // What moved to Looks is no longer listed here.
        assertTrue(lines.none { it.title == tr(R.string.customize_by_tapping) })
        // The old names still find the rows that took them over.
        fun finds(query: String, title: String) = assertTrue("\"$query\" finds $title", SettingsIndex.search(query).any { it.page == p && it.title == title })
        finds("pure black", "Theme"); finds("amoled", "Theme"); finds("frosted pages", "Glass also on"); finds("artist album pages", "Page colors")
        finds("spectrum", "Preset"); finds("motion", "Reduce motion")
    }

    // ------------------------------------------------------------------ Drawn for real (PNGs in app/build/settings-pages/theme-colors/)

    private fun shot(name: String, glass: Boolean, scroll: Boolean = false) {
        show(glass = glass) { ThemeColorsPage(0.dp, onSearch = {}) }
        if (scroll) { compose.onNodeWithTag(SettingsTags.PAGE_LIST).performScrollToIndex(5); compose.waitForIdle() }
        val bitmap = save("theme-colors", name)
        assertEquals("360 dp wide at xxhdpi", 1080, bitmap.width)
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test fun renderLight() {
        prefs.setThemeMode(ThemeMode.LIGHT); await { stored.themeMode == ThemeMode.LIGHT }
        shot("light", glass = true)
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test fun renderDark() {
        prefs.setThemeMode(ThemeMode.DARK); await { stored.themeMode == ThemeMode.DARK }
        shot("dark", glass = true)
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test fun renderDarkScrolled() {
        prefs.setThemeMode(ThemeMode.DARK); await { stored.themeMode == ThemeMode.DARK }
        shot("dark-scrolled", glass = true, scroll = true)
        // The preview is a strip under the bar.
        compose.onNodeWithTag(SettingsTags.PREVIEW).assertExists()
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test fun renderPerformance() {
        prefs.setThemeMode(ThemeMode.DARK); prefs.setPerformanceMode(true); await { stored.themeMode == ThemeMode.DARK && stored.performanceMode }
        shot("performance", glass = false)
    }

    /** The whole page at once, to look every row over. */
    @Config(qualifiers = "w360dp-h2600dp-xhdpi")
    @Test fun renderWholePageDark() {
        prefs.setThemeMode(ThemeMode.DARK); await { stored.themeMode == ThemeMode.DARK }
        show(glass = true) { ThemeColorsPage(0.dp, onSearch = {}) }
        save("theme-colors", "whole-dark")
    }
}
