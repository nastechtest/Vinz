package com.vinz.music

import com.vinz.music.data.innertube.InnerTube
import com.vinz.music.data.innertube.YouTubeMusic
import com.vinz.music.data.model.SongItem
import com.vinz.music.data.model.quickPicks
import com.vinz.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/** Home's Quick picks in another language (issue #11: Vinz showed recent plays instead of YouTube's picks). */
@Category(LiveNetwork::class)
class QuickPicksLiveTest {
    private val before = Locale.getDefault()

    @After
    fun restore() = Locale.setDefault(before)

    @Test
    fun quickPicksAreFoundInSpanish() = runBlocking {
        Locale.setDefault(Locale.forLanguageTag("es-MX"))
        var settings = AppSettings()
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))
        val shelves = ytm.home().shelves
        println("shelves: " + shelves.joinToString { "${it.title}${if (it.rows) " (rows)" else ""}${if (it.startsRadios) " (radios)" else ""}" })
        val picks = shelves.quickPicks()
        assertNotNull(picks)
        // In Spanish it isn't called "Quick picks", so it was found by what it is: song rows that each start a radio.
        assertNotEquals("Quick picks", picks!!.title)
        assertTrue(picks.startsRadios)
        assertTrue(picks.items.count { it is SongItem } >= 8)
    }
}
