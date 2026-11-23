package com.vinz.music

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.data.spotify.Spotify
import com.vinz.music.data.spotify.SpotifyOAuth
import com.vinz.music.data.spotify.SpotifyStore
import kotlinx.coroutines.flow.MutableStateFlow
import com.vinz.music.ui.screens.SettingsIndex
import com.vinz.music.ui.screens.settings.SettingsMap
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsRowValue
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.SettingsTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.8.4, Settings → Accounts & sharing rebuilt with the kit (plan §2.3, §2.7, the ledger's table): five groups within
 * the budgets (Accounts, Last.fm, Spotify, Discord, Sharing and links); a control for both stored settings the ledger
 * gives the page; each service's first row saying its state; Spotify's steps in a "How to connect" sheet and not on the
 * page, worded as they must be (the official API through the listener's own app, never "sign in with Spotify", nothing
 * about a web player); the rows that only explained gone; Reset this page; the old links; and the page drawn in light,
 * dark, Performance mode and at large text.
 *
 * Nothing here reaches a service: no Spotify switch is turned on, Connect is never tapped, and Discord's switch is only
 * turned off. The Discord sign-in the tests make up is never shown (and that is checked).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h4000dp-xhdpi", application = android.app.Application::class)
class AccountsSharingPageTest : ListeningPageHarness(SettingsPageId.ACCOUNTS_SHARING) {
    private fun t(id: Int, vararg args: Any) = tr(id, *args)
    private val me = SettingsPageId.ACCOUNTS_SHARING
    private val app get() = RuntimeEnvironment.getApplication()

    private val accounts = t(R.string.accounts)
    private val sharing = t(R.string.sharing_and_links)
    private val vinzAccount = t(R.string.vinz_account)
    private val youtubeMusic = t(R.string.youtube_music_2)
    private val history = t(R.string.cn_youtube_history)
    private val spotify = t(R.string.spotify)
    private val discord = t(R.string.cn_discord_show)
    private val discordSignOut = t(R.string.sign_out_of_discord)
    private val links = t(R.string.links_you_share)
    private val openLinks = t(R.string.open_youtube_music_links_in_vinz)
    private val notConnected = t(R.string.cn_not_connected)
    private val spotifyNotConnected = t(R.string.cn_spot_not_connected)
    private val otherServices = t(R.string.cn_other_services)

    /** `ConnectTags`: a line and a paragraph of a sheet's content, the Client ID's field and Connect. */
    private val line = "settings:connect:line"
    private val paragraph = "settings:connect:text"
    private val clientIdField = "settings:connect:spotify-client-id"
    private val connectButton = "settings:connect:spotify-connect"

    /** A row by its title alone (a row's value can be another row's title: "YouTube Music"). */
    private fun titled(title: String): SemanticsNode = rows().single { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text == title }
    private fun hasTitled(title: String) = rows().any { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text == title }
    private fun value(title: String): String = titled(title).config[SettingsRowValue]
    private fun type(title: String): String = titled(title).config[SemanticsProperties.TestTag]

    /** The titles over the page's cards. */
    private fun headings(): List<String> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and hasAnyAncestor(hasTestTag(SettingsTags.GROUP)))
        .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }

    private fun texts(tag: String): List<String> = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
        .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun saveNode(tag: String, name: String): File {
        val shot = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val dir = File("build/settings-pages/accounts-sharing").apply { mkdirs() }
        return File(dir, "$name.png").also { f -> f.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    /**
     * Spotify as connected, with no access at all (no feature is on, and none is turned on): the connection's state is
     * set as it is read. (`connectForTest` seals its tokens with the Android Keystore, which a unit test doesn't have.)
     */
    @Suppress("UNCHECKED_CAST")
    private fun connectSpotify() {
        val state = Spotify::class.java.getDeclaredField("_connection").apply { isAccessible = true }.get(container.spotify) as MutableStateFlow<Spotify.Connection>
        state.value = Spotify.Connection.Connected("Tester")
    }

    /**
     * Spotify as connected by the sign-in on Spotify's page (1.8.7.5), or signed out there ([state] Revoked): the
     * session's mark in its own preferences (no token: it would be sealed with the Keystore), and the state as read.
     */
    @Suppress("UNCHECKED_CAST")
    private fun signInToSpotify(state: Spotify.Connection = Spotify.Connection.Connected("Tester"), canvasOfferAnswered: Boolean = true) {
        app.getSharedPreferences(SpotifyStore.AUTH_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("session_on", true).putBoolean("session_ended", state == Spotify.Connection.Revoked).commit()
        val flow = Spotify::class.java.getDeclaredField("_connection").apply { isAccessible = true }.get(container.spotify) as MutableStateFlow<Spotify.Connection>
        flow.value = state
        assertTrue("the session's mark is read", container.spotify.bySession)
        // 1.8.7.6: the one-time offer of Spotify's canvas, answered already unless a test is about it.
        if (canvasOfferAnswered) {
            container.settings.answerSpotifyCanvasOffer(false)
            until("the offer answered") { it.behavior.canvas.spotifyOffered }
        }
    }

    // ------------------------------------------------------------------ 1.8.7.6: the offer of Spotify's canvas

    private val offerTitle get() = t(R.string.spot_canvas_offer_title)
    private fun offerShown() = compose.onAllNodes(hasText(offerTitle)).fetchSemanticsNodes().isNotEmpty()

    /** Right after a session sign-in (or the next time this page opens for someone signed in before): once; Turn on sets the switch. */
    @Test fun aSessionSignInOffersSpotifysCanvasOnceAndTurnOnSetsIt() {
        signInToSpotify(canvasOfferAnswered = false)
        show()
        compose.waitUntil(5_000) { offerShown() }
        compose.onNodeWithText(t(R.string.spot_canvas_offer_text)).assertExists()
        assertFalse(stored.behavior.canvas.spotifyFirst)
        // The whole sheet, title included, for the design review (build/settings-pages/accounts-sharing).
        val sheet = compose.onAllNodes(androidx.compose.ui.test.isDialog()).fetchSemanticsNodes()
        if (sheet.isNotEmpty()) {
            val shot = compose.onAllNodes(androidx.compose.ui.test.isDialog())[0].captureToImage().asAndroidBitmap()
            val dir = File("build/settings-pages/accounts-sharing").apply { mkdirs() }
            File(dir, "accounts-dark-spotify-canvas-offer.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } else saveNode("spot_canvas_offer", "accounts-dark-spotify-canvas-offer")
        compose.onNodeWithTag("spot_canvas_offer_on").performClick()
        until("turned on and answered") { it.behavior.canvas.spotifyFirst && it.behavior.canvas.spotifyOffered }
        compose.waitUntil(5_000) { !offerShown() }
        // The page opened again: never offered again.
        assertFalse(com.vinz.music.ui.screens.settings.spotifyCanvasOfferShown(true, true, stored))
        assertNoSecretShown()
    }

    /** 1.8.7.6, the A16 check: with Vinz's own Canvas off (both switches), the offer still comes and Turn on sets it. */
    @Test fun theOfferComesWithVinzsCanvasOffAndTurnOnSetsIt() {
        container.settings.setCanvasKinds(covers = false, videos = false)
        until("Vinz's Canvas off") { !it.behavior.canvas.enabled }
        signInToSpotify(canvasOfferAnswered = false)
        show()
        compose.waitUntil(5_000) { offerShown() }
        compose.onNodeWithTag("spot_canvas_offer_on").performClick()
        until("turned on") { it.behavior.canvas.spotifyFirst && it.behavior.canvas.spotifyOffered }
        assertFalse("Vinz's own Canvas stays as it was", stored.behavior.canvas.enabled)
        assertTrue("and Spotify's canvas is asked", com.vinz.music.data.canvas.spotifyCanvasOn(container.settings.current, true))
    }

    @Test fun notNowAnswersTheOfferForGoodAndLeavesTheSwitchOff() {
        signInToSpotify(canvasOfferAnswered = false)
        show()
        compose.waitUntil(5_000) { offerShown() }
        compose.onNodeWithTag("spot_canvas_offer_not_now").performClick()
        until("answered") { it.behavior.canvas.spotifyOffered }
        assertFalse(stored.behavior.canvas.spotifyFirst)
        compose.waitUntil(5_000) { !offerShown() }
        assertFalse(com.vinz.music.ui.screens.settings.spotifyCanvasOfferShown(true, true, stored))
    }

    @Test fun theOwnDeveloperAppIsNeverOfferedSpotifysCanvas() {
        connectSpotify()
        assertFalse(container.spotify.bySession)
        show()
        compose.waitForIdle()
        assertFalse(offerShown())
        assertFalse(stored.behavior.canvas.spotifyOffered)
    }

    @Test fun theOfferIsNotShownOnceTheSwitchWasSetByHand() {
        container.settings.setCanvasSpotifyFirst(true)
        until("set by hand") { it.behavior.canvas.spotifyFirst && it.behavior.canvas.spotifyOffered }
        container.settings.setCanvasSpotifyFirst(false)
        until("off again") { !it.behavior.canvas.spotifyFirst }
        signInToSpotify(canvasOfferAnswered = false)
        show()
        compose.waitForIdle()
        assertFalse(offerShown())
    }

    @Test fun whenTheOfferShows() {
        val on = AppSettings()
        val s = com.vinz.music.ui.screens.settings.spotifyCanvasOfferShown(true, true, on)
        assertTrue(s)
        assertFalse("not connected", com.vinz.music.ui.screens.settings.spotifyCanvasOfferShown(false, true, on))
        assertFalse("the own developer app", com.vinz.music.ui.screens.settings.spotifyCanvasOfferShown(true, false, on))
        fun with(f: (com.vinz.music.data.prefs.CanvasPrefs) -> com.vinz.music.data.prefs.CanvasPrefs) = on.copy(behavior = on.behavior.copy(canvas = f(on.behavior.canvas)))
        assertFalse("answered", com.vinz.music.ui.screens.settings.spotifyCanvasOfferShown(true, true, with { it.copy(spotifyOffered = true) }))
        assertFalse("already on", com.vinz.music.ui.screens.settings.spotifyCanvasOfferShown(true, true, with { it.copy(spotifyFirst = true) }))
        // 1.8.7.6 (the A16 check): Vinz's own two switches have no say; Performance mode holds it back for later.
        for ((covers, videos) in listOf(true to true, true to false, false to true, false to false)) {
            val kinds = com.vinz.music.data.prefs.CanvasKinds(covers, videos).toStored(com.vinz.music.data.prefs.CanvasStored(true, true, true))
            assertTrue("covers $covers, videos $videos", com.vinz.music.ui.screens.settings.spotifyCanvasOfferShown(true, true,
                with { it.copy(enabled = kinds.enabled, covers = kinds.covers, useVideos = kinds.videos) }))
        }
        assertFalse("Performance mode", com.vinz.music.ui.screens.settings.spotifyCanvasOfferShown(true, true, on.copy(performanceMode = true)))
        // Its words: unofficial, no exclamation marks, no em dashes.
        val text = t(R.string.spot_canvas_offer_text)
        assertTrue(text, "unofficial" in text.lower())
        for (id in listOf(R.string.spot_canvas_offer_title, R.string.spot_canvas_offer_text, R.string.spot_canvas_first, R.string.spot_canvas_first_info)) {
            assertFalse(t(id), t(id).contains('!') || t(id).contains('—'))
        }
        assertTrue("unofficial" in t(R.string.spot_canvas_first_info).lower())
    }

    private fun String.lower() = lowercase()

    /** The Spotify row's "Connect Spotify" sheet, then its own-app card: "How to connect". */
    private fun openHowToConnect() {
        compose.onNode(rowIs(spotify)).performClick()
        compose.waitForIdle()
        compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText(t(R.string.spot_session_own_app))).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(t(R.string.cn_how_to_connect))).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun route(): String? = navController?.currentDestination?.route

    /** Every text on the screen (merged rows and dialogs too). */
    private fun allTexts(): List<String> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    /** Discord as signed in (a made-up sign-in in its own preferences), with the switch as stored. */
    private fun signInToDiscord() {
        app.getSharedPreferences("discord", Context.MODE_PRIVATE).edit().putString("token", MADE_UP_ACCESS).commit()
        container.discord.userName.value = "tester"
    }

    private fun assertNoSecretShown() {
        val all = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) or SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text) }
        assertTrue("the page's text was read", all.size > 5)
        assertTrue("the made-up sign-in is on the page", all.none { MADE_UP_ACCESS in it })
    }

    @Test fun fiveGroupsWithinTheBudgets() {
        show()
        assertBudgets("defaults")
        // Accounts 4 (Bring your history is the last) · Last.fm 1 · Spotify 1 · Discord 1 · Sharing and links 2.
        assertEquals(listOf(4, 1, 1, 1, 2), groupSizes())
        assertEquals(0, changedRows())
        // Last.fm, Spotify and Discord have no title of their own over their cards (their first row names them, §2.7),
        // but one heading over the three (W46, 1.8.7.6).
        assertEquals(listOf(accounts, otherServices, sharing), headings())
        assertTrue(headings().none { it == me.title })

        assertEquals(SettingsTags.ROW_LINK, type(vinzAccount))
        assertEquals(t(R.string.cn_not_set_up), value(vinzAccount))
        assertEquals(SettingsTags.ROW_LINK, type(youtubeMusic))
        assertEquals(t(R.string.tv_not_signed_in), value(youtubeMusic))
        assertEquals(SettingsTags.ROW_LINK, type(history))
        assertEquals(t(R.string.ytlive_connection_signed_out), value(history))
        // Last.fm: "Last.fm" · Not connected (W47, 1.8.7.6: a plain name, as Spotify's row), or (a copy built without
        // Last.fm's key) a row that says so.
        if (container.lastFm.available) {
            assertEquals(SettingsTags.ROW_LINK, type("Last.fm"))
            assertEquals(notConnected, value("Last.fm"))
        } else {
            assertEquals(t(R.string.this_copy_of_vinz_was_built), value("Last.fm"))
        }
        assertEquals(SettingsTags.ROW_PICK, type(spotify))
        // W48 (1.8.7.6): Spotify's says what connecting reads.
        assertEquals(spotifyNotConnected, value(spotify))
        assertEquals(SettingsTags.ROW_SWITCH, type(discord))
        assertEquals(t(R.string.tv_not_signed_in), value(discord))
        assertFalse(hasTitled(discordSignOut))
        assertEquals(SettingsTags.ROW_PICK, type(links))
        assertEquals(youtubeMusic, value(links))
        assertEquals(SettingsTags.ROW_LINK, type(openLinks))
        assertTrue(value(openLinks).isNotBlank())

        // §2.7: nothing that only explains is on the page: Spotify's notes and steps, Discord's "About this", and the
        // sentences that sat under the old rows.
        for (id in listOf(
            R.string.spot_intro, R.string.spot_premium_note, R.string.spot_step_1, R.string.spot_step_6, R.string.spot_set_up, R.string.spot_client_id,
            R.string.about_this, R.string.a_small_risk_to_your_discord, R.string.listening_to_vinz_on_your_profile, R.string.opens_last_fm_to_let_vinz,
        )) {
            assertEquals(t(id), 0, compose.onAllNodes(hasText(t(id))).fetchSemanticsNodes().size)
        }
    }

    /** Principle 8: both stored settings the ledger gives the page have their control here, and it stores them. */
    @Test fun everyStoredSettingHasItsControl() {
        val d = AppSettings()
        // discord_presence: the switch shows on only signed in to Discord. It is stored on first, signed out (so Vinz
        // doesn't go to Discord), given time to be read, and only then is a made-up sign-in put in place.
        container.settings.setDiscordPresence(true)
        until("discord_presence stored") { it.discordPresence }
        Thread.sleep(600)
        signInToDiscord()
        show()

        assertEquals(t(R.string.on_discord_as, "tester"), value(discord))
        assertTrue("signed in: Sign out shows", hasTitled(discordSignOut))
        assertEquals(listOf(4, 1, 1, 2, 2), groupSizes())
        tap(discord)
        until("discord_presence") { !it.discordPresence }
        assertEquals(t(R.string.cn_signed_in_as, "tester"), value(discord))
        // Sign out of Discord forgets the sign-in; its row goes.
        tap(discordSignOut)
        compose.waitUntil(5_000) { !hasTitled(discordSignOut) }
        assertFalse(container.discord.signedIn)
        assertEquals(t(R.string.tv_not_signed_in), value(discord))

        // share_youtube_links: in its pick sheet, a card for each.
        tap(links)
        assertEquals(2, compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION)).fetchSemanticsNodes().size)
        swipeSheetAway()
        assertEquals("a sheet swiped away changes nothing", d.shareYoutubeLinks, stored.shareYoutubeLinks)
        pick(links, t(R.string.youtube))
        until("share_youtube_links") { it.shareYoutubeLinks }
        assertEquals(t(R.string.youtube), value(links))
        assertEquals(1, changedRows())

        // Reset this page lists it and puts it back.
        val lines = resetLines(confirm = true)
        assertEquals(listOf("$links: ${t(R.string.youtube)} → $youtubeMusic"), lines)
        until("the reset") { it.shareYoutubeLinks == d.shareYoutubeLinks && it.discordPresence == d.discordPresence }
        assertEquals(0, changedRows())
        assertNoSecretShown()
    }

    /** Signed out of Discord, the switch goes to Discord's own sign-in page, and the accounts' rows to their screens. */
    @Test fun theLinksLeadToTheirOwnScreens() {
        val source = File("src/main/java/com/vinz/music/ui/screens/settings/AccountsSharingPage.kt").readText()
        assertTrue("if (on && !signedIn) nav.go(Routes.DISCORD_LOGIN) else c.settings.setDiscordPresence(on)" in source)
        assertTrue("{ nav.go(Routes.ACCOUNT) }" in source)
        assertTrue("{ nav.go(if (s.isLoggedIn) Routes.LOGIN_ADD else Routes.LOGIN) }" in source)
        assertTrue("{ nav.go(Routes.BRING_HISTORY) }" in source)
        assertTrue("{ LinkHandling.openSettings(context) }" in source)
        // The page is its own code now.
        for (old in listOf("InterimPage(", "SpotifyPage(", "DiscordGroup(", "SharingGroup(", "LastFmGroup(")) assertTrue(old, old !in source)
        // Signed in to YouTube Music, the rows say so.
        show(over = { it.copy(cookie = "SAPISID=x", accountName = "Mina") })
        assertEquals(t(R.string.cn_signed_in_as, "Mina"), value(youtubeMusic))
        assertTrue(value(history) == t(R.string.ytlive_connection_off) || value(history) == t(R.string.ytlive_connection_on))
        assertBudgets("signed in")
    }

    /**
     * §2.7: Spotify's set-up is a "How to connect" sheet behind its row: the six steps in order, the dashboard's link,
     * the redirect address to copy, the Client ID's field and Connect. Its words: the official API through the
     * listener's own developer app; never "sign in with Spotify"; nothing about a web player.
     */
    @Test fun spotifysStepsAreInItsHowToConnectSheet() {
        show()
        assertEquals(0, texts(paragraph).size)
        openHowToConnect()
        assertEquals(1, sheets())
        assertEquals(1, compose.onAllNodes(hasText(t(R.string.cn_how_to_connect))).fetchSemanticsNodes().size)
        val said = texts(paragraph)
        val steps = listOf(R.string.spot_step_1, R.string.spot_step_2, R.string.spot_step_3, R.string.spot_step_4, R.string.spot_step_5, R.string.spot_step_6).map { t(it) }
        assertEquals("the steps, in order", steps, said.filter { it in steps })
        assertEquals(steps.indices.map { "${it + 1}." }, steps.map { it.take(2) })
        assertEquals(listOf(t(R.string.spot_intro), t(R.string.spot_premium_note)), said.take(2))
        // The dashboard (a link out of Vinz) and the redirect address (a tap copies it) are the kit's rows, between
        // their steps; nothing else in the sheet is a line of its own.
        assertEquals(0, compose.onAllNodes(hasTestTag(line)).fetchSemanticsNodes().size)
        val links = compose.onAllNodes(hasTestTag(SettingsTags.ROW_LINK) and hasAnyAncestor(hasTestTag(SettingsTags.SHEET))).fetchSemanticsNodes()
            .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        assertTrue(links.toString(), links.containsAll(listOf(t(R.string.spot_open_dashboard), t(R.string.spot_redirect_uri), SpotifyOAuth.REDIRECT_TO_REGISTER)))

        // The wording rules, over everything Spotify says here and behind its ⓘs.
        val words = (said + links + listOf(R.string.spot_about, R.string.spot_set_up_info, R.string.cn_how_to_connect, R.string.spot_client_id, R.string.spot_connect).map { t(it) })
            .joinToString("\n").lowercase()
        assertFalse("never \"sign in with Spotify\"", "sign in with spotify" in words || "log in with spotify" in words || "login with spotify" in words)
        assertFalse("nothing about a web player", "web player" in words || "web-player" in words || "webplayer" in words || "open.spotify.com" in words)
        assertTrue("the official API", "official" in words)
        assertTrue("the listener's own developer app", "developer" in words && ("your own" in words || "for yourself" in words))

        // The Client ID is typed in the sheet; Connect waits for one that can be a Client ID.
        compose.onNodeWithTag(connectButton).assertIsNotEnabled()
        compose.onNodeWithTag(clientIdField).performTextInput("not-an-id")
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodes(hasText(t(R.string.spot_client_id_invalid))).fetchSemanticsNodes().size)
        compose.onNodeWithTag(connectButton).assertIsNotEnabled()
        // (Connect is never tapped here: it opens Spotify's page.)
        assertEquals(Spotify.Connection.None, container.spotify.connection.value)
        swipeSheetAway()
        assertEquals(spotifyNotConnected, value(spotify))
        assertBudgets("after the sheet")
    }

    /** Connected, Spotify's card is titled, holds the features' switches, and Disconnect says who is connected. */
    @Test fun spotifysOptionsShowWhileConnected() {
        connectSpotify()
        show()
        assertBudgets("Spotify connected")
        assertEquals(listOf(accounts, otherServices, spotify, sharing), headings())
        // Recent plays, Top songs and artists, Your playlists, Liked Songs both ways, Disconnect Spotify.
        assertEquals(listOf(4, 1, 5, 1, 2), groupSizes())
        for (id in listOf(R.string.spot_recent, R.string.spot_top, R.string.spot_playlists, R.string.spot_likes)) assertEquals(t(id), SettingsTags.ROW_SWITCH, type(t(id)))
        // "Keep in step" only shows with the playlists on.
        assertFalse(hasTitled(t(R.string.spot_keep_in_step)))
        val disconnect = t(R.string.cn_disconnect_spotify)
        assertEquals(SettingsTags.ROW_LINK, type(disconnect))
        assertEquals(t(R.string.spot_connected_as, "Tester"), value(disconnect))
        assertFalse("its own row is gone", hasTitled(spotify))
        assertNoSecretShown()

        // Disconnect asks first, as before: what to forget, and Cancel changes nothing.
        tap(disconnect)
        compose.onNodeWithText(t(R.string.spot_disconnect_title)).assertExists()
        compose.onNodeWithText(t(R.string.spot_disconnect_text)).assertExists()
        assertEquals(1, compose.onAllNodes(hasText(t(R.string.spot_forget_client_id))).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasText(t(R.string.spot_keep_playlists))).fetchSemanticsNodes().size)
        compose.onNodeWithText(t(R.string.disconnect)).assertIsEnabled()
        compose.onNodeWithText(t(R.string.cancel)).performClick()
        compose.waitForIdle()
        assertEquals(Spotify.Connection.Connected("Tester"), container.spotify.connection.value)
        assertNoSecretShown()
    }

    /**
     * 1.8.7.5: the Spotify row opens "Connect Spotify", a card for each way: "Sign in to Spotify" first (to the "Before
     * you sign in" screen), then "Use your own developer app" (to "How to connect", as before). The sign-in is never
     * called official; nothing is picked beforehand.
     */
    @Test fun connectOffersTheTwoWays() {
        show()
        assertEquals(SettingsTags.ROW_PICK, type(spotify))
        compose.onNode(rowIs(spotify)).performClick()
        compose.waitForIdle()
        assertEquals(1, sheets())
        assertEquals(1, compose.onAllNodes(hasText(t(R.string.spot_session_choose_title))).fetchSemanticsNodes().size)
        val cards = compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION)).fetchSemanticsNodes()
        assertEquals(2, cards.size)
        val labels = cards.map { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text } }
        assertEquals(listOf(t(R.string.spot_session_sign_in), t(R.string.spot_session_own_app)), labels)
        assertTrue("nothing is ringed as picked", cards.none { it.config.getOrNull(SemanticsProperties.Selected) == true })
        // The sign-in's words: never "official" (the own app's way may say Spotify's official API).
        val signIn = listOf(R.string.spot_session_sign_in, R.string.spot_session_sign_in_1, R.string.spot_session_sign_in_2, R.string.spot_session_sign_in_3, R.string.spot_session_about, R.string.spot_session_likes_info)
            .joinToString("\n") { t(it) } + "\n" + t(R.string.spot_session_choose_info).substringBefore("\n\n")
        assertFalse(signIn, "official" in signIn.lowercase())
        // Nothing explains on the sheet itself: the longer words are behind its ⓘ.
        assertEquals(0, compose.onAllNodes(hasText(t(R.string.spot_session_choose_info))).fetchSemanticsNodes().size)

        // Sign in to Spotify: the sheet goes, then the "Before you sign in" screen opens.
        compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText(t(R.string.spot_session_sign_in))).performClick()
        compose.waitUntil(5_000) { route() == "spotify_before" }
        assertEquals(Spotify.Connection.None, container.spotify.connection.value)
    }

    /**
     * Connected by the sign-in: "Connected as", Your playlists and a read-only Liked Songs; no Recent plays, no Top, no
     * "both ways" (nor its counts); Disconnect doesn't ask about a Client ID.
     */
    @Test fun aSessionShowsOnlyWhatItReads() {
        signInToSpotify()
        show()
        assertBudgets("Spotify signed in")
        assertEquals(listOf(accounts, otherServices, spotify, sharing), headings())
        // Your playlists, Liked Songs, Disconnect Spotify.
        assertEquals(listOf(4, 1, 3, 1, 2), groupSizes())
        assertEquals(SettingsTags.ROW_SWITCH, type(t(R.string.spot_playlists)))
        assertEquals(SettingsTags.ROW_SWITCH, type(t(R.string.spot_session_likes)))
        for (id in listOf(R.string.spot_recent, R.string.spot_top, R.string.spot_likes)) assertFalse(t(id), hasTitled(t(id)))
        assertTrue(allTexts().none { "both ways" in it || it == t(R.string.spot_likes_counting) })
        // The read-only switch says so (behind its ⓘ), and every switch is off until turned on.
        val info = t(R.string.spot_session_likes_info)
        assertTrue(info, "brought into vinz" in info.lowercase() && "nothing is written to spotify" in info.lowercase())
        assertFalse(container.spotify.options.value.likes || container.spotify.options.value.playlists || container.spotify.options.value.recent || container.spotify.options.value.top)
        // The sign-in reads followed playlists: its ⓘ texts never say they can't be read, nor name a developer app.
        for (id in listOf(R.string.spot_session_playlists_info, R.string.spot_session_keep_in_step_info)) {
            assertTrue(t(id), t(id).lowercase().let { "can’t be read" !in it && "can't be read" !in it && "developer app" !in it })
        }
        assertTrue(t(R.string.spot_session_playlists_info).contains(t(R.string.spot_session_followed_all)))
        val disconnect = t(R.string.cn_disconnect_spotify)
        assertEquals(t(R.string.spot_connected_as, "Tester"), value(disconnect))

        tap(disconnect)
        compose.onNodeWithText(t(R.string.spot_disconnect_title)).assertExists()
        assertEquals("no Client ID to forget", 0, compose.onAllNodes(hasText(t(R.string.spot_forget_client_id))).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasText(t(R.string.spot_keep_playlists))).fetchSemanticsNodes().size)
        compose.onNodeWithText(t(R.string.cancel)).performClick()
        compose.waitForIdle()
        assertEquals(Spotify.Connection.Connected("Tester"), container.spotify.connection.value)
        assertNoSecretShown()
    }

    /** Signed out by Spotify after the sign-in: "Sign in again" (to "Before you sign in"), not the own app's Reconnect. */
    @Test fun aSessionTakenBackSignsInAgain() {
        signInToSpotify(Spotify.Connection.Revoked)
        show()
        assertBudgets("Spotify session ended")
        val again = t(R.string.spot_session_sign_in_again)
        assertEquals(SettingsTags.ROW_LINK, type(again))
        assertEquals(t(R.string.spot_session_signed_out), value(again))
        assertTrue(hasTitled(t(R.string.cn_disconnect_spotify)))
        assertFalse("no own-app row", hasTitled(spotify))
        assertTrue(allTexts().none { it == t(R.string.cn_spot_access_taken_back) || it == t(R.string.spot_reconnect) })
        tap(again)
        compose.waitUntil(5_000) { route() == "spotify_before" }
    }

    /** Search finds the Spotify card by the sign-in's words. */
    @Test fun theSignInIsFoundBySearch() {
        for (q in listOf("spotify sign in", "spotify login", "spotify account")) {
            assertTrue(q, SettingsIndex.search(q).any { it.page == me && it.group == spotify })
        }
        assertTrue(SettingsIndex.search("spotify login").any { it.title == t(R.string.spot_session_sign_in) })
    }

    /**
     * 1.8.7.6: "Bring your history" moved here from Recommendations & history: it is the last row of Accounts (after
     * "YouTube Music history"), opens its screen, has its search line here and none there, and the old link finds it.
     */
    @Test fun bringYourHistoryIsTheLastRowOfAccounts() {
        show()
        val bring = t(R.string.imp_title)
        val titles = rows().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
        assertEquals(titles.indexOf(history) + 1, titles.indexOf(bring))
        assertEquals(titles.indexOf(vinzAccount) + 3, titles.indexOf(bring))
        assertEquals(SettingsTags.ROW_LINK, type(bring))
        assertEquals(t(R.string.imp_row_detail), value(bring))
        val nc = navController!!
        compose.runOnUiThread {
            val navigator = nc.navigatorProvider.getNavigator(androidx.navigation.compose.ComposeNavigator::class.java)
            nc.graph.addDestination(
                androidx.navigation.compose.ComposeNavigator.Destination(navigator) { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier) }
                    .apply { route = com.vinz.music.ui.Routes.BRING_HISTORY },
            )
        }
        tap(bring)
        compose.waitUntil(5_000) { route() == com.vinz.music.ui.Routes.BRING_HISTORY }
        // Search: one line here, in Accounts; none on Recommendations & history.
        val lines = SettingsIndex.entries.filter { it.title == bring }
        assertEquals(listOf(me), lines.map { it.page })
        assertEquals(accounts, lines.single().group)
        assertTrue(SettingsIndex.search("spotify history").any { it.page == me && it.title == bring })
        assertTrue(SettingsIndex.entries.none { it.page == SettingsPageId.RECOMMENDATIONS_HISTORY && it.title == bring })
        // The old link (library, Bring your history) opens this page at its Accounts group.
        assertEquals(SettingsTarget(me, accounts), SettingsMap.resolve("library", bring))
    }

    /** The old links land, and every line of the page's search names a group the page draws. */
    @Test fun theOldRoutesAndSearch() {
        val discordKey = t(R.string.discord)
        assertEquals(SettingsTarget(me, discordKey), SettingsMap.resolve("discord"))
        assertEquals(SettingsTarget(me, "Last.fm"), SettingsMap.resolve("lastfm"))
        assertEquals(SettingsTarget(me, spotify), SettingsMap.resolve("spotify"))
        // Spotify's "Set up" and "Connection" groups are its one card.
        assertEquals(SettingsTarget(me, spotify), SettingsMap.resolve("spotify", t(R.string.spot_set_up)))
        assertEquals(SettingsTarget(me, spotify), SettingsMap.resolve("spotify", t(R.string.spot_connection)))
        // Sharing and links came from Devices & connections.
        assertEquals(SettingsTarget(me, sharing), SettingsMap.resolve("devices", sharing))

        val mine = SettingsIndex.entries.filter { it.page == me && !it.isPage }
        assertEquals(setOf(accounts, otherServices, "Last.fm", spotify, discordKey, sharing), mine.map { it.group }.toSet())
        // The groups' keys are in the page as it is written (three of the cards have no title to read them from).
        val source = File("src/main/java/com/vinz/music/ui/screens/settings/AccountsSharingPage.kt").readText()
        for (key in listOf("group(tr(R.string.accounts))", "group(tr(R.string.cn_other_services), key = LAST_FM)", "key = tr(R.string.spotify)", "group(null, key = tr(R.string.discord))", "group(tr(R.string.sharing_and_links))")) {
            assertTrue(key, key in source)
        }
        // The rows that only explained have no line any more; what they said is words for the row that took it over.
        assertTrue(mine.none { it.title == t(R.string.about_this) })
        assertTrue(SettingsIndex.search("discord risk").any { it.title == discord })
        assertTrue(SettingsIndex.search("scrobble").any { it.page == me && it.group == "Last.fm" })
        assertTrue(SettingsIndex.search("spotify client id").any { it.page == me && it.group == spotify })
        assertTrue(SettingsIndex.search("links you share").any { it.title == links })
        // Search never says "sign in with Spotify" either.
        assertTrue(mine.none { "sign in with spotify" in it.shown.lowercase() || "sign in with spotify" in it.title.lowercase() })
    }

    @Test fun atLargeTextNothingIsCutOff() {
        connectSpotify()
        signInToDiscord()
        show(fontScale = 1.5f)
        assertBudgets("large text")
        assertNoTextCutOff("font scale 1.5")
    }

    private fun drawn(name: String, glass: Boolean, over: (AppSettings) -> AppSettings) {
        show(glass = glass, over = over)
        val file = save("accounts-sharing", name)
        assertTrue(file.path, file.length() > 10_000)
        assertBudgets(name)
        assertNoTextCutOff(name)
        assertNoSecretShown()
    }

    @Test @Config(qualifiers = "w360dp-h900dp-xxhdpi") fun drawnLight() = drawn("accounts-light", true) { it.copy(themeMode = ThemeMode.LIGHT) }
    @Test @Config(qualifiers = "w360dp-h900dp-xxhdpi") fun drawnDark() = drawn("accounts-dark", true) { it.copy(themeMode = ThemeMode.DARK) }
    @Test @Config(qualifiers = "w360dp-h900dp-xxhdpi") fun drawnPerformance() = drawn("accounts-performance", false) { it.copy(themeMode = ThemeMode.DARK, performanceMode = true) }

    /** The page's longest state: Spotify connected and signed in to Discord (the whole page, on a tall window). */
    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun drawnConnected() {
        connectSpotify()
        signInToDiscord()
        drawn("accounts-dark-connected", true) { it.copy(themeMode = ThemeMode.DARK, cookie = "SAPISID=x", accountName = "Mina") }
    }

    /** "How to connect", and the "Links you share" sheet. */
    @Test @Config(qualifiers = "w360dp-h900dp-xxhdpi") fun drawnSheets() {
        show(glass = true) { it.copy(themeMode = ThemeMode.DARK) }
        compose.onNode(rowIs(spotify)).performClick()
        compose.waitForIdle()
        assertEquals(1, sheets())
        val ways = runCatching { saveNode(SettingsTags.SHEET, "accounts-dark-connect-spotify") }
        assertTrue("the sheet's picture: ${ways.exceptionOrNull()}", ways.isSuccess)
        swipeSheetAway()
        openHowToConnect()
        assertEquals(1, sheets())
        val how = runCatching { saveNode(SettingsTags.SHEET, "accounts-dark-how-to-connect") }
        assertTrue("the sheet's picture: ${how.exceptionOrNull()}", how.isSuccess)
        swipeSheetAway()
        tap(links)
        assertEquals(1, sheets())
        val cards = runCatching { saveNode(SettingsTags.SHEET, "accounts-dark-links-you-share") }
        assertTrue("the sheet's picture: ${cards.exceptionOrNull()}", cards.isSuccess)
    }

    private companion object {
        const val MADE_UP_ACCESS = "made-up-sign-in-for-the-test"
    }
}
