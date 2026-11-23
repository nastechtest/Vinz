package com.vinz.music

import com.prism.music.data.db.LyricsDao
import com.prism.music.data.db.LyricsEntity
import com.vinz.music.data.innertube.InnerTube
import com.vinz.music.data.innertube.SearchFilter
import com.vinz.music.data.innertube.YouTubeMusic
import com.vinz.music.data.lyrics.Lyrics
import com.vinz.music.data.lyrics.LyricsRepository
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.AlbumRef
import com.vinz.music.data.model.Song
import com.vinz.music.data.model.SongItem
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.LyricsSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.8.7 §6.1's coverage check: what each lyric source finds for ~60 varied real songs, so a stricter match check can be
 * compared with the one before it. Uses only the repository API that both have (fetch per source, auto), and the same
 * songs on both runs (resolved once from YouTube Music's song search, kept in the temp folder). Prints a table and
 * writes it to `<tmp>/vinz-lyric-coverage-<old|new>.tsv`; counts refusals per host so rate limiting shows as that,
 * not as misses. Run: `-PliveOnly --tests *LyricCoverageLiveTest`.
 */
@Category(LiveNetwork::class)
class LyricCoverageLiveTest {
    /** Search, and what kind of song it is. "~topic" / "~label": the found song credited to "<artist> - Topic" / a label. */
    private val queries = listOf(
        "pop" to "Espresso Sabrina Carpenter", "pop" to "APT. ROSÉ Bruno Mars", "pop" to "Die With A Smile Lady Gaga Bruno Mars",
        "pop" to "Birds of a Feather Billie Eilish", "pop" to "Blinding Lights The Weeknd", "pop" to "Flowers Miley Cyrus",
        "pop" to "Anti-Hero Taylor Swift", "pop" to "As It Was Harry Styles", "pop" to "Good Luck, Babe! Chappell Roan",
        "pop" to "Not Like Us Kendrick Lamar", "pop" to "Shape of You Ed Sheeran", "pop" to "Despacito Luis Fonsi",
        "rock" to "Don't Stop Me Now Queen", "rock" to "Hotel California Eagles", "rock" to "Stairway to Heaven Led Zeppelin",
        "rock" to "Paint It Black Rolling Stones", "rock" to "Come Together Beatles", "rock" to "Dreams Fleetwood Mac",
        "rock" to "Sweet Child O' Mine Guns N' Roses", "rock" to "Wish You Were Here Pink Floyd",
        "kpop" to "Dynamite BTS", "kpop" to "Hype Boy NewJeans", "kpop" to "How You Like That BLACKPINK", "kpop" to "LOVE DIVE IVE",
        "kpop" to "Super Shy NewJeans", "kpop" to "밤양갱 BIBI", "kpop" to "봄날 BTS", "kpop" to "Gangnam Style PSY",
        "jpop" to "夜に駆ける YOASOBI", "jpop" to "Lemon 米津玄師", "jpop" to "アイドル YOASOBI", "jpop" to "紅蓮華 LiSA",
        "jpop" to "Pretender Official髭男dism", "jpop" to "ただ君に晴れ ヨルシカ", "jpop" to "春泥棒 ヨルシカ", "jpop" to "残酷な天使のテーゼ 高橋洋子",
        "mandarin" to "告白气球 周杰伦", "mandarin" to "晴天 周杰伦", "mandarin" to "演员 薛之谦", "mandarin" to "红豆 王菲", "mandarin" to "光年之外 G.E.M.",
        "india" to "Kesariya Arijit Singh", "india" to "Tum Hi Ho Arijit Singh", "india" to "Naatu Naatu RRR", "india" to "Srivalli Pushpa",
        "india" to "Chaleya Jawan", "india" to "Butta Bomma",
        "soundtrack" to "We Don't Talk About Bruno Encanto", "soundtrack" to "Let It Go Idina Menzel", "soundtrack" to "Can't Stop the Feeling Justin Timberlake",
        "classical" to "Clair de Lune Debussy", "classical" to "Für Elise Beethoven", "classical" to "Nessun dorma Pavarotti",
        "live" to "Hotel California Live 1994 Eagles", "live" to "Bohemian Rhapsody Live Aid Queen",
        "feat" to "Stay The Kid LAROI Justin Bieber", "feat" to "Peaches Justin Bieber Daniel Caesar Giveon", "feat" to "Unholy Sam Smith Kim Petras",
        "~topic" to "Take On Me a-ha", "~label" to "Kesariya Arijit Singh",
    )

    private val tmp = File(System.getProperty("java.io.tmpdir"))
    private val songsFile = File(tmp, "vinz-lyric-coverage-songs.tsv")
    private val label = if (runCatching { Class.forName("com.vinz.music.data.lyrics.SongMatch") }.isSuccess) "new" else "old"

    private val codes = ConcurrentHashMap<String, AtomicInteger>()
    private val http = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            val host = chain.request().url.host
            val r = try { chain.proceed(chain.request()) } catch (e: java.io.IOException) {
                codes.computeIfAbsent("$host io", { AtomicInteger() }).incrementAndGet(); throw e
            }
            if (r.code == 429 || r.code == 403 || r.code >= 500) codes.computeIfAbsent("$host ${r.code}", { AtomicInteger() }).incrementAndGet()
            r
        }).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    private fun kind(l: Lyrics?) = when { l == null -> "none"; l.wordSynced -> "word"; l.synced -> "line"; else -> "plain" }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    private suspend fun songs(): List<Pair<String, Song>> {
        if (songsFile.exists()) return songsFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { line ->
            val p = line.split('\t')
            p[0] to Song(p[1], p[2], p[3].split('\u0001').map { ArtistRef(it) }, p[4].takeIf { it.isNotBlank() }?.let { AlbumRef(it) }, p[5].toInt())
        }
        val out = ArrayList<Pair<String, Song>>()
        for ((kind, q) in queries) {
            val found = runCatching { ytm.search(q, SearchFilter.SONGS).items.filterIsInstance<SongItem>().firstOrNull()?.song }.getOrNull()
            if (found == null) { println("[coverage] no song for $q"); continue }
            val s = when (kind) {
                "~topic" -> found.copy(artists = found.artists.take(1).map { ArtistRef(it.name + " - Topic") })
                "~label" -> found.copy(id = found.id + "-label", artists = listOf(ArtistRef("T-Series")))
                else -> found
            }
            out += kind to s
            Thread.sleep(400)
        }
        songsFile.writeText(out.joinToString("\n") { (k, s) ->
            listOf(k, s.id, clean(s.title), s.artists.joinToString("\u0001") { clean(it.name) }, clean(s.album?.title.orEmpty()), s.durationSec.toString()).joinToString("\t")
        }, Charsets.UTF_8)
        return out
    }

    /** In-memory stand-in for the word-timing prefs, so word lyrics are compared with line-timed ones as in the app. */
    private class MemPrefs : android.content.SharedPreferences {
        val map = ConcurrentHashMap<String, Any>()
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?) = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun contains(key: String?) = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
            val put = HashMap<String, Any?>()
            override fun putString(key: String, value: String?) = apply { put[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { put[key] = values }
            override fun putInt(key: String, value: Int) = apply { put[key] = value }
            override fun putLong(key: String, value: Long) = apply { put[key] = value }
            override fun putFloat(key: String, value: Float) = apply { put[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { put[key] = value }
            override fun remove(key: String) = apply { put[key] = null }
            override fun clear() = apply { map.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { put.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v } }
        }
    }

    @Test fun coverage() = runBlocking {
        val list = songs()
        val sources = LyricsSource.entries.filter { it != LyricsSource.EMBEDDED }
        val rows = HashMap<String, LyricsEntity>()
        val dao = object : LyricsDao {
            override suspend fun get(songId: String, source: String) = rows["$songId|$source"]
            override suspend fun put(e: LyricsEntity) { rows["${e.songId}|${e.source}"] = e }
            override suspend fun clearMisses() {}
            override suspend fun allFor(songId: String) = rows.values.filter { it.songId == songId && it.content.isNotEmpty() }
        }
        val repo = LyricsRepository(http, dao, ytm, { settings }).apply { wordShifts = MemPrefs() }
        val out = StringBuilder("kind\tid\ttitle\tartist\tsec\tauto\tautoKind\t" + sources.joinToString("\t") { it.name } + "\n")
        for ((k, s) in list) {
            val before = codes.toMap().mapValues { it.value.get() }
            val per = sources.map { src -> kind(withTimeoutOrNull(40_000) { runCatching { repo.fetch(s, src) }.getOrNull() }) }
            val auto = withTimeoutOrNull(60_000) { runCatching { repo.auto(s) }.getOrNull() }
            val trouble = codes.mapValues { it.value.get() }.filter { (h, n) -> n > (before[h] ?: 0) }
            val line = listOf(k, s.id, s.title, s.artistText, s.durationSec.toString(), auto?.source?.name ?: "-", kind(auto)) + per
            out.append(line.joinToString("\t")).append('\n')
            println("[coverage] ${line.joinToString(" | ")}" + if (trouble.isEmpty()) "" else "  TROUBLE $trouble")
            Thread.sleep(3_000)
        }
        File(tmp, "vinz-lyric-coverage-$label.tsv").writeText(out.toString(), Charsets.UTF_8)
        println("[coverage] $label: auto hits ${out.lines().drop(1).count { it.split('\t').getOrNull(6).let { k -> k != null && k != "none" } }} of ${list.size}")
        println("[coverage] refusals/errors by host: ${codes.mapValues { it.value.get() }}")
    }
}
