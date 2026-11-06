package com.vinz.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for Customize Vinz → Icons & shapes. The page is lazy, so reaching its end creates controls
 * that are not present when it first opens; both locale paths exercise those deferred icon previews.
 */
@RunWith(AndroidJUnit4::class)
class IconsShapesScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    @Test fun iconsAndShapesOpensScrollsAndReturnsInEnglish() = exerciseIconsAndShapes()

    @Test fun iconsAndShapesOpensScrollsAndReturnsInSwahili() {
        val context = Fixture.context
        val configuration = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag("sw"))
        }
        Strings.resources = context.createConfigurationContext(configuration).resources
        try {
            exerciseIconsAndShapes()
        } finally {
            Strings.resources = null
        }
    }

    private fun exerciseIconsAndShapes() {
        Fixture.ready()
        Fixture.launch()
        val settings = tr(R.string.settings)
        val customize = tr(R.string.customize_vinz)
        val iconsAndShapes = tr(R.string.icons_shapes)
        compose.openSettings(settings)
        compose.clickWhenFound(hasText(customize) and hasClickAction(), 15_000)
        compose.clickWhenFound(hasText(iconsAndShapes) and hasClickAction(), 15_000)
        compose.waitForText(iconsAndShapes)
        scrollToEnd()
        device.pressBack()
        compose.waitForText(customize)
    }

    /** Swipes the lazy settings page to its last group, stopping once the visible semantics stop changing. */
    private fun scrollToEnd() {
        var previous = ""
        repeat(40) {
            val scrollables = compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes()
            if (scrollables.isEmpty()) return
            compose.onAllNodes(hasScrollToNodeAction())[0].performTouchInput { swipeUp() }
            compose.waitForIdle()
            val visible = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
                .joinToString("|") { node ->
                    node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() ?: ""
                }
            if (visible == previous) return
            previous = visible
        }
    }
}
