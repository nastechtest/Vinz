package com.vinz.music

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.prism.music.data.db.PlayEventEntity
import com.vinz.music.ui.support.SupportAsks
import com.vinz.music.ui.support.SupportHomeCard
import com.vinz.music.ui.support.SupportMarks
import com.vinz.music.ui.support.SupportStoryLine
import com.vinz.music.ui.support.SupportTags
import com.vinz.music.ui.support.SupportWhatsNewLine
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.8.7.6, the three asks as they draw (plan-1.8.7.6 §4.1 S4–S6): the 100-hour card on Home, the line at the end of a
 * Replay or Rewind, and the line on a release's last What's new card. Each shows when [SupportAsks.due] says so, is
 * marked as shown, opens only Ko-fi, and is never drawn with "I've supported Vinz" on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2000dp-mdpi", application = android.app.Application::class)
class SupportLinesTest {
    @get:Rule val compose = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()
    private val made = lazy { AppContainer(app) }
    private val container by made
    private val opened = ArrayList<String>()

    @Before fun ready() {
        runBlocking { container.settings.editNow { it.clear(); it[booleanPreferencesKey("onboarded")] = true } }
        waitFor { container.settings.stored.value.onboarded }
        SupportMarks.testInstalledAt = System.currentTimeMillis() - 30L * 86_400_000L
        app.getSharedPreferences(SupportMarks.PREFS, 0).edit().clear().commit()
    }

    @After fun clear() {
        SupportMarks.testInstalledAt = null
        app.getSharedPreferences(SupportMarks.PREFS, 0).edit().clear().commit()
        runCatching { runBlocking { container.settings.editNow { it.clear() } } }
        runCatching { container.db.clearAllTables() }
        made.closeAfterTest()
    }

    private fun waitFor(ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!ok() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue(ok())
    }

    private var swap: ((Int) -> Unit)? = null

    /** [what]: 0 nothing, else one of the asks, swapped in place so a second showing is a new composition. */
    private fun show(start: Int, otherOffer: Boolean = false) {
        compose.setContent {
            val s by container.settings.stored.collectAsState()
            var what by androidx.compose.runtime.remember { mutableStateOf(start) }
            swap = { what = it }
            val links = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
            CompositionLocalProvider(LocalContainer provides container, LocalUriHandler provides links) {
                VinzTheme(s, artworkColor = null) {
                    CompositionLocalProvider(LocalAppSettings provides s) {
                        Column {
                            when (what) {
                                1 -> SupportStoryLine(rewind = false)
                                2 -> SupportStoryLine(rewind = true)
                                3 -> SupportWhatsNewLine(67, current = false)
                                4 -> SupportWhatsNewLine(67, current = true)
                                5 -> SupportHomeCard(otherOffer)
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun drawn(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
    private fun marks() = SupportMarks(app).shown()
    private fun again(what: Int) { swap!!(0); compose.waitForIdle(); swap!!(what); compose.waitForIdle() }

    @Test fun replaysLineOnceAndRewindsOnce() {
        show(1)
        assertEquals(1, drawn(SupportTags.STORY_LINE))
        assertEquals(setOf(SupportAsks.REPLAY.key), marks())
        compose.onNode(hasText(tr(R.string.support_vinz))).performClick()
        assertEquals(listOf(SupportAsks.KOFI_URL), opened)
        // The next Replay: gone.
        again(1)
        assertEquals(0, drawn(SupportTags.STORY_LINE))
        // The year's Rewind has its own once, but not on the same day as the Replay's.
        again(2)
        assertEquals("not two asks in a day", 0, drawn(SupportTags.STORY_LINE))
        SupportMarks.testNow = System.currentTimeMillis() + 86_400_000L
        try {
            again(2)
            assertEquals(1, drawn(SupportTags.STORY_LINE))
            assertEquals(setOf(SupportAsks.REPLAY.key, SupportAsks.REWIND.key), marks())
            again(2)
            assertEquals(0, drawn(SupportTags.STORY_LINE))
        } finally { SupportMarks.testNow = null }
    }

    @Test fun whatsNewLineOnlyWhenItsCardIsOnScreen() {
        show(3)
        assertEquals("a card beside the one on screen doesn't ask", 0, drawn(SupportTags.WHATS_NEW_LINE))
        assertTrue(marks().isEmpty())
        swap!!(4); compose.waitForIdle()
        assertEquals(1, drawn(SupportTags.WHATS_NEW_LINE))
        assertEquals(setOf(SupportAsks.whatsNew(67).key), marks())
        again(4)
        assertEquals("once per release", 0, drawn(SupportTags.WHATS_NEW_LINE))
    }

    @Test fun nothingWithTheSwitchOn() {
        runBlocking { container.settings.editNow { it[booleanPreferencesKey("supported")] = true } }
        waitFor { container.settings.stored.value.behavior.supported }
        hundredHours()
        show(0)
        for (what in 1..5) {
            again(what)
            compose.waitForIdle()
            assertEquals(0, drawn(SupportTags.STORY_LINE) + drawn(SupportTags.WHATS_NEW_LINE) + drawn(SupportTags.HOME_CARD))
        }
        assertTrue(marks().isEmpty())
    }

    private fun hundredHours() = runBlocking {
        val now = System.currentTimeMillis()
        (1..100).forEach { container.db.plays().insert(PlayEventEntity(songId = "s$it", timestamp = now - it * 18_000_000L, playedMs = 3_600_000L)) }
    }

    @Test fun theHomeCardAt100HoursAndDontShowAgain() {
        // 99 hours: not yet.
        runBlocking { (1..99).forEach { container.db.plays().insert(PlayEventEntity(songId = "s$it", timestamp = System.currentTimeMillis() - it * 18_000_000L, playedMs = 3_600_000L)) } }
        show(5)
        compose.waitForIdle()
        assertEquals(0, drawn(SupportTags.HOME_CARD))
        runBlocking { container.db.plays().insert(PlayEventEntity(songId = "s100", timestamp = System.currentTimeMillis() - 1_000, playedMs = 3_600_000L)) }
        again(5)
        compose.waitUntil(5_000) { drawn(SupportTags.HOME_CARD) == 1 }
        // Seen, not answered: no mark yet (it stays until answered).
        assertTrue(marks().isEmpty())
        again(5)
        compose.waitUntil(5_000) { drawn(SupportTags.HOME_CARD) == 1 }
        compose.onNode(hasText(tr(R.string.don_t_show_again))).performClick()
        compose.waitForIdle()
        assertEquals(0, drawn(SupportTags.HOME_CARD))
        assertEquals(setOf(SupportAsks.HOME.key), marks())
        assertTrue("nothing opened", opened.isEmpty())
        again(5)
        compose.waitForIdle()
        assertEquals(0, drawn(SupportTags.HOME_CARD))
    }

    @Test fun theHomeCardWaitsForAnotherOffer() {
        hundredHours()
        show(5, otherOffer = true)
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        assertEquals(0, drawn(SupportTags.HOME_CARD))
        assertFalse(SupportAsks.HOME.key in marks())
    }

    @Test fun supportOnKofiEndsTheCardToo() {
        hundredHours()
        show(5)
        compose.waitUntil(5_000) { drawn(SupportTags.HOME_CARD) == 1 }
        compose.onNode(hasText(tr(R.string.support_on_kofi))).performClick()
        compose.waitForIdle()
        assertEquals(listOf(SupportAsks.KOFI_URL), opened)
        assertEquals(0, drawn(SupportTags.HOME_CARD))
        assertEquals(setOf(SupportAsks.HOME.key), marks())
    }
}
