package com.vinz.music.data.imports

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vinz.music.R
import com.vinz.music.container
import com.vinz.music.data.Notifications
import com.vinz.music.tr
import java.util.concurrent.TimeUnit

/** Says when another [every] songs have been done since it last said so (the count may start anywhere, or start over). */
class PlaceSteps(private val every: Int) {
    private var last = -1
    fun due(done: Int): Boolean {
        if (last < 0 || done < last) { last = done; return false }
        if (done - last < every) return false
        last = done
        return true
    }
}

/**
 * Matches imported songs to YouTube Music in the background (1.8.1): on Wi-Fi unless the listener allows mobile data,
 * a few minutes at a time (it carries on where it stopped, after a restart too), a quiet notification saying how far
 * it's got. Songs nothing was found for are tried again weekly ([enqueue]'s weekly run).
 */
class ImportMatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val until = System.currentTimeMillis() + RUN_MS
        // What is matched goes where it belongs as the run goes, not only at its end: Spotify's mirrored playlists and
        // waiting likes fill while the listener is anywhere in the app or out of it (only when Spotify is connected;
        // read from its sign-in preferences without building it).
        val spotifyOn = runCatching {
            val auth = applicationContext.getSharedPreferences(com.vinz.music.data.spotify.SpotifyStore.AUTH_PREFS, Context.MODE_PRIVATE)
            com.vinz.music.data.spotify.SpotifyStore.connectedIn(auth)
        }.getOrDefault(false)
        val steps = PlaceSteps(PLACE_EVERY)
        val finished = runCatching {
            c.imports.matchSome(until, { isStopped }) { done, total ->
                setProgress(workDataOf(DONE to done, TOTAL to total))
                notify(done, total)
                if (spotifyOn && steps.due(done)) runCatching { c.spotify.placeMatched() }
            }
        }.getOrDefault(false)
        cancelNotice()
        if (spotifyOn) runCatching { c.spotify.placeMatched() }
        // More to do (the time ran out, or the network went): WorkManager runs it again shortly.
        return if (finished) Result.success() else Result.retry()
    }

    private fun notify(done: Int, total: Int) {
        val ctx = applicationContext
        if (!Notifications.allowed(ctx) || total <= 0) return
        NotificationManagerCompat.from(ctx).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(tr(R.string.imp_title)).setDescription(tr(R.string.imp_channel_desc)).build()
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.imp_matching_notice))
            .setContentText(tr(R.string.imp_matching_n_of_n, done, total))
            .setProgress(total, done.coerceAtMost(total), false)
            .setOnlyAlertOnce(true).setSilent(true).setOngoing(false)
            .setContentIntent(Notifications.openIntent(ctx, "vinz://bring", NOTICE_ID))
            .build()
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(ctx).notify(CHANNEL, NOTICE_ID, n) }
    }

    private fun cancelNotice() = runCatching { NotificationManagerCompat.from(applicationContext).cancel(CHANNEL, NOTICE_ID) }

    companion object {
        private const val NAME = "import_match"
        private const val WEEKLY = "import_match_weekly"
        private const val CHANNEL = "imports"
        private const val NOTICE_ID = 4_901
        /** One run's share of time; WorkManager stops a run at 10 minutes. */
        private const val RUN_MS = 8 * 60_000L
        /** Matched songs are placed (Spotify playlists, likes) every this many songs of a run. */
        const val PLACE_EVERY = 20
        const val DONE = "done"
        const val TOTAL = "total"

        private fun constraints(mobile: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (mobile) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Starts matching (or picks up its network rule again), and keeps the weekly retry lined up. */
        fun enqueue(context: Context, mobile: Boolean) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniqueWork(
                NAME, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ImportMatchWorker>().setConstraints(constraints(mobile))
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build(),
            )
            wm.enqueueUniquePeriodicWork(WEEKLY, ExistingPeriodicWorkPolicy.UPDATE, weekly(mobile))
        }

        /** The weekly retry: its first run a week on (at once, it would match beside the one-time run just started). */
        internal fun weekly(mobile: Boolean) = PeriodicWorkRequestBuilder<ImportMatchWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(7, TimeUnit.DAYS).setConstraints(constraints(mobile)).build()

        fun workName() = NAME
    }
}
