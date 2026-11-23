package com.vinz.music.data.spotify

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where "Your playlists" and "Liked Songs" read the listener's library from: Spotify's Web API with their own
 * developer app ([ApiLibrary]), or the sign-in on Spotify's page ([WebLibrary], 1.8.7.5), which can only read.
 * Everything after the reading (matching, the mirror, the likes' plan) is the same for both.
 */
interface SpotifyLibrary {
    /** False for the sign-in: nothing is ever written to Spotify with it. */
    val canWrite: Boolean

    /** True when the songs of a playlist the listener only follows can be read (the sign-in; a developer app gets 403). */
    val readsFollowed: Boolean

    /** True when a snapshot changes whenever the songs do (the Web API); false when it only counts them (the sign-in). */
    val snapshotsTell: Boolean

    /** Every playlist in the library (own, collaborative and followed), up to [maxPages] pages of 50. */
    suspend fun playlists(maxPages: Int): List<SpotifyPlaylist>

    /** One playlist's songs in order; [SpotifyError.Forbidden] when Spotify keeps them to itself. */
    suspend fun playlistItems(id: String, maxPages: Int): List<SpotifyTrack>

    /** What tells that a playlist changed, and its name now; null when it can't be asked. */
    suspend fun playlistSnapshot(id: String): Pair<String, String?>?

    suspend fun savedTracks(max: Int): List<SavedTrack>
}

class ApiLibrary(private val api: SpotifyApi) : SpotifyLibrary {
    override val canWrite = true
    override val readsFollowed = false
    override val snapshotsTell = true

    override suspend fun playlists(maxPages: Int): List<SpotifyPlaylist> {
        val all = ArrayList<SpotifyPlaylist>()
        var page = SpotifyParse.playlists(api.get("/me/playlists", mapOf("limit" to "50")))
        all += page.items
        var pages = 1
        while (pages < maxPages) { val next = page.next ?: break; page = SpotifyParse.playlists(api.next(next)); all += page.items; pages++ }
        return all
    }

    override suspend fun playlistItems(id: String, maxPages: Int): List<SpotifyTrack> {
        val out = ArrayList<SpotifyTrack>()
        var page = SpotifyParse.playlistItems(api.get("/playlists/$id/items", mapOf("limit" to "50", "additional_types" to "track", "fields" to SpotifyPlaylists.ITEM_FIELDS)))
        out += page.items.filterNotNull()
        var pages = 1
        while (pages < maxPages) { val next = page.next ?: break; page = SpotifyParse.playlistItems(api.next(next)); out += page.items.filterNotNull(); pages++ }
        return out
    }

    override suspend fun playlistSnapshot(id: String): Pair<String, String?>? =
        runCatching { SpotifyParse.playlistSnapshot(api.get("/playlists/$id", mapOf("fields" to "snapshot_id,name,description"))) }.getOrNull()

    override suspend fun savedTracks(max: Int): List<SavedTrack> {
        val out = ArrayList<SavedTrack>()
        var page = SpotifyParse.savedTracks(api.get("/me/tracks", mapOf("limit" to "50")))
        out += page.items
        while (out.size < max) { val next = page.next ?: break; page = SpotifyParse.savedTracks(api.next(next)); out += page.items }
        return out
    }
}

/**
 * The library through the sign-in ([SpotifyWeb]: reads only). The web player's answers name no snapshot, so a
 * playlist's snapshot is its number of songs ([snapshotOf]); [SpotifyPlaylists] compares the songs of the ones whose
 * count stayed ([snapshotsTell]).
 */
class WebLibrary(
    private val web: SpotifyWeb,
    /** The playlists that are mirrored (the listener's own, and followed ones they brought in): each gets a look. */
    private val mirrored: (SpotifyPlaylist) -> Boolean,
) : SpotifyLibrary {
    override val canWrite = false
    override val readsFollowed = true
    override val snapshotsTell = false

    /**
     * The library's list names no song count for a playlist (seen on the A16, 2026-11-15), so each of the listener's
     * mirrored playlists is asked for its count with one small read; the others are only named and keep 0.
     */
    override suspend fun playlists(maxPages: Int): List<SpotifyPlaylist> {
        val all = ArrayList<SpotifyPlaylist>()
        var offset = 0
        for (i in 0 until maxPages) {
            val page = SpotifyWebParse.playlists(web.read("libraryV3", libraryVars(PAGE, offset)))
            all += page.items
            offset += PAGE
            if (page.items.isEmpty() || offset >= page.total) break
        }
        // A list that moved between two pages can name a playlist twice: once is enough.
        return all.distinctBy { it.id }.map { p ->
            if (p.total > 0) p.copy(snapshot = snapshotOf(p.total))
            else if (mirrored(p)) {
                val total = SpotifyWebParse.playlistItems(web.read("fetchPlaylist", playlistVars(p.id, 1, 0))).total
                p.copy(total = total, snapshot = snapshotOf(total))
            } else p
        }
    }

    override suspend fun playlistItems(id: String, maxPages: Int): List<SpotifyTrack> {
        val out = ArrayList<SpotifyTrack>()
        var offset = 0
        repeat(maxPages) {
            val page = SpotifyWebParse.playlistItems(web.read("fetchPlaylist", playlistVars(id, PAGE, offset)))
            out += page.items.filterNotNull()
            offset += PAGE
            if (page.items.isEmpty() || offset >= page.total) return out
        }
        return out
    }

    override suspend fun playlistSnapshot(id: String): Pair<String, String?>? = runCatching {
        val data = web.read("fetchPlaylist", playlistVars(id, 1, 0))
        snapshotOf(SpotifyWebParse.playlistItems(data).total) to SpotifyWebParse.playlistName(data).first
    }.getOrElse { if (it is SpotifyError.RateLimited || it is SpotifyError.Revoked) throw it else null }

    override suspend fun savedTracks(max: Int): List<SavedTrack> {
        val out = ArrayList<SavedTrack>()
        var offset = 0
        while (out.size < max) {
            val page = SpotifyWebParse.liked(web.read("fetchLibraryTracks", pageVars(PAGE, offset)))
            out += page.items
            offset += PAGE
            if (page.items.isEmpty() || offset >= page.total) break
        }
        return out
    }

    companion object {
        const val PAGE = 50

        fun snapshotOf(total: Int) = "songs:$total"

        fun pageVars(limit: Int, offset: Int) = JsonObject(mapOf("offset" to JsonPrimitive(offset), "limit" to JsonPrimitive(limit)))

        fun libraryVars(limit: Int, offset: Int) = JsonObject(mapOf(
            "filters" to JsonArray(listOf(JsonPrimitive("Playlists"))),
            "order" to JsonNull,
            "textFilter" to JsonPrimitive(""),
            "features" to JsonArray(listOf("LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS").map { JsonPrimitive(it) }),
            "limit" to JsonPrimitive(limit), "offset" to JsonPrimitive(offset),
            // Playlists inside folders too, each as itself.
            "flatten" to JsonPrimitive(true),
            "expandedFolders" to JsonArray(emptyList()),
            "folderUri" to JsonNull,
            "includeFoldersWhenFlattening" to JsonPrimitive(false),
        ))

        fun playlistVars(id: String, limit: Int, offset: Int) = JsonObject(mapOf(
            "uri" to JsonPrimitive("spotify:playlist:$id"), "offset" to JsonPrimitive(offset), "limit" to JsonPrimitive(limit),
            "enableWatchFeedEntrypoint" to JsonPrimitive(true),
        ))
    }
}
