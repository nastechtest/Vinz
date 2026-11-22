package com.vinz.music

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.ui.screens.RecentSearchChips
import com.vinz.music.ui.screens.RecentSearches
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #477: Search keeps the last 30 searches, shows 12 at first and the rest behind "Show all", and can clear them all. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp", application = Application::class)
class RecentSearchesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun thirtyAreKeptNewestFirstEachOnce() {
        var list = emptyList<String>()
        for (i in 1..40) list = RecentSearches.add(list, "q$i")
        assertEquals(30, list.size)
        assertEquals("q40", list.first())
        assertEquals("q11", list.last())
        // Searched again: to the top, not twice.
        list = RecentSearches.add(list, "q20")
        assertEquals(30, list.size)
        assertEquals("q20", list.first())
        assertEquals(1, list.count { it == "q20" })
    }

    /** The TV's Search writes the phone's list: one search there keeps all 30 (it once cut them to 12) and shows 12. */
    @Test fun aTvSearchKeepsThePhonesThirty() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("search", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("q", (1..30).joinToString("\n") { "phone $it" }).commit()
        com.vinz.music.ui.tv.TvSearchHistory.add(context, " tv ")
        val kept = com.vinz.music.ui.tv.TvSearchHistory.read(context)
        assertEquals(RecentSearches.KEEP, kept.size)
        assertEquals("tv", kept.first())
        assertEquals("phone 29", kept.last())
        assertEquals(RecentSearches.SHOWN, com.vinz.music.ui.tv.TvSearchHistory.shown(context).size)
        // Searched again on the TV: to the top, not twice.
        com.vinz.music.ui.tv.TvSearchHistory.add(context, "phone 5")
        assertEquals(listOf("phone 5", "tv", "phone 1"), com.vinz.music.ui.tv.TvSearchHistory.read(context).take(3))
        assertEquals(1, com.vinz.music.ui.tv.TvSearchHistory.read(context).count { it == "phone 5" })
    }

    @Test fun twelveShownThenShowAllThenClearAll() {
        val recents = (1..30).map { "search $it" }
        var cleared = false
        var removed: String? = null
        compose.setContent {
            VinzTheme(AppSettings(), artworkColor = null) {
                RecentSearchChips(recents, onPick = {}, onRemove = { removed = it }, onClearAll = { cleared = true })
            }
        }
        assertEquals(1, compose.onAllNodesWithText("search 12").fetchSemanticsNodes().size)
        assertTrue("the 13th waits behind Show all", compose.onAllNodesWithText("search 13").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Show all").performClick()
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("search 30").fetchSemanticsNodes().size)
        assertTrue(compose.onAllNodesWithText("Show all").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Clear all").performClick()
        assertTrue(cleared)
        assertEquals(null, removed)
    }

    @Test fun aFewHaveNoShowAll() {
        compose.setContent {
            VinzTheme(AppSettings(), artworkColor = null) {
                RecentSearchChips((1..5).map { "s$it" }, onPick = {}, onRemove = {}, onClearAll = {})
            }
        }
        assertTrue(compose.onAllNodesWithText("Show all").fetchSemanticsNodes().isEmpty())
        assertEquals(1, compose.onAllNodesWithText("Clear all").fetchSemanticsNodes().size)
    }
}
