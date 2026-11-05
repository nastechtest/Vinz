package com.vinz.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.vinz.music.ui.theme.AppIcon
import com.vinz.music.ui.theme.AppIconPreview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Verifies all adaptive-icon vector and layer-list resources render through the Compose preview. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi", application = android.app.Application::class)
class AppIconCrashTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everyIconPreviewRendersWithoutCrashing() {
        compose.setContent {
            Column {
                AppIcon.entries.forEach { icon ->
                    AppIconPreview(icon, Modifier.size(60.dp))
                }
            }
        }
        compose.waitForIdle()
    }
}
