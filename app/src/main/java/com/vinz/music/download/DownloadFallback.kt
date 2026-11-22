package com.vinz.music.download

import com.vinz.music.data.model.Song
import com.vinz.music.data.stream.ForgetOnForbidden

/**
 * 1.8.7 (#408): when a song download that failed comes down as its music video's sound instead, by playback's own rule
 * for playing it so (PlaybackService: `wontPlayHere`, the same length within [SLACK_SEC]).
 */
internal object DownloadFallback {
    /** Playback's slack between a song's length and its video's (PlaybackService's SOUND_FROM_SLACK_SEC). */
    const val SLACK_SEC = 8

    /**
     * Whether [song]'s download, failed with [error], may come down as its music video's sound: a song (not a video, a
     * file on the phone, a live stream or an episode) YouTube refused (403) through the retries, or that no way gave a
     * link for, and not age-restricted, Premium-only or on a blocked network (the video won't help there). Not when it's
     * coming down that way [already].
     */
    fun mayTry(song: Song, error: Throwable, already: Boolean): Boolean =
        !already && !song.isVideo && !song.isLocal && !song.isLive && !song.isEpisode && !song.isRss &&
            com.vinz.music.playback.wontPlayHere(error, refused = ForgetOnForbidden.refusedCode(error) == 403)

    /** The video runs as long as the song (within [SLACK_SEC]), so it's the same recording. */
    fun sameLength(songSec: Long, videoSec: Long): Boolean =
        videoSec > 0 && (songSec <= 0 || kotlin.math.abs(videoSec - songSec) <= SLACK_SEC)
}
