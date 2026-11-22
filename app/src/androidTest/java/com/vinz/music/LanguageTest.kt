package com.vinz.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings → Language & region → Language (1.7.9; a page of its own before 1.8.4): search finds it, and its sheet offers
 * the phone's language and every one Vinz has. Picking one restarts Vinz, which would end the test, so that part is
 * checked by hand.
 */
@RunWith(AndroidJUnit4::class)
class LanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @Before fun start() {
        Fixture.ready()
        Fixture.launch()
    }

    @Test fun theLanguagePageListsTheLanguages() {
        compose.openSettings()
        compose.onNode(hasSetTextAction()).performTextInput("idioma")
        compose.clickWhenFound(hasText("Language") and hasClickAction())
        // Its page is Language & region since 1.8.4: the Language row says what is picked, and its sheet lists the
        // phone's language and every one Vinz has (a long list: each is scrolled to).
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        compose.openSettingsSheet("Language")
        seeOption("Your phone's language")
        AppLanguage.available.forEach { seeOption(AppLanguage.nativeName(it)) }
    }

    /** Waits for the open sheet's option [label], scrolling its list to it. */
    private fun seeOption(label: String) {
        val option = sheetOption(label)
        val end = System.currentTimeMillis() + 10_000
        while (compose.onAllNodes(option).fetchSemanticsNodes().isEmpty()) {
            if (System.currentTimeMillis() > end) throw AssertionError("The Language sheet doesn't list $label")
            compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().indices.forEach { i ->
                runCatching { compose.onAllNodes(hasScrollToNodeAction())[i].performScrollToNode(option) }
            }
            compose.waitForIdle()
        }
    }
}
