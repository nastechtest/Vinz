package com.vinz.music

import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.TypedValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.ui.theme.AppIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xmlpull.v1.XmlPullParser

/** PR #29's layer-list crash must not return when the launcher previews compose during a scroll. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi", application = android.app.Application::class)
class AppIconCrashTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everyPreviewLayerUsesAComposeSupportedResource() {
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        AppIcon.entries.forEach { icon ->
            listOf(icon.background, icon.foreground).forEach { resource ->
                val value = TypedValue()
                resources.getValue(resource, value, true)
                if (value.string.toString().endsWith(".xml")) {
                    val parser = resources.getXml(resource)
                    try {
                        while (parser.eventType != XmlPullParser.START_TAG && parser.eventType != XmlPullParser.END_DOCUMENT) {
                            parser.next()
                        }
                        assertEquals("${icon.name}: painterResource cannot render ${parser.name}", "vector", parser.name)
                    } finally {
                        parser.close()
                    }
                } else {
                    val bitmap = BitmapFactory.decodeResource(resources, resource)
                    assertNotNull("${icon.name}: preview must decode as a bitmap", bitmap)
                    bitmap?.recycle()
                }
            }
        }
    }

    @Test
    fun foregroundIsTransparentRatherThanAnOpaqueBackgroundTile() {
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        AppIcon.entries.forEach { icon ->
            val bitmap = BitmapFactory.decodeResource(resources, icon.foreground)
            assertNotNull("${icon.name}: foreground must be a transparent bitmap, not layer-list XML", bitmap)
            requireNotNull(bitmap)
            try {
                assertTrue("${icon.name}: foreground has no alpha channel", bitmap.hasAlpha())
                listOf(0 to 0, (bitmap.width - 1) to 0, 0 to (bitmap.height - 1), (bitmap.width - 1) to (bitmap.height - 1)).forEach { (x, y) ->
                    assertEquals("${icon.name}: foreground covers the background at $x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun everyIconLayerComposesWithoutCrashing() {
        compose.setContent {
            Column {
                AppIcon.entries.forEach { icon ->
                    Image(painterResource(icon.background), null, Modifier.size(24.dp).testTag("app-icon-layer"))
                    Image(painterResource(icon.foreground), null, Modifier.size(24.dp).testTag("app-icon-layer"))
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("app-icon-layer").assertCountEquals(AppIcon.entries.size * 2)
    }
}
