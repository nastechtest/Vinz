package com.vinz.music.data.meta

import com.vinz.music.data.SongVersions
import com.vinz.music.data.innertube.InnerTube
import com.vinz.music.data.model.Song
import com.vinz.music.playback.VideoCover
import com.vinz.music.playback.writeAtomically
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a row's pairing came from, strongest first ([PlaysAsRules.replaces]). A row is a music video in a list; its
 * pairing is the song it plays as (with "Songs, not videos"), or, for [COVER], only the song whose cover it shows.
 */
enum class PairSource(val strength: Int) {
    /** The listener picked a version ("Play a different version", [SongVersions]): it always wins, kept with its picks. */
    LISTENER_PICK(100),
    /** The row was downloaded as its song (#186, #315): what the phone holds is what plays. Kept with the downloads. */
    DOWNLOAD(80),
    /** An album page's own playlist, track by track ([com.vinz.music.data.innertube.YouTubeMusic.pairAlbumTracks]). */
    ALBUM_PAGE(60),
    /** YouTube's own pairing in a playlist's queue (#383, the playlist asked once as it starts). */
    PLAYLIST(60),
    /** YouTube's own pairing in a radio or mix's queue ([com.vinz.music.data.model.NextResult.songPairs]). */
    RADIO(60),
    /**
     * The song found for a video as it was tapped, came up next in the queue or was downloaded
     * ([com.vinz.music.data.innertube.YouTubeMusic.songForVideo]: YouTube's pairing for it, else a search by title).
     */
    VIDEO_MATCH(40),
    /** A song of the video's title at any length, found for its cover and album only: the video plays as itself. */
    COVER(10),
    /** A song YouTube won't play here, played as its music video's sound (#93): for this run only ([PlaysAs.blocked]). */
    BLOCKED_VIDEO(0);

    /** Learned from YouTube or a match, and kept in [PlaysAs]'s own file. */
    val learned: Boolean get() = this == ALBUM_PAGE || this == PLAYLIST || this == RADIO || this == VIDEO_MATCH || this == COVER
}

/**
 * One row's pairing: [row] (a music video's id) plays as [sound] (its song's id), shown as [song]; for [PairSource.COVER]
 * [sound] is the row's own and [song] only lends its cover. The row is the pairing's music video.
 */
@Serializable
data class PlaysAsEntry(
    val row: String,
    val sound: String,
    /** The song it's paired with (title, artists, album, length, cover); null where only its id is known. */
    val song: Song? = null,
    val source: PairSource,
    /** When it was learned (ms since 1970). Among equally strong sources the newer one stands. */
    val at: Long = 0,
    /** For [PairSource.VIDEO_MATCH] and [PairSource.COVER]: the matcher that found it ([PlaysAs.MATCHER]). */
    val v: Int = 0,
) {
    /** The row plays as another recording, not as itself. */
    val swaps: Boolean get() = sound != row && source != PairSource.COVER
}

/**
 * What plays for a row, as every reader takes it. [queued] is the song that goes into the queue in its place (or the row
 * itself): the play history, likes and the lyrics lookup name it. [sound] is the recording whose bytes play (a picked
 * version's, its song's own). [video] is the row's music video. [shown] is how it shows (title, artists, album, cover).
 * [source] is what decided it; null when nothing is known and the row plays as itself.
 */
data class PlaysAsAnswer(val queued: Song, val sound: String, val video: String?, val shown: Song, val source: PairSource?)

/** The one rule of what plays for a row (1.8.7 §3.2). Pure, so every case is a unit test. */
object PlaysAsRules {
    /**
     * Whether [new] takes [old]'s place for a row: a stronger source always, an equally strong one when it's as new or
     * newer (YouTube re-pairs now and then), a weaker one never. The listener's pick isn't stored here: [answer] puts it
     * first whatever is stored.
     */
    fun replaces(old: PlaysAsEntry?, new: PlaysAsEntry): Boolean =
        old == null || new.source.strength > old.source.strength || (new.source.strength == old.source.strength && new.at >= old.at)

    /** The one of [a] and [b] that stands ([replaces]). */
    fun stronger(a: PlaysAsEntry?, b: PlaysAsEntry?): PlaysAsEntry? = when {
        a == null -> b
        b == null -> a
        replaces(a, b) -> b
        else -> a
    }

    /** A row that may play as another recording: a music video from YouTube (not live, an episode, an upload or a file). */
    fun canSwap(row: Song): Boolean = row.isVideo && !row.isLive && !row.isEpisode && !row.isUpload && !row.isLocal

    /**
     * What plays for [row]. First the listener's pick for it ([pickOf]): the row stays itself and plays the pick, shown as
     * it. Then its pairing ([entry]): with [swap] (the reader swaps videos for their songs, as "Songs, not videos" does)
     * the song goes in its place, playing the song's own pick if it has one; without, the row plays as itself and shows
     * its song's cover and album ([VideoCover]). Otherwise the row as it is.
     */
    fun answer(row: Song, pickOf: (String) -> SongVersions.Pick?, entry: PlaysAsEntry?, swap: Boolean = true): PlaysAsAnswer {
        val video = row.id.takeIf { row.isVideo }
        pickOf(row.id)?.takeIf { it.id != row.id }?.let { p ->
            return PlaysAsAnswer(row, p.id, video, SongVersions.shownAs(row, p.song), PairSource.LISTENER_PICK)
        }
        val paired = entry?.song?.takeIf { entry.row == row.id && canSwap(row) }
            ?: return PlaysAsAnswer(row, row.id, video, row, null)
        if (swap && entry.swaps) {
            val songPick = pickOf(paired.id)?.takeIf { it.id != paired.id }
            return PlaysAsAnswer(paired, songPick?.id ?: paired.id, video, SongVersions.shownAs(paired, songPick?.song), entry.source)
        }
        val cover = paired.thumbnail?.takeIf { it != row.thumbnail }
        return PlaysAsAnswer(row, row.id, video, if (cover != null) VideoCover.withSong(row, paired, cover) else row, entry.source)
    }

    /**
     * Each row whose download is kept under another id, its song's: for the downloaded mark, Downloads' own lookups and
     * lists' downloads. A stand-in (the row was downloaded as its song, [downloaded]) counts as it always did; a pairing
     * learned any other way ([learned]) counts while music videos play as their songs ([everywhere]), since that's when
     * the row plays, and downloads, as the song (#315: a pairing learned after the song was downloaded). A row the
     * listener picked a version for ([picked]) plays, and downloads, as itself.
     */
    fun keptAs(
        downloaded: Map<String, PlaysAsEntry>, learned: Map<String, PlaysAsEntry>, picked: (String) -> Boolean, everywhere: Boolean,
    ): Map<String, String> {
        val out = HashMap<String, String>()
        if (everywhere) for ((row, e) in learned) if (e.swaps) out[row] = e.sound
        for ((row, e) in downloaded) if (e.sound != row) out[row] = e.sound
        if (out.isNotEmpty()) out.keys.removeAll { picked(it) }
        return out
    }
}

/**
 * 1.8.7 §3.2: one answer to "what plays for this row". Nine places used to decide it each on their own, most of them
 * for the session only (a tap, a playlist's pairing, the next video in the queue, the cover, album pages, radios,
 * downloads and their stand-ins, the listener's pick, a blocked song's video), and #315, #328 and #390 came from two of
 * them disagreeing. Every one of them now writes here and every reader asks here: playback, downloads, the downloaded
 * mark, covers, the session's history and (through the song that goes in the queue) the lyrics lookup.
 *
 * Where each source is kept:
 * - the listener's picks stay in [SongVersions] (their own preferences: the Library part of a backup, and Bring your
 *   Vinz), read through [pickOf], and always win;
 * - download stand-ins stay with the downloads ("download_standins", the Downloads part of a backup), handed over by
 *   the download list ([setDownloaded]);
 * - what's learned from YouTube and matches ([PairSource.learned]) is kept in [file], read in off the main thread and
 *   written on IO a moment after it changes. It's YouTube's answers, learned again when missing, so no backup carries it;
 * - a blocked song's video is for this run only ([blocked]).
 *
 * Reads are memory lookups, safe on the main thread. Until the file has been read only what's been learned since is
 * known (a reader then asks YouTube, as it always did).
 */
class PlaysAs(
    private val file: File?,
    private val scope: CoroutineScope,
    private val pickOf: (String) -> SongVersions.Pick? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val writeDelayMs: Long = WRITE_DELAY_MS,
) {
    private val lock = Any()
    private val _learned = MutableStateFlow<Map<String, PlaysAsEntry>>(emptyMap())
    /** What's been learned, by row. */
    val learned: StateFlow<Map<String, PlaysAsEntry>> = _learned
    private val _downloaded = MutableStateFlow<Map<String, PlaysAsEntry>>(emptyMap())
    /** The download stand-ins, by row ([setDownloaded]). */
    val downloaded: StateFlow<Map<String, PlaysAsEntry>> = _downloaded

    /**
     * Songs YouTube won't play where the listener is, and the music video whose sound plays in their place (#93): set by
     * the player when a song fails as unavailable, for this run only. Not a pairing: the song stays the song (its
     * history, lyrics and likes), only its sound comes from the video.
     */
    val blocked = ConcurrentHashMap<String, String>()

    private val ready = CompletableDeferred<Unit>()
    private var dirty = false
    private var writing = false
    @Volatile private var inverse: Pair<Map<String, PlaysAsEntry>, Map<String, String>>? = null

    init {
        if (file == null) ready.complete(Unit)
        else scope.launch(io) { try { load(file) } finally { ready.complete(Unit) } }
    }

    /** Returns once the file has been read (at once without one). */
    suspend fun awaitLoaded() = ready.await()

    /** [row]'s pairing, the strongest known: its download stand-in, else what was learned. */
    fun entry(row: String): PlaysAsEntry? {
        val learned = _learned.value[row]
        val d = _downloaded.value[row] ?: return learned
        // A stand-in from before songs were kept with it names only the song's id: the learned pairing may know the song.
        val song = d.song ?: learned?.song?.takeIf { it.id == d.sound }
        return if (song != null) d.copy(song = song) else learned ?: d
    }

    /** What plays for [row] ([PlaysAsRules.answer]). */
    fun answer(row: Song, swap: Boolean = true): PlaysAsAnswer = PlaysAsRules.answer(row, pickOf, entry(row.id), swap)

    /** The listener picked a version for [row]: it plays as itself, with the pick. */
    fun picked(row: String): Boolean = pickOf(row)?.let { it.id != row } == true

    /** The song [row] (a music video) plays as when it's swapped for its song; null when it plays as itself. */
    fun songFor(row: Song): Song? = answer(row).takeIf { it.source != null && it.source != PairSource.LISTENER_PICK && it.queued.id != row.id }?.queued

    /**
     * The song [video] plays as: the strongest pairing known, at once; else what [lookup] finds, kept as a
     * [PairSource.VIDEO_MATCH]. Null when the listener picked a version for the video (it plays as itself, with the pick)
     * or nothing's found.
     */
    suspend fun songForVideo(video: Song, lookup: suspend () -> Song?): Song? {
        if (picked(video.id)) return null
        songFor(video)?.let { return it }
        val found = lookup() ?: return null
        learn(video.id, found, PairSource.VIDEO_MATCH)
        return songFor(video) ?: found
    }

    /** The song whose cover and album [row] (a music video playing as itself) shows, from any pairing known. */
    fun coverSong(row: String): Song? = entry(row)?.song

    /** The music video [songId] is paired with, by any pairing known (the row it stands for). */
    fun videoOf(songId: String): String? {
        val m = _learned.value
        val inv = inverse?.takeIf { it.first === m }?.second
            ?: HashMap<String, String>().also { out -> m.values.forEach { if (it.swaps) out[it.sound] = it.row } }.also { inverse = m to it }
        return inv[songId] ?: _downloaded.value.values.firstOrNull { it.sound == songId && it.sound != it.row }?.row
    }

    /** Rows downloaded under their song's id ([PlaysAsRules.keptAs]); [everywhere]: music videos play as their songs. */
    fun keptAs(everywhere: Boolean): Map<String, String> = PlaysAsRules.keptAs(_downloaded.value, _learned.value, ::picked, everywhere)

    /** The download list's stand-ins ([standIns]: row -> song id), each with the song when [songOf] knows it. */
    fun setDownloaded(standIns: Map<String, String>, songOf: (String) -> Song?) {
        _downloaded.value = standIns.mapValues { (row, sound) -> PlaysAsEntry(row, sound, songOf(sound), PairSource.DOWNLOAD) }
    }

    /** [row] (a music video) is paired with [song] by [source]. */
    fun learn(row: String, song: Song, source: PairSource) = learnAll(listOf(row to song), source)

    /** Each (row, song) of [pairs] is paired by [source]; a weaker source never takes a stronger one's place. */
    fun learnAll(pairs: Collection<Pair<String, Song>>, source: PairSource) {
        if (!source.learned || pairs.isEmpty()) return
        val t = now()
        val changed = synchronized(lock) {
            var m: HashMap<String, PlaysAsEntry>? = null
            for ((row, song) in pairs) {
                if (row.isBlank() || song.id.isBlank() || song.id == row) continue
                val e = PlaysAsEntry(row, if (source == PairSource.COVER) row else song.id, song, source, t, MATCHER)
                val old = (m ?: _learned.value)[row]
                if (old != null && old.sound == e.sound && old.source == e.source && old.song == e.song) continue
                if (!PlaysAsRules.replaces(old, e)) continue
                if (m == null) m = HashMap(_learned.value)
                m[row] = e
            }
            m?.let { trim(it); _learned.value = it } != null
        }
        if (changed) scheduleWrite()
    }

    private fun trim(m: HashMap<String, PlaysAsEntry>) {
        if (m.size <= MAX) return
        // The weakest and oldest go first.
        m.values.sortedWith(compareBy({ it.source.strength }, { it.at })).take(m.size - MAX * 9 / 10).forEach { m.remove(it.row) }
    }

    private fun load(f: File) {
        val read = read(f)
        synchronized(lock) {
            val m = HashMap<String, PlaysAsEntry>()
            for (e in read.sortedBy { it.at }) if (PlaysAsRules.replaces(m[e.row], e)) m[e.row] = e
            // Learned while the file was being read: by the same rule, over what it held.
            for (e in _learned.value.values) if (PlaysAsRules.replaces(m[e.row], e)) m[e.row] = e
            trim(m)
            _learned.value = m
        }
    }

    /** Writes on [io], a moment after a change (one write for a page's worth), never before the file was read. */
    private fun scheduleWrite() {
        val f = file ?: return
        synchronized(lock) {
            dirty = true
            if (writing) return
            writing = true
        }
        scope.launch(io) {
            delay(writeDelayMs)
            ready.await()
            while (true) {
                synchronized(lock) {
                    if (!dirty) { writing = false; return@launch }
                    dirty = false
                }
                runCatching { write(f) }
            }
        }
    }

    private fun write(f: File) {
        val text = InnerTube.json.encodeToString(Stored.serializer(), Stored(entries = _learned.value.values.sortedBy { it.at }))
        f.parentFile?.mkdirs()
        writeAtomically(f, text)
    }

    @Serializable
    private data class Stored(val format: Int = 1, val entries: List<PlaysAsEntry>)

    companion object {
        /** Its folder right under the app's files folder (a backup goes by it, its file's write-in-progress copy too). */
        const val DIR = "plays_as"

        /** The file in [filesDir]. */
        fun fileIn(filesDir: File) = File(File(filesDir, DIR), "pairings.json")
        /**
         * The video-to-song matcher's version. Raise it when [com.vinz.music.data.innertube.YouTubeMusic.pickSongForVideo]
         * becomes stricter: matches it found before are then dropped and found again ([read]).
         */
        const val MATCHER = 1
        /** Pairings kept; the weakest and oldest go first beyond it (a few hundred KB). */
        const val MAX = 4_000
        private const val WRITE_DELAY_MS = 2_000L

        /**
         * What [f] holds: each entry on its own (one that doesn't read is skipped), learned sources only, and matches by an
         * older matcher ([MATCHER]) left out.
         */
        internal fun read(f: File): List<PlaysAsEntry> = runCatching {
            if (!f.exists()) return emptyList()
            InnerTube.json.parseToJsonElement(f.readText()).jsonObject["entries"]?.jsonArray.orEmpty().mapNotNull {
                runCatching { InnerTube.json.decodeFromJsonElement(PlaysAsEntry.serializer(), it) }.getOrNull()
            }
        }.getOrDefault(emptyList()).filter {
            it.source.learned && it.row.isNotBlank() && it.sound.isNotBlank() &&
                (it.source.strength > PairSource.VIDEO_MATCH.strength || it.v >= MATCHER)
        }
    }
}
