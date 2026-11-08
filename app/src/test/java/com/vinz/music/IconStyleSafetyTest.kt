package com.vinz.music

import android.content.Context
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.datastore.preferences.core.floatPreferencesKey
import com.vinz.music.data.prefs.SettingsRepository
import com.vinz.music.ui.theme.IconFill
import com.vinz.music.ui.theme.IconPack
import com.vinz.music.ui.theme.IconStyle
import com.vinz.music.ui.theme.IconWeight
import com.vinz.music.ui.theme.VinzIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Regression coverage for corrupt, old and valid icon-style preferences. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class IconStyleSafetyTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("icon_style", Context.MODE_PRIVATE)

    @After
    fun resetIconStyle() {
        prefs.edit().clear().commit()
        IconStyle.load(context)
    }

    @Test
    fun wronglyTypedPreferencesFallBackWithoutThrowing() {
        prefs.edit()
            .putBoolean("weight", true)
            .putString("rounded", "false")
            .putBoolean("pack", true)
            .putBoolean("fill", true)
            .putString("like_star", "true")
            .commit()

        assertEquals(IconWeight.REGULAR to true, IconStyle.saved(context))
        assertEquals(IconPack.VINZ, IconStyle.savedPack(context))
        assertEquals(IconFill.OUTLINE, IconStyle.savedFill(context))
        assertEquals(false, IconStyle.savedLikeStar(context))
        IconStyle.load(context)
        assertEquals(IconWeight.REGULAR, IconStyle.weight)
        assertEquals(true, IconStyle.rounded)
        assertEquals(IconPack.VINZ, IconStyle.pack)
        assertEquals(IconFill.OUTLINE, IconStyle.fill)
        assertEquals(false, IconStyle.likeStar)
    }

    @Test
    fun unknownEnumNamesFallBackToDefaults() {
        prefs.edit()
            .putString("weight", "HEAVY")
            .putBoolean("rounded", false)
            .putString("pack", "RETRO_FUTURE")
            .putString("fill", "GLOW")
            .putBoolean("like_star", true)
            .commit()

        assertEquals(IconWeight.REGULAR to false, IconStyle.saved(context))
        assertEquals(IconPack.VINZ, IconStyle.savedPack(context))
        assertEquals(IconFill.OUTLINE, IconStyle.savedFill(context))
        assertEquals(true, IconStyle.savedLikeStar(context))
    }

    @Test
    fun validPreferencesAreKeptThenLoadAndCleanupResetDrawnState() {
        IconStyle.save(context, IconWeight.BOLD, false)
        IconStyle.savePack(context, IconPack.DOTS)
        IconStyle.saveFill(context, IconFill.DUOTONE)
        IconStyle.saveLikeStar(context, true)

        assertEquals(IconWeight.BOLD to false, IconStyle.saved(context))
        assertEquals(IconPack.DOTS, IconStyle.savedPack(context))
        assertEquals(IconFill.DUOTONE, IconStyle.savedFill(context))
        assertEquals(true, IconStyle.savedLikeStar(context))
        IconStyle.load(context)
        assertEquals(IconWeight.BOLD, IconStyle.weight)
        assertEquals(false, IconStyle.rounded)
        assertEquals(IconPack.DOTS, IconStyle.pack)
        assertEquals(IconFill.DUOTONE, IconStyle.fill)
        assertEquals(true, IconStyle.likeStar)

        prefs.edit().clear().commit()
        IconStyle.load(context)
        assertEquals(IconWeight.REGULAR, IconStyle.weight)
        assertEquals(true, IconStyle.rounded)
        assertEquals(IconPack.VINZ, IconStyle.pack)
        assertEquals(IconFill.OUTLINE, IconStyle.fill)
        assertEquals(false, IconStyle.likeStar)
    }

    @Test
    fun everyPackAndFillPreviewBuildsIncludingCupertinoSmoke() {
        IconPack.entries.forEach { pack ->
            IconFill.entries.forEach { fill ->
                val preview = VinzIcons.preview("Home", fill, pack)
                assertDraws("$pack / $fill", preview)
            }
        }
        val cupertino = VinzIcons.cupertinoPreview()
        assertEquals(4, cupertino.size)
        cupertino.forEachIndexed { index, preview -> assertDraws("Cupertino preview #$index", preview) }
    }

    @Test
    fun rawCornerScaleDecoderDropsNaNAndClampsFiniteValues(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = SettingsRepository(context, scope)
        val cornerScale = floatPreferencesKey("corner_scale")
        try {
            settings.editNow { it[cornerScale] = 3f }
            assertEquals(2f, withTimeout(5_000) {
                settings.flow.first { it.shapes.cornerScale == 2f }
            }.shapes.cornerScale)

            settings.editNow { it[cornerScale] = Float.NaN }
            assertEquals(null, withTimeout(5_000) {
                settings.flow.first { it.shapes.cornerScale == null }
            }.shapes.cornerScale)

            settings.editNow { it[cornerScale] = -1f }
            assertEquals(0f, withTimeout(5_000) {
                settings.flow.first { it.shapes.cornerScale == 0f }
            }.shapes.cornerScale)
        } finally {
            settings.editNow { it.remove(cornerScale) }
            scope.cancel()
        }
    }

    private fun assertDraws(name: String, icon: ImageVector) {
        assertTrue("$name has no vector nodes", icon.root.iterator().hasNext())
    }
}
