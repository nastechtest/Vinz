package com.vinz.music

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.vinz.music.ui.screens.EQ_REFUSED_TAG
import com.vinz.music.ui.screens.EqRefusedLine
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #519: the Equalizer screen's line for when Android refuses the effects (the playback service's AudioEffects sets
 * `eqRefused`): shown with the equalizer on while refused, not otherwise, and gone again once Android makes them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2000dp-mdpi", application = android.app.Application::class)
class EqRefusedLineTest {
    @get:Rule val compose = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()
    private val made = lazy { AppContainer(app) }
    private val container by made
    private var setEnabled: ((Boolean) -> Unit)? = null

    @Before fun ready() {
        runBlocking { container.settings.editNow { it.clear(); it[booleanPreferencesKey("onboarded")] = true } }
        val until = System.currentTimeMillis() + 5_000
        while (!container.settings.stored.value.onboarded && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    @After fun clear() {
        container.eqRefused.value = false
        runCatching { runBlocking { container.settings.editNow { it.clear() } } }
        made.closeAfterTest()
    }

    private fun show(enabled: Boolean) {
        compose.setContent {
            val s by container.settings.stored.collectAsState()
            var on by androidx.compose.runtime.remember { mutableStateOf(enabled) }
            setEnabled = { on = it }
            CompositionLocalProvider(LocalContainer provides container) {
                VinzTheme(s, artworkColor = null) {
                    CompositionLocalProvider(LocalAppSettings provides s) { Column { EqRefusedLine(on) } }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun lines() = compose.onAllNodes(hasTestTag(EQ_REFUSED_TAG)).fetchSemanticsNodes().size

    @Test fun shownWhileRefusedWithTheEqualizerOn() {
        show(enabled = true)
        assertEquals("shown while Android makes the effects", 0, lines())
        container.eqRefused.value = true
        compose.waitForIdle()
        assertEquals(1, lines())
        assertTrue(compose.onAllNodes(hasText(tr(R.string.eq_refused))).fetchSemanticsNodes().size == 1)
        // Android making them again: the line goes.
        container.eqRefused.value = false
        compose.waitForIdle()
        assertEquals(0, lines())
    }

    @Test fun notShownWithTheEqualizerOff() {
        container.eqRefused.value = true
        show(enabled = false)
        assertEquals(0, lines())
        setEnabled!!(true)
        compose.waitForIdle()
        assertEquals(1, lines())
    }
}
