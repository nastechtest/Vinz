package com.vinz.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.data.db.PlayEventEntity
import com.vinz.music.ui.support.SupportAsks
import com.vinz.music.ui.support.SupportMarks
import com.vinz.music.ui.support.SupportTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.8.7.6, Support Vinz's 100-hour card (plan-1.8.7.6 §4.1 S4, §4.6 S-c): with 100 hours of plays on this phone and
 * Vinz installed weeks ago, Home shows the card; "Don't show again" takes it away for good, also after the activity is
 * made again; with "I've supported Vinz" on it never shows.
 */
@RunWith(AndroidJUnit4::class)
class SupportAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val title = "100 hours of music with Vinz"

    @Before fun seed() {
        Fixture.ready()
        val c = Fixture.container
        runBlocking {
            // Smart Downloads answered, so its card (an offer of its own) isn't on Home beside this one.
            c.settings.editNow { it[booleanPreferencesKey("smart_downloads")] = false }
            val now = System.currentTimeMillis()
            // 101 hours of this phone's own listening, an hour a play, over the last weeks.
            (1..101).forEach { i ->
                c.db.plays().insert(PlayEventEntity(songId = "tst${i.toString().padStart(8, '0')}", timestamp = now - i * 3_600_000L * 5, playedMs = 3_600_000L))
            }
            assertTrue(c.db.plays().totalMs(0, now) >= SupportAsks.HOURS_MS)
        }
        // Installed a month ago (a debug build's hook; a release build reads Android's install date).
        SupportMarks.testInstalledAt = System.currentTimeMillis() - 30L * 86_400_000L
    }

    @After fun clear() {
        SupportMarks.testInstalledAt = null
        Fixture.context.getSharedPreferences(SupportMarks.PREFS, 0).edit().clear().commit()
    }

    private fun cardShown() = compose.onAllNodes(hasTestTag(SupportTags.HOME_CARD)).fetchSemanticsNodes().isNotEmpty()

    @Test fun theCardShowsAndDontShowAgainEndsItForGood() {
        val scenario = Fixture.launch()
        compose.waitForText(title, 20_000)
        assertTrue(cardShown())
        compose.onAllNodes(hasText("Don't show again") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10_000) { !cardShown() }
        assertTrue(SupportAsks.HOME.key in SupportMarks(Fixture.context).shown())
        // The activity made again: the card stays away.
        scenario.recreate()
        compose.waitForTab("Home")
        Thread.sleep(3_000)
        compose.waitForIdle()
        assertTrue("the card came back", !cardShown())
        scenario.close()
    }

    @Test fun withTheSwitchOnItNeverShows() {
        runBlocking { Fixture.container.settings.editNow { it[booleanPreferencesKey("supported")] = true } }
        val scenario = Fixture.launch()
        compose.waitForTab("Home")
        Thread.sleep(3_000)
        compose.waitForIdle()
        assertTrue("the card shows with the switch on", !cardShown())
        assertTrue(SupportMarks(Fixture.context).shown().isEmpty())
        scenario.close()
    }
}
