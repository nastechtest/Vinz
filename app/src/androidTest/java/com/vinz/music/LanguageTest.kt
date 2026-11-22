package com.vinz.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings → Language (1.7.9): search finds it, and it offers the phone's language and every one Vinz has. Picking one
 * restarts Vinz, which would end the test, so that part is checked by hand.
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
        compose.waitForText("Your phone's language")
        AppLanguage.available.forEach { compose.waitForText(AppLanguage.nativeName(it)) }
    }
}
