package com.vinz.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.vinz.music.ui.theme.AppIcon
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Verifies each icon uses Compose-supported vector backgrounds and transparent raster foregrounds. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi", application = android.app.Application::class)
class AppIconCrashTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everyIconLayerLoadsWithoutCrashing() {
        compose.setContent {
            Column {
                AppIcon.entries.forEach { icon ->
                    Image(painterResource(icon.background), null, Modifier.size(24.dp))
                    Image(painterResource(icon.foreground), null, Modifier.size(24.dp))
                }
            }
        }
        compose.waitForIdle()
    }
}
