package com.vinz.music

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.vinz.music.ui.screens.settings.SettingsMap
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every Settings page opens and scrolls to its end without crashing (1.7.9's check: Downloads, storage & data crashed
 * as soon as its Smart Downloads estimate passed 1,000 songs, "1,363" read back as a number). Pages are lazy, so each
 * one is scrolled all the way down: a group below the fold is only drawn then.
 *
 * Since 1.8.4 the pages are the 17 of [SettingsMap], each one tap from the root (no pages inside pages); the one screen
 * still opened from a page here is the licenses, from Backup & about.
 */
@RunWith(AndroidJUnit4::class)
class SettingsPagesTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    /** The root, top to bottom: its five groups' pages. */
    private fun everyPage(): List<SettingsPageId> = SettingsMap.groups.flatMap { it.second }

    @Test fun everySettingsPageOpensAndScrolls() {
        Fixture.ready()
        Fixture.launch()
        compose.openSettings()
        assertEquals("the map's 17 pages", 17, everyPage().size)
        walk(everyPage())
    }

    /**
     * The same in Spanish (#255: 1.7.9's Icons & shapes closed Vinz in every language but English, its examples asking
     * for drawings by their translated names, "Inicio"). Vinz reads every word through [Strings.resources], so the
     * Spanish ones are handed to it before it starts; page titles come from the same place.
     */
    @Test fun everySettingsPageOpensInSpanish() = walkIn("es")

    /**
     * And in Arabic's words (longer ones, six plural forms). Not laid out right to left: Android applies a per-app
     * language by restarting the app, and here the app is the test's own process, so that part is checked by hand.
     */
    @Test fun everySettingsPageOpensInArabic() = walkIn("ar")

    /**
     * #208 (1.7.9.2): with "Slide between screens" on, pages slide in and Back slides them out: every page still
     * opens, and Back still lands on the page before (a slide left half-way would leave the root unreachable).
     */
    @Test fun pagesSlideInAndBackSlidesThemOut() {
        Fixture.ready()
        kotlinx.coroutines.runBlocking { Fixture.container.settings.setPageSlide(true) }
        try {
            Fixture.launch()
            compose.openSettings()
            walk(listOf(SettingsPageId.THEME_COLORS, SettingsPageId.PLAYER, SettingsPageId.GESTURES_BUTTONS), licenses = false)
            // Back on the root: another page's row is there to tap.
            compose.openFromSettingsRoot(SettingsPageId.DOWNLOADS_DATA)
        } finally {
            kotlinx.coroutines.runBlocking { Fixture.container.settings.setPageSlide(false) }
        }
    }

    private fun walkIn(tag: String) {
        val ctx = Fixture.context
        val config = android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(tag)) }
        Strings.resources = ctx.createConfigurationContext(config).resources
        try {
            Fixture.ready()
            Fixture.launch()
            // The pages' titles are read where Vinz reads them (SettingsPageId), so they are in the language it shows.
            compose.openSettings(tr(R.string.settings))
            walk(everyPage())
        } finally {
            Strings.resources = null
        }
    }

    /** Each page from the root: opened by its row, scrolled to its end, and Back to the root. */
    private fun walk(pages: List<SettingsPageId>, licenses: Boolean = true) {
        for (page in pages) {
            compose.openFromSettingsRoot(page)
            scrollToEnd()
            if (licenses && page == SettingsPageId.BACKUP_ABOUT) {
                // Its row is in "About" (the page is at its end now; the row is scrolled back to).
                compose.clickWhenFound(settingsRow(tr(R.string.open_source_licenses)), 15_000)
                compose.waitForIdle()
                scrollToEnd()
                back()
            }
            // Back on the root (kept where it was scrolled; the next page's row is scrolled to when opened).
            back()
            compose.waitUntil(10_000) { compose.onAllNodes(settingsRootRow(page)).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    /** Swipes the page up until nothing new comes into view (at most 40 swipes). */
    private fun scrollToEnd() {
        var last = ""
        repeat(40) {
            // A settings page's own list; a screen that isn't one (the licenses), its first list.
            val list = if (compose.onAllNodes(hasTestTag(SettingsTags.PAGE_LIST)).fetchSemanticsNodes().isNotEmpty()) hasTestTag(SettingsTags.PAGE_LIST) else hasScrollToNodeAction()
            if (compose.onAllNodes(list).fetchSemanticsNodes().isEmpty()) return
            compose.onAllNodes(list)[0].performTouchInput { swipeUp() }
            compose.waitForIdle()
            val now = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
                .joinToString("|") { n -> n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() ?: "" }
            if (now == last) return
            last = now
        }
    }

    private fun back() {
        device.pressBack()
        compose.waitForIdle()
    }
}
