package com.vinz.music.data.spotify

import com.prism.music.data.db.PrismDatabase
import com.vinz.music.data.imports.HistoryImporter
import com.vinz.music.data.imports.MatchResult
import com.vinz.music.data.imports.TrackToMatch
import com.vinz.music.data.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One of the listener's Spotify playlists as last read, and the Vinz playlist that shows it. */
@Serializable
data class MirroredPlaylist(
    val playlist: SpotifyPlaylist,
    val tracks: List<SpotifyTrack> = emptyList(),
    val vinzId: String? = null,
    /** The listener deleted its Vinz playlist: it isn't made again. */
    val removed: Boolean = false,
    val readAt: Long = 0,
)

@Serializable
data class PlaylistMirror(
    val lists: List<MirroredPlaylist> = emptyList(),
    /** Playlists the listener follows but doesn't own: Development Mode apps can't read their songs (February 2026). */
    val notYours: Int = 0,
    val listedAt: Long = 0,
    /** Followed playlists the listener brought in (1.8.7.5, the sign-in only): mirrored like their own from then on. */
    val brought: List<String> = emptyList(),
)

/**
 * "Your Spotify playlists" (1.8.1): the playlists the listener owns or collaborates on, each shown in Library as a
 * Vinz playlist with a Spotify mark, whose songs are their YouTube Music matches in Spotify's order. "Keep in step"
 * reads a playlist again when it's opened (only when Spotify says it changed: its snapshot) and all of them daily on
 * Wi-Fi. Matches are remembered with every other import's, so a song is found once; opening a playlist matches its
 * songs at once, a few at a time, and the background matching does the rest.
 *
 * Followed playlists aren't mirrored: since February 2026 a Development Mode app gets only their names, not their songs.
 * Spotify's own mixes (Discover Weekly, Daily Mix…) are Spotify's playlists, so they can't be read either. They're
 * listed in Library all the same ([followed]), with how to bring one over as a copy of the listener's own.
 */
class SpotifyPlaylists(
    private val api: SpotifyApi,
    private val db: PrismDatabase,
    private val importer: HistoryImporter,
    private val dir: File,
    private val hands: Hands,
    /** Finds one song on YouTube Music (the import matcher, or a test's). */
    private val match: suspend (TrackToMatch) -> MatchResult,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** Where the library is read from now: the Web API, or the sign-in (1.8.7.5). */
    private val library: () -> SpotifyLibrary = ApiLibrary(api).let { own -> { own } },
) {
    /** Where the Vinz playlists live (Vinz's library; a test's own). */
    interface Hands {
        fun create(name: String, songs: List<Song>, description: String): String
        fun replace(id: String, songs: List<Song>)
        fun rename(id: String, name: String, description: String)
        fun exists(id: String): Boolean
        fun songIds(id: String): List<String>
        fun delete(id: String)
    }

    private val file = File(dir, "playlists.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    @Volatile private var cached: PlaylistMirror? = null

    fun mirror(): PlaylistMirror = cached ?: (runCatching { json.decodeFromString(PlaylistMirror.serializer(), file.readText()) }.getOrNull() ?: PlaylistMirror()).also { cached = it }

    /** The Vinz playlists that show Spotify ones (for Library's mark). */
    fun vinzIds(): Set<String> = mirror().lists.mapNotNull { it.vinzId?.takeIf { _ -> !it.removed } }.toSet()

    fun isMirror(vinzId: String) = vinzId in vinzIds()

    /** The mirrored playlists' songs: their matches stay when an import is removed ([com.vinz.music.data.imports.HistoryImporter.remove]). */
    fun matchKeys(): Set<String> = mirror().lists.flatMap { l -> l.tracks.map(SpotifyQueue::key) }.toSet()

    private fun save(m: PlaylistMirror) {
        dir.mkdirs()
        val tmp = File(dir, "playlists.json.tmp")
        tmp.writeText(json.encodeToString(PlaylistMirror.serializer(), m))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        cached = m
        _mirrored.value = vinzIds()
    }

    private val _mirrored by lazy { MutableStateFlow(vinzIds()) }

    /** [vinzIds], as they come and go. */
    val mirrored: StateFlow<Set<String>> get() = _mirrored

    /** [done] of a playlist's [total] songs have been looked up on YouTube Music. */
    data class Fill(val done: Int, val total: Int)

    private val _filling = MutableStateFlow<Map<String, Fill>>(emptyMap())

    /** The playlists (by Vinz id) with songs still waiting to be looked up; one all looked up isn't in it. */
    val filling: StateFlow<Map<String, Fill>> get() = _filling

    /**
     * Reads the list of playlists and, of each that changed, its songs; lines the songs up for matching and brings the
     * Vinz playlists up to date. [userId]: the connected account (its own playlists are the readable ones).
     */
    suspend fun refreshAll(userId: String, now: Long = System.currentTimeMillis(), bringAll: Boolean = false, except: Set<String> = emptySet()) = lock.withLock {
        val all = library().playlists(MAX_LIST_PAGES)

        // With the sign-in, a followed playlist the listener brought in is read like one of their own.
        val kept = mirror().brought.filter { id -> all.any { it.id == id } }
        val brought = when {
            !library().readsFollowed -> emptyList()
            // "Followed playlists too": the first [AUTO_FOLLOWED] in Spotify's order come in by themselves, less the
            // ones hidden from Library; any brought in by hand before stay.
            bringAll -> (kept + all.filter { !isYours(it, userId) && it.id !in except }.map { it.id }).distinct().take(maxOf(kept.size, AUTO_FOLLOWED))
            else -> kept
        }
        val (yours, others) = all.partition { isYours(it, userId) || it.id in brought }
        val old = mirror().lists.associateBy { it.playlist.id }
        val refused = ArrayList<SpotifyPlaylist>()
        // The sign-in names no snapshot, only a song count: a playlist that swaps songs and keeps its length (Lofi
        // Girl's, most editorial ones) looks unchanged. So the ones that look unchanged get their songs read and
        // compared, the longest unchecked first, [LOOKS_PER_RUN] a run.
        val look = if (library().snapshotsTell) emptySet() else yours
            .filter { p -> old[p.id]?.let { it.playlist.snapshot == p.snapshot && it.readAt > 0 && !it.removed } == true }
            .sortedBy { old.getValue(it.id).readAt }.take(LOOKS_PER_RUN).map { it.id }.toSet()
        val lists = yours.mapNotNull { p ->
            val before = old[p.id]
            if (before != null && before.playlist.snapshot == p.snapshot && before.readAt > 0) {
                if (p.id !in look) return@mapNotNull before.copy(playlist = p)
                val items = try { readItems(p.id) } catch (e: SpotifyError.Forbidden) { return@mapNotNull before.copy(playlist = p) }
                return@mapNotNull if (sameSongs(items, before.tracks)) before.copy(playlist = p, readAt = now)
                else MirroredPlaylist(p, items, before.vinzId, before.removed, now)
            }
            // One Spotify won't let this app read (a collaborative one it treats as another's): left out, the rest go on.
            val items = try { readItems(p.id) } catch (e: SpotifyError.Forbidden) { refused += p; return@mapNotNull before }
            MirroredPlaylist(p, items, before?.vinzId, before?.removed ?: false, now)
        }
        // A playlist gone from Spotify (deleted there, or no longer shared with the listener) goes from Library too.
        old.values.filter { o -> lists.none { it.playlist.id == o.playlist.id } }.forEach { gone -> gone.vinzId?.let { if (hands.exists(it)) hands.delete(it) } }
        SpotifyQueue.remember(db, lists.flatMap { it.tracks })
        save(PlaylistMirror(apply(lists), others.size + refused.size, now, brought))
        // The ones whose songs Spotify keeps to itself: only named in Library (never Vinz playlists).
        saveFollowed(others + refused.filter { r -> lists.none { it.playlist.id == r.id } })
    }

    /**
     * "Bring it in" (1.8.7.5, the sign-in only): a playlist the listener follows is read and becomes a Vinz playlist
     * that stays in step like their own. One at a time, when asked: some people follow hundreds. Returns its Vinz
     * playlist, or null when this connection can't read followed playlists or it isn't one of them.
     */
    suspend fun bringIn(id: String, now: Long = System.currentTimeMillis()): String? = lock.withLock {
        if (!library().readsFollowed) return@withLock null
        val m = mirror()
        m.lists.firstOrNull { it.playlist.id == id && !it.removed }?.vinzId?.let { return@withLock it }
        val p = _followed.value.firstOrNull { it.id == id } ?: return@withLock null
        val items = readItems(id)
        val snapshot = library().playlistSnapshot(id)?.first ?: WebLibrary.snapshotOf(items.size)
        SpotifyQueue.remember(db, items)
        matchNow(items)
        val entry = MirroredPlaylist(p.copy(total = items.size, snapshot = snapshot), items, null, false, now)
        val lists = apply(m.lists.filter { it.playlist.id != id } + entry)
        save(m.copy(lists = lists, notYours = (m.notYours - 1).coerceAtLeast(0), brought = (m.brought + id).distinct()))
        saveFollowed(_followed.value.filter { it.id != id })
        lists.firstOrNull { it.playlist.id == id }?.vinzId
    }

    // ---- Followed and saved playlists (1.8.1): listed, not mirrored

    private val followedFile = File(dir, "followed.json")
    private val _followed by lazy { MutableStateFlow(readFollowed()) }

    /**
     * The playlists the listener follows or saved (Lofi Girl's, Spotify's own "Made for you"…) as /me/playlists last
     * named them: since February 2026 Spotify answers 403 for their songs, so Library shows them with a note and the way
     * to bring one (a copy of your own), nothing more. Kept in files/spotify/followed.json (a small file, read once);
     * gone with [forget].
     */
    val followed: StateFlow<List<SpotifyPlaylist>> get() = _followed

    private fun readFollowed(): List<SpotifyPlaylist> =
        runCatching { json.decodeFromString(FOLLOWED, followedFile.readText()) }.getOrNull().orEmpty()

    private fun saveFollowed(list: List<SpotifyPlaylist>) {
        dir.mkdirs()
        val tmp = File(dir, "followed.json.tmp")
        tmp.writeText(json.encodeToString(FOLLOWED, list))
        if (!tmp.renameTo(followedFile)) { followedFile.delete(); tmp.renameTo(followedFile) }
        _followed.value = list
    }

    /**
     * "Keep in step" for one playlist being opened ([vinzId], its Vinz playlist): read again if Spotify says it
     * changed, a few of its songs matched now, the Vinz playlist brought up to date.
     */
    suspend fun refreshOne(vinzId: String, readAgain: Boolean, now: Long = System.currentTimeMillis()) = lock.withLock {
        val m = mirror()
        val at = m.lists.indexOfFirst { it.vinzId == vinzId }.takeIf { it >= 0 } ?: return@withLock
        var entry = m.lists[at]
        if (readAgain) {
            val snapshot = library().playlistSnapshot(entry.playlist.id)

            if (snapshot != null && snapshot.first != entry.playlist.snapshot) {
                val p = entry.playlist.copy(snapshot = snapshot.first, name = snapshot.second ?: entry.playlist.name)
                entry = entry.copy(playlist = p, tracks = readItems(p.id), readAt = now)
                SpotifyQueue.remember(db, entry.tracks)
            } else if (snapshot != null && !library().snapshotsTell && now - entry.readAt >= LOOK_AGAIN_MS) {
                // Same length: the songs themselves are compared (not on every open: a long playlist is many reads).
                val items = readItems(entry.playlist.id)
                entry = if (sameSongs(items, entry.tracks)) entry.copy(readAt = now) else entry.copy(tracks = items, readAt = now)
                SpotifyQueue.remember(db, entry.tracks)
            }
        }
        matchNow(entry.tracks)
        val lists = m.lists.toMutableList().also { it[at] = entry }
        save(m.copy(lists = apply(lists)))
    }

    /** Songs matched since are put in their playlists (no request to Spotify). */
    suspend fun reapply() = lock.withLock {
        val m = mirror()
        if (m.lists.isNotEmpty()) save(m.copy(lists = apply(m.lists)))
    }

    /** The songs of one playlist, page by page (owned or collaborative ones only answer). */
    private suspend fun readItems(id: String): List<SpotifyTrack> = library().playlistItems(id, MAX_ITEM_PAGES)


    /** Matches up to [AT_ONCE_ON_OPEN] of [tracks] not looked up yet, now. */
    private suspend fun matchNow(tracks: List<SpotifyTrack>) {
        val pending = SpotifyQueue.pending(db, tracks.map(SpotifyQueue::key))
        val todo = tracks.filter { SpotifyQueue.key(it) in pending }.distinctBy(SpotifyQueue::key).take(AT_ONCE_ON_OPEN)
        for (t in todo) {
            val row = db.imports().match(SpotifyQueue.key(t)) ?: continue
            val r = runCatching { match(TrackToMatch(t.title, t.artist, t.album, t.durationMs, 0, t.isrc)) }.getOrNull() ?: continue
            importer.applyMatch(row, r)
            pause(MATCH_GAP_MS)
        }
    }

    /** The Vinz playlists made or brought up to date from [lists]; returns them with their Vinz ids. */
    private suspend fun apply(lists: List<MirroredPlaylist>): List<MirroredPlaylist> {
        val keys = lists.flatMap { l -> l.tracks.map(SpotifyQueue::key) }
        val matched = SpotifyQueue.matched(db, keys)
        val waiting = SpotifyQueue.pending(db, keys)
        return lists.map { l ->
            if (l.removed) return@map l
            val songs = songsOf(l.tracks, matched)
            val id = l.vinzId
            when {
                id == null -> l.copy(vinzId = hands.create(l.playlist.name, songs, l.playlist.description))
                // The listener deleted it from Library: left deleted.
                !hands.exists(id) -> l.copy(removed = true)
                else -> {
                    if (hands.songIds(id) != songs.map { it.id }) hands.replace(id, songs)
                    hands.rename(id, l.playlist.name, l.playlist.description)
                    l
                }
            }
        }.also { out ->
            _filling.value = out.filter { !it.removed && it.vinzId != null }.mapNotNull { l ->
                val all = l.tracks.map(SpotifyQueue::key).distinct()
                val left = all.count { it in waiting }
                if (left > 0) l.vinzId!! to Fill(all.size - left, all.size) else null
            }.toMap()
        }
    }

    /** Takes every Spotify playlist out of Library ([keep]: they stay as plain Vinz playlists), and forgets them. */
    suspend fun forget(keep: Boolean) = lock.withLock {
        if (!keep) mirror().lists.forEach { l -> l.vinzId?.let { if (hands.exists(it)) hands.delete(it) } }
        file.delete()
        cached = null
        _mirrored.value = emptySet()
        _filling.value = emptyMap()
        followedFile.delete()
        _followed.value = emptyList()
    }

    companion object {
        private const val MAX_LIST_PAGES = 10
        private const val MAX_ITEM_PAGES = 40
        const val AT_ONCE_ON_OPEN = 25
        /** "Followed playlists too" brings in this many at most by itself; the rest stay a tap away. */
        const val AUTO_FOLLOWED = 50
        const val MATCH_GAP_MS = 400L
        /** With the sign-in: this many same-length playlists have their songs compared in one run (every 3 hours). */
        const val LOOKS_PER_RUN = 25
        /** With the sign-in: an opened playlist's songs are compared again at most this often. */
        const val LOOK_AGAIN_MS = 15 * 60_000L

        /** The same songs in the same order. */
        fun sameSongs(a: List<SpotifyTrack>, b: List<SpotifyTrack>) = a.map(SpotifyQueue::key) == b.map(SpotifyQueue::key)
        /** Only what a song needs, from each item (both the new "item" and the old "track"). */
        private const val TRACK_FIELDS = "type,uri,name,duration_ms,explicit,is_local,external_ids(isrc),artists(name),album(name,images)"
        const val ITEM_FIELDS = "next,total,items(is_local,item($TRACK_FIELDS),track($TRACK_FIELDS))"
        private val FOLLOWED = kotlinx.serialization.builtins.ListSerializer(SpotifyPlaylist.serializer())

        /**
         * /me/playlists split into the ones Spotify lets this app read (the listener's own and collaborative ones) and
         * the rest (followed or saved: their songs answer 403), each in Spotify's order.
         */
        fun split(all: List<SpotifyPlaylist>, userId: String): Pair<List<SpotifyPlaylist>, List<SpotifyPlaylist>> =
            all.partition { isYours(it, userId) }

        fun isYours(p: SpotifyPlaylist, userId: String) = p.ownerId == userId || p.collaborative

        /** Where Spotify's app opens a playlist (it catches the link; a browser shows the web player otherwise). */
        fun openLink(id: String) = "https://open.spotify.com/playlist/$id"

        /** [tracks]' matched songs in their order, each once. */
        fun songsOf(tracks: List<SpotifyTrack>, matched: Map<String, Song>): List<Song> =
            tracks.mapNotNull { matched[SpotifyQueue.key(it)] }.distinctBy { it.id }
    }
}
