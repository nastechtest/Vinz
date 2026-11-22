package com.vinz.music

import androidx.datastore.preferences.core.booleanPreferencesKey
import com.vinz.music.data.looks.BuiltInLooks
import com.vinz.music.data.looks.LookStep
import com.vinz.music.data.looks.LookStore
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1.8.7.6: a new install's own defaults (`NewInstall`) are Vinz's own Look on that install. Since 1.8.6 the first run
 * left Settings → Looks saying "your own mix of settings", a History step nobody made ("Animated cover fills the player
 * → On and 4 more"), and every Look picked turned those five back off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NewInstallLookTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings by lazy { SettingsRepository(app, scope) }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The new install's defaults that are in a Look. */
    private val five = listOf("album_hero_cover", "backdrop_fade", "canvas_fills_background", "cover_flight", "cover_flight_back")

    private fun fiveOf(s: AppSettings) = listOf(s.view.albumHeroCover, s.view.backdropFade, s.behavior.canvas.fillsBackground, s.view.coverFlight, s.view.coverFlightBack)

    private val history get() = File(app.filesDir, "looks/history.json")

    /** The store and the Looks' files are one per JVM: start and leave them as Vinz comes. */
    @Before fun fresh() = runBlocking { tidyUp() }
    @After fun tidy() = runBlocking { tidyUp() }

    private suspend fun tidyUp() {
        settings.editNow { it.clear() }
        withTimeout(5_000) { settings.stored.first { !it.onboarded && it.view.coverFlight == AppSettings().view.coverFlight } }
        File(app.filesDir, "looks").deleteRecursively()
        app.getSharedPreferences("looks", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    private suspend fun onboard() {
        settings.setOnboarded(true)
        withTimeout(5_000) { settings.flow.first { it.onboarded && fiveOf(it).all { on -> on } } }
        // currentId is worked out off the main thread from the settings: give it a moment to catch up.
        delay(500)
    }

    /** Test 1: right after the first run, Vinz's own Look is the one in use. */
    @Test fun theFirstRunIsOnVinzsOwnLook(): Unit = runBlocking {
        val looks = LookStore(app, settings, scope)
        withTimeout(5_000) { looks.currentId.first { it == "vinz" } }
        onboard()
        assertEquals("the first run is on Vinz's Look", "vinz", looks.currentId.value)
    }

    /** Test 2: the first run leaves nothing in History. */
    @Test fun theFirstRunLeavesNothingInHistory(): Unit = runBlocking {
        val looks = LookStore(app, settings, scope)
        onboard()
        assertEquals("the first run leaves no History step", emptyList<LookStep>(), looks.steps.value)
    }

    /** Test 3: another Look keeps the new install's defaults it doesn't set itself, and keeps no unasked "Before <Look>". */
    @Test fun anotherLookKeepsTheNewInstallsDefaults(): Unit = runBlocking {
        val looks = LookStore(app, settings, scope)
        onboard()
        looks.apply(BuiltInLooks.of("paper")!!)
        withTimeout(5_000) { looks.currentId.first { it == "paper" } }
        val s = withTimeout(5_000) { settings.stored.first { it.themeMode.name == "LIGHT" } }
        assertTrue("Canvas still fills the player", s.behavior.canvas.fillsBackground)
        assertEquals(listOf(true, true, true, true, true), fiveOf(s))
        assertNull("nothing of the listener's to keep: Vinz's Look was on", looks.mine.value.firstOrNull { it.id == LookStore.BEFORE_ID })
        // And back to Vinz's own: as it came.
        looks.apply(BuiltInLooks.of("vinz")!!)
        withTimeout(5_000) { looks.currentId.first { it == "vinz" } }
        assertEquals(listOf(true, true, true, true, true), fiveOf(settings.stored.value))
    }

    /** Test 5: someone set up before 1.8.6 who signs in later isn't given the new install's defaults unasked. */
    @Test fun aLaterSignInOnAnInstallSetUpBeforeChangesNothing(): Unit = runBlocking {
        settings.editNow { it[booleanPreferencesKey("onboarded")] = true }
        settings.setOnboarded(true)
        settings.editNow { }
        val s = withTimeout(5_000) { settings.stored.first { it.onboarded } }
        assertEquals(listOf(false, false, false, false, false), fiveOf(s))
        assertFalse("music videos are still asked about in the player", settings.canvasVideosAnswered)
        assertFalse(s.sound.pauseFade)
    }

    // ------------------------------------------------------------ Test 4: the repair for 1.8.6 to 1.8.7.5

    private val firstRunStep = LookStep("Animated cover fills the player → On and 4 more", 1_000L, emptyMap(), five)
    private val usersStep = LookStep("Corners → Sharp", 2_000L, emptyMap(), listOf("corners"))

    /** An install set up on 1.8.6 to 1.8.7.5: onboarded, the nine written by the first run, and its History. */
    private suspend fun affected(steps: List<LookStep>?) {
        settings.editNow { p ->
            p[booleanPreferencesKey("onboarded")] = true
            p[booleanPreferencesKey("canvas_use_videos")] = true
            for (n in five + listOf("pause_fade", "artist_you", "finish_lyric", "canvas_lyrics")) p[booleanPreferencesKey(n)] = true
        }
        withTimeout(5_000) { settings.flow.first { it.onboarded && fiveOf(it).all { on -> on } } }
        if (steps != null) {
            history.parentFile!!.mkdirs()
            history.writeText(json.encodeToString(ListSerializer(LookStep.serializer()), steps))
        }
    }

    private suspend fun historyOnDisk(cond: (List<LookStep>) -> Boolean): List<LookStep> = withTimeout(5_000) {
        while (true) {
            val read = runCatching { json.decodeFromString(ListSerializer(LookStep.serializer()), history.readText()) }.getOrNull()
            if (read != null && cond(read)) return@withTimeout read
            delay(20)
        }
        @Suppress("UNREACHABLE_CODE") emptyList()
    }

    @Test fun theRepairTakesOutTheFirstRunsStepAndPutsVinzsLookBack(): Unit = runBlocking {
        affected(listOf(usersStep, firstRunStep))
        val looks = LookStore(app, settings, scope)
        assertEquals("the listener's own step stays", listOf(usersStep), looks.steps.value)
        assertEquals(listOf(usersStep), historyOnDisk { it.size == 1 })
        withTimeout(5_000) { looks.currentId.first { it == "vinz" } }

        // Again (Vinz started once more): nothing changes.
        val again = LookStore(app, settings, scope)
        assertEquals(listOf(usersStep), again.steps.value)
        delay(300)
        assertEquals(listOf(usersStep), historyOnDisk { true })
        assertEquals("vinz", again.currentId.value)
    }

    @Test fun theRepairLeavesTheListenersOwnStepsAlone(): Unit = runBlocking {
        // The same five, but changed by the listener (they were stored before): not the first run's.
        val theirs = LookStep("Cover flies into the player → Off and 4 more", 3_000L, five.associateWith { "b:1" }, five)
        affected(listOf(theirs, usersStep))
        val looks = LookStore(app, settings, scope)
        assertEquals(listOf(theirs, usersStep), looks.steps.value)
        delay(300)
        assertEquals(listOf(theirs, usersStep), historyOnDisk { true })
    }

    @Test fun theRepairNeverThrowsOnAMissingOrBrokenHistory(): Unit = runBlocking {
        affected(null)
        val none = LookStore(app, settings, scope)
        assertEquals(emptyList<LookStep>(), none.steps.value)
        withTimeout(5_000) { none.currentId.first { it == "vinz" } }

        tidyUp()
        affected(null)
        history.parentFile!!.mkdirs()
        history.writeText("{ not a history")
        val broken = LookStore(app, settings, scope)
        assertEquals(emptyList<LookStep>(), broken.steps.value)
        delay(300)
        assertEquals("a broken file is left as it is", "{ not a history", history.readText())
    }

    /** Someone who updated from before 1.8.6 (never given the five) is left as they are. */
    @Test fun theRepairLeavesAnOlderInstallAlone(): Unit = runBlocking {
        settings.editNow { p -> p[booleanPreferencesKey("onboarded")] = true; p[booleanPreferencesKey("cover_flight")] = true }
        withTimeout(5_000) { settings.flow.first { it.onboarded && it.view.coverFlight } }
        val looks = LookStore(app, settings, scope)
        delay(500)
        assertEquals("their own mix stays their own", null, looks.currentId.value)
        // Paper doesn't turn on what they never had.
        looks.apply(BuiltInLooks.of("paper")!!)
        val s = withTimeout(5_000) { settings.stored.first { it.themeMode.name == "LIGHT" } }
        assertEquals(listOf(false, false, false, false, false), fiveOf(s))
        looks.delete(LookStore.BEFORE_ID)
    }
}
