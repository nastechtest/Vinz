package com.vinz.music

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.CollectionKind
import com.vinz.music.data.model.CollectionPage
import com.vinz.music.data.model.Song
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.FullWidthPictures
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.screens.CollectionContent
import com.vinz.music.ui.screens.CollectionType
import com.vinz.music.ui.screens.LiveCollection
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * D55 (1.8.4): an album's page with its picture across the whole width ("Albums and playlists"). Play and Shuffle stay
 * above the description and clear of the mini player: 1.8.2.1's bug (a long description above them pushed Play behind
 * the mini player) mustn't come back with the taller picture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp", application = Application::class)
class AlbumHeroPageTest {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val c by made
    @org.junit.After fun close() = made.closeAfterTest()
    /** The room the bar and the mini player take at the bottom of a page. */
    private val mini = 150.dp
    private val about = ("Tidewater is the second album by Mira Vale, recorded over two winters in a house by the sea. " +
        "Its ten songs were written on a piano that had not been tuned in years, and it shows. ").repeat(4) + "\n\nFrom Wikipedia"
    private val album = CollectionPage(
        "MPREb_test", CollectionKind.ALBUM, "Tidewater", "Mira Vale", description = about, thumbnail = null,
        songs = (1..10).map { Song("t$it", "Track $it", listOf(ArtistRef("Mira Vale")), durationSec = 200) },
        artists = listOf(ArtistRef("Mira Vale")),
    )

    private fun settings(pictures: FullWidthPictures) = AppSettings().let {
        it.copy(playlistHeroCover = pictures != FullWidthPictures.OFF, view = it.view.copy(albumHeroCover = pictures == FullWidthPictures.ALBUMS_AND_PLAYLISTS))
    }

    private fun show(pictures: FullWidthPictures) {
        val s = settings(pictures)
        assertEquals(pictures, s.fullWidthPictures)
        val live = LiveCollection(album, c.scope, null)
        compose.setContent {
            val nav = Navigator(androidx.navigation.compose.rememberNavController(), openPlayer = {})
            VinzTheme(s, artworkColor = null) {
                CompositionLocalProvider(LocalContainer provides c, LocalNavigator provides nav) {
                    CollectionContent(CollectionType.ALBUM, live, null, bottomPadding = mini) {}
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun anAlbumsPictureGoesAcrossThePageWhenAskedFor() {
        show(FullWidthPictures.ALBUMS_AND_PLAYLISTS)
        val hero = compose.onNodeWithTag("collectionHero").assertIsDisplayed().getUnclippedBoundsInRoot()
        val page = compose.onRoot().getUnclippedBoundsInRoot()
        assertEquals("edge to edge", page.width, hero.width)
        assertEquals(0.dp, hero.left)
    }

    @Test fun playIsAboveTheDescriptionAndClearOfTheMiniPlayer() {
        show(FullWidthPictures.ALBUMS_AND_PLAYLISTS)
        val play = compose.onNodeWithTag("collectionPlay").assertIsDisplayed().getUnclippedBoundsInRoot()
        val box = compose.onNodeWithTag("collectionAbout").getUnclippedBoundsInRoot()
        val page = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("Play (down to ${play.bottom}) is above the description (from ${box.top})", play.bottom <= box.top)
        assertTrue("Play (down to ${play.bottom}) is above the mini player's room (from ${page.height - mini})", play.bottom <= page.height - mini)
    }

    @Test fun asVinzShipsAnAlbumKeepsItsCover() {
        show(FullWidthPictures.PLAYLISTS)
        compose.onNodeWithTag("collectionHero").assertDoesNotExist()
        val play = compose.onNodeWithTag("collectionPlay").assertIsDisplayed().getUnclippedBoundsInRoot()
        val page = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue(play.bottom <= page.height - mini)
    }
}
