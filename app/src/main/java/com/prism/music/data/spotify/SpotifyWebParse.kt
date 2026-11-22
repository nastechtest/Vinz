package com.vinz.music.data.spotify

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reads the web player's own answers ([SpotifyWeb.read]'s "data") into the same things [SpotifyParse] makes from the
 * Web API, so everything after reading (matching, mirroring, likes) is shared. Shapes as Meld (GPL-3.0) reads them and
 * as the A16 trial saw them on 2026-11-15 (field names only). These answers carry no ISRC: matching goes by title,
 * artist and length. Episodes, local files and anything without a track address are left out.
 */
object SpotifyWebParse {
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.o(k: String) = this[k]?.takeIf { it !is JsonNull } as? JsonObject
    private fun JsonObject.arr(k: String) = (this[k]?.takeIf { it !is JsonNull } as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

    fun profile(data: JsonObject): SpotifyProfile {
        val p = data.o("me")?.o("profile") ?: throw IllegalArgumentException("no profile")
        val id = p.str("uri")?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("no user id")
        return SpotifyProfile(id, p.str("name")?.takeIf { it.isNotBlank() } ?: id)
    }

    /** The smallest picture that is at least 300 wide, else the biggest there is. */
    private fun image(sources: List<JsonObject>): String? {
        val sized = sources.filter { it.str("url") != null }
        return (sized.filter { (it.int("width") ?: 0) >= 300 }.minByOrNull { it.int("width") ?: 0 } ?: sized.maxByOrNull { it.int("width") ?: 0 })?.str("url")
    }

    /**
     * A track's "data" ([uri] from its wrapper when the data has none); null when it isn't a playable music track.
     * Liked songs give the length as `duration`, playlists as `trackDuration`.
     */
    fun track(data: JsonObject?, uri: String? = null): SpotifyTrack? {
        data ?: return null
        val address = (data.str("uri") ?: uri)?.takeIf { it.startsWith("spotify:track:") } ?: return null
        val title = data.str("name")?.takeIf { it.isNotBlank() } ?: return null
        val artists = data.o("artists")?.arr("items").orEmpty().mapNotNull { it.o("profile")?.str("name") }.filter { it.isNotBlank() }
        val album = data.o("albumOfTrack")
        val rating = data.o("contentRating")?.str("label")
        return SpotifyTrack(
            uri = address, title = title, artists = artists, album = album?.str("name"),
            durationMs = (data.o("duration") ?: data.o("trackDuration"))?.long("totalMilliseconds") ?: 0,
            isrc = null,
            explicit = rating == "EXPLICIT",
            image = album?.o("coverArt")?.arr("sources")?.let(::image),
        )
    }

    /** `fetchLibraryTracks`: a page of liked songs and how many there are in all. */
    fun liked(data: JsonObject): SpotifyPage<SavedTrack> {
        val t = data.o("me")?.o("library")?.o("tracks") ?: throw IllegalArgumentException("no liked songs")
        val list = t.arr("items").mapNotNull { item ->
            val wrap = item.o("track") ?: return@mapNotNull null
            val track = track(wrap.o("data"), wrap.str("_uri") ?: wrap.str("uri")) ?: return@mapNotNull null
            val at = item.o("addedAt")?.str("isoString")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
            SavedTrack(track, at)
        }
        return SpotifyPage(list, null, t.int("totalCount") ?: list.size)
    }

    /**
     * `libraryV3` filtered to playlists: the listener's own and the ones they follow. "Liked Songs" (a pseudo
     * playlist) and folders are left out. There is no snapshot id here: [SpotifyPlaylist.snapshot] stays empty.
     */
    fun playlists(data: JsonObject): SpotifyPage<SpotifyPlaylist> {
        val lib = data.o("me")?.o("libraryV3") ?: throw IllegalArgumentException("no library")
        val list = lib.arr("items").mapNotNull { item ->
            val wrap = item.o("item") ?: return@mapNotNull null
            val d = wrap.o("data") ?: return@mapNotNull null
            val id = (wrap.str("_uri") ?: d.str("uri"))?.takeIf { it.startsWith("spotify:playlist:") }?.substringAfterLast(':') ?: return@mapNotNull null
            val owner = d.o("ownerV2")?.o("data")
            val ownerId = owner?.str("uri")?.substringAfterLast(':') ?: owner?.str("username").orEmpty()
            SpotifyPlaylist(
                id = id, name = d.str("name").orEmpty().ifBlank { id }, ownerId = ownerId,
                ownerName = owner?.str("name")?.takeIf { it.isNotBlank() } ?: ownerId,
                collaborative = (d["collaborative"] as? JsonPrimitive)?.booleanOrNull ?: false,
                image = d.o("images")?.arr("items")?.firstOrNull()?.arr("sources")?.let(::image),
                total = d.int("count") ?: 0,
                description = d.str("description").orEmpty(),
            )
        }
        return SpotifyPage(list, null, lib.int("totalCount") ?: list.size)
    }

    /** `fetchPlaylist`: a page of one playlist's songs (null where an item isn't a track, so positions still count). */
    fun playlistItems(data: JsonObject): SpotifyPage<SpotifyTrack?> {
        val c = data.o("playlistV2")?.o("content") ?: throw IllegalArgumentException("no playlist")
        val list = c.arr("items").map { item ->
            val v2 = item.o("itemV2")
            if (v2?.str("__typename") == "TrackResponseWrapper" || v2?.str("__typename") == null) track(v2?.o("data"), v2?.str("_uri")) else null
        }
        return SpotifyPage(list, null, c.int("totalCount") ?: list.size)
    }

    /** `fetchPlaylist`'s name and description, for a mirrored playlist that was renamed. */
    fun playlistName(data: JsonObject): Pair<String?, String?> = data.o("playlistV2").let { it?.str("name") to it?.str("description") }
}
