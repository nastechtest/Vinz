package com.vinz.music.data

import com.vinz.music.R
import com.vinz.music.data.SelfTestChecks.Premium
import com.vinz.music.data.innertube.YouTubeSeen
import com.vinz.music.tr
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * 1.8.7, "Is playback working?" (plan §9): the runner (in order, one failing check doesn't stop the rest, the checks
 * that need it are skipped, each has a timeout), each of the twelve checks with a fake probe, ok and with a problem,
 * and the text "Share the result" adds to the diagnostics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SelfTestTest {
    // ------------------------------------------------------------------ The runner

    private fun check(key: String, needs: List<String> = emptyList(), timeoutMs: Long = 5_000, run: suspend () -> CheckResult) =
        SelfTestCheck(key, "Title $key", "Fix $key", needs, timeoutMs, run)

    @Test fun checksRunInOrderAndAProblemDoesNotStopTheRest() = runBlocking {
        val order = ArrayList<String>()
        val rows = SelfTest(listOf(
            check("a") { order += "a"; CheckResult.ok() },
            check("b") { order += "b"; throw IOException("no route") },
            check("c") { order += "c"; CheckResult.ok("fine") },
        )).run()
        assertEquals(listOf("a", "b", "c"), order)
        assertEquals(listOf(CheckStatus.OK, CheckStatus.PROBLEM, CheckStatus.OK), rows.map { it.result.status })
        // A check that throws is a problem with its message, and its own sentence on what to do.
        assertEquals(CheckResult(CheckStatus.PROBLEM, "no route", "Fix b"), rows[1].result)
        assertEquals("fine", rows[2].result.detail)
    }

    @Test fun checksThatNeedAFailedOneAreSkipped() = runBlocking {
        var ranAfter = false
        val rows = SelfTest(listOf(
            check("online") { CheckResult.problem() },
            check("youtube", listOf("online")) { ranAfter = true; CheckResult.ok() },
            check("stream", listOf("youtube")) { ranAfter = true; CheckResult.ok() },
            check("battery") { CheckResult.ok() },
        )).run()
        assertEquals(false, ranAfter)
        assertEquals(listOf(CheckStatus.PROBLEM, CheckStatus.SKIPPED, CheckStatus.SKIPPED, CheckStatus.OK), rows.map { it.result.status })
        // Each says which check it waited on.
        assertEquals("Title online", rows[1].result.after)
        assertEquals("Title youtube", rows[2].result.after)
        // A skipped check is not "worked": what needs it is skipped too. A problem gets its check's sentence.
        assertEquals("Fix online", rows[0].result.fix)
    }

    @Test fun aCheckPastItsTimeoutIsAProblemAndTheRestGoOn() = runBlocking {
        val started = System.currentTimeMillis()
        val rows = SelfTest(listOf(
            check("slow", timeoutMs = 100) { awaitCancellation() },
            check("blocking", timeoutMs = 100) { Thread.sleep(3_000); CheckResult.ok() },
            check("next", listOf("slow")) { CheckResult.ok() },
            check("last") { CheckResult.ok() },
        )).run()
        assertTrue("not waited for", System.currentTimeMillis() - started < 2_500)
        assertEquals(CheckResult(CheckStatus.PROBLEM, SelfTest.TIMED_OUT, "Fix slow"), rows[0].result)
        assertEquals(CheckResult(CheckStatus.PROBLEM, SelfTest.TIMED_OUT, "Fix blocking"), rows[1].result)
        assertEquals(CheckStatus.SKIPPED, rows[2].result.status)
        assertEquals(CheckStatus.OK, rows[3].result.status)
    }

    @Test fun progressIsShownAsChecksGo() = runBlocking {
        val seen = ArrayList<List<CheckStatus>>()
        SelfTest(listOf(check("a") { CheckResult.ok() }, check("b") { CheckResult.problem() })).run { rows -> seen += rows.map { it.result.status } }
        val w = CheckStatus.WAITING; val r = CheckStatus.RUNNING
        assertEquals(listOf(
            listOf(w, w), listOf(r, w), listOf(CheckStatus.OK, w), listOf(CheckStatus.OK, r), listOf(CheckStatus.OK, CheckStatus.PROBLEM),
        ), seen)
    }

    // ------------------------------------------------------------------ The twelve checks, with a fake probe

    /** Everything working, signed in, getting Premium; each test breaks one thing. */
    private class Fake : SelfTestChecks.Probe {
        var online = true
        var youtube = true
        var signedIn = true
        var savedAt: Long? = 1_762_000_000_000L
        var seen = YouTubeSeen(true, "member")
        var link: SelfTestChecks.StreamLink? = SelfTestChecks.StreamLink("https://example.invalid/a", 251, 130, "IOS")
        var read = SelfTestChecks.Read(206, 16_384)
        var solver = true
        var bot = false
        var premium = Premium.GETTING
        var lyrics = true
        var output = SelfTestChecks.Output("Bluetooth A2DP 'Buds' out #3", false)
        var background = true
        var notifications = true
        val asked = ArrayList<String>()

        override fun online() = online
        override suspend fun youtube() { asked += "youtube"; if (!youtube) throw IOException("HTTP 503") }
        override fun signedIn() = signedIn
        override fun signedInAt() = savedAt
        override suspend fun accountSeen(): YouTubeSeen { asked += "account"; return seen }
        override suspend fun streamLink() = link?.also { asked += "stream" } ?: throw IOException("no playable audio")
        override suspend fun readStart(link: SelfTestChecks.StreamLink) = read.also { asked += "read" }
        override suspend fun solver() { asked += "solver"; if (!solver) throw IOException("Player JS failed") }
        override suspend fun botAsked() = bot.also { asked += "bot" }
        override fun premium() = premium
        override suspend fun lyrics() = lyrics.also { asked += "lyrics" }
        override fun output() = output
        override fun backgroundAllowed() = background
        override fun notificationsAllowed() = notifications
    }

    private fun run(p: Fake) = runBlocking { SelfTest(SelfTestChecks.checks(p)).run() }
    private fun List<SelfTestRow>.of(key: String) = single { it.check.key == key }.result

    @Test fun everythingWorking() {
        val rows = run(Fake())
        assertEquals(12, rows.size)
        assertEquals(listOf(
            SelfTestChecks.ONLINE, SelfTestChecks.YOUTUBE, SelfTestChecks.SIGN_IN, SelfTestChecks.STREAM, SelfTestChecks.FIRST_SECOND,
            SelfTestChecks.SOLVER, SelfTestChecks.BOT, SelfTestChecks.PREMIUM, SelfTestChecks.LYRICS, SelfTestChecks.OUTPUT,
            SelfTestChecks.BATTERY, SelfTestChecks.NOTIFICATIONS,
        ), rows.map { it.check.key })
        assertTrue(rows.map { "${it.check.key} ${it.result}" }.toString(), rows.all { it.result.status == CheckStatus.OK })
        // Which way worked, and how old the sign-in is, are in the details.
        assertEquals("itag 251, 130 kbps, client IOS", rows.of(SelfTestChecks.STREAM).detail)
        assertEquals("HTTP 206, 16384 bytes, client IOS", rows.of(SelfTestChecks.FIRST_SECOND).detail)
        val signIn = rows.of(SelfTestChecks.SIGN_IN).detail!!
        assertTrue(signIn, signIn.startsWith(tr(R.string.selftest_signed_in_on, "").trim()) && signIn.endsWith("signed in yes, membership member"))
        assertEquals("Bluetooth A2DP 'Buds' out #3", rows.of(SelfTestChecks.OUTPUT).detail)
    }

    private fun problem(key: String, fix: Int, change: Fake.() -> Unit): List<SelfTestRow> {
        val rows = run(Fake().apply(change))
        val r = rows.of(key)
        assertEquals("$key: ${r}", CheckStatus.PROBLEM, r.status)
        assertEquals(tr(fix), r.fix)
        return rows
    }

    @Test fun offlineSkipsEverythingThatGoesOut() {
        val p = Fake().apply { online = false }
        val rows = run(p)
        assertEquals(CheckStatus.PROBLEM, rows.of(SelfTestChecks.ONLINE).status)
        assertEquals(tr(R.string.selftest_fix_online), rows.of(SelfTestChecks.ONLINE).fix)
        for (k in listOf(SelfTestChecks.YOUTUBE, SelfTestChecks.SIGN_IN, SelfTestChecks.STREAM, SelfTestChecks.FIRST_SECOND, SelfTestChecks.SOLVER, SelfTestChecks.BOT, SelfTestChecks.PREMIUM, SelfTestChecks.LYRICS)) {
            assertEquals(k, CheckStatus.SKIPPED, rows.of(k).status)
        }
        assertEquals("nothing was asked", emptyList<String>(), p.asked)
        // What's on the phone is still checked.
        for (k in listOf(SelfTestChecks.OUTPUT, SelfTestChecks.BATTERY, SelfTestChecks.NOTIFICATIONS)) assertEquals(k, CheckStatus.OK, rows.of(k).status)
    }

    @Test fun youTubeUnreachableSkipsTheYouTubeChecks() {
        val rows = problem(SelfTestChecks.YOUTUBE, R.string.selftest_fix_youtube) { youtube = false }
        assertEquals("HTTP 503", rows.of(SelfTestChecks.YOUTUBE).detail)
        for (k in listOf(SelfTestChecks.SIGN_IN, SelfTestChecks.STREAM, SelfTestChecks.FIRST_SECOND, SelfTestChecks.SOLVER, SelfTestChecks.BOT, SelfTestChecks.PREMIUM)) {
            assertEquals(k, CheckStatus.SKIPPED, rows.of(k).status)
        }
        // Lyrics come from elsewhere: still asked.
        assertEquals(CheckStatus.OK, rows.of(SelfTestChecks.LYRICS).status)
    }

    @Test fun signIn() {
        problem(SelfTestChecks.SIGN_IN, R.string.selftest_fix_signin) { seen = YouTubeSeen(false, null) }
        // Signed out: not a problem, and Premium isn't asked about either.
        val out = run(Fake().apply { signedIn = false })
        assertEquals(CheckResult.skipped(tr(R.string.selftest_not_signed_in)), out.of(SelfTestChecks.SIGN_IN))
        assertEquals(CheckStatus.SKIPPED, out.of(SelfTestChecks.PREMIUM).status)
        // YouTube not saying either way, and an old sign-in with no date: still works.
        val unsaid = run(Fake().apply { seen = YouTubeSeen(null, null); savedAt = null }).of(SelfTestChecks.SIGN_IN)
        assertEquals(CheckResult.ok("signed in not said"), unsaid)
    }

    @Test fun streamLinkAndItsFirstSecond() {
        val rows = problem(SelfTestChecks.STREAM, R.string.selftest_fix_stream) { link = null }
        assertEquals("no playable audio", rows.of(SelfTestChecks.STREAM).detail)
        assertEquals(CheckStatus.SKIPPED, rows.of(SelfTestChecks.FIRST_SECOND).status)
        // The 403 check: refused, or nothing came.
        val refused = problem(SelfTestChecks.FIRST_SECOND, R.string.selftest_fix_first_second) { read = SelfTestChecks.Read(403, 0) }
        assertEquals("HTTP 403, 0 bytes, client IOS", refused.of(SelfTestChecks.FIRST_SECOND).detail)
        problem(SelfTestChecks.FIRST_SECOND, R.string.selftest_fix_first_second) { read = SelfTestChecks.Read(200, 0) }
    }

    @Test fun solverAndBotCheck() {
        problem(SelfTestChecks.SOLVER, R.string.selftest_fix_solver) { solver = false }
        problem(SelfTestChecks.BOT, R.string.selftest_fix_bot) { bot = true }
    }

    @Test fun premium() {
        // Premium's own format came with the link: that's it, whatever was known before.
        val direct = run(Fake().apply { link = link!!.copy(itag = 774); premium = Premium.NOT_KNOWN }).of(SelfTestChecks.PREMIUM)
        assertEquals(CheckResult.ok("itag 774"), direct)
        problem(SelfTestChecks.PREMIUM, R.string.selftest_fix_premium) { premium = Premium.MISSING }
        assertEquals(CheckResult.skipped(tr(R.string.selftest_premium_off)), run(Fake().apply { premium = Premium.NOT_ASKED }).of(SelfTestChecks.PREMIUM))
        assertEquals(CheckResult.skipped(tr(R.string.selftest_premium_not_known)), run(Fake().apply { premium = Premium.NOT_KNOWN }).of(SelfTestChecks.PREMIUM))
    }

    @Test fun whatIsOnThePhone() {
        problem(SelfTestChecks.LYRICS, R.string.selftest_fix_lyrics) { lyrics = false }
        val silent = problem(SelfTestChecks.OUTPUT, R.string.selftest_fix_output) { output = SelfTestChecks.Output("speaker 'Phone' out #2", true) }
        assertEquals("speaker 'Phone' out #2", silent.of(SelfTestChecks.OUTPUT).detail)
        problem(SelfTestChecks.BATTERY, R.string.selftest_fix_battery) { background = false }
        problem(SelfTestChecks.NOTIFICATIONS, R.string.selftest_fix_notifications) { notifications = false }
    }

    /** The song every check asks about is the one the live tests ask about (a stable, popular song). */
    @Test fun theSongIsAKnownOne() {
        assertEquals("bpOSxM0rNPM", SelfTestChecks.SONG_ID)
        assertTrue(java.io.File("src/test/java/com/vinz/music/WordLyricsLiveTest.kt").readText().contains("\"${SelfTestChecks.SONG_ID}\""))
    }

    // ------------------------------------------------------------------ The words

    @Test fun valueLinesAndTheSharedText() {
        val c1 = SelfTestCheck("a", "Online", "Connect.") { CheckResult.ok() }
        val c2 = SelfTestCheck("b", "YouTube", "Try another network.") { CheckResult.ok() }
        val c3 = SelfTestCheck("c", "Sign-in", "Sign in.") { CheckResult.ok() }
        val c4 = SelfTestCheck("d", "Premium", "Sign out.") { CheckResult.ok() }
        val rows = listOf(
            SelfTestRow(c1, CheckResult.ok("fine")),
            SelfTestRow(c2, CheckResult.problem("HTTP 503", "Try another network.")),
            SelfTestRow(c3, CheckResult(CheckStatus.SKIPPED, after = "YouTube")),
            SelfTestRow(c4, CheckResult.skipped("Not signed in")),
        )
        // On the sheet: the result, and on a problem the sentence on what to do under it.
        assertEquals(tr(R.string.selftest_ok), SelfTestText.value(rows[0].result))
        assertEquals(tr(R.string.selftest_problem) + "\nTry another network.", SelfTestText.value(rows[1].result))
        assertEquals(tr(R.string.selftest_skipped_after, "YouTube"), SelfTestText.value(rows[2].result))
        assertEquals(tr(R.string.selftest_skipped) + ": Not signed in", SelfTestText.value(rows[3].result))
        assertEquals(tr(R.string.selftest_checking), SelfTestText.value(CheckResult(CheckStatus.RUNNING)))
        // Shared: one line a check, its details and what to do.
        val text = SelfTestText.report(rows, at = 1_762_000_000_000L)
        val lines = text.lines()
        assertTrue(lines[0], lines[0].startsWith(tr(R.string.selftest_title) + " ("))
        assertEquals("1. Online: ${tr(R.string.selftest_ok)} (fine)", lines[1])
        assertEquals("2. YouTube: ${tr(R.string.selftest_problem)} (HTTP 503) Try another network.", lines[2])
        assertEquals("3. Sign-in: ${tr(R.string.selftest_skipped_after, "YouTube")}", lines[3])
        assertEquals("4. Premium: ${tr(R.string.selftest_skipped)} (Not signed in)", lines[4])
        assertEquals(5, lines.size)
    }
}
