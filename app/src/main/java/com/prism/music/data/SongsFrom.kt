package com.vinz.music.data

import com.vinz.music.data.innertube.YouTubeMusic
import com.vinz.music.data.model.CollectionPage
import com.vinz.music.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Library → Songs' "From" (1.8.3, G142, #382): liked songs, as always, and the songs of playlists the listener picks,
 * together and once each. Only a view: nothing is liked and nothing is written to the account.
 */
data class SongsFrom(val liked: Boolean = true, val playlists: List<String> = emptyList()) {
    /** Liked songs only: Songs as it was before 1.8.3. */
    val isDefault get() = liked && playlists.isEmpty()

    fun encode(): String = (if (liked) LIKED else "") + "|" + playlists.joinToString(",")

    companion object {
        private const val LIKED = "liked"
        const val KEY = "library:songs:from"

        fun decode(s: String?): SongsFrom {
            if (s.isNullOrBlank() || '|' !in s) return SongsFrom()
            val (a, b) = s.split('|', limit = 2)
            val lists = b.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            // Nothing chosen at all would be an empty tab with no way to tell why: liked songs then.
            return if (a != LIKED && lists.isEmpty()) SongsFrom() else SongsFrom(a == LIKED, lists)
        }

        /**
         * Liked songs first (newest first, as today), then each playlist's songs in the order they were picked and
         * in the playlist's own order; a song in several shows once, where it first comes.
         */
        fun merge(liked: List<Song>, lists: List<List<Song>>, from: SongsFrom): List<Song> {
            val seen = HashSet<String>()
            val out = ArrayList<Song>()
            if (from.liked) liked.forEach { if (seen.add(it.id)) out += it }
            lists.forEach { l -> l.forEach { if (seen.add(it.id)) out += it } }
            return out
        }
    }
}

/** A playlist's songs and whether they're all of them: a read that stopped at a page that didn't come is shown, not kept. */
data class PlaylistRead(val songs: List<Song>, val complete: Boolean)

/**
 * A playlist's songs, all of them (up to [MAX]), for views that put several playlists together. Kept for the session:
 * [refresh] forgets one when it's opened again or edited.
 */
class PlaylistSongs(private val load: suspend (id: String) -> PlaylistRead) {
    private val kept = ConcurrentHashMap<String, List<Song>>()

    /**
     * Only a whole read is kept (#473): one that stopped at a page that didn't come shows what it has and is read again
     * next time; a cancelled one (the screen was left) keeps nothing.
     */
    suspend fun songs(id: String): List<Song>? = kept[id] ?: try {
        load(id).let { read -> if (read.complete) kept[id] = read.songs; read.songs }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    /** Whether [id]'s songs are kept whole (a read that stopped short isn't). */
    fun isComplete(id: String): Boolean = kept.containsKey(id)

    fun refresh(id: String) { kept.remove(id) }

    companion object {
        /** The most songs read of one playlist (as a queue takes). */
        const val MAX = 2000
    }
}

/**
 * A whole playlist for Library → Songs' "From" (#473, based on PR #474 by Irobrine): [first] and then each page after
 * it, up to [PlaylistSongs.MAX] songs. A page that doesn't come is asked again ([PAGE_TRIES] times, [retryMs] more apart
 * each time); if it still doesn't, the read is returned short and not [PlaylistRead.complete]. Cancelling the read (the
 * screen was left) is passed on, never turned into a short list.
 */
suspend fun readPlaylist(
    first: CollectionPage,
    page: suspend (String) -> YouTubeMusic.PlaylistChunk,
    retryMs: Long = PAGE_RETRY_MS,
): PlaylistRead {
    val songs = first.songs.toMutableList()
    var token = first.continuation
    var empty = 0
    while (token != null && songs.size < PlaylistSongs.MAX) {
        val chunk = pageWithRetries(page, token, retryMs)
            ?: return PlaylistRead(songs.distinctBy { it.id }, complete = false)
        // A page none of whose rows could be read may still point to more: read on. Page after page of nothing is
        // shown as far as it got and read again next time, not kept as the whole playlist.
        if (chunk.songs.isEmpty()) {
            if (chunk.next != null && ++empty >= EMPTY_PAGES) return PlaylistRead(songs.distinctBy { it.id }, complete = false)
        } else empty = 0
        songs += chunk.songs
        token = chunk.next
    }
    return PlaylistRead(songs.distinctBy { it.id }, complete = true)
}

private suspend fun pageWithRetries(
    page: suspend (String) -> YouTubeMusic.PlaylistChunk,
    token: String,
    retryMs: Long,
): YouTubeMusic.PlaylistChunk? {
    for (attempt in 0 until PAGE_TRIES) {
        try {
            return page(token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (attempt < PAGE_TRIES - 1) delay(retryMs * (attempt + 1))
        }
    }
    return null
}

private const val PAGE_TRIES = 3
/** Pages in a row with no song read (but more to come) before the read stops short. */
private const val EMPTY_PAGES = 3
private const val PAGE_RETRY_MS = 500L
