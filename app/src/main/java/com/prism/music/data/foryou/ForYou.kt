package com.prism.music.data.foryou

import android.content.Context
import android.util.Log
import com.prism.music.AppContainer
import com.prism.music.data.db.MixEntity
import com.prism.music.data.model.Song
import com.prism.music.download.PlayLikelihood
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

/** A mix as the screens use it. */
data class Mix(
    val id: String,
    val title: String,
    val subtitle: String,
    val artists: List<String>,
    val color: Int,
    val createdAt: Long,
    val songs: List<Song>,
) {
    /** Up to four different covers for its picture. */
    val covers: List<String> get() = songs.mapNotNull { it.thumbnail }.distinct().take(4)
}

/**
 * Made for you, all on the phone from the listener's own plays (nothing about them leaves it; YouTube is only asked
 * for radios to find new songs):
 * - **Daily Mixes** (up to six): the listener's artists grouped by what goes together ([MadeForYou.clusters]), each a
 *   50-song mix of songs they know by those artists and new ones like them. Made once a day, so they stay the same
 *   all day (and offline).
 * - **The time-of-day mix**: what they play at this time of day, plus more like it ("Your Tuesday evening hip-hop"),
 *   made again when the part of the day changes.
 * Disliked songs and excluded artists never appear; songs played three times in the last three days rest.
 */
class ForYou(private val c: AppContainer) {
    private val prefs = c.app.getSharedPreferences("for_you", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val songList = ListSerializer(Song.serializer())
    private val lock = Mutex()
    private var retitledAt = 0L

    /**
     * The mixes, read once per change and kept (null until the first read). Their songs are stored as JSON; decoding a
     * few hundred songs happened on the main thread every time Made for you scrolled back onto Home (1.7.4).
     */
    val mixes: StateFlow<List<Mix>?> = c.db.mixes().allFlow().map { list -> list.map(::toMix).sortedBy(::order) }
        .flowOn(Dispatchers.Default)
        .stateIn(c.scope, SharingStarted.Eagerly, null)

    suspend fun mix(id: String): Mix? = c.db.mixes().get(id)?.let(::toMix)

    private fun toMix(e: MixEntity) = Mix(
        e.id, e.title, e.subtitle, e.seedArtists.split('|').filter { it.isNotBlank() }, e.color, e.createdAt,
        runCatching { json.decodeFromString(songList, e.songsJson) }.getOrDefault(emptyList()),
    )

    /** Time-of-day first, then Daily Mixes, then Release Radar. */
    private fun order(m: Mix) = when {
        m.id == MadeForYou.TIME -> 0
        m.id.startsWith("daily:") -> 1 + (m.id.substringAfter(':').toIntOrNull() ?: 9)
        else -> 20
    }

    suspend fun saveMix(id: String, title: String, subtitle: String, artists: List<String>, color: Int, songs: List<Song>) {
        c.db.mixes().put(MixEntity(id, title, subtitle, artists.joinToString("|"), color, System.currentTimeMillis(), json.encodeToString(songList, songs)))
    }

    /** Makes whatever is due: the Daily Mixes once a day, the time-of-day mix when the part of the day changes. */
    suspend fun refresh(force: Boolean = false) = lock.withLock {
        val today = LocalDate.now().toString()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val bucket = MadeForYou.TimeBucket.of(hour)
        val dailyDue = force || prefs.getString(DAILY_DAY, null) != today
        val timeDue = force || prefs.getString(TIME_KEY, null) != "$today|${bucket.name}"
        // Made for you asks for this each time it comes into view on Home: the name is looked at again every ten minutes.
        if (!timeDue && System.currentTimeMillis() - retitledAt > 10 * 60_000L) {
            retitledAt = System.currentTimeMillis()
            runCatching { retitleTimeMix() }.onFailure { Log.w(TAG, "time mix title failed", it) }
        }
        if (!dailyDue && !timeDue) return@withLock
        c.quietNetwork()
        val data = withContext(Dispatchers.IO) { Listening.load(c) } ?: return@withLock
        if (dailyDue) runCatching { makeDaily(data) }.onFailure { Log.w(TAG, "daily mixes failed", it) }
            .onSuccess { prefs.edit().putString(DAILY_DAY, today).apply() }
        if (timeDue) runCatching { makeTimeMix(data, bucket) }.onFailure { Log.w(TAG, "time mix failed", it) }
            .onSuccess { prefs.edit().putString(TIME_KEY, "$today|${bucket.name}").apply() }
    }

    /** What the mixes are made from: 90 days of plays that count towards taste, the songs, likes, and a play model. */
    private class Listening(
        val events: List<com.prism.music.data.db.PlayEventEntity>,
        val songs: Map<String, Song>,
        val liked: List<Song>,
        val model: PlayLikelihood,
        val now: Long,
    ) {
        val played90 = events.map { it.songId }.toSet()
        /** Songs played three or more times in the last three days: they rest. */
        val tired: Set<String> = events.filter { now - it.timestamp < 3 * DAY }.groupingBy { it.songId }.eachCount().filter { it.value >= 3 }.keys

        companion object {
            suspend fun load(c: AppContainer): Listening? {
                val now = System.currentTimeMillis()
                val events = c.db.plays().tasteEventsSince(now - 90 * DAY)
                val liked = c.db.songs().liked().map { it.toSong() }
                if (events.isEmpty() && liked.isEmpty()) return null
                val songs = events.map { it.songId }.distinct().chunked(500).flatMap { c.db.songs().getAll(it) }.associate { it.id to it.toSong() }.toMutableMap()
                liked.forEach { songs.putIfAbsent(it.id, it) }
                return Listening(events, songs, liked, PlayLikelihood(events, songs, liked.map { it.id }.toSet(), now), now)
            }
        }
    }

    private fun ok(s: Song, data: Listening) = !s.isLocal && !s.isEpisode && !s.isLive && !c.library.isDisliked(s.id) && !c.exclusions.blocks(s) && s.id !in data.tired

    /** A day's own shuffle of near-equal scores, so a mix isn't the same songs in the same order every day. */
    private fun jitter(id: String, day: String) = 0.85 + 0.3 * ((id + day).hashCode().toLong().and(0xffff) / 65535.0)

    private suspend fun makeDaily(data: Listening) {
        val day = LocalDate.now().toString()
        val plays = data.events.mapNotNull { e -> data.songs[e.songId]?.let { MadeForYou.Play(e.songId, it.primaryArtist, e.timestamp) } } +
            data.liked.map { MadeForYou.Play(it.id, it.primaryArtist, data.now - 7 * DAY, 0.5) }
        val genres = artistGenres(MadeForYou.topArtists(plays.filter { it.artist.isNotBlank() }, data.now), data)
        val clusters = MadeForYou.clusters(plays.filter { it.artist.isNotBlank() }, genres, data.now)
        val made = ArrayList<String>()
        for (cl in clusters) {
            // Numbered by the mixes actually made: a group without enough songs is skipped without leaving a gap.
            val n = made.size + 1
            val members = cl.artists.map { it.lowercase() }.toSet()
            val known = data.songs.values.filter { it.primaryArtist.lowercase() in members && ok(it, data) && !it.isVideo }
                .sortedByDescending { data.model.score(it) * jitter(it.id, day) }
            val own = known.take(30)
            val fresh = discover(own.take(3), data, members, day, exclude = own.map { it.id }.toSet())
            val songs = MadeForYou.interleave(own, fresh, 50) { it.primaryArtist.lowercase() }
            if (songs.size < 10) continue
            saveMix(MadeForYou.daily(n), "Daily Mix $n", MadeForYou.namesLine(cl.artists), cl.artists.take(4), COLORS[(n - 1) % COLORS.size], songs)
            made += MadeForYou.daily(n)
        }
        // Mixes from a day with more groups than today's go.
        c.db.mixes().deleteOthers("daily:%", made.ifEmpty { listOf("-") })
        Log.i(TAG, "daily mixes: ${clusters.joinToString(" | ") { "${it.artists} %.0f%%".format(it.weight * 100) }}")
    }

    /**
     * New songs like [seeds] (their radios), that the listener hasn't played in 90 days, best first. [near]: artists
     * the mix is about (a new song by one of them is welcome).
     */
    private suspend fun discover(seeds: List<Song>, data: Listening, near: Set<String>, day: String, exclude: Set<String>, max: Int = 20): List<Song> {
        val found = LinkedHashMap<String, Pair<Song, Double>>()
        for (seed in seeds) {
            val radio = runCatching { withContext(Dispatchers.IO) { c.radio(seed.id, "RDAMVM${seed.id}") } }.getOrNull()?.songs ?: continue
            val seedScore = data.model.score(seed)
            radio.forEachIndexed { rank, s ->
                if (s.id in exclude || s.id in data.played90 || s.isVideo || !ok(s, data)) return@forEachIndexed
                val bonus = if (s.primaryArtist.lowercase() in near) 1.15 else 1.0
                val score = data.model.score(s, recommendedFrom = seedScore, rank = rank) * bonus * jitter(s.id, day)
                if ((found[s.id]?.second ?: -1.0) < score) found[s.id] = s to score
            }
        }
        return found.values.sortedByDescending { it.second }.map { it.first }.distinctBy { it.title.lowercase() to it.primaryArtist.lowercase() }.take(max)
    }

    private suspend fun makeTimeMix(data: Listening, bucket: MadeForYou.TimeBucket) {
        val day = LocalDate.now().toString()
        val cal = Calendar.getInstance()
        val weight = HashMap<String, Double>()
        for (e in data.events) {
            cal.timeInMillis = e.timestamp
            if (cal.get(Calendar.HOUR_OF_DAY) !in bucket) continue
            weight.merge(e.songId, 1.0, Double::plus)
        }
        val known = weight.entries.mapNotNull { (id, w) -> data.songs[id]?.takeIf { ok(it, data) && !it.isVideo }?.let { it to w * data.model.score(it) * jitter(id, day) } }
            .sortedByDescending { it.second }.map { it.first }
        if (known.size < 5) { c.db.mixes().deleteOthers(MadeForYou.TIME, listOf("-")); return }
        val own = known.take(18)
        val fresh = discover(own.take(2), data, own.map { it.primaryArtist.lowercase() }.toSet(), day, own.map { it.id }.toSet(), max = 12)
        val songs = MadeForYou.interleave(own, fresh, 30) { it.primaryArtist.lowercase() }
        // Artists whose genre isn't known yet are looked up first (briefly), so the name has the whole mix to go by.
        withTimeoutOrNull(6_000) {
            val known = c.meta.cachedGenres(songs)
            val missing = songs.filter { it.id !in known }.map { it.primaryArtist }.filter { it.isNotBlank() }.distinct()
            coroutineScope { missing.map { a -> async { runCatching { c.meta.artistGenre(a) } } }.awaitAll() }
        }
        val weekday = LocalDate.now().dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val artists = own.map { it.primaryArtist }.distinct()
        saveMix(MadeForYou.TIME, MadeForYou.timeTitle(weekday, bucket, timeGenre(songs)), MadeForYou.namesLine(artists),
            artists.take(4), TIME_COLORS.getValue(bucket), songs)
    }

    /** The genre word in the time-of-day mix's name ("indie", "hip-hop"), or null for "mix". */
    private suspend fun timeGenre(songs: List<Song>): String? {
        val g = c.meta.cachedGenres(songs)
        return MadeForYou.mainGenre(songs.map { g[it.id] })?.substringBefore(" & ")
    }

    /**
     * The time-of-day mix named again from what's known of its songs' genres now. A new mix is named while most
     * artists only have Deezer's quick genre, and Apple's (looked up in the background) can differ: Deezer files
     * Bring Me The Horizon and A Day To Remember under Alternative, Apple under Rock, so a mix of them was called
     * "indie" while its own page listed it as mostly rock.
     */
    private suspend fun retitleTimeMix() {
        val e = c.db.mixes().get(MadeForYou.TIME) ?: return
        val made = java.time.Instant.ofEpochMilli(e.createdAt).atZone(java.time.ZoneId.systemDefault())
        val bucket = MadeForYou.TimeBucket.of(made.hour)
        val title = MadeForYou.timeTitle(made.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH), bucket, timeGenre(toMix(e).songs))
        if (title != e.title) {
            c.db.mixes().put(e.copy(title = title))
            Log.i(TAG, "time mix renamed: ${e.title} -> $title")
        }
    }

    /** Each artist's genre (Prism's genre buckets), from what's been looked up; the ones not known yet are looked up (briefly). */
    private suspend fun artistGenres(artists: List<String>, data: Listening): Map<String, String?> {
        val bySong = c.meta.cachedGenres(data.songs.values.toList())
        val out = HashMap<String, String?>()
        val votes = HashMap<String, HashMap<String, Int>>()
        for (s in data.songs.values) bySong[s.id]?.let { g -> votes.getOrPut(s.primaryArtist) { HashMap() }.merge(g, 1, Int::plus) }
        for (a in artists) out[a] = votes[a]?.maxByOrNull { it.value }?.key
        // The rest are looked up (Deezer / iTunes, cached for good after), each given its own time so one slow
        // answer doesn't cost the others theirs.
        val missing = artists.filter { out[it] == null }
        if (missing.isNotEmpty()) coroutineScope {
            missing.map { a -> async { a to withTimeoutOrNull(15_000) { runCatching { c.meta.artistGenre(a) }.getOrNull() } } }.awaitAll()
        }.forEach { (a, g) -> if (g != null) out[a] = g }
        Log.i(TAG, "genres for ${artists.size} artists, ${out.values.count { it != null }} known: " + artists.joinToString { "$it=${out[it]}" })
        return out
    }

    private companion object {
        const val TAG = "ForYou"
        const val DAILY_DAY = "daily_day"
        const val TIME_KEY = "time_key"
        const val DAY = 86_400_000L
        val COLORS = listOf(0xFFE0533D, 0xFF3D7BE0, 0xFF8E44AD, 0xFF27AE60, 0xFFD4A017, 0xFFD6336C).map { it.toInt() }
        val TIME_COLORS = mapOf(
            MadeForYou.TimeBucket.MORNING to 0xFFF2A541.toInt(),
            MadeForYou.TimeBucket.AFTERNOON to 0xFF4FB3BF.toInt(),
            MadeForYou.TimeBucket.EVENING to 0xFFE76F51.toInt(),
            MadeForYou.TimeBucket.NIGHT to 0xFF5C6BC0.toInt(),
            MadeForYou.TimeBucket.LATE to 0xFF37474F.toInt(),
        )
    }
}
