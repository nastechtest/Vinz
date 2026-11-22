package com.vinz.music

import androidx.compose.ui.graphics.toArgb
import com.vinz.music.data.looks.Backgrounds
import com.vinz.music.data.looks.Look
import com.vinz.music.data.looks.LookCode
import com.vinz.music.data.looks.LookStore
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.SettingsRepository
import com.vinz.music.ui.theme.Backdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #413 (1.8.4): a solid color as a screen's background, kept in the backgrounds map as "COLOR|#RRGGBB": how it's read
 * and written, that Performance mode keeps it, and that a Look, a share code and a backup carry it unchanged.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class SolidColorBackdropTest {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()
    private fun repo() = SettingsRepository(app, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Test
    fun aColorIsReadAsItWasSaved() {
        val (style, detail) = Backdrop.parse("COLOR|#1A2B3C")
        assertEquals("COLOR", style.name)
        assertEquals("#1A2B3C", detail)
        // Typed in small letters (a Look written by hand): the same color, in the one spelling.
        assertEquals("COLOR" to "#1A2B3C", Backdrop.parse("COLOR|#1a2b3c").let { it.first.name to it.second })
    }

    @Test
    fun junkFallsBackToTheDefault() {
        for (junk in listOf(
            "COLOR", "COLOR|", "COLOR|#", "COLOR|#12", "COLOR|#GGGGGG", "COLOR|1A2B3C", "COLOR|#1A2B3C80", "COLOR|#1A2B3C|x",
            "COLOR|/data/user/0/x/secret.jpg", "COLOR| #1A2B3C", "color|#1A2B3C",
        )) assertEquals(junk, Backdrop.DEFAULT, Backdrop.parse(junk).first)
        // Under the right word, nothing of a wrong color is handed on either.
        for (junk in listOf("COLOR", "COLOR|#12", "COLOR|#GGGGGG", "COLOR|#1A2B3C80", "COLOR|/data/user/0/x/secret.jpg")) assertEquals(junk, null, Backdrop.parse(junk).second)
        assertEquals(null, Backdrop.solidColor("1A2B3C"))
        assertEquals(null, Backdrop.solidColor(null))
        // Written back in the one spelling, without any see-through part.
        assertEquals("COLOR|#1A2B3C", Backdrop.colorValue(0x801A2B3C.toInt()))
        assertEquals("COLOR|#00000A", Backdrop.colorValue(0xFF00000A.toInt()))
        assertEquals(0xFF1A2B3C.toInt(), Backdrop.solidColor(Backdrop.parse(Backdrop.colorValue(0xFF1A2B3C.toInt())).second)?.toArgb())
    }

    @Test
    fun performanceModeKeepsAColorAndStillDropsDesigns() {
        val e = AppSettings(
            performanceMode = true,
            backdrops = mapOf("HOME" to "COLOR|#1A2B3C", "SEARCH" to "SILK", "LIBRARY" to "AURORA", "REPLAY" to "PHOTO|/data/p.jpg", "PLAYER" to "COLOR|#FFFFFF"),
        ).effective()
        assertEquals(mapOf("HOME" to "COLOR|#1A2B3C", "REPLAY" to "PHOTO|/data/p.jpg", "PLAYER" to "COLOR|#FFFFFF"), e.backdrops)
    }

    @Test
    fun aSharedLookCarriesItsColor() {
        val kinds = repo().lookKinds
        val look = Look(
            "x", "Mine",
            values = mapOf(Look.BACKDROPS to Backgrounds.tag(mapOf("HOME" to "COLOR|#1A2B3C", "SEARCH" to "AURORA", "LIBRARY" to "COLOR|/data/user/0/x/secret.jpg"))),
        )
        val back = LookCode.decode(LookCode.encode(look), kinds)!!
        // The color travels as it is; a path under the same word still never does.
        assertEquals(mapOf("HOME" to "COLOR|#1A2B3C", "SEARCH" to "AURORA"), Backgrounds.parse(back.values[Look.BACKDROPS]))
    }

    @Test
    fun aSavedLookAndABackupCarryIt(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = SettingsRepository(app, scope)
        // The settings store is one per process: cleared after, so later test classes start from nothing.
        try {
            val picked = mapOf("HOME" to "COLOR|#1A2B3C", "SEARCH" to "COLOR|#FFE066")
            settings.setBackdrops(picked)
            withTimeout(5_000) { settings.stored.first { it.backdrops == picked } }
            withTimeout(5_000) { settings.lookValues.first { Backgrounds.parse(it[Look.BACKDROPS]) == picked } }

            // Saved as a Look, and as that Look is put back on.
            val looks = LookStore(app, settings, scope)
            val mine = looks.saveCurrent("Solid")
            assertEquals(picked, Backgrounds.parse(mine.values[Look.BACKDROPS]))
            assertEquals(picked, Backgrounds.parse(looks.resolved(mine)[Look.BACKDROPS]))
            assertEquals(picked, settings.previewLook(looks.resolved(mine)).backdrops)

            // A backup's settings, restored over other backgrounds.
            val backup = settings.forBackup(settings = true, accounts = false)
            settings.setBackdrops(mapOf("HOME" to "AURORA"))
            withTimeout(5_000) { settings.stored.first { it.backdrops == mapOf("HOME" to "AURORA") } }
            settings.fromBackup(backup, settings = true, accounts = false)
            assertEquals(picked, withTimeout(5_000) { settings.stored.first { it.backdrops == picked } }.backdrops)
            looks.delete(mine.id)
        } finally {
            settings.editNow { it.clear() }
        }
    }
}
