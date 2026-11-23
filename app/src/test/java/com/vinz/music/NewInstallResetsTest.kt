package com.vinz.music

import androidx.datastore.preferences.core.booleanPreferencesKey
import com.vinz.music.data.looks.BuiltInLooks
import com.vinz.music.data.looks.LookStore
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.Corners
import com.vinz.music.data.prefs.SettingsRepository
import com.vinz.music.data.prefs.asItComes
import com.vinz.music.ui.screens.settings.playerSetting
import com.vinz.music.ui.screens.settings.resetPlayerPage
import com.vinz.music.ui.screens.settings.themeColorsChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1.8.7.6, part two: on a phone whose first run wrote a new install's own defaults (the marker), "how Vinz comes" means
 * one thing. Reset look and a page's reset put the five back on, Settings' changed dots and reset lines don't count them,
 * and a phone without the marker behaves exactly as before. Also: a Look stays marked in use under Performance mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NewInstallResetsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings by lazy { SettingsRepository(app, scope) }

    /** The new install's defaults that are in a Look. */
    private val five = listOf("album_hero_cover", "backdrop_fade", "canvas_fills_background", "cover_flight", "cover_flight_back")
    private val allOn = List(5) { true }
    private val allOff = List(5) { false }

    private fun fiveOf(s: AppSettings) = listOf(s.view.albumHeroCover, s.view.backdropFade, s.behavior.canvas.fillsBackground, s.view.coverFlight, s.view.coverFlightBack)

    /** The store and the Looks' files are one per JVM: start and leave them as Vinz comes. */
    @Before fun fresh() = runBlocking { tidyUp() }
    @After fun tidy() = runBlocking { tidyUp() }

    private suspend fun tidyUp() {
        settings.editNow { it.clear() }
        withTimeout(5_000) { settings.stored.first { !it.onboarded && !it.performanceMode && it.view.coverFlight == AppSettings().view.coverFlight } }
        File(app.filesDir, "looks").deleteRecursively()
        app.getSharedPreferences("looks", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    private suspend fun onboard() {
        settings.setOnboarded(true)
        withTimeout(5_000) { settings.flow.first { it.onboarded && fiveOf(it).all { on -> on } } }
        delay(500)
    }

    private suspend fun stored(cond: (AppSettings) -> Boolean): AppSettings = withTimeout(5_000) { settings.stored.first(cond) }

    // ------------------------------------------------------------ The resets

    @Test fun resetLookAfterTheFirstRunIsVinzsOwnLook(): Unit = runBlocking {
        val looks = LookStore(app, settings, scope)
        onboard()
        settings.setCorners(Corners.SHARP)
        withTimeout(5_000) { looks.currentId.first { it == null } }
        settings.resetLook()
        withTimeout(5_000) { settings.lookValues.first { it["corners"] == null } }
        delay(500)
        assertEquals("Reset look leaves the five on", allOn, fiveOf(settings.stored.value))
        assertEquals("and Vinz's own Look is the one in use", "vinz", looks.currentId.value)
    }

    @Test fun aPlayerPageResetLeavesTheFiveOn(): Unit = runBlocking {
        onboard()
        settings.setPlayerTitleScroll(false)
        val changed = stored { !it.player.titleScroll }
        resetPlayerPage(settings, changed)
        val s = stored { it.player.titleScroll }
        delay(300)
        assertEquals("Canvas fill and the cover's flight stay on", allOn, fiveOf(settings.stored.value))
        assertTrue(s.onboarded)
    }

    @Test fun aThemePageResetLeavesTheBackgroundsFadeOn(): Unit = runBlocking {
        onboard()
        settings.setCorners(Corners.SHARP)
        stored { it.corners == Corners.SHARP }
        settings.resetLookKeys(listOf("backdrop_fade", "corners"))
        stored { it.corners != Corners.SHARP }
        delay(300)
        assertTrue(settings.stored.value.view.backdropFade)
    }

    // ------------------------------------------------------------ The changed dots and reset lines

    /** The one baseline the pages compare with is what the store reads as, as Vinz comes, with and without the marker. */
    @Test fun howItComesIsWhatTheStoreHolds(): Unit = runBlocking {
        assertEquals(AppSettings(), AppSettings().asItComes())
        settings.editNow { it[booleanPreferencesKey("new_install_look")] = true }
        val s = withTimeout(5_000) { settings.flow.first { it.behavior.newInstallLook } }
        assertEquals("only these five differ", allOn, fiveOf(s.asItComes()))
        assertEquals(settings.previewLook(emptyMap()), s.asItComes())
    }

    @Test fun afterTheFirstRunNoneOfTheFiveCountsAsChanged(): Unit = runBlocking {
        onboard()
        val s = settings.stored.value
        for (k in listOf("canvas_fills_background", "cover_flight", "cover_flight_back")) {
            assertFalse("$k carries no changed dot", playerSetting(k).changed(s))
        }
        assertEquals("Theme & colors lists nothing to reset", emptyList<String>(), themeColorsChanges(s, dark = true))
    }

    // ------------------------------------------------------------ Without the marker: as before

    @Test fun withoutTheMarkerEverythingIsAsBefore(): Unit = runBlocking {
        // Someone who turned the five on by hand (set up before 1.8.6): no marker.
        settings.editNow { p ->
            p[booleanPreferencesKey("onboarded")] = true
            five.forEach { p[booleanPreferencesKey(it)] = true }
        }
        val s = stored { it.onboarded && fiveOf(it) == allOn }
        for (k in listOf("canvas_fills_background", "cover_flight", "cover_flight_back")) assertTrue("$k counts as changed", playerSetting(k).changed(s))
        assertTrue("Theme & colors lists the screen backgrounds", themeColorsChanges(s, dark = true).isNotEmpty())
        // Player's reset puts its three off, as before; the other two stay.
        resetPlayerPage(settings, s)
        assertEquals(listOf(true, true, false, false, false), fiveOf(stored { !it.view.coverFlight && !it.behavior.canvas.fillsBackground }))
        // A page's reset by keys, then Reset look: all off.
        settings.resetLookKeys(listOf("backdrop_fade"))
        stored { !it.view.backdropFade }
        settings.resetLook()
        assertEquals(allOff, fiveOf(stored { fiveOf(it) == allOff }))
        // And with nothing stored, a fresh phone before its first run: Reset look is Vinz's Look, all five off.
        val looks = LookStore(app, settings, scope)
        withTimeout(5_000) { looks.currentId.first { it == "vinz" } }
    }

    // ------------------------------------------------------------ Performance mode

    @Test fun underPerformanceModeTheLookInUseIsStillMarked(): Unit = runBlocking {
        val looks = LookStore(app, settings, scope)
        onboard()
        assertEquals("vinz", looks.currentId.value)
        settings.setPerformanceMode(true)
        withTimeout(5_000) { settings.flow.first { it.performanceMode } }
        delay(500)
        assertEquals("Vinz's Look is still the one in use", "vinz", looks.currentId.value)
        looks.apply(BuiltInLooks.of("paper")!!)
        withTimeout(5_000) { looks.currentId.first { it == "paper" } }
    }
}
