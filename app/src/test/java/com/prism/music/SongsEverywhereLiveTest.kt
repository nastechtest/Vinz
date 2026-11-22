package com.vinz.music

import com.vinz.music.data.innertube.InnerTube
import com.vinz.music.data.innertube.YouTubeMusic
import com.vinz.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.concurrent.TimeUnit

/**
 * #383 (1.8.3): with "Songs, not videos: Everywhere", two playlists played from Home gave the videos' sound. What YouTube
 * gives for their video rows, signed out: a song found for each (Vinz's search match), and what the playlist's own
 * watch queue pairs.
 */
@Category(LiveNetwork::class)
class SongsEverywhereLiveTest {
    private var settings = AppSettings()
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))
    private fun log(msg: String) = println("[live] $msg")

    /** The songs YouTube files as episodes there (no show, the singer linked) are read as videos with their artist. */
    @Test fun noSongReadAsAnEpisode() = runBlocking {
        val songs = ytm.playlistAll("PLEwlFF5xcXHzubwrqTqBzexAGjhl_1LoA", 60).songs
        val episodes = songs.filter { it.isEpisode }
        log("${songs.size} rows, ${episodes.size} read as episodes: ${episodes.take(5).map { it.title }}")
        if (episodes.isNotEmpty()) {
            val api = InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) })
            val text = api.post("browse", kotlinx.serialization.json.buildJsonObject { put("browseId", kotlinx.serialization.json.JsonPrimitive("VLPLEwlFF5xcXHzubwrqTqBzexAGjhl_1LoA")) }).toString()
            val at = text.indexOf("\"text\":\"${episodes.first().title}\"")
            if (at >= 0) log("raw: " + text.substring(at, (at + 1500).coerceAtMost(text.length)).replace(Regex("\"(clickT|t)rackingParams\":\"[^\"]*\",?"), ""))
        }
        org.junit.Assert.assertTrue(episodes.isEmpty())
    }

    /**
     * #383 (1.8.4.1): the four the reporter named after 1.8.3 ("Yeshanagula", "Dheema", "Private Party", "Kajalu
     * Chellivaa") are each found as a song by Vinz's own search match (signed out, so no pairing from YouTube).
     */
    @Test fun theFourNamedAfter183FindTheirSongs() = runBlocking {
        val named = listOf("Yeshanagula", "Dheema", "Private Party", "Kajalu")
        val rows = listOf("PLtLEpc9CWsdfxbsiuKYw02kpbCF1mnNcS", "PLEwlFF5xcXHzubwrqTqBzexAGjhl_1LoA")
            .flatMap { ytm.playlistAll(it, 80).songs }.filter { s -> s.isVideo && named.any { s.title.startsWith(it, true) } }
        val missed = rows.filter { v ->
            val s = ytm.songForVideo(v)
            log("${v.id} \"${v.title}\" / ${v.artistText} (${v.durationSec}s) -> ${s?.let { "${it.id} \"${it.title}\" ${it.artistText} ${it.durationSec}s" } ?: "none"}")
            s == null
        }
        org.junit.Assert.assertEquals(4, rows.size)
        org.junit.Assert.assertTrue("missed: ${missed.map { it.title }}", missed.isEmpty())
    }

    /**
     * #383 (1.8.5): the two playlists named after 1.8.4.1. Every video row, what's searched for it, what the search gives
     * and which song is picked: the misses show which rule turned the song down.
     */
    @Test fun playlistsNamedAfter1841() = runBlocking {
        var videosSeen = 0
        var missed = 0
        for (id in listOf("PLEV4Wjn7aSFHRm-VkBxh2TJKwNyfUGqsO", "PLV8MOG0oNwgPXs35owR0R1BbHwTFGRMYP")) {
            val songs = ytm.playlistAll(id, 120).songs
            val videos = songs.filter { it.isVideo }
            log("$id: ${songs.size} rows, ${videos.size} videos, ${songs.count { it.isEpisode }} episodes")
            for (v in videos) {
                videosSeen++
                val picked = runCatching { ytm.songForVideo(v) }.getOrNull()
                if (picked != null) continue
                missed++
                val query = YouTubeMusic.songForVideoQuery(v)
                log("MISS ${v.id} \"${v.title}\" / ${v.artistText} (${v.durationSec}s) query=\"$query\"")
                val results = query?.let { q -> runCatching { ytm.search(q, com.vinz.music.data.innertube.SearchFilter.SONGS).items.filterIsInstance<com.vinz.music.data.model.SongItem>().map { it.song } }.getOrNull() }.orEmpty()
                results.take(8).forEach { s -> log("     ${s.id} \"${s.title}\" / ${s.artistText} (${s.durationSec}s) album=${s.album} video=${s.isVideo}") }
            }
        }
        log("missed $missed of $videosSeen videos")
    }

    /**
     * #383 (1.8.7, plan 3.1): the three named after 1.8.5 ("Dandiya India", "Sairo Sairo", "Sailaja Sailaja") find their
     * songs by their videos' full titles; the rest of the playlist's videos are counted, and what's left is logged.
     */
    @Test fun theThreeNamedAfter185AndWhatIsLeft() = runBlocking {
        val named = listOf("Dandiya India", "Sairo Sairo", "Sailaja Sailaja")
        val videos = ytm.playlistAll("PLEV4Wjn7aSFHRm-VkBxh2TJKwNyfUGqsO", 120).songs.filter { it.isVideo }
        val left = videos.filter { v ->
            val s = runCatching { ytm.songForVideo(v) }.getOrNull()
            log("${v.id} \"${v.title}\" / ${v.artistText} (${v.durationSec}s) -> ${s?.let { "${it.id} \"${it.title}\" ${it.artistText} (${it.album?.title}) ${it.durationSec}s" } ?: "none"}")
            s == null
        }
        log("left unmatched: ${left.size} of ${videos.size} videos: ${left.map { it.title }}")
        val namedRows = videos.filter { v -> named.any { v.title.equals(it, true) } }
        org.junit.Assert.assertEquals(3, namedRows.size)
        org.junit.Assert.assertTrue("still videos: ${left.map { it.title }.filter { it in named }}", namedRows.none { it in left })
    }

    @Test fun reportersPlaylists() = runBlocking {
        for (id in listOf("PLEwlFF5xcXHzubwrqTqBzexAGjhl_1LoA", "PLtLEpc9CWsdfxbsiuKYw02kpbCF1mnNcS")) {
            val songs = ytm.playlistAll(id, 60).songs
            val videos = songs.filter { it.isVideo }
            log("$id: ${songs.size} rows, ${videos.size} videos, ${songs.count { it.isEpisode }} read as episodes")
            songs.filter { it.isEpisode || it.title.contains("Iraga", true) }.forEach { log("  row ${it.id} \"${it.title}\" episode=${it.isEpisode} video=${it.isVideo} artists=${it.artistText}") }
            var found = 0
            for (v in videos.take(25)) {
                val s = runCatching { ytm.songForVideo(v) }.getOrNull()
                if (s != null) found++
                log("  ${v.id} \"${v.title}\" / ${v.artistText} (${v.durationSec}s) -> ${s?.let { "${it.id} \"${it.title}\" ${it.durationSec}s" } ?: "none"}")
            }
            log("  matched $found of ${videos.take(25).size}")
            val first = songs.firstOrNull() ?: continue
            val panel = runCatching { ytm.next(first.id, id) }.getOrNull()
            log("  next(first, playlist): ${panel?.songs?.size} rows, ${panel?.counterparts?.size} paired, ${panel?.songs?.count { it.isVideo }} videos")
        }
    }
}
