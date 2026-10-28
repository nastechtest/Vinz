package com.prism.music.ui.player

import com.prism.music.ui.theme.PrismIcons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.prism.music.data.model.Song
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.ActionGroup
import com.prism.music.ui.components.ActionHeader
import com.prism.music.ui.components.ActionItem
import com.prism.music.ui.components.ActionSheet
import com.prism.music.ui.components.PlaylistPicker
import com.prism.music.ui.components.QuickActions
import com.prism.music.ui.components.SheetIconButton
import com.prism.music.ui.components.runAction
import com.prism.music.ui.components.shareTarget
import com.prism.music.ui.theme.LocalContainer

/**
 * The player's ⋯ menu, in the app's frosted look (the song's cover as frosted glass): like and
 * share up top, the everyday actions as tiles, then lyrics, player and "go to" groups.
 */
@Composable
fun PlayerOptionsSheet(
    song: Song,
    hasCanvas: Boolean,
    statsShown: Boolean,
    hasNext: Boolean,
    onLyricsSource: () -> Unit,
    onLyricsTiming: () -> Unit,
    onRedownloadLyrics: () -> Unit,
    onToggleStats: () -> Unit,
    onSleepTimer: () -> Unit,
    onCollapse: () -> Unit,
    /** The lyrics have lines in a script other than Latin letters: romanizing them is offered. */
    romanizable: Boolean = false,
    onSing: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val liked by c.library.likedIds.collectAsState()
    val downloads by c.downloads.downloads.collectAsState()
    val videos by c.downloads.videos.collectAsState()
    val hidden by c.canvas.hidden.collectAsState()
    val saved by c.canvas.saved.collectAsState()
    val sleepAt by c.player.sleepAt.collectAsState()
    val sleepEnd by c.player.sleepEndOfSong.collectAsState()
    val together by c.together.state.collectAsState()
    val disliked by c.library.dislikedIds.collectAsState()
    var pickPlaylist by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    var outputs by remember { mutableStateOf(false) }
    var credits by remember { mutableStateOf(false) }
    if (credits) { com.prism.music.ui.components.SongCreditsSheet(song) { credits = false; onDismiss() }; return }
    if (pickPlaylist) { PlaylistPicker(song) { pickPlaylist = false; onDismiss() }; return }
    // Speakers and TVs, from the menu too (Reddit 1.7: "where's the cast button?").
    if (outputs) {
        val profile by c.account.profile.collectAsState()
        val name = (profile?.name ?: c.settings.current.accountName).trim().split(" ").firstOrNull()?.takeIf { it.isNotBlank() }?.let { "$it's Phone" } ?: "This Phone"
        OutputPickerSheet(name) { outputs = false; onDismiss() }
        return
    }
    if (sharing) { com.prism.music.ui.components.ShareSheet(song.shareTarget()) { sharing = false; onDismiss() }; return }

    val isLiked = song.id in liked
    val dl = downloads[song.id]
    val downloaded = dl?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    val sleepMin = if (sleepAt > 0) ((sleepAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1 else 0
    val sleeping = sleepAt > 0 || sleepEnd
    val inSession = together !is com.prism.music.playback.together.TogetherState.Idle

    val quick = listOfNotNull(
        ActionItem(PrismIcons.Radio, "Radio") { c.player.playRadio(song) },
        ActionItem(
            if (downloaded) PrismIcons.DownloadDone else PrismIcons.Download, "Download",
            value = when { downloaded -> "Downloaded"; dl != null -> "${dl.percent.toInt().coerceAtLeast(0)}%"; else -> null },
            active = downloaded, keepOpen = true,
        ) { if (dl != null) c.downloads.remove(song.id) else c.downloads.download(song) },
        ActionItem(PrismIcons.PlaylistAdd, "Playlist", keepOpen = true) { pickPlaylist = true },
        ActionItem(
            PrismIcons.Moon, "Sleep",
            value = when { sleepEnd -> "Song end"; sleepAt > 0 -> "${sleepMin} min"; else -> null },
            active = sleeping, onClick = onSleepTimer,
        ),
    )
    val speed by c.player.episodeSpeed.collectAsState()
    if (song.isEpisode) {
        val speeds = EPISODE_SPEEDS
        val episode = listOfNotNull(
            ActionItem(
                PrismIcons.Speed, "Playback speed", value = "${speed.toString().removeSuffix(".0")}×", active = speed != 1f, keepOpen = true,
            ) { c.player.setEpisodeSpeed(speeds[(speeds.indexOf(speed) + 1) % speeds.size]) },
            song.podcastId?.let { id -> ActionItem(PrismIcons.Podcasts, "Go to podcast") { onCollapse(); nav.go(Routes.podcast(id)) } },
            ActionItem(PrismIcons.Stats, "Stats for nerds", active = statsShown, onClick = onToggleStats),
        )
        ActionSheet(song.thumbnail, onDismiss) { close ->
            ActionHeader(song.thumbnail, song.title, song.artistText) {
                SheetIconButton(PrismIcons.Share, "Share") { sharing = true }
            }
            QuickActions(quick.filter { it.label != "Radio" && it.label != "Playlist" }) { runAction(it, close) }
            ActionGroup("Episode", episode) { runAction(it, close) }
        }
        return
    }
    val optionSettings = c.settings.flow.collectAsState().value
    val translating = optionSettings.lyricsTranslate
    // Says which language, so turning it on for a song already in it explains itself.
    val translateInto = optionSettings.lyricsTranslateTo.ifBlank { defaultTranslateTarget() }.let { tag ->
        java.util.Locale.forLanguageTag(tag).getDisplayLanguage(java.util.Locale.getDefault()).ifBlank { tag }
    }
    val lyrics = listOf(
        ActionItem(PrismIcons.Mic, "Sing", value = if (c.sing.on.collectAsState().value) "On" else null, active = c.sing.on.collectAsState().value, onClick = onSing),
        ActionItem(PrismIcons.Lyrics, "Lyrics source", onClick = onLyricsSource),
        ActionItem(PrismIcons.Timer, "Lyrics timing", onClick = onLyricsTiming),
        ActionItem(
            PrismIcons.Translate, "Translate lyrics", value = if (translating) "Into $translateInto" else "Off", active = translating, keepOpen = true,
        ) { c.settings.setLyricsTranslate(!translating) },
        ActionItem(PrismIcons.Refresh, "Download lyrics again") { onRedownloadLyrics(); toast("Downloading lyrics again") },
    ) + listOfNotNull(
        // This song in Latin letters, or not (whatever Settings → Lyrics says for songs in general).
        if (!romanizable) null else {
            val romanOn = romanizeMode(song.id) != com.prism.music.data.prefs.RomanizeMode.OFF
            ActionItem(PrismIcons.Romanize, "Romanize lyrics", value = if (romanOn) "On" else "Off", active = romanOn, keepOpen = true) {
                c.romanizer.setOverride(song.id, !romanOn)
            }
        },
        // Into your own FLAC / MP3 file, for other players (#16).
        if (song.isLocal || c.downloads.downloads.value[song.id]?.lossless == true)
            ActionItem(PrismIcons.Download, "Save lyrics into the file") { c.lyricsToFiles.request(listOf(song)) } else null,
    )
    val cover = com.prism.music.data.canvas.CanvasRepository.albumKey(song)
    val immersive = c.settings.flow.collectAsState().value.immersivePlayer
    val player = listOfNotNull(
        ActionItem(PrismIcons.Cast, "Play on a speaker or TV", keepOpen = true) { outputs = true },
        // The cover fills the screen with the lyrics over it; a tap there brings this back.
        ActionItem(PrismIcons.Fullscreen, "Immersive mode", value = if (immersive) "On" else null, active = immersive) { c.settings.setImmersivePlayer(!immersive) },
        com.prism.music.ui.components.videoDownloadAction(song, videos[song.id]),
        ActionItem(PrismIcons.Groups, "Listen together", value = if (inSession) "On" else null, active = inSession) { onCollapse(); nav.go(Routes.TOGETHER) },
        ActionItem(PrismIcons.Stats, "Stats for nerds", active = statsShown, onClick = onToggleStats),
        // Where Best part takes you in this song (also: hold the Best part button).
        if (song.isLive) null else ActionItem(
            PrismIcons.Flame, "Your best part", value = c.bestParts.mine(song.id)?.let { "%d:%02d".format(it / 60_000, it / 1000 % 60) },
        ) { BestPartEditor.open = true },
        // Playback diagnostics are on (Settings → About): the listener marks the moment they heard a stutter.
        if (!c.settings.flow.collectAsState().value.audioDiagnostics) null else ActionItem(PrismIcons.Info, "Mark a stutter") {
            val at = c.player.position
            com.prism.music.playback.AudioDiagnostics.log(
                "mark", "the listener heard a stutter in ${song.title} – ${song.artistText} (${song.id}) at %d:%02d".format(at / 60_000, at / 1000 % 60),
            )
            toast("Marked at %d:%02d".format(at / 60_000, at / 1000 % 60))
        },
        if (hasCanvas) ActionItem(PrismIcons.AnimatedCover, "Animated cover", active = song.id !in hidden, keepOpen = true) { c.canvas.setHidden(song.id, song.id !in hidden) } else null,
        if (!hasCanvas) null else if (saved.has(song.id, cover)) ActionItem(PrismIcons.Download, "Animated cover saved", active = true, keepOpen = true) { c.canvasStore.unsave(song) }
        else ActionItem(PrismIcons.Download, "Save animated cover", keepOpen = true) {
            c.canvasStore.save(song)
            toast("Saving animated covers for this album")
        },
    )
    val go = listOfNotNull(
        song.album?.id?.let { id -> ActionItem(PrismIcons.Album, "Go to album") { onCollapse(); nav.go(Routes.album(id)) } },
    ) + com.prism.music.ui.components.goToArtists(song) { route -> onCollapse(); nav.go(route) } + listOfNotNull(
        if (song.isEpisode || song.isLocal || song.isLive) null else ActionItem(PrismIcons.Info, "Song credits", keepOpen = true) { credits = true },
        if (song.id in disliked) ActionItem(PrismIcons.ThumbDownFilled, "Not for me", active = true, keepOpen = true) { c.library.setDisliked(song, false) }
        else ActionItem(PrismIcons.ThumbDown, "Not for me") {
            c.library.setDisliked(song, true)
            toast("Got it — Vinz will steer clear of this song")
            if (hasNext) c.player.next()
        },
    )

    ActionSheet(song.thumbnail, onDismiss) { close ->
        ActionHeader(song.thumbnail, song.title, song.artistText) {
            SheetIconButton(if (isLiked) PrismIcons.HeartFilled else PrismIcons.Heart, if (isLiked) "Remove from liked" else "Like", active = isLiked) {
                c.library.toggleLike(song)
            }
            if (!song.isLocal) SheetIconButton(PrismIcons.Share, "Share") { sharing = true }
        }
        // A file on the phone: no radio, download or music video (a Prism playlist is fine).
        QuickActions(if (song.isLocal) quick.filter { it.label == "Sleep" || it.label == "Playlist" } else quick) { runAction(it, close) }
        ActionGroup("Lyrics", lyrics) { runAction(it, close) }
        // The player's look, changed right here over the player ("Customize this", 1.7.3), with the whole Player page a
        // tap further; Settings → Player where there's no sheet to open.
        val customize = com.prism.music.ui.components.LocalCustomize.current
        val settingsRow = ActionItem(PrismIcons.Settings, "Player settings") {
            if (customize != null) customize(com.prism.music.ui.components.CustomizeTarget.PLAYER, null)
            else { onCollapse(); nav.go(Routes.settings(com.prism.music.ui.screens.SettingsSection.PLAYER.key)) }
        }
        ActionGroup("Player", (if (song.isLocal) player.filter { it.label == "Play on a speaker or TV" || it.label == "Stats for nerds" || it.label == "Immersive mode" || it.label == "Mark a stutter" || it.label == "Your best part" } else player) + settingsRow) { runAction(it, close) }
        ActionGroup("More", go) { runAction(it, close) }
    }
}
