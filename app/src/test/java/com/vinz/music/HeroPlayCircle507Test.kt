package com.vinz.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.data.model.BrowseItem
import com.vinz.music.data.model.PlaylistItem
import com.vinz.music.data.model.Song
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.playback.HistoryShuffleOrder
import com.vinz.music.ui.screens.HeroRow
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #507 (1.8.7.5): Home's big cards (Spotlight, [HeroRow]) show a play circle; tapping it opened the mix like the rest of
 * the card. Now the circle plays it straight away (as the long press's Play does), and the rest of the card still opens it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class HeroPlayCircle507Test {
    @get:Rule val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<VinzApp>()
    private val c get() = app.container
    private lateinit var player: ExoPlayer

    @Before fun setUp() {
        player = ExoPlayer.Builder(app).build()
        player.setShuffleOrder(HistoryShuffleOrder(IntArray(0)) { player.currentMediaItemIndex })
        c.player.testPlayer = player
    }

    @After fun tearDown() {
        c.player.testPlayer = null
        player.release()
    }

    @Test fun thePlayCirclePlaysTheMixAndTheCardOpensIt() {
        // A list on the phone, so playing it needs no network.
        val songs = (1..3).map { Song("m$it", "Mix song $it") }
        val id = c.vinzLibrary.create("My mix", songs)
        val mix = PlaylistItem(id, "My mix", "Made for you", null)
        val opened = mutableListOf<BrowseItem>()
        compose.setContent {
            VinzTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) {
                CompositionLocalProvider(LocalContainer provides c) {
                    Column(Modifier.width(400.dp)) { HeroRow(listOf(mix)) { opened += it } }
                }
            }
        }

        // The circle is a button of its own, named with the existing "Play".
        compose.onNodeWithContentDescription(app.getString(R.string.widget_play)).assertHasClickAction().performClick()
        // The list is read off the main thread and played back on it.
        val main = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        repeat(100) { if (player.mediaItemCount < 3) { Thread.sleep(20); main.idleFor(java.time.Duration.ofMillis(50)) } }
        assertEquals(songs.map { it.id }, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
        assertEquals("the circle doesn't open the mix", emptyList<BrowseItem>(), opened)

        compose.onNodeWithText("My mix").performClick()
        assertEquals("the rest of the card still opens it", listOf<BrowseItem>(mix), opened)
    }
}
