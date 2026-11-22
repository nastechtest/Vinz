package com.vinz.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsTags
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vinz.music.data.imports.ImportSource
import com.vinz.music.data.imports.YouTubeLive
import com.vinz.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * YouTube Music (live) in the app (1.8.1), offline: two reads of the History page handed in through the source's test
 * hook (no YouTube, no sign-in). Turning it on reads the starting point at once (nothing from before comes in); the next
 * read brings the song newly at the top, matched at once; then "Count history from" lists every source in its sheet (the
 * ones not brought in as links to where they are), and switching YouTube Music (live) on makes Replay count its play.
 */
@RunWith(AndroidJUnit4::class)
class YouTubeLiveInAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private var page: List<Pair<String, List<Song>>> = emptyList()
    private var now = System.currentTimeMillis() - 6 * 3_600_000L

    @Before fun start() {
        Fixture.ready()
        runBlocking { ImportSource.entries.forEach { c.imports.remove(it) } }
        c.imports.youtubeLive.forTest = YouTubeLive.TestHooks(history = { page }, now = { now })
    }

    @After fun clean() {
        c.imports.setYouTubeLive(false)
        c.imports.youtubeLive.forTest = null
        runBlocking { ImportSource.entries.forEach { c.imports.remove(it) } }
    }

    private fun livePlays() = runBlocking { c.db.imports().summaries().firstOrNull { it.origin == ImportSource.YOUTUBE_LIVE.key }?.plays ?: 0 }

    @Test fun twoReadsBringWhatsNewAndCountHistoryFromListsEverySource() {
        val songs = Fixture.songs(4, "Live test")
        page = listOf("Today" to songs.drop(1))
        c.imports.setYouTubeLive(true)
        compose.waitUntil(10_000) { c.imports.youtubeLive.lastCheck.value != null }
        assertEquals("the starting point brings nothing from before", 0, livePlays())

        now += 3 * 3_600_000L
        page = listOf("Today" to songs)
        assertEquals("the song newly at the top", 1, runBlocking { c.imports.runYouTubeLive() })
        val matched = runBlocking { c.db.imports().match(YouTubeLive.keyOf(songs[0])) }
        assertEquals("matched at once", songs[0].id, matched?.matchedId)
        val vinzOnly = runBlocking { c.imports.replay().totalMs(0, Long.MAX_VALUE) }

        Fixture.launch()
        // 1.8.4: Settings → Recommendations & history → Replay → "Count history from", one row whose sheet lists every
        // source (they were a list on the page, with "Bring it in" / "Turn on" beside the ones without plays).
        compose.openSettingsPage(SettingsPageId.RECOMMENDATIONS_HISTORY)
        seeText("Count history from")
        compose.openSettingsSheet("Count history from")
        // Every source: the ones without plays here as links to where they are brought in ("Not brought in yet") or
        // turned on (a live one says "Off").
        seeText("Not brought in yet")
        seeText("Last.fm")
        assertTrue(
            "Spotify (live) isn't on: its row leads to Spotify",
            compose.onAllNodes(settingsRow("Spotify (live)") and hasText(tr(R.string.off))).fetchSemanticsNodes().isNotEmpty(),
        )
        // The one with plays is a switch.
        compose.clickWhenFound(hasText("YouTube Music (live)") and hasClickAction() and hasTestTag(SettingsTags.ROW_SWITCH))
        compose.waitUntil(5_000) { ImportSource.YOUTUBE_LIVE in c.imports.counted.value }
        assertEquals(vinzOnly + songs[0].durationSec * 1000L, runBlocking { c.imports.replay().totalMs(0, Long.MAX_VALUE) })
        assertTrue(c.imports.youtubeLive.on.value)
    }

    /**
     * Both live histories where people look for them (owner, 1.8.1): Accounts & sharing (Devices & connections before
     * 1.8.4) has a "YouTube Music history" row that opens Bring your history, whose "Keep it in step" lists Spotify
     * (live) too, opening Spotify's row on Accounts & sharing, and that its set-up guide.
     */
    @Test fun bothLiveHistoriesAreFoundFromConnectionsAndKeepItInStep() {
        Fixture.launch()
        compose.openSettingsPage(SettingsPageId.ACCOUNTS_SHARING)
        seeText("Sign in to keep your history in step")
        // By its line ("YouTube Music history" in the Accounts group; "YouTube Music" above it is the account's own row).
        compose.clickWhenFound(settingsRow(tr(R.string.cn_youtube_history)) and hasText("Sign in to keep your history in step"))
        seeText("Keep it in step")
        seeText("Spotify (live)")
        seeText("Connect Spotify to use this")
        compose.clickWhenFound(hasText("Spotify (live)") and hasClickAction())
        // Back on Accounts & sharing, at Spotify: not connected, it is one row, and the guide is the sheet it opens.
        val spotifyRow = settingsRow(tr(R.string.spotify)) and hasText(tr(R.string.cn_spot_not_connected))
        compose.scrollSettingsTo(spotifyRow)
        compose.clickWhenFound(spotifyRow)
        compose.clickWhenFound(hasText("Use your own developer app") and hasClickAction())   // 1.8.7.5: two ways to connect
        seeText("Create app")
    }

    /** Waits for [text], scrolling the page's lists to it (as BringHistoryTest's). */
    private fun seeText(text: String, timeoutMs: Long = 15_000) {
        val matcher = hasText(text, substring = true, ignoreCase = true)
        val end = System.currentTimeMillis() + timeoutMs
        while (compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            if (System.currentTimeMillis() > end) {
                val shown = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
                    .mapNotNull { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() }
                throw AssertionError("Not found: $text. On screen: ${shown.joinToString(" | ").take(1500)}")
            }
            compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction()).fetchSemanticsNodes().indices.forEach { i ->
                runCatching { compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction())[i].performScrollToNode(matcher) }
            }
            compose.waitForIdle()
            Thread.sleep(200)
        }
    }
}
