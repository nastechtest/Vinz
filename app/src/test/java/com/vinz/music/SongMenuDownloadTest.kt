package com.vinz.music

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.compose.rememberNavController
import com.vinz.music.data.model.Song
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.download.DownloadInfo
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.components.SongActionsSheet
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #471: a downloaded song's menu said "Downloaded" on its download tile, and tapping it took the download away, so
 * nobody found how to remove one download. The tile now says what a tap does: "Remove download".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2000dp-xhdpi", application = android.app.Application::class)
class SongMenuDownloadTest {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val container by made

    @org.junit.After fun close() { container.downloadState.value = emptyMap(); made.closeAfterTest() }

    private val song = Song(id = "dQw4w9WgXcQ", title = "A song", artists = emptyList(), album = null, durationSec = 200, thumbnail = null)

    private fun showMenu(state: Int?) {
        container.downloadState.value = if (state == null) emptyMap() else mapOf(song.id to DownloadInfo(song, state, 100f, 4_000_000L))
        compose.setContent {
            val nav = Navigator(rememberNavController(), openPlayer = {})
            CompositionLocalProvider(LocalContainer provides container, LocalNavigator provides nav) {
                VinzTheme(AppSettings(), artworkColor = null) { SongActionsSheet(song) {} }
            }
        }
        compose.waitForIdle()
    }

    @Test fun aDownloadedSongsMenuOffersToRemoveIt() {
        showMenu(Download.STATE_COMPLETED)
        compose.onNodeWithText("Remove download", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Downloaded", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun tappingRemoveDownloadRemovesIt() {
        showMenu(Download.STATE_COMPLETED)
        val app = shadowOf(RuntimeEnvironment.getApplication())
        while (app.nextStartedService != null) Unit
        compose.onNodeWithText("Remove download", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        val sent = generateSequence { app.nextStartedService }.firstOrNull { it.action == DownloadService.ACTION_REMOVE_DOWNLOAD }
        assertNotNull("the download service was asked to remove it", sent)
        assertEquals(song.id, sent!!.getStringExtra(DownloadService.KEY_CONTENT_ID))
    }

    @Test fun aSongNotDownloadedStillOffersDownload() {
        showMenu(null)
        compose.onNodeWithText("Download", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Remove download", useUnmergedTree = true).assertDoesNotExist()
    }
}
