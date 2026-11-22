package com.vinz.music.data.spotify

import com.vinz.music.data.imports.MatchStatus
import com.vinz.music.data.imports.TrackMatcher
import com.vinz.music.data.imports.TrackToMatch
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * "Spotify's canvas first" (1.8.7.6, plan-spotify-canvas.md): a song's canvas asked of Spotify, with the web-player
 * session's own reads ([SpotifyWeb]: `searchDesktop`, then `canvas`). Read only, Spotify's own addresses only, no client
 * token. This file has no Android in it: what to ask, and what an answer means. Remembering answers and when to ask at
 * all is [com.vinz.music.data.canvas.SpotifyCanvasSource].
 */

/** What Spotify says about one song's canvas. */
sealed class SpotifyCanvasAnswer {
    /** A looping video on Spotify's own file host. */
    data class Loop(val url: String) : SpotifyCanvasAnswer()
    /** The song was found surely, and Spotify has no video for it (none, a still picture, or a link off its hosts). */
    object None : SpotifyCanvasAnswer()
    /** The search found nothing Vinz's matcher is sure is this song: no canvas is taken from it. */
    object NoSureMatch : SpotifyCanvasAnswer()
}

/**
 * One song → its Spotify track (only a match [TrackMatcher.judge] calls sure) → that track's canvas. [read] is
 * [SpotifyWeb.read]; its errors (refused, 429, no network, signed out) go to the caller as they are.
 */
class SpotifyCanvas(private val read: suspend (op: String, variables: JsonObject) -> JsonObject) {
    suspend fun find(song: Song): SpotifyCanvasAnswer {
        val artist = song.artists.joinToString(", ") { it.name }.ifBlank { song.primaryArtist }
        val hits = SpotifyCanvasParse.hits(read(SEARCH, SpotifyCanvasParse.searchVars("${song.title} ${song.primaryArtist}")))
        val pick = SpotifyCanvasParse.sure(TrackToMatch(song.title, artist, durationMs = song.durationSec * 1000L), hits)
            ?: return SpotifyCanvasAnswer.NoSureMatch
        val c = SpotifyCanvasParse.canvas(read(CANVAS, SpotifyCanvasParse.canvasVars(pick.uri)))
        return if (c != null) SpotifyCanvasAnswer.Loop(c) else SpotifyCanvasAnswer.None
    }

    companion object {
        const val SEARCH = "searchDesktop"
        const val CANVAS = "canvas"
    }
}

object SpotifyCanvasParse {
    /** A track in a search answer. The URI stays in memory; nothing about it is written anywhere. */
    class Hit(val uri: String, val title: String, val artists: List<String>, val durationMs: Long)

    /** `searchDesktop`'s variables as the trial sent them (the web player's own, read 2026-11-16). */
    fun searchVars(term: String, limit: Int = 10): JsonObject = JsonObject(mapOf(
        "searchTerm" to JsonPrimitive(term), "offset" to JsonPrimitive(0), "limit" to JsonPrimitive(limit),
        "numberOfTopResults" to JsonPrimitive(5), "includeAudiobooks" to JsonPrimitive(false),
        "includeArtistHasConcertsField" to JsonPrimitive(false), "includePreReleases" to JsonPrimitive(false),
        "includeLocalConcertsField" to JsonPrimitive(false), "includeAuthors" to JsonPrimitive(false),
    ))

    fun canvasVars(trackUri: String): JsonObject = JsonObject(mapOf("trackUri" to JsonPrimitive(trackUri)))

    private fun JsonElement?.o(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement?.s(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The tracks of a search answer's `data`, best first: `searchV2.tracksV2.items[].item.data`. */
    fun hits(data: JsonObject): List<Hit> {
        val items = data.o("searchV2").o("tracksV2")?.get("items") as? JsonArray ?: return emptyList()
        return items.mapNotNull { (it as? JsonObject).o("item").o("data")?.let(::hit) }.distinctBy { it.uri }
    }

    private fun hit(o: JsonObject): Hit? {
        val uri = o.s("uri")?.takeIf { it.startsWith("spotify:track:") } ?: return null
        val name = o.s("name") ?: return null
        val artists = ((o["artists"] as? JsonObject)?.get("items") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject).o("profile").s("name") }
        val ms = ((o.o("duration") ?: o.o("trackDuration"))?.get("totalMilliseconds") as? JsonPrimitive)?.longOrNull ?: 0L
        return Hit(uri, name, artists, ms)
    }

    /** The hit Vinz's matcher is sure is [want] (the import direction's rules: title, artist, length), else null. */
    fun sure(want: TrackToMatch, hits: List<Hit>): Hit? {
        if (hits.isEmpty()) return null
        val songs = hits.map { Song(it.uri, it.title, it.artists.map { a -> ArtistRef(a) }, durationSec = (it.durationMs / 1000).toInt()) }
        val r = TrackMatcher.judge(want, songs)
        if (r.status != MatchStatus.SURE) return null
        return r.song?.let { s -> hits.firstOrNull { it.uri == s.id } }
    }

    /**
     * The loop's address in a `canvas` answer's `data` (`trackUnion.canvas` {url, type}), or null: only a video (plan D4:
     * a still picture or anything else is none) on Spotify's own file hosts over https ([isSpotifyFile]).
     */
    fun canvas(data: JsonObject): String? {
        val c = data.o("trackUnion").o("canvas") ?: return null
        val type = c.s("type").orEmpty()
        if (!type.startsWith("VIDEO")) return null
        return c.s("url")?.takeIf(::isSpotifyFile)
    }

    /** https, on a host ending `.scdn.co` or `.spotifycdn.com` (the trial's files were all on `canvaz.scdn.co`). */
    fun isSpotifyFile(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        if (!u.isHttps || u.username.isNotEmpty() || u.password.isNotEmpty() || u.port != 443) return false
        val h = u.host.lowercase()
        return h.endsWith(".scdn.co") || h.endsWith(".spotifycdn.com")
    }
}
