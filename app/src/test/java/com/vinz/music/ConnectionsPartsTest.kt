package com.vinz.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.vinz.music.connect.ShownCode
import com.vinz.music.data.lastfm.LastFm
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.data.spotify.Spotify
import com.vinz.music.playback.DiscordPresence
import com.vinz.music.ui.screens.settings.LastFmRows
import com.vinz.music.ui.screens.settings.SettingsGroup
import com.vinz.music.ui.screens.settings.SettingsRowChanged
import com.vinz.music.ui.screens.settings.SettingsRowValue
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.discordValue
import com.vinz.music.ui.screens.settings.pairedDevicesValue
import com.vinz.music.ui.screens.settings.sharedLinkLabel
import com.vinz.music.ui.screens.settings.spotifyValue
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.8.4, the parts of Speakers, TVs & browser and Accounts & sharing that say a connection's state, tested without the
 * connection: what each service's row says ("Connected as …", "Not connected"), and Last.fm's rows in each of its
 * three states (this copy of Vinz may be built without Last.fm's key, so the page's own test can't reach them all).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class ConnectionsPartsTest {
    @get:Rule val compose = createComposeRule()

    private fun t(id: Int) = tr(id)

    @Test fun discordsLineSaysItsState() {
        val off = DiscordPresence.State.OFF
        assertEquals("Not signed in", discordValue(signedIn = false, on = false, state = off, user = null))
        // Stored on from before, but signed out: still not signed in.
        assertEquals("Not signed in", discordValue(signedIn = false, on = true, state = DiscordPresence.State.SIGNED_OUT, user = null))
        assertEquals("Signed in as mina", discordValue(signedIn = true, on = false, state = off, user = "mina"))
        assertEquals(t(R.string.signed_in_2), discordValue(signedIn = true, on = false, state = off, user = null))
        assertEquals("On as mina", discordValue(signedIn = true, on = true, state = DiscordPresence.State.CONNECTED, user = "mina"))
        assertEquals(t(R.string.on_2), discordValue(signedIn = true, on = true, state = DiscordPresence.State.CONNECTED, user = null))
        assertEquals(t(R.string.connecting_to_discord), discordValue(signedIn = true, on = true, state = DiscordPresence.State.CONNECTING, user = "mina"))
        assertEquals("On Discord as mina", discordValue(signedIn = true, on = true, state = off, user = "mina"))
        assertEquals(t(R.string.on_discord), discordValue(signedIn = true, on = true, state = off, user = null))
    }

    @Test fun spotifysLineSaysItsState() {
        // W48 (1.8.7.6): what connecting reads, as Last.fm's row is only "Not connected".
        assertEquals("Not connected · playlists and likes", spotifyValue(Spotify.Connection.None))
        assertEquals(t(R.string.spot_waiting), spotifyValue(Spotify.Connection.Waiting))
        assertEquals("Access taken back · reconnect", spotifyValue(Spotify.Connection.Revoked))
        // Why the last try failed is said in the sheet, under Connect.
        assertEquals("Not connected · playlists and likes", spotifyValue(Spotify.Connection.Failed("Couldn't reach Spotify.")))
        assertEquals("Connected as Mina", spotifyValue(Spotify.Connection.Connected("Mina")))
        // Never "sign in with Spotify".
        for (c in listOf(Spotify.Connection.None, Spotify.Connection.Waiting, Spotify.Connection.Revoked)) assertFalse("sign in" in spotifyValue(c).lowercase())
    }

    @Test fun theOtherLines() {
        assertEquals("YouTube Music", sharedLinkLabel(false))
        assertEquals("YouTube", sharedLinkLabel(true))
        val paired = listOf("a" to "Living room TV", "b" to "Pixel Tablet")
        assertEquals("Living room TV, Pixel Tablet", pairedDevicesValue(null, paired))
        // While this device shows a code, the row says it (it is what the listener is looking for).
        assertEquals("123 456 · type it on Pixel Tablet", pairedDevicesValue(ShownCode("123456", "Pixel Tablet"), paired))
        assertEquals("123 456 · type it on Pixel Tablet", pairedDevicesValue(ShownCode("123456", "Pixel Tablet"), emptyList()))
    }

    // ------------------------------------------------------------------ Last.fm's rows

    private val isRow = SemanticsMatcher("is one of the kit's five rows") { it.config.getOrNull(SemanticsProperties.TestTag) in SettingsTags.rowTypes }
    private fun rows(): List<SemanticsNode> = compose.onAllNodes(isRow).fetchSemanticsNodes()
    private fun titles() = rows().map { it.config[SemanticsProperties.Text].first().text }
    private fun row(title: String) = compose.onNode(isRow and (hasText(title) or hasAnyDescendant(hasText(title))))
    private fun tap(title: String) { row(title).performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle() }

    private var available by mutableStateOf(true)
    private var user by mutableStateOf<String?>(null)
    private var options by mutableStateOf(LastFm.Options())
    private var waiting by mutableStateOf(0)
    private var connects = 0

    private fun showLastFm() {
        compose.setContent {
            VinzTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) {
                Box(Modifier.width(360.dp).background(MaterialTheme.colorScheme.background)) {
                    SettingsGroup(null) {
                        val d = LastFm.Options()
                        LastFmRows(
                            available, user, options, waiting,
                            scrobbleChanged = options.scrobble != d.scrobble, firstArtistChanged = options.firstArtistOnly != d.firstArtistOnly, loveChanged = options.loveOnLike != d.loveOnLike,
                            onOptions = { options = it }, onConnect = { connects++ }, onDisconnect = { user = null },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun lastFmNotConnectedIsOneLinkThatSaysSo() {
        showLastFm()
        // W47 (1.8.7.6): the plain name, as Spotify's row ("Connect Last.fm" stays as a search line).
        assertEquals(listOf("Last.fm"), titles())
        assertEquals(SettingsTags.ROW_LINK, rows().single().config[SemanticsProperties.TestTag])
        assertEquals("Not connected", rows().single().config[SettingsRowValue])
        tap("Last.fm")
        assertEquals("a tap opens Last.fm's own page", 1, connects)
    }

    @Test fun lastFmConnectedShowsItsOptions() {
        user = "mina"
        showLastFm()
        val scrobble = t(R.string.last_fm_scrobbling)
        val first = t(R.string.first_artist_only)
        val love = t(R.string.love_when_i_like_a_song)
        val disconnect = t(R.string.cn_disconnect_lastfm)
        assertEquals(listOf(scrobble, first, love, disconnect), titles())
        assertEquals(listOf(SettingsTags.ROW_SWITCH, SettingsTags.ROW_SWITCH, SettingsTags.ROW_SWITCH, SettingsTags.ROW_LINK), rows().map { it.config[SemanticsProperties.TestTag] })
        assertEquals("Connected as mina", row(scrobble).fetchSemanticsNode().config[SettingsRowValue])
        assertTrue("nothing differs from how Last.fm comes", rows().none { it.config[SettingsRowChanged] })
        // What waits to be sent is part of the line.
        waiting = 3
        compose.waitForIdle()
        assertEquals("Connected as mina · 3 waiting to send", row(scrobble).fetchSemanticsNode().config[SettingsRowValue])
        // Each switch sets its own option and leaves the others.
        tap(first)
        assertEquals(LastFm.Options(scrobble = true, firstArtistOnly = true, loveOnLike = false), options)
        tap(love)
        assertEquals(LastFm.Options(scrobble = true, firstArtistOnly = true, loveOnLike = true), options)
        tap(scrobble)
        assertEquals(LastFm.Options(scrobble = false, firstArtistOnly = true, loveOnLike = true), options)
        assertEquals(3, rows().count { it.config[SettingsRowChanged] })
        // The sentences under the old switches are behind the ⓘ now.
        for (id in listOf(R.string.a_b_c_goes_to_last, R.string.liking_here_loves_it_on_last, R.string.stops_scrobbling_and_forgets_the_connection)) {
            assertEquals(t(id), 0, compose.onAllNodes(hasText(t(id))).fetchSemanticsNodes().size)
        }
        // Disconnect: back to the one link.
        tap(disconnect)
        assertEquals(listOf("Last.fm"), titles())
    }

    @Test fun aCopyBuiltWithoutLastFmSaysSo() {
        available = false
        user = "mina"
        showLastFm()
        assertEquals(listOf("Last.fm"), titles())
        assertEquals(t(R.string.this_copy_of_vinz_was_built), rows().single().config[SettingsRowValue])
        assertEquals(SettingsTags.ROW_LINK, rows().single().config[SemanticsProperties.TestTag])
    }
}
