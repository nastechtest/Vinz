package com.vinz.music

import androidx.room.Room
import com.prism.music.data.db.PrismDatabase
import com.vinz.music.data.imports.CountedPlays
import com.vinz.music.data.imports.ImportSource
import com.vinz.music.data.imports.VinzPlays
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * The listening clock and calendar stay quick on a big history ([BigHistory]: 60,000 Vinz plays over three years and
 * 10,000 imported Spotify plays): each query under [MAX_MS] on this JVM, for a year, the whole history and all time, in
 * a zone with daylight saving time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.NATIVE)
class ListeningClockSpeedTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PrismDatabase::class.java).allowMainThreadQueries().build()
    private val oldZone: TimeZone = TimeZone.getDefault()

    @After fun close() {
        TimeZone.setDefault(oldZone)
        db.close()
    }

    private suspend fun <T> timed(block: suspend () -> T): Pair<T, Long> {
        val t = System.nanoTime()
        val v = block()
        return v to (System.nanoTime() - t) / 1_000_000
    }

    @Test fun clockQueriesAreQuickOnABigHistory() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        val (filled, fillMs) = timed { BigHistory.fill(db) }
        val year = BigHistory.END - 365L * 86_400_000L
        val timings = StringBuilder("fill ${fillMs} ms (${filled.vinzPlays} + ${filled.importedPlays} imported plays, ${filled.songs} songs)\n")
        val slow = ArrayList<String>()
        for ((name, stats) in listOf("Vinz" to VinzPlays(db.plays()), "Vinz+Spotify" to CountedPlays(listOf(ImportSource.SPOTIFY), db.counted(), db.plays()))) {
            for ((range, span) in listOf("year" to (year to BigHistory.END), "history" to (BigHistory.START to BigHistory.END), "all time" to (0L to Long.MAX_VALUE))) {
                val (a, b) = span
                val (hours, hMs) = timed { stats.byHour(a, b) }
                val (days, dMs) = timed { stats.byDay(a, b) }
                val (top, tMs) = timed { stats.topSongsInHour(a, b, 21, 10) }
                timings.append("$name, $range: byHour $hMs ms (${hours.size} rows), byDay $dMs ms (${days.size} rows), topSongsInHour $tMs ms\n")
                listOf("byHour" to hMs, "byDay" to dMs, "topSongsInHour" to tMs).filter { it.second >= MAX_MS }.forEach { slow += "$name $range ${it.first}" }
                assertEquals(stats.count(a, b), hours.sumOf { it.plays })
                assertEquals(stats.totalMs(a, b), days.sumOf { it.ms })
                assertEquals(10, top.size)
            }
        }
        println("[clock] $timings")
        assertTrue("slower than $MAX_MS ms: $slow\n$timings", slow.isEmpty())
    }

    companion object { const val MAX_MS = 500L }
}
