package com.vinz.music

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.8.7.6: DownloadsDataPageTest.fiveGroupsWithinTheBudgets failed in some full runs with "uncaught exceptions before
 * the test started". The WorkManager the class kept from its first test still had a Room refresh to run when that test
 * ended, and Robolectric had closed the database's connection by then: the refresh failed in Room's own scope, and the
 * next Compose test was failed for it. Here WorkManager's queue is slowed as on a loaded PC so the refresh is still
 * waiting when the first test ends; the second test starts clean only if the first one's WorkManager was let finish and
 * closed ([OwnWorkManager]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OwnWorkManagerTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule(order = 1) val workManager = OwnWorkManager { androidx.work.Configuration.Builder().setTaskExecutor(late).build() }

    /** A write that ends now, so Room's refresh for it is queued (and, from WorkManager's own thread, starts late). */
    @Test fun a_aWriteAtTheTestsEnd() {
        val wm = androidx.work.WorkManager.getInstance(RuntimeEnvironment.getApplication())
        wm.cancelUniqueWork("nothing").result.get()
    }

    @Test fun b_theNextTestStartsClean() {
        compose.setContent { }
        compose.waitForIdle()
    }

    companion object {
        private val n = AtomicInteger()
        private val pool = Executors.newCachedThreadPool { r -> Thread(r, "late-wm-${n.incrementAndGet()}").apply { isDaemon = true } }

        /** What WorkManager's own threads hand on waits 300 ms; what the test hands it runs at once. */
        private val late = Executor { r ->
            if (Thread.currentThread().name.startsWith("late-wm-")) pool.execute { Thread.sleep(300); r.run() } else pool.execute(r)
        }
    }
}
