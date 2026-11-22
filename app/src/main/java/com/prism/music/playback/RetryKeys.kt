package com.vinz.music.playback

/**
 * The playback service's once-only retries ("file:", "cache:", "codec:", "stuck:" and a song's own count), all cleared
 * at each song change (1.8.7, plan 4.2). Before, only the song's own count and "stuck:" were: an unreadable download or
 * cached part got its one retry once per service lifetime, so the same song broken again later (played again, or after
 * a skip back) was skipped at once. A retry itself plays on without a song change, so it can't loop.
 */
internal object RetryKeys {
    fun onSongChange(retries: MutableMap<String, Int>) = retries.clear()
}
