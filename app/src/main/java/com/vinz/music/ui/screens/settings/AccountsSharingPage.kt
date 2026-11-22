package com.vinz.music.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.LinkHandling
import com.vinz.music.data.lastfm.LastFm
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.spotify.Spotify
import com.vinz.music.data.spotify.SpotifyFeature
import com.vinz.music.data.spotify.SpotifyGuard
import com.vinz.music.data.spotify.SpotifyOAuth
import com.vinz.music.playback.DiscordPresence
import com.vinz.music.tr
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.components.VinzButton
import com.vinz.music.ui.components.VinzOutlinedButton
import com.vinz.music.ui.components.VinzTextButton
import com.vinz.music.ui.components.VinzTextField
import com.vinz.music.ui.components.rememberResumeCount
import com.vinz.music.ui.screens.VinzCheck
import com.vinz.music.ui.screens.SettingEntry
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzIcons
import kotlinx.coroutines.launch

private val page = SettingsPageId.ACCOUNTS_SHARING

/** What "Links you share" is called at each of its two stored values (`share_youtube_links`). */
internal fun sharedLinkLabel(youtube: Boolean): String = if (youtube) tr(R.string.youtube) else tr(R.string.youtube_music_2)

/**
 * What the Discord switch's line says: not signed in; signed in but off; and, on, how the connection is going. A pure
 * function of the four things it reads, so that it's tested without Discord.
 */
internal fun discordValue(signedIn: Boolean, on: Boolean, state: DiscordPresence.State, user: String?): String = when {
    !signedIn -> tr(R.string.tv_not_signed_in)
    !on -> user?.let { tr(R.string.cn_signed_in_as, it) } ?: tr(R.string.signed_in_2)
    state == DiscordPresence.State.CONNECTED -> user?.let { tr(R.string.on_as, it) } ?: tr(R.string.on_2)
    state == DiscordPresence.State.CONNECTING -> tr(R.string.connecting_to_discord)
    else -> user?.let { tr(R.string.on_discord_as, it) } ?: tr(R.string.on_discord)
}

/** What the Spotify row says while it isn't connected. */
internal fun spotifyValue(connection: Spotify.Connection): String = when (connection) {
    is Spotify.Connection.Connected -> tr(R.string.spot_connected_as, connection.name)
    Spotify.Connection.Waiting -> tr(R.string.spot_waiting)
    Spotify.Connection.Revoked -> tr(R.string.cn_spot_access_taken_back)
    else -> tr(R.string.cn_spot_not_connected)
}

/**
 * Settings → Accounts & sharing (1.8.4's map, plan §2.3), built with the kit. This file is the only place for the
 * page: its composable, its lines in Settings' search ([accountsSharingIndex]), its "Reset this page" keys
 * ([accountsSharingLookKeys]), its line on the root ([accountsSharingValue]) and the old group names it answers to
 * ([accountsSharingOldGroups]).
 *
 * Five groups: Accounts (the Vinz Account and YouTube Music, each a link to its own screen, and YouTube Music's
 * history, a link to Bring your history), Last.fm, Spotify, Discord, and Sharing and links (from the old Devices &
 * connections). A service's first row says its state ("Connected as …", "Not connected"); its options show while it's
 * connected. Last.fm, Spotify and Discord sit under one heading, "Other services" (1.8.7.6, over Last.fm's card), and
 * keep a card each (Spotify's titled while connected); the old keys "lastfm", "spotify" and "discord" still open the
 * page at their card.
 *
 * Spotify is connected one of two ways, picked on the "Connect Spotify" sheet behind its row (1.8.7.5): the sign-in on
 * Spotify's own page (read only: playlists and liked songs), or the listener's own developer app and Spotify's official
 * API (the steps in the "How to connect" sheet, with the Client ID's field).
 */
@Composable
fun AccountsSharingPage(bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val d = remember { AppSettings() }
    val reset = ListeningResets()
    val onOff: (Boolean) -> String = { ListeningResets.onOff(it) }

    // Accounts
    val profile by c.account.profile.collectAsState()
    val ytLive by c.imports.youtubeLive.on.collectAsState()

    // Last.fm (its options are kept by the Last.fm client, not in the settings)
    val lastFm = c.lastFm
    val lastFmUser by lastFm.user.collectAsState()
    val lastFmOptions by lastFm.options.collectAsState()
    val lastFmWaiting by lastFm.waiting.collectAsState()
    val lastFmDefault = remember { LastFm.Options() }
    val lastFmOn = lastFm.available && lastFmUser != null
    val scrobbleChanged = lastFmOn && reset.add(tr(R.string.last_fm_scrobbling), lastFmOptions.scrobble, lastFmDefault.scrobble, onOff) {
        lastFm.setOptions(lastFm.options.value.copy(scrobble = it))
    }
    val firstArtistChanged = lastFmOn && reset.add(tr(R.string.first_artist_only), lastFmOptions.firstArtistOnly, lastFmDefault.firstArtistOnly, onOff) {
        lastFm.setOptions(lastFm.options.value.copy(firstArtistOnly = it))
    }
    val loveChanged = lastFmOn && reset.add(tr(R.string.love_when_i_like_a_song), lastFmOptions.loveOnLike, lastFmDefault.loveOnLike, onOff) {
        lastFm.setOptions(lastFm.options.value.copy(loveOnLike = it))
    }

    // Spotify
    val spotifyConnection by c.spotify.connection.collectAsState()

    // Discord
    val discordState by c.discord.state.collectAsState()
    val discordUser by c.discord.userName.collectAsState()
    val discordChanged = reset.add(tr(R.string.cn_discord_show), s.discordPresence, d.discordPresence, onOff) { c.settings.setDiscordPresence(it) }

    // Sharing
    val linksChanged = reset.add(tr(R.string.links_you_share), s.shareYoutubeLinks, d.shareYoutubeLinks, { sharedLinkLabel(it) }) { c.settings.setShareYoutubeLinks(it) }
    // Android 12+ asks the listener whether Vinz opens music.youtube.com links (an app that isn't YouTube Music can't
    // claim them), so the state is read again whenever Vinz comes back from that page.
    val resumed = rememberResumeCount()
    val opensLinks = remember(resumed) { LinkHandling.opensYouTubeMusicLinks(context) }

    // 1.8.7.6 (plan-spotify-canvas §2a): right after a session sign-in (it lands back here), or for someone signed in
    // before this version the next time this page opens, one offer of Spotify's canvas; never at start-up, never for the
    // own developer app, and never again once answered (a swipe away counts as Not now) or set by hand.
    var offerAnswered by remember { mutableStateOf(false) }
    if (!offerAnswered && spotifyCanvasOfferShown(spotifyConnection is Spotify.Connection.Connected, c.spotify.bySession, s)) {
        SpotifyCanvasOfferSheet { turnOn ->
            offerAnswered = true
            c.settings.answerSpotifyCanvasOffer(turnOn)
        }
    }

    SettingsPageScaffold(page.title, bottomPadding, showBack = showBack, onSearch = onSearch, find = find, reset = reset.pageReset()) {
        group(tr(R.string.accounts)) {
            // Each has its own screen; the root's account card is where signing out and switching are.
            LinkRow(tr(R.string.vinz_account), value = profile?.name ?: tr(R.string.cn_not_set_up), info = tr(R.string.your_name_and_picture_kept_on)) { nav.go(Routes.ACCOUNT) }
            LinkRow(
                tr(R.string.youtube_music_2),
                value = if (s.isLoggedIn) s.accountName.takeIf { it.isNotBlank() }?.let { tr(R.string.cn_signed_in_as, it) } ?: tr(R.string.signed_in_2) else tr(R.string.tv_not_signed_in),
                info = if (s.isLoggedIn) tr(R.string.cn_youtube_account_info) else tr(R.string.optional_your_library_history_and_recommendation),
            ) { nav.go(if (s.isLoggedIn) Routes.LOGIN_ADD else Routes.LOGIN) }
            // YouTube Music (live): the switch is in Bring your history, next to Takeout, which brings the past.
            LinkRow(
                tr(R.string.cn_youtube_history),
                value = when {
                    !s.isLoggedIn -> tr(R.string.ytlive_connection_signed_out)
                    ytLive -> tr(R.string.ytlive_connection_on)
                    else -> tr(R.string.ytlive_connection_off)
                },
            ) { nav.go(Routes.BRING_HISTORY) }
            // 1.8.7.6: moved here from Recommendations & history, so every account is in one place.
            LinkRow(tr(R.string.imp_title), value = tr(R.string.imp_row_detail), info = tr(R.string.imp_group_info)) { nav.go(Routes.BRING_HISTORY) }
        }

        // 1.8.7.6: one heading over the three services' cards (they had none); each card keeps its own key for search
        // and links.
        group(tr(R.string.cn_other_services), key = LAST_FM) {
            LastFmRows(
                lastFm.available, lastFmUser, lastFmOptions, lastFmWaiting, scrobbleChanged, firstArtistChanged, loveChanged,
                onOptions = { lastFm.setOptions(it) },
                onConnect = { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(lastFm.authUrl()))) } },
                onDisconnect = { lastFm.signOut() },
            )
        }

        // Titled only while connected (or signed out by Spotify after the sign-in): its rows then are the features', or
        // "Sign in again", and none of them says "Spotify". Connected by the sign-in on Spotify's page (1.8.7.5), only
        // what that sign-in can read shows.
        val session = c.spotify.bySession
        val spotifyTitled = spotifyConnection is Spotify.Connection.Connected || (session && spotifyConnection == Spotify.Connection.Revoked)
        val spotifyInfo = when {
            !spotifyTitled -> null
            session -> tr(R.string.spot_session_about)
            else -> tr(R.string.spot_about)
        }
        group(if (spotifyTitled) tr(R.string.spotify) else null, key = tr(R.string.spotify), info = spotifyInfo) {
            when (val conn = spotifyConnection) {
                is Spotify.Connection.Connected -> {
                    SpotifyFeatureRows(session)
                    SpotifyDisconnectRow(conn.name, session)
                }
                Spotify.Connection.Revoked -> if (session) {
                    LinkRow(tr(R.string.spot_session_sign_in_again), value = tr(R.string.spot_session_signed_out), info = tr(R.string.spot_session_signed_out_info)) { nav.go(SPOTIFY_BEFORE) }
                    SpotifyDisconnectRow(null, session = true)
                } else {
                    SpotifyConnectRow(conn)
                    SpotifyDisconnectRow(null, session = false)
                }
                else -> SpotifyConnectRow(conn)
            }
        }

        group(null, key = tr(R.string.discord)) {
            val signedIn = c.discord.signedIn
            SwitchRow(
                tr(R.string.cn_discord_show), s.discordPresence && signedIn,
                value = discordValue(signedIn, s.discordPresence, discordState, discordUser),
                // "About this" was a row that only explained: the small risk to the account is said here.
                info = tr(R.string.your_profile_shows_the_song_and) + "\n\n" + tr(R.string.discord_has_no_way_for_android),
                changed = discordChanged,
            ) { on ->
                // The first time, it signs in on Discord's own page; the switch is turned on from there.
                if (on && !signedIn) nav.go(Routes.DISCORD_LOGIN) else c.settings.setDiscordPresence(on)
            }
            if (signedIn) LinkRow(tr(R.string.sign_out_of_discord), info = tr(R.string.forgets_the_sign_in_on_this), action = true) {
                c.settings.setDiscordPresence(false)
                c.discord.signOut()
            }
        }

        group(tr(R.string.sharing_and_links)) {
            PickCardsRow(
                tr(R.string.links_you_share), listOf(false, true), s.shareYoutubeLinks, { sharedLinkLabel(it) },
                info = tr(R.string.albums_have_no_page_on_youtube), changed = linksChanged, exampleScale = 1f,
                onPick = { c.settings.setShareYoutubeLinks(it) },
            ) { SharedLinkExample(it) }
            LinkRow(
                tr(R.string.open_youtube_music_links_in_vinz),
                value = when (opensLinks) {
                    true -> tr(R.string.on_links_you_tap_open_here)
                    false -> tr(R.string.off_turn_on_open_supported_links)
                    null -> tr(R.string.choose_vinz_when_a_link_asks)
                },
                info = tr(R.string.android_only_lets_an_app_open), external = true,
            ) { LinkHandling.openSettings(context) }
        }
    }
}

/**
 * 1.8.7.6: whether the one-time offer of "Spotify's canvas first" shows: connected by the session sign-in (not the own
 * developer app, which can't ask for a canvas), the offer not answered and the switch not on. Whatever Vinz's own
 * Canvas switches say (Spotify's canvas works with both off), but not in Performance mode, where it would be paused:
 * "Turn on" is to end in Spotify's canvases showing, so the offer waits for the next visit outside it.
 */
internal fun spotifyCanvasOfferShown(connected: Boolean, session: Boolean, s: AppSettings): Boolean =
    connected && session && !s.performanceMode && !s.behavior.canvas.spotifyOffered && !s.behavior.canvas.spotifyFirst

/** The offer itself, in the kit's sheet: what it is in a line or two (unofficial), "Turn on" and "Not now". */
@Composable
internal fun SpotifyCanvasOfferSheet(onAnswer: (turnOn: Boolean) -> Unit) {
    RowsSheet(tr(R.string.spot_canvas_offer_title), onDismiss = { onAnswer(false) }) { SpotifyCanvasOfferContent(onAnswer) }
}

/** What [SpotifyCanvasOfferSheet] holds under its title (apart, so it's tested without a sheet's window). */
@Composable
internal fun SpotifyCanvasOfferContent(onAnswer: (turnOn: Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("spot_canvas_offer")) {
        Text(
            tr(R.string.spot_canvas_offer_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        VinzButton(onClick = { onAnswer(true) }, modifier = Modifier.fillMaxWidth().testTag("spot_canvas_offer_on")) { Text(tr(R.string.turn_on)) }
        VinzTextButton(onClick = { onAnswer(false) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("spot_canvas_offer_not_now")) {
            Text(tr(R.string.not_now))
        }
    }
}

/** A "Links you share" card: the link as it's sent, over where it opens. */
@Composable
private fun SharedLinkExample(youtube: Boolean) {
    Column {
        Text(
            if (youtube) tr(R.string.youtu_be_fj9ruzimczq) else tr(R.string.music_youtube_com_watch_v_fj9ruzimczq),
            Modifier.fillMaxWidth().padding(vertical = 10.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        Text(
            if (youtube) tr(R.string.opens_in_youtube) else tr(R.string.opens_in_youtube_music),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------- Last.fm

/**
 * Last.fm's rows (#133, #160), from what the Last.fm client says (so that they're tested without it): in a copy of
 * Vinz built without Last.fm, a row that says so; not connected, "Connect Last.fm" (it opens Last.fm's own page in
 * the browser); connected ([user]), scrobbling's switch with who is connected and what waits to be sent, the two
 * options, and Disconnect.
 */
@Composable
internal fun LastFmRows(
    available: Boolean,
    user: String?,
    options: LastFm.Options,
    waiting: Int,
    scrobbleChanged: Boolean,
    firstArtistChanged: Boolean,
    loveChanged: Boolean,
    onOptions: (LastFm.Options) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val about = tr(R.string.sends_what_you_listen_to_scrobbles_all)
    when {
        !available -> LinkRow(LAST_FM, value = tr(R.string.this_copy_of_vinz_was_built), enabled = false) {}
        user == null -> LinkRow(LAST_FM, value = tr(R.string.cn_not_connected), info = about, external = true, onClick = onConnect)
        else -> {
            SwitchRow(
                tr(R.string.last_fm_scrobbling), options.scrobble,
                // (The string's own leading space doesn't survive strings.xml: "Connected as mina· 3 waiting" before 1.8.4.)
                value = tr(R.string.connected_as, user) + if (waiting > 0) " " + tr(R.string.waiting_to_send, waiting).trim() else "",
                info = about, changed = scrobbleChanged,
            ) { onOptions(options.copy(scrobble = it)) }
            SwitchRow(
                tr(R.string.first_artist_only), options.firstArtistOnly,
                info = tr(R.string.a_b_c_goes_to_last) + ". " + tr(R.string.last_fm_matches_one_artist_per), changed = firstArtistChanged,
            ) { onOptions(options.copy(firstArtistOnly = it)) }
            SwitchRow(tr(R.string.love_when_i_like_a_song), options.loveOnLike, info = tr(R.string.liking_here_loves_it_on_last), changed = loveChanged) {
                onOptions(options.copy(loveOnLike = it))
            }
            LinkRow(tr(R.string.cn_disconnect_lastfm), info = tr(R.string.stops_scrobbling_and_forgets_the_connection), action = true, onClick = onDisconnect)
        }
    }
}

// ---------------------------------------------------------------------------- Spotify

/** The "Before you sign in" screen that leads to the sign-in on Spotify's own page (1.8.7.5; its route is registered with it). */
internal const val SPOTIFY_BEFORE = "spotify_before"

/** The two ways to connect Spotify, as the Connect sheet's cards show them. */
internal enum class SpotifyWay { SIGN_IN, OWN_APP }

/**
 * Spotify while it isn't connected: one row that says so (or that Vinz is waiting for Spotify's answer, or that
 * Spotify took the access back). It opens "Connect Spotify" (1.8.7.5): a card for each way, "Sign in to Spotify" (to
 * the "Before you sign in" screen) and "Use your own developer app" (to "How to connect", as before). Waiting for the
 * own app's answer, or its access taken back, the row opens "How to connect" straight away (Cancel and Reconnect are there).
 */
@Composable
private fun SpotifyConnectRow(connection: Spotify.Connection) {
    val nav = LocalNavigator.current
    val title = tr(R.string.spotify)
    var choosing by rememberSaveable(title, "spotify-choose") { mutableStateOf(false) }
    var open by rememberSaveable(title, "spotify-connect") { mutableStateOf(false) }
    // The card picked, followed once the Connect sheet is gone (so two sheets never show at once).
    var picked by remember { mutableStateOf<SpotifyWay?>(null) }
    val straightToOwnApp = connection == Spotify.Connection.Revoked || connection == Spotify.Connection.Waiting
    val info = tr(R.string.spot_session_choose_info)
    PickRow(title, spotifyValue(connection), info = info) { if (straightToOwnApp) open = true else choosing = true }
    if (choosing) PickSheet<SpotifyWay?>(
        tr(R.string.spot_session_choose_title), listOf(SpotifyWay.SIGN_IN, SpotifyWay.OWN_APP), null,
        label = { tr(if (it == SpotifyWay.SIGN_IN) R.string.spot_session_sign_in else R.string.spot_session_own_app) },
        onPick = { picked = it },
        onDismiss = {
            choosing = false
            when (picked) {
                SpotifyWay.SIGN_IN -> nav.go(SPOTIFY_BEFORE)
                SpotifyWay.OWN_APP -> open = true
                null -> Unit
            }
            picked = null
        },
        info = info, exampleScale = 1f,
    ) { SpotifyWayExample(it ?: SpotifyWay.SIGN_IN) }
    if (open) SpotifyConnectSheet(reconnect = connection == Spotify.Connection.Revoked) { open = false }
}

/** A Connect card's picture: what that way gives, three short lines with a tick. */
@Composable
private fun SpotifyWayExample(way: SpotifyWay) {
    val lines = if (way == SpotifyWay.SIGN_IN) {
        listOf(R.string.spot_session_sign_in_1, R.string.spot_session_sign_in_2, R.string.spot_session_sign_in_3)
    } else {
        listOf(R.string.spot_session_own_app_1, R.string.spot_session_own_app_2, R.string.spot_session_own_app_3)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (id in lines) Row(verticalAlignment = Alignment.Top) {
            Icon(VinzIcons.Check, null, Modifier.padding(top = 1.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(tr(id), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * "How to connect": the steps to make a developer app in Spotify's dashboard (the dashboard's link and the redirect
 * address to copy among them), the Client ID's field, and Connect. The steps are a numbered list, as they were on the
 * page before 1.8.4 (§2.7). Only Spotify's official API, through the listener's own app.
 */
@Composable
private fun SpotifyConnectSheet(reconnect: Boolean, onDismiss: () -> Unit) {
    val spotify = LocalContainer.current.spotify
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val copy = copier()
    val connection by spotify.connection.collectAsState()
    var clientId by rememberSaveable { mutableStateOf(spotify.store.clientId) }
    val valid = SpotifyOAuth.validClientId(clientId)
    RowsSheet(tr(R.string.cn_how_to_connect), onDismiss, info = tr(R.string.spot_set_up_info)) {
        if (reconnect) ConnectSheetText(tr(R.string.spot_revoked)) else {
            ConnectSheetText(tr(R.string.spot_intro))
            ConnectSheetText(tr(R.string.spot_premium_note))
        }
        ConnectSheetText(tr(R.string.spot_step_1))
        // The kit's rows: a link out of Vinz, and an action (a tap copies the address).
        LinkRow(tr(R.string.spot_open_dashboard), value = Spotify.DASHBOARD.removePrefix("https://"), external = true) { runCatching { uri.openUri(Spotify.DASHBOARD) } }
        ConnectSheetText(tr(R.string.spot_step_2))
        ConnectSheetText(tr(R.string.spot_step_3))
        LinkRow(tr(R.string.spot_redirect_uri), value = SpotifyOAuth.REDIRECT_TO_REGISTER, action = true) { copy(SpotifyOAuth.REDIRECT_TO_REGISTER) }
        ConnectSheetText(tr(R.string.spot_step_4))
        ConnectSheetText(tr(R.string.spot_step_5))
        ConnectSheetText(tr(R.string.spot_step_6))
        VinzTextField(
            clientId, { clientId = it.trim() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag(ConnectTags.SPOTIFY_CLIENT_ID),
            label = { Text(tr(R.string.spot_client_id)) },
            singleLine = true,
            isError = clientId.isNotEmpty() && !valid,
            supportingText = if (clientId.isNotEmpty() && !valid) ({ Text(tr(R.string.spot_client_id_invalid)) }) else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (connection == Spotify.Connection.Waiting) {
                Text(tr(R.string.spot_waiting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                VinzOutlinedButton(onClick = { spotify.cancelConnect() }) { Text(tr(R.string.cancel)) }
            } else {
                VinzButton(onClick = { spotify.connect(context, clientId) }, modifier = Modifier.testTag(ConnectTags.SPOTIFY_CONNECT), enabled = valid) {
                    Text(if (reconnect) tr(R.string.spot_reconnect) else tr(R.string.spot_connect))
                }
            }
        }
        (connection as? Spotify.Connection.Failed)?.let { ConnectSheetText(it.message) }
    }
}

/**
 * Each feature's switch; its line says how it's going when that isn't simply "fine". Connected by the sign-in
 * ([session]), only the playlists and a read-only "Liked Songs" (no counts asked first: nothing goes to Spotify);
 * Spotify gives that sign-in no recent plays or top.
 */
@Composable
private fun SpotifyFeatureRows(session: Boolean) {
    val spotify = LocalContainer.current.spotify
    val o by spotify.options.collectAsState()
    val scope = rememberCoroutineScope()
    var dryRun by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    /** Spotify not answering for [f] (and when Vinz tries again), else [normal]. */
    @Composable
    fun status(f: SpotifyFeature, normal: String? = null): String? {
        val state by spotify.guards.getValue(f).state.collectAsState()
        return when (state) {
            SpotifyGuard.State.OK -> normal
            SpotifyGuard.State.NOT_ANSWERING -> tr(R.string.spot_not_answering)
            SpotifyGuard.State.PAUSED -> tr(R.string.spot_paused)
        }
    }
    if (!session) {
        SwitchRow(tr(R.string.spot_recent), o.recent, value = status(SpotifyFeature.RECENT), info = tr(R.string.spot_recent_info)) { spotify.setEnabled(SpotifyFeature.RECENT, it) }
        SwitchRow(tr(R.string.spot_top), o.top, value = status(SpotifyFeature.TOP), info = tr(R.string.spot_top_info)) { spotify.setEnabled(SpotifyFeature.TOP, it) }
    }
    val mirror = remember(o.playlists) { spotify.playlists.mirror() }
    // How many are in Library, and how many Spotify doesn't share (what to do about those is behind the ⓘ).
    val counted = if (o.playlists && mirror.listedAt > 0) listOfNotNull(
        tr(R.string.spot_playlists_count, mirror.lists.size),
        if (mirror.notYours > 0 && !session) tr(R.string.cn_spot_followed_short, mirror.notYours) else null,
    ).joinToString(" · ") else null
    // The sign-in reads followed playlists, so its ⓘ doesn't say they can't be read (nor how to copy them).
    val followed = if (o.playlists && mirror.notYours > 0 && !session) {
        "\n\n" + tr(R.string.spot_playlists_not_yours, mirror.notYours) + "\n" + tr(R.string.spot_followed_why) + "\n" + tr(R.string.spot_followed_steps) + "\n" + tr(R.string.spot_followed_tunemymusic)
    } else ""
    SwitchRow(
        tr(R.string.spot_playlists), o.playlists, value = status(SpotifyFeature.PLAYLISTS, counted), info = tr(if (session) R.string.spot_session_playlists_info else R.string.spot_playlists_info) + followed,
    ) { spotify.setEnabled(SpotifyFeature.PLAYLISTS, it) }
    if (o.playlists) SwitchRow(tr(R.string.spot_keep_in_step), o.keepInStep, info = tr(if (session) R.string.spot_session_keep_in_step_info else R.string.spot_keep_in_step_info)) { spotify.setKeepInStep(it) }
    // The sign-in can read the playlists you follow: by themselves with this on, a tap each in Library with it off.
    if (session && o.playlists) SwitchRow(tr(R.string.spot_session_followed_all), o.followedToo, info = tr(R.string.spot_session_followed_all_info)) { spotify.setFollowedToo(it) }
    // Read only: Spotify's likes come into Vinz, nothing goes back.
    if (session) {
        SwitchRow(tr(R.string.spot_session_likes), o.likes, value = status(SpotifyFeature.LIKES), info = tr(R.string.spot_session_likes_info)) { spotify.setEnabled(SpotifyFeature.LIKES, it) }
    } else SwitchRow(
        tr(R.string.spot_likes), o.likes, value = status(SpotifyFeature.LIKES, if (checking) tr(R.string.spot_likes_counting) else null),
        info = tr(R.string.spot_likes_info),
    ) { on ->
        if (!on) spotify.setEnabled(SpotifyFeature.LIKES, false)
        else if (!checking) {
            // First the counts, then the listener's word.
            checking = true
            scope.launch {
                val r = spotify.likesDryRun()
                checking = false
                dryRun = r.fold({ tr(R.string.spot_likes_dry_run, it.toLikeHere, it.toLikeOnSpotify) }, { "" })
            }
        }
    }
    if (!session) dryRun?.let { text ->
        AlertDialog(
            onDismissRequest = { dryRun = null },
            title = { Text(tr(R.string.spot_likes)) },
            text = { Text(text.ifEmpty { tr(R.string.spot_not_answering) }) },
            confirmButton = {
                if (text.isNotEmpty()) VinzTextButton(onClick = { dryRun = null; spotify.setEnabled(SpotifyFeature.LIKES, true) }) { Text(tr(R.string.spot_likes_turn_on)) }
            },
            dismissButton = { VinzTextButton(onClick = { dryRun = null }) { Text(tr(R.string.cancel)) } },
        )
    }
}

/**
 * Disconnect (its line says who is connected), asking what to forget. [name] null: Spotify took the access back.
 * Connected by the sign-in ([session]) there is no Client ID to forget, so that choice isn't asked.
 */
@Composable
private fun SpotifyDisconnectRow(name: String?, session: Boolean) {
    val c = LocalContainer.current
    var asking by remember { mutableStateOf(false) }
    var clientIdToo by remember { mutableStateOf(false) }
    var keepPlaylists by remember { mutableStateOf(false) }
    LinkRow(tr(R.string.cn_disconnect_spotify), value = name?.let { tr(R.string.spot_connected_as, it) }, info = tr(R.string.spot_disconnect_sub), action = true) { asking = true }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text(tr(R.string.spot_disconnect_title)) },
        text = {
            Column {
                Text(tr(R.string.spot_disconnect_text))
                if (!session) Row(verticalAlignment = Alignment.CenterVertically) {
                    VinzCheck(clientIdToo, label = tr(R.string.spot_forget_client_id)) { clientIdToo = it }
                    Text(tr(R.string.spot_forget_client_id))
                }
                if (name != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    VinzCheck(keepPlaylists, label = tr(R.string.spot_keep_playlists)) { keepPlaylists = it }
                    Text(tr(R.string.spot_keep_playlists))
                }
            }
        },
        confirmButton = {
            VinzTextButton(onClick = { asking = false; c.scope.launch { c.spotify.disconnect(clientIdToo = clientIdToo && !session, keepPlaylists = keepPlaylists) } }) { Text(tr(R.string.disconnect)) }
        },
        dismissButton = { VinzTextButton(onClick = { asking = false }) { Text(tr(R.string.cancel)) } },
    )
}

// ---------------------------------------------------------------------------- The page's other parts

/**
 * The root's line for Accounts & sharing: the accounts it holds, with "Discord on" first while it shows what you play
 * (the one account whose state is in the settings).
 */
fun accountsSharingValue(s: AppSettings, dark: Boolean): String =
    if (s.discordPresence) tr(R.string.root_accounts_discord_on) else tr(R.string.root_accounts_short)

/** The look settings "Reset this page" puts back: none (accounts aren't part of a look; the page resets its rows itself). */
val accountsSharingLookKeys: List<String> = emptyList()

/**
 * Old group titles → this page's groups: Spotify's "Set up" and "Connection" are its one card now. (Discord, Last.fm,
 * Spotify and "Sharing and links" are still found by those names: they are the cards' keys.)
 */
val accountsSharingOldGroups: Map<String, String> by lazy {
    mapOf(
        tr(R.string.spot_set_up) to tr(R.string.spotify), tr(R.string.spot_connection) to tr(R.string.spotify),
        // "Bring your history" is a row of Accounts since 1.8.7.6 (its old link "library?find=Bring your history" comes here).
        tr(R.string.imp_title) to tr(R.string.accounts),
    )
}

/** The page's lines in Settings' search (each names the group it is drawn in). */
val accountsSharingIndex: List<SettingEntry> by lazy {
    val accounts = tr(R.string.accounts)
    val discord = tr(R.string.discord)
    val spotify = tr(R.string.spotify)
    val sharing = tr(R.string.sharing_and_links)
    val spot = tr(R.string.spot_keywords)
    val session = tr(R.string.spot_session_keywords)
    listOf(
        SettingEntry(page, accounts, tr(R.string.vinz_account), tr(R.string.profile_name_picture_photo_local_on)),
        SettingEntry(page, accounts, tr(R.string.youtube_music_2), tr(R.string.cn_youtube_keywords), shown = tr(R.string.sign_in_to_youtube_music)),
        SettingEntry(page, accounts, tr(R.string.cn_youtube_history), tr(R.string.cn_youtube_history_keywords)),
        SettingEntry(page, accounts, tr(R.string.imp_title), tr(R.string.imp_keywords)),
        // 1.8.7.6: the heading over the three services' cards (it opens the page at Last.fm's, the first).
        SettingEntry(page, tr(R.string.cn_other_services), tr(R.string.cn_other_services), listOf(LAST_FM, spotify, discord).joinToString(" ")),
        // Last.fm: its row ("Connect Last.fm" until 1.8.7.6, still a search line) until it's connected, then its three
        // switches and Disconnect.
        SettingEntry(page, LAST_FM, tr(R.string.connect_last_fm), tr(R.string.lastfm_last_fm_scrobble_scrobbling_account)),
        SettingEntry(page, LAST_FM, tr(R.string.last_fm_scrobbling), tr(R.string.lastfm_last_fm_scrobble) + " " + tr(R.string.lastfm_scrobble_send_plays) + " " + tr(R.string.scrobble)),
        SettingEntry(page, LAST_FM, tr(R.string.first_artist_only), tr(R.string.lastfm_featured_artists_main_artist)),
        SettingEntry(page, LAST_FM, tr(R.string.love_when_i_like_a_song), tr(R.string.lastfm_love_like_heart)),
        SettingEntry(page, LAST_FM, tr(R.string.cn_disconnect_lastfm), tr(R.string.lastfm_sign_out)),
        // 1.8.1: Spotify, through the listener's own developer app. Its set-up is the "How to connect" sheet of its row.
        SettingEntry(page, spotify, spotify, spot),
        SettingEntry(page, spotify, tr(R.string.cn_how_to_connect), spot + " " + tr(R.string.spot_set_up), shown = spotify + ": " + tr(R.string.cn_how_to_connect)),
        SettingEntry(page, spotify, tr(R.string.spot_open_dashboard), spot),
        SettingEntry(page, spotify, tr(R.string.spot_redirect_uri), spot),
        SettingEntry(page, spotify, tr(R.string.spot_client_id), spot),
        // The switches' second lines of before 1.8.4 are words to find them by.
        SettingEntry(page, spotify, tr(R.string.spot_recent), spot + " " + tr(R.string.spot_recent_sub)),
        SettingEntry(page, spotify, tr(R.string.spot_top), spot + " " + tr(R.string.spot_top_sub)),
        SettingEntry(page, spotify, tr(R.string.spot_playlists), spot + " " + tr(R.string.spot_playlists_sub)),
        SettingEntry(page, spotify, tr(R.string.spot_keep_in_step), spot + " " + tr(R.string.spot_keep_in_step_sub)),
        SettingEntry(page, spotify, tr(R.string.spot_likes), spot + " " + tr(R.string.spot_likes_sub)),
        SettingEntry(page, spotify, tr(R.string.cn_disconnect_spotify), spot + " " + tr(R.string.spot_connected_as, "").trim()),
        // 1.8.7.5: the Connect sheet's two ways, and the card connected by the sign-in on Spotify's page (read only).
        SettingEntry(page, spotify, tr(R.string.spot_session_choose_title), spot + " " + session, shown = spotify + ": " + tr(R.string.spot_session_choose_title)),
        SettingEntry(page, spotify, tr(R.string.spot_session_sign_in), spot + " " + session),
        SettingEntry(page, spotify, tr(R.string.spot_session_own_app), spot + " " + tr(R.string.spot_set_up)),
        SettingEntry(page, spotify, tr(R.string.spot_session_likes), spot + " " + session),
        SettingEntry(page, spotify, tr(R.string.spot_session_followed_all), spot + " " + session),
        SettingEntry(page, spotify, tr(R.string.spot_session_sign_in_again), spot + " " + session + " " + tr(R.string.spot_session_signed_out)),
        // "About this" (the small risk to the Discord account) is behind the switch's ⓘ now.
        SettingEntry(page, discord, tr(R.string.cn_discord_show), tr(R.string.discord_rich_presence_status_listening) + " " + tr(R.string.discord_terms_risk)),
        SettingEntry(page, discord, tr(R.string.sign_out_of_discord)),
        SettingEntry(page, sharing, sharing, tr(R.string.share_link)),
        SettingEntry(page, sharing, tr(R.string.links_you_share), tr(R.string.share_link_youtube_youtu_be_youtube)),
        SettingEntry(page, sharing, tr(R.string.open_youtube_music_links_in_vinz), tr(R.string.open_by_default_supported_links_music)),
    )
}
