package com.vinz.music

import android.os.Looper
import androidx.work.impl.close
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.robolectric.Shadows.shadowOf

/**
 * A container a test made for itself is closed as [VinzApp.onTerminate] closes the app's: its work is cancelled and
 * waited for, then its database closes. Without it the library's lists keep reading a database Robolectric has already
 * let go of, and the error ("Illegal connection pointer", from Room's invalidation tracker or a read on Dispatchers.IO)
 * lands in whichever Compose test runs next as "uncaught exceptions before the test started" (1.8.4: SettingsMapTest, then
 * ComposeTestThreadTest, in full runs).
 *
 * The wait runs the main thread's queue meanwhile (a cancelled coroutine that has to come back to the main thread can't
 * end while this holds it) and gives a read already inside SQLite time to come out, up to [waitMs]: a test can afford
 * longer than the app's own 300 ms.
 */
fun Lazy<AppContainer>.closeAfterTest(waitMs: Long = 5_000) {
    if (!isInitialized()) return
    val c = value
    val job = c.scope.coroutineContext[Job]
    c.scope.cancel()
    val main = shadowOf(Looper.getMainLooper())
    val until = System.currentTimeMillis() + waitMs
    while (job != null && !job.isCompleted && System.currentTimeMillis() < until) {
        runCatching { main.idle() }
        Thread.sleep(5)
    }
    // Room's own refresh runs on its executor, outside the container's scope: let one already started finish.
    runCatching { main.idle() }
    Thread.sleep(50)
    runCatching { c.db.close() }
}

/**
 * A WorkManager of the test's own: made before the test and closed when it ends, as a rule inside the Compose rule
 * (`@get:Rule(order = 1)`), so after the test's @After methods and while the Compose rule still listens.
 *
 * WorkManager keeps itself in statics, so one a test started outlived it (DownloadsDataPageTest made it in its first
 * test and kept it for the class). Robolectric closes every SQLite connection when a test ends, under WorkManager's Room
 * database; a write's invalidation refresh still pending then runs in Room's own coroutine scope, which nothing catches
 * (Room 2.7+), and its "Illegal connection pointer" is held for the next Compose test: "uncaught exceptions before the
 * test started" in DownloadsDataPageTest.fiveGroupsWithinTheBudgets, in some full runs (1.8.7, 1.8.7.5). Closed here, its
 * own queue is let run first, then its scope and its database close (the database waits for a refresh already started
 * and skips the rest).
 */
class OwnWorkManager(
    private val configuration: () -> androidx.work.Configuration = { androidx.work.Configuration.Builder().build() },
) : org.junit.rules.ExternalResource() {
    override fun before() {
        forget()
        androidx.work.WorkManager.initialize(org.robolectric.RuntimeEnvironment.getApplication(), configuration())
    }

    override fun after() {
        val wm = androidx.work.impl.WorkManagerImpl.getInstance() ?: return
        try {
            // Its start-up checks and any enqueue still waiting run on its serial queue: let them finish.
            val drained = java.util.concurrent.CountDownLatch(1)
            wm.workTaskExecutor.executeOnTaskThread { drained.countDown() }
            drained.await(5, java.util.concurrent.TimeUnit.SECONDS)
            closeWorkManager(wm)
        } finally {
            forget()
        }
    }

    private fun forget() {
        for (field in listOf("sDelegatedInstance", "sDefaultInstance")) {
            androidx.work.impl.WorkManagerImpl::class.java.getDeclaredField(field).apply { isAccessible = true }.set(null, null)
        }
    }
}

/** WorkManager's own close (`WorkManagerImpl.close()`, work-runtime 2.12): its scope cancelled and joined, then its database closed. */
@android.annotation.SuppressLint("RestrictedApi")
private fun closeWorkManager(wm: androidx.work.impl.WorkManagerImpl) = wm.close()
