package com.vinz.music.ui.player

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * #280 (1.8.2): "Reopen on the last page". What the player had open when it was left, kept in plain preferences of its
 * own: it changes with every page turn, and a settings write there would pass through everything that watches settings.
 */
enum class PlayerPage {
    COVER, LYRICS, QUEUE;

    companion object {
        fun of(lyricsOpen: Boolean, queueOpen: Boolean): PlayerPage = when {
            queueOpen -> QUEUE
            lyricsOpen -> LYRICS
            else -> COVER
        }

        /** An episode has no lyrics, so a song's lyrics page opens as the cover there. */
        fun opensOn(saved: String?, episode: Boolean): PlayerPage {
            val page = entries.firstOrNull { it.name == saved } ?: COVER
            return if (page == LYRICS && episode) COVER else page
        }
    }
}

/**
 * A page asked for from outside the player, taken by it the next time it's on screen with that song: a recognized song
 * played opens on its lyrics (#215). [lyricsFor] is the song's id.
 */
object PlayerAsks {
    var lyricsFor by androidx.compose.runtime.mutableStateOf<String?>(null)
}

class PlayerPageMemory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("player_page", Context.MODE_PRIVATE)

    fun opensOn(episode: Boolean): PlayerPage = PlayerPage.opensOn(prefs.getString(KEY, null), episode)

    fun keep(page: PlayerPage) = prefs.edit().putString(KEY, page.name).apply()

    private companion object {
        const val KEY = "page"
    }
}
