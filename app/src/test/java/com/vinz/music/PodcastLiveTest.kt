package com.vinz.music

import com.vinz.music.data.innertube.InnerTube
import com.vinz.music.data.innertube.SearchFilter
import com.vinz.music.data.innertube.YouTubeMusic
import com.vinz.music.data.model.PodcastItem
import com.vinz.music.data.model.SongItem
import com.vinz.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Podcasts, live against YouTube Music (signed out).
 *
 * Network-dependent, so it is deliberately named *LiveTest: scripts/ci/run-unit-tests.sh skips that
 * pattern, and a YouTube Music hiccup or a change upstream therefore cannot fail a merge or block a
 * release. Run it by hand with
 *   ./gradlew :app:testDebugUnitTest --tests com.vinz.music.PodcastLiveTest
 * The parsing those tests lean on is covered offline in PodcastTest.
 */
class PodcastLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    @Test
    fun podcastsPageHasShowsAndEpisodes() = runBlocking {
        val shelves = ytm.podcastsHome()
        shelves.take(4).forEach { s -> println("${s.title}: ${s.items.take(3).joinToString { it.title }}") }
        val items = shelves.flatMap { it.items }
        assertTrue("no shows", items.any { it is PodcastItem })
        assertTrue("no episodes", items.any { it is SongItem && it.song.isEpisode && !it.song.isVideo })
    }

    @Test
    fun showPageListsEpisodesAndMore() = runBlocking {
        val show = ytm.search("joe rogan experience", SearchFilter.PODCASTS).items.filterIsInstance<PodcastItem>().first()
        println("show ${show.id} ${show.title} — ${show.subtitle}")
        assertTrue(show.id.startsWith("MPSP"))
        val page = ytm.podcast(show.id)
        page.episodes.take(3).forEach { println("${it.song.id}  ${it.song.title}  ${it.date}  ${it.song.durationSec}s") }
        assertTrue("no episodes", page.episodes.isNotEmpty())
        assertTrue(page.episodes.all { it.song.isEpisode && it.song.podcastId == page.id })
        assertTrue("no lengths", page.episodes.any { it.song.durationSec > 600 })
        val token = page.continuation
        if (token != null) {
            val (more, _) = ytm.podcastEpisodes(token, show)
            println("next page: ${more.size} episodes")
            assertTrue(more.isNotEmpty())
            assertTrue(more.none { m -> page.episodes.any { it.song.id == m.song.id } })
        }
    }

    @Test
    fun episodeSearchFindsEpisodesWithTheirShow() = runBlocking {
        val eps = ytm.search("joe rogan", SearchFilter.EPISODES).items.filterIsInstance<SongItem>().map { it.song }
        eps.take(3).forEach { println("${it.id}  ${it.title} — ${it.artistText} (${it.podcastId})") }
        assertTrue(eps.isNotEmpty())
        assertTrue(eps.all { it.isEpisode })
        assertTrue(eps.any { it.podcastId?.startsWith("MPSP") == true })
    }
}
