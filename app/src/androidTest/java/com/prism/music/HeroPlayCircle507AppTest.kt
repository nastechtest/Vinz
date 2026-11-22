package com.vinz.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vinz.music.data.model.BrowseItem
import com.vinz.music.data.model.PlaylistItem
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.screens.HeroRow
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * #507 (1.8.7.5), on a phone: the play circle on Home's big cards ([HeroRow]) plays the card straight away, through the
 * app's own container and playback service; the rest of the card still opens it. A signed-out Home may show no big
 * cards, so the row is put on screen here with a card of its own, in Vinz's theme (as AlbumBoxAndCardAppTest does).
 *
 * No network: the card is a Vinz playlist of test songs (ids no service knows), whose songs are read on the phone. Their
 * streams can't be had, so only the queue the tap sets is asserted, read in the step that first sees it filled.
 */
@RunWith(AndroidJUnit4::class)
class HeroPlayCircle507AppTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var listId: String? = null

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { c.player.stop() } }
        listId?.let { id -> runCatching { c.vinzLibrary.delete(id) } }
        instrumentation.uiAutomation.executeShellCommand("cmd media_session volume --stream 3 --set 0").close()
    }

    private fun <T> onMain(read: () -> T): T {
        var out: Any? = null
        instrumentation.runOnMainSync { out = read() }
        @Suppress("UNCHECKED_CAST") return out as T
    }

    private fun waitFor(what: String, ms: Long, done: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!onMain(done)) {
            if (System.currentTimeMillis() > until) throw AssertionError("never: $what")
            Thread.sleep(50)
        }
    }

    @Test fun thePlayCirclePlaysTheCardAndTheCardOpensIt() {
        Fixture.ready()
        instrumentation.uiAutomation.executeShellCommand("cmd media_session volume --stream 3 --set 0").close()
        val songs = Fixture.songs(3, prefix = "Hero song")
        val id = Fixture.vinzPlaylist("Hero test mix", songs).also { listId = it }
        val mix = PlaylistItem(id, "Hero test mix", "Made for you", null)

        // Nothing on: the playback service up with an empty queue (a run without its data cleared may have restored one).
        java.io.File(Fixture.context.filesDir, "queue.json").delete()
        instrumentation.runOnMainSync { c.player.stop() }
        waitFor("the playback service with an empty queue", 20_000) { c.player.player.let { it != null && it.mediaItemCount == 0 } }

        val opened = Collections.synchronizedList(ArrayList<BrowseItem>())
        compose.setContent {
            VinzTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) {
                CompositionLocalProvider(LocalContainer provides c) {
                    Column(Modifier.fillMaxSize().background(Color.Black)) { HeroRow(listOf(mix)) { opened += it } }
                }
            }
        }
        compose.waitForIdle()
        Fixture.screenshot("hero-play-circle-507")

        // The circle is a button of its own, named with the existing "Play".
        val play = Fixture.context.getString(R.string.widget_play)
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(play) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(play).assertHasClickAction().performClick()

        // The list is read off the main thread and played on it: the queue as the tap set it.
        var queued = emptyList<String>()
        var asked = false
        waitFor("the card's songs in the queue", 15_000) {
            val p = c.player.player ?: return@waitFor false
            if (p.mediaItemCount == 0) return@waitFor false
            queued = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
            asked = p.playWhenReady
            true
        }
        assertEquals("the circle plays the card's songs, in order", songs.map { it.id }, queued.take(songs.size))
        assertTrue("and they were asked to play", asked)
        compose.waitForIdle()
        assertEquals("the circle doesn't open the card", emptyList<BrowseItem>(), opened.toList())

        compose.onNodeWithText("Hero test mix").performClick()
        compose.waitForIdle()
        assertEquals("the rest of the card still opens it", listOf<BrowseItem>(mix), opened.toList())
    }
}
