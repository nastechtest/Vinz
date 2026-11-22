package com.vinz.music

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.screens.SpotifyBeforeScreen
import com.vinz.music.ui.screens.SpotifySignIn
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
 * 1.8.7.5, "Sign in to Spotify": when the sign-in page counts as finished (only Spotify's web player over https, with
 * the session cookie), the finish running once per try, and the screen before it (both buttons, Not now leaves).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class SpotifySignInScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theWebPlayerSignedInFinishes() {
        assertTrue(SpotifySignIn.landedSignedIn("https://open.spotify.com/", true))
        assertTrue(SpotifySignIn.landedSignedIn("https://open.spotify.com", true))
        assertTrue(SpotifySignIn.landedSignedIn("https://OPEN.spotify.com/collection/tracks?x=1", true))
        assertTrue(SpotifySignIn.landedSignedIn("https://open.spotify.com:443/", true))
    }

    @Test fun nothingElseFinishes() {
        // Not signed in yet.
        assertFalse(SpotifySignIn.landedSignedIn("https://open.spotify.com/", false))
        // Still on the sign-in pages.
        assertFalse(SpotifySignIn.landedSignedIn(SignInUrl, true))
        assertFalse(SpotifySignIn.landedSignedIn("https://accounts.spotify.com/en/status", true))
        assertFalse(SpotifySignIn.landedSignedIn("https://challenge.spotify.com/", true))
        // Plain http, lookalikes, someone else's host.
        assertFalse(SpotifySignIn.landedSignedIn("http://open.spotify.com/", true))
        assertFalse(SpotifySignIn.landedSignedIn("https://open.spotify.com.example.com/", true))
        assertFalse(SpotifySignIn.landedSignedIn("https://evilopen.spotify.com/", true))
        assertFalse(SpotifySignIn.landedSignedIn("https://open-spotify.com/", true))
        assertFalse(SpotifySignIn.landedSignedIn("https://open.spotify.com@example.com/", true))
        assertFalse(SpotifySignIn.landedSignedIn("https://example.com/?next=https://open.spotify.com/", true))
        assertFalse(SpotifySignIn.landedSignedIn("https://open.spotify.com:8443/", true))
        assertFalse(SpotifySignIn.landedSignedIn("spotify://open.spotify.com/", true))
        // Nothing, or not an address.
        assertFalse(SpotifySignIn.landedSignedIn(null, true))
        assertFalse(SpotifySignIn.landedSignedIn("", true))
        assertFalse(SpotifySignIn.landedSignedIn("not a url at all", true))
    }

    @Test fun theFinishRunsOncePerTry() {
        val once = SpotifySignIn.Once()
        assertTrue(once.start())
        assertFalse(once.start())
        assertFalse(once.start())
        once.reset()
        assertTrue(once.start())
        assertFalse(once.start())
    }

    @Test fun theScreenBeforeHasBothButtonsAndNotNowLeaves() {
        var signIns = 0
        var backs = 0
        compose.setContent {
            VinzTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) {
                SpotifyBeforeScreen(onSignIn = { signIns++ }, onBack = { backs++ })
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(tr(R.string.spot_signin_before_title)).assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.spot_signin_terms)).assertExists()
        compose.onNodeWithText(tr(R.string.spot_signin_reads_only)).assertExists()
        compose.onNodeWithText(tr(R.string.spot_signin_other_buttons)).assertExists()
        compose.onNodeWithTag("spot_signin_go").assertExists()
        compose.onNodeWithTag("spot_signin_not_now").assertExists()

        compose.onNodeWithTag("spot_signin_not_now").performClick()
        compose.waitForIdle()
        assertEquals(1, backs)
        assertEquals(0, signIns)

        compose.onNodeWithTag("spot_signin_go").performClick()
        compose.waitForIdle()
        assertEquals(1, signIns)
        assertEquals(1, backs)
    }

    @Test fun noLineCallsItOfficial() {
        for (id in listOf(
            R.string.spot_signin_before_title, R.string.spot_signin_terms, R.string.spot_signin_risk,
            R.string.spot_signin_reads_only, R.string.spot_signin_on_phone, R.string.spot_signin_other_buttons,
        )) assertFalse(tr(id), tr(id).contains("official", ignoreCase = true))
    }

    private companion object {
        const val SignInUrl = com.vinz.music.data.spotify.SessionWeb.SIGN_IN
    }
}
