package com.vinz.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.vinz.music.data.innertube.InnerTube
import com.vinz.music.data.model.Song
import com.vinz.music.data.model.hiRes

const val EXTRA_SONG = "vinz.song"
const val EXTRA_VIDEO = "vinz.video"

/**
 * vinz://audio/<id>[?video=<videoId>] – resolved to a real stream URL at load time.
 * Live streams are vinz://live/<id>, resolved to their HLS manifest, which carries the picture too: their video mode
 * keeps the same URI (so switching doesn't reload the stream) and only turns the video track on.
 */
fun Song.toMediaItem(videoId: String? = null): MediaItem {
    val uri = if (isLive) Uri.Builder().scheme("vinz").authority("live").appendPath(id).build()
    else Uri.Builder().scheme("vinz").authority("audio").appendPath(id).apply {
        if (videoId != null) appendQueryParameter("video", videoId)
        // The listener's own upload (#101): asked for as their account from the start.
        if (isUpload) appendQueryParameter("upload", "1")
    }.build()
    val extras = Bundle().apply {
        putString(EXTRA_SONG, InnerTube.json.encodeToString(Song.serializer(), this@toMediaItem))
        if (videoId != null) putString(EXTRA_VIDEO, videoId)
    }
    // #328: shown as the version picked to play in its place (the item itself stays the song, for history and likes).
    val shown = com.vinz.music.data.SongVersions.shownOf(this)
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setCustomCacheKey(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(shown.title)
                .setArtist(shown.artistText)
                .setAlbumTitle(shown.album?.title)
                // The cover picked for the song or its album (#305), through the artwork provider so a car can show it too.
                .setArtworkUri(com.vinz.music.data.PlaylistCovers.artOf(this@toMediaItem).let { art ->
                    if (art != null && art.startsWith("file:")) ArtworkProvider.contentUri(art) else hiRes(art, 544)?.let(Uri::parse)
                })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(if (isEpisode) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(extras)
                .build()
        )
        .build()
}

fun MediaItem.toSong(): Song? = mediaMetadata.extras?.getString(EXTRA_SONG)?.let {
    runCatching { InnerTube.json.decodeFromString(Song.serializer(), it) }.getOrNull()
}

val MediaItem.videoModeId: String? get() = mediaMetadata.extras?.getString(EXTRA_VIDEO)
