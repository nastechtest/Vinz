package com.vinz.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.SongEntity
import com.vinz.music.data.prefs.HomeSectionConfig
import com.vinz.music.data.prefs.HomeSections
import com.vinz.music.data.prefs.SectionStyle
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.screens.RecapCardDismissals
import com.vinz.music.ui.screens.RecapCardState
import com.vinz.music.ui.screens.RecapHomeCardTags
import com.vinz.music.ui.screens.ReplaySection
import com.vinz.music.ui.screens.forgetYearSoFar
import com.vinz.music.ui.screens.loadRecapCard
import com.vinz.music.ui.screens.rememberRecapCard
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.TimeZone

/**
 * 1.8.5, the monthly recap card on Home (plan §3.1): in a month's first week, last month's recap under the Replay card,
 * saying the month, the time listened (as Replay is set to show it) and the top song; nothing on day 8 or after a light
 * month; closed for its month and back for the next; a tap opens that month's story on Replay; in December's first week,
 * the Rewind card above it. Drawn as Home draws its Replay section, with a real container and seeded plays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2000dp-xhdpi", application = android.app.Application::class)
class RecapHomeCardTest {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val c by made
    private val oldZone: TimeZone = TimeZone.getDefault()
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private var nav: NavHostController? = null
    private var state: RecapCardState? = null

    @Before fun fresh() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        c.settings.editNow { it.clear() }
        withContext(Dispatchers.IO) { c.db.clearAllTables() }
        RecapCardDismissals.clear(c.app)
        forgetYearSoFar()
        c.recapRequest.value = null
        c.db.songs().upsertAll(listOf(
            SongEntity("s1", "Midnight City", "M83", "UCm83", "Hurry Up, We're Dreaming", null, 243, null, primaryArtist = "M83"),
            SongEntity("s2", "Teardrop", "Massive Attack", "UCma", "Mezzanine", null, 330, null, primaryArtist = "Massive Attack"),
        ))
    }

    @After fun close() {
        forgetYearSoFar()
        TimeZone.setDefault(oldZone)
        runBlocking { runCatching { c.settings.editNow { it.clear() } } }
        made.closeAfterTest()
    }

    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    /** [minutes] of [song] on [day] of [month], one 10-minute play at a time. */
    private fun listen(month: YearMonth, day: Int, song: String, minutes: Int) = runBlocking {
        repeat(minutes / 10) { i ->
            c.db.plays().insert(PlayEventEntity(songId = song, timestamp = at(month.year, month.monthValue, day, 9 + i % 12), playedMs = 10 * 60_000L))
        }
    }

    /** October 2026: 70 minutes, Midnight City the most. */
    private fun october() {
        listen(YearMonth.of(2026, 10), 4, "s1", 50)
        listen(YearMonth.of(2026, 10), 20, "s2", 20)
    }

    /** Home's Replay section at [now], as Home draws it (Spotlight, the default look). */
    private fun show(now: Long, hours: Boolean = false) {
        compose.setContent {
            val controller = rememberNavController().also { nav = it }
            val s by c.settings.stored.collectAsState()
            val shown = s.copy(replayHours = hours)
            CompositionLocalProvider(LocalContainer provides c, LocalNavigator provides Navigator(controller, openPlayer = {})) {
                VinzTheme(shown, artworkColor = null) {
                    CompositionLocalProvider(LocalAppSettings provides shown) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            NavHost(controller, "home") {
                                composable("home") {
                                    val recap = rememberRecapCard(wanted = true) { now }.also { state = it }
                                    ReplaySection(HomeSectionConfig(HomeSections.REPLAY, "Replay teaser", style = SectionStyle.HERO), recap, now)
                                }
                                composable(Routes.REPLAY) { Box(Modifier.fillMaxSize()) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { state?.loaded == true }
        compose.waitForIdle()
    }

    private fun cards() = compose.onAllNodes(hasTestTag(RecapHomeCardTags.CARD)).fetchSemanticsNodes()
    private fun has(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun theFirstWeekShowsLastMonth() {
        october()
        show(at(2026, 11, 3))
        assertEquals("one recap card", 1, cards().size)
        assertTrue("says the month", has("Your October"))
        assertTrue("the time and the top song", has("70 minutes · Midnight City"))
        assertEquals(YearMonth.of(2026, 10), state?.card?.month)
    }

    @Test fun theTimeIsInHoursWhenReplayShowsHours() {
        october()
        show(at(2026, 11, 7, 23), hours = true)
        assertTrue("70 minutes as Replay shows hours", has("1h 10m · Midnight City"))
    }

    @Test fun goneOnTheEighth() {
        october()
        show(at(2026, 11, 8, 0))
        assertTrue(cards().isEmpty())
        assertNull(state?.card)
    }

    @Test fun noCardAfterALightMonth() {
        listen(YearMonth.of(2026, 10), 4, "s1", 30)   // 30 minutes: not more than a light month
        show(at(2026, 11, 2))
        assertTrue(cards().isEmpty())
    }

    @Test fun closedForItsMonth() {
        october()
        show(at(2026, 11, 2))
        compose.onNodeWithTag(RecapHomeCardTags.CLOSE).performClick()
        compose.waitForIdle()
        assertTrue("gone at once", cards().isEmpty())
        assertTrue(RecapCardDismissals.isDismissed(c.app, YearMonth.of(2026, 10)))
        // Home again later that week: still put away (while the month itself still has its recap).
        assertNotNull(runBlocking { c.recaps.card(at(2026, 11, 5)) })
        assertNull("October's stays closed", runBlocking { loadRecapCard(c, at(2026, 11, 5)) })
    }

    @Test fun nextMonthsCardComesBackAfterOneWasClosed() {
        october()
        listen(YearMonth.of(2026, 11), 10, "s2", 40)
        RecapCardDismissals.dismiss(c.app, YearMonth.of(2026, 10))
        show(at(2026, 12, 2))
        assertEquals("November's card is back", 1, cards().size)
        assertTrue(has("Your November"))
        assertFalse(RecapCardDismissals.isDismissed(c.app, YearMonth.of(2026, 11)))
    }

    @Test fun aTapOpensThatMonthsStoryOnReplay() {
        october()
        show(at(2026, 11, 3))
        compose.onNodeWithTag(RecapHomeCardTags.CARD).performClick()
        compose.waitForIdle()
        assertEquals(YearMonth.of(2026, 10), c.recapRequest.value)
        assertEquals(Routes.REPLAY, nav?.currentDestination?.route)
    }

    @Test fun inDecembersFirstWeekRewindComesFirst() {
        october()
        listen(YearMonth.of(2026, 11), 10, "s2", 40)
        show(at(2026, 12, 3))
        compose.waitUntil(10_000) { has("REWIND 2026") }
        val rewind = compose.onAllNodes(hasText("REWIND 2026"), useUnmergedTree = true).fetchSemanticsNodes().first()
        val recap = cards().single()
        assertTrue(has("Your November"))
        assertTrue("Rewind's card is above the recap", rewind.boundsInRoot.bottom <= recap.boundsInRoot.top)
        assertNotNull(state?.card)
    }
}
