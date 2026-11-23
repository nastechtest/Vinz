package com.vinz.music

import com.vinz.music.ui.screens.loadOrEmpty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How Replay's 1.8.5 sections (clock, calendar, milestones) keep what they read. Found on the A16 in 1.8.5's check: a
 * fling past the listening clock while it was reading left it gone until the period changed, because the cancelled
 * read was kept as "nothing to show".
 */
@RunWith(RobolectricTestRunner::class)
class ReplaySectionLoadTest {
    @Test fun aReadThatWasCancelledKeepsNothingSoTheSectionReadsAgain() = runBlocking {
        var held: List<Int>? = null
        val started = CompletableDeferred<Unit>()
        // As the sections do it: the read's result goes into what the screen holds.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            held = loadOrEmpty(emptyList()) { started.complete(Unit); awaitCancellation() }
        }
        started.await()
        job.cancelAndJoin() // the section scrolled away
        assertTrue(job.isCancelled)
        assertNull("a cancelled read must not be kept as empty", held)
        // Back on screen: it reads again, and this time it's kept.
        held = loadOrEmpty(emptyList()) { listOf(1, 2, 3) }
        assertEquals(listOf(1, 2, 3), held)
    }

    @Test fun aReadThatFailsGivesTheEmptySection() = runBlocking {
        assertEquals(emptyList<Int>(), loadOrEmpty(emptyList<Int>()) { error("the database said no") })
    }
}
