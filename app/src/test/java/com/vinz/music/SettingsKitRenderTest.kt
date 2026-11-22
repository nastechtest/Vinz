package com.vinz.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.components.LocalFrosted
import com.vinz.music.ui.screens.ShowOption
import com.vinz.music.ui.screens.settings.ChoiceRow
import com.vinz.music.ui.screens.settings.LanguageRegionContent
import com.vinz.music.ui.screens.settings.LinkRow
import com.vinz.music.ui.screens.settings.PageReset
import com.vinz.music.ui.screens.settings.PickCards
import com.vinz.music.ui.screens.settings.PickList
import com.vinz.music.ui.screens.settings.PickRow
import com.vinz.music.ui.screens.settings.SettingsPageScaffold
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.ShowChipsRow
import com.vinz.music.ui.screens.settings.SliderRow
import com.vinz.music.ui.screens.settings.SwitchRow
import com.vinz.music.ui.theme.Backdrop
import com.vinz.music.ui.theme.BackdropLayer
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.8.4, the settings kit drawn for real, for the owner's look before the pages are built (plan §2.10 step 1): the
 * sample page (Language & region) in light, dark and Performance mode at 360 dp, and a page that shows every part of
 * the kit (the pinned preview, jump chips, the five rows, the two sheets). PNGs go to app/build/settings-kit/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h760dp-xxhdpi")
class SettingsKitRenderTest {
    @get:Rule val compose = createComposeRule()

    /** [glass]: on a background, as the app's frosted pages are (the Spectrum field stands in for the ambient frost). */
    private fun show(settings: AppSettings, glass: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            VinzTheme(settings, artworkColor = null) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (glass) BackdropLayer(Backdrop.SPECTRUM, null, 0.35f, motion = false, modifier = Modifier.fillMaxSize())
                    CompositionLocalProvider(LocalFrosted provides glass) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun save(name: String): Bitmap {
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/settings-kit").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return shot
    }

    private fun languageRegion(name: String, settings: AppSettings, glass: Boolean) {
        show(settings, glass) { LanguageRegionContent(0.dp, AppLanguage.PHONE, "DE", {}, {}, onBack = {}, onSearch = {}) }
        val shot = save(name)
        assertEquals("360 dp wide at xxhdpi", 1080, shot.width)
        assertEquals(2, compose.onAllNodes(hasTestTag(SettingsTags.ROW_PICK)).fetchSemanticsNodes().size)
    }

    @Test fun languageRegionLight() = languageRegion("language-region-light", AppSettings(themeMode = ThemeMode.LIGHT), glass = true)
    @Test fun languageRegionDark() = languageRegion("language-region-dark", AppSettings(themeMode = ThemeMode.DARK), glass = true)

    /** Performance mode: no frosted page behind it, and the card a plain surface. */
    @Test fun languageRegionPerformance() = languageRegion("language-region-performance", AppSettings(themeMode = ThemeMode.DARK, performanceMode = true), glass = false)

    /** A stand-in for a page's live preview: a cover, two lines and a progress bar (just the cover and lines as a strip). */
    @Composable
    private fun SamplePreview(strip: Boolean) {
        val cover = Brush.linearGradient(listOf(Color(0xFFFF9A62), Color(0xFFC94B7C), Color(0xFF3A3A8C)))
        if (strip) androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(cover))
            Column(Modifier.padding(start = 12.dp)) {
                Text("Test title", style = MaterialTheme.typography.titleSmall)
                Text("Test artist", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(100.dp).clip(RoundedCornerShape(14.dp)).background(cover))
            Text("Test title", Modifier.fillMaxWidth().padding(top = 10.dp), style = MaterialTheme.typography.titleSmall)
            Text("Test artist", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)))
        }
    }

    @Composable
    private fun EveryPart() {
        SettingsPageScaffold(
            "Player", bottomPadding = 0.dp, onBack = {}, onSearch = {}, reset = PageReset(listOf("Background: Blurred cover → Cover colors")) {},
            preview = { strip -> SamplePreview(strip) },
        ) {
            group("Layout") {
                PickRow("Layout", "Classic", thumbnail = { Box(Modifier.fillMaxSize().background(Color(0xFF1D1D26))) }) {}
                PickRow("Background", "Blurred cover", changed = true, thumbnail = { Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF6D4A8F), Color(0xFF23233A))))) }) {}
                SwitchRow("Ring on the play button", true, info = "A ring of your light round the play button.") {}
            }
            group("Progress bar") {
                PickRow("Style", "Wave") {}
                ChoiceRow("Color", listOf("Your light", "Accent", "White"), "Your light", { it }) {}
                SliderRow("Thickness", 4f, 2f..8f, { "${it.toInt()} dp" }, changed = true) {}
            }
            group("Cover") {
                ChoiceRow("Edge to edge", listOf("Off", "Cover", "Video", "Both"), "Off", { it }) {}
                SwitchRow("Cover flies into place", false, value = "Opening the player") {}
            }
            group("On it") {
                ShowChipsRow("Show", listOf(ShowOption("Like", true) {}, ShowOption("Lyrics", true) {}, ShowOption("Queue", true) {}, ShowOption("Sleep timer", false) {}, ShowOption("Speed", false) {}))
                LinkRow("Lock screen and desk mode", value = "Cover layout", icon = com.vinz.music.ui.theme.VinzIcons.Settings) {}
            }
        }
    }

    @Test fun everyPartDark() {
        show(AppSettings(themeMode = ThemeMode.DARK), glass = true) { EveryPart() }
        save("kit-dark")
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsTags.JUMP_CHIPS)).fetchSemanticsNodes().size)
        // Scrolled: the preview is a strip under the bar, the title sits in the bar.
        compose.onNodeWithTag(SettingsTags.PAGE_LIST).performScrollToIndex(4)
        compose.waitForIdle()
        save("kit-dark-scrolled")
    }

    @Test fun everyPartLight() {
        show(AppSettings(themeMode = ThemeMode.LIGHT), glass = true) { EveryPart() }
        save("kit-light")
    }

    @Test fun everyPartPerformance() {
        show(AppSettings(themeMode = ThemeMode.DARK, performanceMode = true), glass = false) { EveryPart() }
        save("kit-performance")
    }

    @Test fun everyPartAtLargeText() {
        show(AppSettings(themeMode = ThemeMode.DARK, textScale = 1.5f), glass = true) { EveryPart() }
        save("kit-dark-large-text")
    }

    /** The two sheets' contents, as they sit in the sheet (drawn here without the sheet's window, which a capture of the page leaves out). */
    @Test fun sheets() {
        val styles = listOf("Line", "Wave", "Thick", "Steps", "Thin", "Dotted")
        show(AppSettings(themeMode = ThemeMode.DARK), glass = false) {
            Column(Modifier.fillMaxSize(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                Column(Modifier.clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(top = 22.dp)) {
                    PickCards("Progress bar style", styles, "Wave", { it }, "How the line under the song is drawn.", 2, 0.8f, { style ->
                        Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxWidth().height(if (style == "Thick") 8.dp else 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)))
                        }
                    }) {}
                }
                Column(Modifier.clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(top = 22.dp)) {
                    PickList("Content country", listOf("Automatic (follow the phone)", "United States", "Austria", "Belgium", "Germany"), "Germany", { it }, null, { null }, true) {}
                }
            }
        }
        val shot = save("sheets-dark")
        assertTrue(shot.height > 0)
    }

    /** The 1.8.4 kit fixes drawn: the bar's title once scrolled (the longest page name), and the new pieces at large text. */
    private fun barTitle(name: String, title: String, fontScale: Float) {
        compose.setContent {
            val d = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, fontScale)) {
                VinzTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        SettingsPageScaffold(title, 0.dp, onBack = {}, onSearch = {}, reset = PageReset(emptyList()) {}) {
                            repeat(4) { g -> group("Group ${g + 1}") { repeat(5) { SwitchRow("Option ${it + 1}", it % 2 == 0) {} } } }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag(SettingsTags.PAGE_LIST).performScrollToIndex(3)
        compose.waitForIdle()
        save(name)
    }

    @Test fun barTitleLongest() = barTitle("bar-title-recommendations-1.0", "Recommendations & history", 1f)
    @Test fun barTitleLongestAt13() = barTitle("bar-title-recommendations-1.3", "Recommendations & history", 1.3f)
    @Test fun barTitleTabs() = barTitle("bar-title-tabs-1.0", "Tabs & mini player", 1f)

    @Test fun fixesAtLargeText() {
        show(AppSettings(themeMode = ThemeMode.DARK, textScale = 1.5f), glass = true) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 40.dp)) {
                com.vinz.music.ui.screens.settings.SettingsGroup("Kit fixes") {
                    ShowChipsRow("Show", listOf(ShowOption("Song and artist names in the bar", true) {}, ShowOption("Covers", false) {}, ShowOption("Durations", true) {}))
                    ShowChipsRow("Nothing ticked", listOf(ShowOption("Covers", false) {}, ShowOption("Durations", false) {}))
                    ChoiceRow("Density", listOf("Standard", "Balanced", "Compact", "Minimal"), "Standard", { it }) {}
                    ChoiceRow("Keep the screen on", listOf("Never", "Only while a song is playing", "Always"), "Never", { it }) {}
                    LinkRow("Back up now", value = "Last: yesterday", action = true) {}
                    LinkRow("Licenses") {}
                }
            }
        }
        save("kit-fixes-large-text")
    }

    @Test fun rowsSheet() {
        show(AppSettings(themeMode = ThemeMode.DARK), glass = false) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(top = 22.dp)) {
                    com.vinz.music.ui.screens.settings.SheetRows("Streaming quality", "What Vinz asks YouTube Music for.") {
                        SwitchRow("Higher on Wi-Fi", true) {}
                        PickRow("On mobile data", "Normal") {}
                        ChoiceRow("Video", listOf("Off", "Low", "High"), "Low", { it }) {}
                        LinkRow("Clear the cache", value = "212 MB", action = true) {}
                    }
                }
            }
        }
        save("rows-sheet-dark")
    }

    private val fewCountries = listOf("Automatic (follow the phone)", "United States", "Austria", "Belgium", "Germany")

    @Composable
    private fun ListSheet(picked: String) {
        Column(Modifier.clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(top = 22.dp)) {
            PickList("Content country", fewCountries, picked, { it }, null, { null }, true) {}
        }
    }

    /**
     * Reported from Language & region's sheet: "a stray mark under the search field". The list opens with the current
     * pick in view; picked far down, the first row is part-way off the top, and the feet of its letters showed as
     * marks right under the search field. The rows now fade out at the top of a scrolled list.
     */
    @Test fun noStrayMarkUnderTheListSheetsSearchField() {
        show(AppSettings(themeMode = ThemeMode.DARK), glass = false) { ListSheet("Germany") }
        val shot = save("list-sheet-pick-last")
        val search = compose.onNodeWithTag(SettingsTags.SHEET_SEARCH).fetchSemanticsNode().boundsInRoot
        // The list starts 8 dp under the search field.
        val top = (search.bottom + with(compose.density) { 8.dp.toPx() }).toInt() + 1
        fun far(a: Int, b: Int) = maxOf(
            kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
            kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
            kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)),
        )
        var marks = 0
        for (y in top until top + 3) {
            val plain = shot.getPixel(2, y)
            for (x in 0 until shot.width) if (far(shot.getPixel(x, y), plain) > 10) marks++
        }
        assertEquals("pixels of a cut-off row in the list's first three lines", 0, marks)
    }

    /** The other half of that report ("the current pick, Automatic, isn't at the top"): picked, it is the list's first row. */
    @Test fun thePickAtTheTopOfTheListSheetIsItsFirstRow() {
        show(AppSettings(themeMode = ThemeMode.DARK), glass = false) { ListSheet(fewCountries[0]) }
        save("list-sheet-pick-first")
        val search = compose.onNodeWithTag(SettingsTags.SHEET_SEARCH).fetchSemanticsNode().boundsInRoot
        val first = compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION)).fetchSemanticsNodes().first()
        assertEquals(true, first.config[androidx.compose.ui.semantics.SemanticsProperties.Selected])
        assertEquals(fewCountries[0], first.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text })
        assertEquals("whole, right under the search field", search.bottom + with(compose.density) { 8.dp.toPx() }, first.boundsInRoot.top, 1.5f)
    }
}
