package com.vinz.music.data.canvas

import com.vinz.music.data.prefs.AppSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Whether a song's Canvas loop from its music video is wanted (1.8.6, plan-canvas §3.1, §3.7, §11a R1), in one place, so
 * the player, the next-song preparation ([CanvasAhead]), downloads and diagnostics can never disagree.
 *
 * [settings] are the ones Vinz uses ([AppSettings.effective]: Performance mode reads as Canvas off).
 */
data class CanvasWant(
    /** A loop is the picture that would show, if the song has one. */
    val video: Boolean,
    /** The stream height it is cut from: 480 lighter, 1080 full screen, else 720. 0 when not [video]. */
    val height: Int,
    /** The network may be used for it now (else only a loop already on the phone plays). */
    val network: Boolean,
    /** Why no loop is asked for, when not [video]. */
    val notAsked: NotAsked?,
)

/** Why a loop isn't asked for (diagnostics' "not asked: …"). */
enum class NotAsked(val text: String) {
    CANVAS_OFF("Canvas off"),
    VIDEOS_OFF("music videos off"),
    HIDDEN("Canvas loop hidden for this song"),
    NOT_A_SONG("not a song (a file, an upload, an episode or live)"),
    COVER_FIRST("the animated cover shows first"),
}

/**
 * The decision: [hasCover] is whether the song has an animated cover that may play ([canvasCoverWanted]), [songChoice]
 * the listener's "Use:" for it, [hidden] its Canvas loop switched off from the player (not its animated cover), [isSong] [ClipGate.isSong]. [metered]: on mobile data (or any
 * network that charges by use).
 */
fun canvasWant(
    settings: AppSettings, metered: Boolean, hasCover: Boolean, songChoice: CanvasSource?, hidden: Boolean, isSong: Boolean,
): CanvasWant {
    val prefs = settings.behavior.canvas
    val notAsked = when {
        !settings.behavior.canvas.enabled -> NotAsked.CANVAS_OFF
        !prefs.useVideos -> NotAsked.VIDEOS_OFF
        hidden -> NotAsked.HIDDEN
        !isSong -> NotAsked.NOT_A_SONG
        pickSource(hasCover, true, true, prefs.videoFirst, songChoice) != CanvasSource.VIDEO -> NotAsked.COVER_FIRST
        else -> null
    }
    if (notAsked != null) return CanvasWant(false, 0, false, notAsked)
    return CanvasWant(true, canvasHeight(settings), canvasNetworkAllowed(settings, metered), null)
}

/**
 * Whether the loop from the song's music video is the picture whatever its animated cover (1.8.7): the video comes
 * first (the setting, or "Use:" for this song). Then nothing about the cover changes the answer, and the player asks for
 * the loop at once instead of after the cover's lookup ([showCanvas]).
 */
fun canvasLoopFirst(settings: AppSettings, metered: Boolean, songChoice: CanvasSource?, hidden: Boolean, isSong: Boolean): Boolean =
    canvasWant(settings, metered, hasCover = true, songChoice = songChoice, hidden = hidden, isSong = isSong).video

/**
 * The order a song's picture comes in on the player (1.8.7). [cover] looks the animated cover up (showing one already
 * known on the way, through the function it is given) and gives what it found; [loop] gives the song's loop from its
 * music video for whether it has a cover (null: none, or not wanted); [show] puts a picture up.
 *
 * Without [loopFirst] the loop waits for the cover's answer, which decides whether it is wanted. With it the loop is
 * asked for at once, beside the cover's lookup (on the A16 that lookup was two to three seconds of a new song's wait):
 * the cover shows while the loop comes and stays when there is none, and once the loop is up nothing takes its place.
 * Runs on one thread (the player's).
 */
suspend fun showCanvas(
    loopFirst: Boolean,
    cover: suspend (show: (CanvasArtwork?) -> Unit) -> CanvasArtwork?,
    loop: suspend (hasCover: Boolean) -> CanvasArtwork?,
    show: (CanvasArtwork?) -> Unit,
): Unit = kotlinx.coroutines.coroutineScope {
    var loopUp = false
    val early = if (loopFirst) async { loop(true)?.also { loopUp = true; show(it) } } else null
    val coverShows: (CanvasArtwork?) -> Unit = { if (!loopUp) show(it) }
    val found = cover(coverShows)
    coverShows(found)
    if (early != null) early.await() else loop(found != null)?.let(show)
}

/**
 * Whether a song's animated cover may play (1.8.6), in one place for the player, the next-song preparation and
 * diagnostics: Canvas on, "Animated covers" on, and not switched off for this song ([coverHidden], the ⋯ menu's
 * "Animated cover"). Apart from the loop from the music video: a song whose cover may not play counts as having none, so
 * its video's loop may show ([canvasWant] with `hasCover = false`).
 *
 * 1.8.7.6: [spotify] is Spotify's canvas being asked ([CanvasRepository.spotifyAsked]): it shows where the animated
 * cover would, so with it the cover's place is wanted whatever Vinz's own two switches say ("Animated covers" and
 * "Canvas" may both be off: Spotify's canvas depends on its own switch alone, [spotifyCanvasOn]).
 */
fun canvasCoverWanted(settings: AppSettings, coverHidden: Boolean, spotify: Boolean = false, spotifyHidden: Boolean = false): Boolean =
    (!coverHidden && vinzCoversOn(settings)) || (spotify && !spotifyHidden)

/** Vinz's own animated covers may play: Canvas on and "Animated covers" on (both off keeps the flag, Canvas off). */
fun vinzCoversOn(settings: AppSettings): Boolean = settings.behavior.canvas.enabled && settings.behavior.canvas.covers

/**
 * 1.8.7.6, "Spotify's canvas first" may be asked: its own switch and the Spotify web-player session sign-in
 * ([session]), nothing else. Vinz's "Animated covers" and "Canvas" don't matter (owner, 2026-11-16: "Turn on" must
 * always end in Spotify's canvases showing). Performance mode pauses it: [settings] are the ones Vinz uses
 * ([AppSettings.effective], where it reads as off).
 */
fun spotifyCanvasOn(settings: AppSettings, session: Boolean): Boolean = settings.behavior.canvas.spotifyFirst && session

/**
 * 1.8.7.6 (owner, 2026-11-16): beside Spotify's canvas, "When a song has both: music video first" does not put the video
 * first. Spotify's canvas is tried first for every song, and only the song's own choice (the ⋯ menu's "Vinz Canvas" or
 * "Use:") shows its video's loop instead. These are [settings] as they count beside Spotify's canvas.
 */
fun spotifyBeforeVideo(settings: AppSettings): AppSettings =
    if (!settings.behavior.canvas.videoFirst) settings
    else settings.copy(behavior = settings.behavior.copy(canvas = settings.behavior.canvas.copy(videoFirst = false)))

/**
 * Whether the player asks for the loop from the music video at once ([canvasLoopFirst]). While Spotify's canvas is tried
 * for the song ([spotifyTried]: asked, the network may be used, not switched off for the song) only the song's own
 * choice does that ([spotifyBeforeVideo]).
 */
fun playerLoopFirst(settings: AppSettings, metered: Boolean, songChoice: CanvasSource?, hidden: Boolean, isSong: Boolean, spotifyTried: Boolean): Boolean =
    canvasLoopFirst(if (spotifyTried) spotifyBeforeVideo(settings) else settings, metered, songChoice, hidden, isSong)

/**
 * The player's canvas for a song (1.8.7.6, moved out of the player so it can be tested): [showCanvas] for Vinz's
 * cover and loop, with Spotify's canvas first in the cover's place.
 *
 * Spotify's answer is waited for about 4 s ([SpotifyCanvasSource.WAIT_MS]) before Vinz's own cover is looked up. An
 * answer that comes later (the first ask after a start builds the Spotify connection and fetches its token: 7 s on the
 * A16) still shows when it comes, in place of what Vinz showed meanwhile, unless the music video's loop comes first
 * for this song ([loopFirst]) and is already up. Without this a late loop was remembered but only shown the next time
 * the song played.
 *
 * [loop] is the player's loop from the music video for whether the song has a cover and whether that cover is Spotify's
 * canvas (then the song's video comes first only by its own choice, [spotifyBeforeVideo]); [shown] what is up now.
 */
suspend fun playerCanvas(
    settings: AppSettings, song: com.vinz.music.data.model.Song, repo: CanvasRepository,
    coverHidden: Boolean, metered: Boolean, coverAllowed: Boolean, loopFirst: Boolean,
    loop: suspend (hasCover: Boolean, spotifys: Boolean) -> CanvasArtwork?,
    shown: () -> CanvasArtwork?, show: (CanvasArtwork?) -> Unit,
    spotifyHidden: Boolean = false,
): Unit = kotlinx.coroutines.coroutineScope {
    val asked = repo.spotifyAsked()
    if (!settings.behavior.canvas.enabled && !asked) { show(null); return@coroutineScope }
    val spotifyNet = canvasNetworkAllowed(settings, metered)
    // The song's own switches (owner, 2026-11-16: each on its own): "Spotify canvas" off leaves Vinz's animated cover,
    // "Animated cover" off leaves Spotify's canvas.
    val spotify = asked && !spotifyHidden
    val vinzCover = coverAllowed && !coverHidden
    val coverWanted = canvasCoverWanted(settings, coverHidden, asked, spotifyHidden)
    // Once Spotify's late loop is up, Vinz's cover (still being looked up) doesn't take its place.
    var spotifyUp = false
    var foundSpotifys = false
    val put: (CanvasArtwork?) -> Unit = { if (!spotifyUp || it?.spotify == true) show(it) }
    if (coverWanted && spotify && spotifyNet) launch {
        val late = repo.spotifyLate(song) ?: return@launch
        val now = shown()
        if (now?.spotify == true) return@launch
        if (loopFirst) { if (now?.clip == null) show(late) } else { spotifyUp = true; show(late) }
    }
    showCanvas(
        loopFirst,
        cover = { early ->
            // Spotify's canvas by the loops' mobile-data rule, Vinz's animated cover by its own ([coverAllowed]).
            if (coverWanted && (vinzCover || (spotify && spotifyNet))) {
                val known = repo.cached(song, spotifyOk = spotifyNet && !spotifyHidden, vinzOk = vinzCover)
                early(known)
                if (known == null) kotlinx.coroutines.delay(500)
                repo.canvasFor(song, spotifyOk = spotifyNet && !spotifyHidden, vinzOk = vinzCover).also { foundSpotifys = it?.spotify == true }
            } else null
        },
        loop = { hasCover -> loop(hasCover, foundSpotifys) },
        show = put,
    )
}

/** Sharp where it fills the screen, lighter on the cover (plan §11a R1); "lighter" is 480 everywhere. */
fun canvasHeight(settings: AppSettings): Int = when {
    settings.behavior.canvas.lighter -> 480
    settings.behavior.canvas.fillsBackground -> 1080
    else -> 720
}

/**
 * Whether a loop may be fetched now: on Wi-Fi always; on mobile data only with "Canvas from videos" in "Use mobile data
 * for" and Data saver off (Data saver, like the rest of Vinz's, only bites on mobile data).
 */
fun canvasNetworkAllowed(settings: AppSettings, metered: Boolean): Boolean =
    !metered || (settings.behavior.canvas.videosOnCellular && !settings.dataSaver)

/**
 * Whether a loop may be prepared ahead of time ([CanvasAhead]): only on an unmetered connection and never with Data
 * saver, whatever the mobile-data chip says (as today's next-song prefetch keeps to Wi-Fi).
 */
fun canvasMayPrepare(settings: AppSettings, metered: Boolean): Boolean = !metered && !settings.dataSaver
