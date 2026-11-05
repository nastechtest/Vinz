package com.vinz.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.vinz.music.ui.theme.AppIcon
import com.vinz.music.ui.theme.AppIconLayer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Verifies every adaptive-icon layer (including XML layer-lists) can be rendered by the settings preview. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi", application = android.app.Application::class)
class AppIconCrashTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun eachAppIconLayerLoadsWithoutCrashing() {
        compose.setContent {
            Column {
                AppIcon.entries.forEach { icon ->
                    AppIconLayer(icon.background, Modifier.size(24.dp))
                    AppIconLayer(icon.foreground, Modifier.size(24.dp))
                }
            }
        }
        compose.waitForIdle()
    }
}
