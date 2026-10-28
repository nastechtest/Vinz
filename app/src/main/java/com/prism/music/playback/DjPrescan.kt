package com.prism.music.playback

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prism.music.container
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * DJ mode's head start: once a day, on Wi-Fi while the phone charges, the songs the listener plays most and likes get
 * their quick scan ([MixTempo.quickScan]), so a DJ set of their own music starts in key order rather than reading as it
 * goes. Up to [MAX_SONGS] a run, for at most [MAX_MINUTES]; already scanned songs cost nothing.
 */
class DjPrescanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val now = System.currentTimeMillis()
        val ids = (c.db.plays().mostPlayedIds(now - 90L * 86_400_000, MAX_SONGS) + c.db.songs().liked().map { it.id })
            .distinct()
            .filter { !it.startsWith(com.prism.music.data.model.Song.LOCAL_PREFIX) && !c.mixTempo.keyed(it) }
            .take(MAX_SONGS)
        if (ids.isEmpty()) return Result.success()
        val started = System.currentTimeMillis()
        val done = java.util.concurrent.atomic.AtomicInteger()
        withTimeoutOrNull(MAX_MINUTES * 60_000L) {
            coroutineScope {
                ids.map { id -> async { if (c.mixTempo.quickScan(id, network = true) == MixTempo.QuickResult.KEYED) done.incrementAndGet() } }.awaitAll()
            }
        }
        Log.i("PrismDj", "pre-scanned ${done.get()} of ${ids.size} songs in ${(System.currentTimeMillis() - started) / 1000} s")
        return Result.success()
    }

    companion object {
        private const val MAX_SONGS = 200
        private const val MAX_MINUTES = 20

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<DjPrescanWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("dj_prescan", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
