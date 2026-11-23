package com.vinz.music.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Where one check of "Is playback working?" stands. */
enum class CheckStatus { WAITING, RUNNING, OK, PROBLEM, SKIPPED }

/**
 * What one check found. [detail] is a short note for the shared text and the value line (which client worked, how old
 * the sign-in is); [fix] the one plain sentence on what to do, on a problem; [after] the title of the check this one
 * waited on, when it was skipped because that one didn't work.
 */
data class CheckResult(val status: CheckStatus, val detail: String? = null, val fix: String? = null, val after: String? = null) {
    companion object {
        fun ok(detail: String? = null) = CheckResult(CheckStatus.OK, detail)
        fun problem(detail: String? = null, fix: String? = null) = CheckResult(CheckStatus.PROBLEM, detail, fix)
        fun skipped(detail: String? = null) = CheckResult(CheckStatus.SKIPPED, detail)
    }
}

/**
 * One check: [run] says what it found (or throws: a problem). It runs only once every check in [needs] (by [key])
 * worked; otherwise it is skipped. Past [timeoutMs] it is a problem ("took too long") and the rest go on. [fix] is the
 * sentence a problem shows when [run] gives none of its own.
 */
class SelfTestCheck(
    val key: String,
    val title: String,
    val fix: String,
    val needs: List<String> = emptyList(),
    val timeoutMs: Long = 15_000,
    val run: suspend () -> CheckResult,
)

data class SelfTestRow(val check: SelfTestCheck, val result: CheckResult)

/**
 * Settings → Backup & about → "Is playback working?" (1.8.7, plan §9): [checks] one after another, each with its own
 * timeout; one that fails or takes too long does not stop the rest, but the checks that need it are skipped. Pure: what
 * each check asks of the phone or of YouTube is in its [SelfTestCheck.run] (SelfTestChecks builds the real ones), so
 * tests hand in fakes. [work] is where the checks' own work runs (blocking network calls are fine there): a check past its
 * timeout is let go, not waited for.
 */
class SelfTest(private val checks: List<SelfTestCheck>, private val work: CoroutineDispatcher = Dispatchers.IO) {
    /** Runs every check in order; [onUpdate] gets the whole list each time a check starts or ends (and once at first). */
    suspend fun run(onUpdate: (List<SelfTestRow>) -> Unit = {}): List<SelfTestRow> {
        val rows = checks.map { SelfTestRow(it, CheckResult(CheckStatus.WAITING)) }.toMutableList()
        onUpdate(rows.toList())
        val done = HashMap<String, CheckStatus>()
        val scope = CoroutineScope(work + SupervisorJob())
        try {
            checks.forEachIndexed { i, c ->
                val blocked = c.needs.firstOrNull { done[it] != CheckStatus.OK }
                val result = if (blocked != null) {
                    CheckResult(CheckStatus.SKIPPED, after = checks.firstOrNull { it.key == blocked }?.title ?: blocked)
                } else {
                    rows[i] = SelfTestRow(c, CheckResult(CheckStatus.RUNNING))
                    onUpdate(rows.toList())
                    one(scope, c)
                }
                done[c.key] = result.status
                rows[i] = SelfTestRow(c, result)
                onUpdate(rows.toList())
            }
        } finally {
            scope.cancel()
        }
        return rows
    }

    private suspend fun one(scope: CoroutineScope, c: SelfTestCheck): CheckResult {
        val job = scope.async { c.run() }
        val result: CheckResult = try {
            val found = withTimeoutOrNull(c.timeoutMs) { job.await() }
            if (found == null) job.cancel()
            found ?: CheckResult.problem(TIMED_OUT)
        } catch (e: CancellationException) {
            job.cancel()
            // The sheet was closed: stop here. Else the check itself was cancelled inside: that is its problem.
            currentCoroutineContext().ensureActive()
            CheckResult.problem(e.javaClass.simpleName)
        } catch (e: Exception) {
            CheckResult.problem((e.message ?: e.javaClass.simpleName).take(160))
        }
        return if (result.status == CheckStatus.PROBLEM && result.fix == null) result.copy(fix = c.fix) else result
    }

    companion object {
        /** The detail of a check that took longer than its timeout. */
        const val TIMED_OUT = "timed out"
    }
}
