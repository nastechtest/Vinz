package com.vinz.music.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vinz.music.AppContainer
import com.vinz.music.R
import com.vinz.music.container
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.SongPlays
import com.vinz.music.tr
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The monthly recap's dates (1.8.5), on the phone's own clock: last month's recap shows on Home for the first
 * [CARD_DAYS] days of a month, and its notice (off unless turned on) comes on the 1st at 10:00.
 */
object RecapCalendar {
    /** Last month's recap is on Home from the 1st to this day of the month. */
    const val CARD_DAYS = 7
    private const val NOTICE_HOUR = 10

    /** [month] from its first millisecond to its last, in [zone] (as [RewindCalendar.bounds] does a year). */
    fun bounds(month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return start to end
    }

    /** The month whose recap is on Home at [now]: last month, during a month's first [CARD_DAYS] days; else none. */
    fun cardMonth(now: Long, zone: ZoneId = ZoneId.systemDefault()): YearMonth? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (today.dayOfMonth <= CARD_DAYS) YearMonth.from(today).minusMonths(1) else null
    }

    /**
     * When [month]'s recap is told about: 10:00 on the 1st of the month after. November's waits a day, for 2 December:
     * 1 December at 10:00 is "Your Rewind is ready" ([Rewinds.schedule]), and two notices from Vinz at once is one too many.
     */
    fun noticeAt(month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Long {
        val first = month.plusMonths(1).atDay(1)
        val day: LocalDate = if (first.monthValue == RewindCalendar.OPENS_MONTH && first.dayOfMonth == RewindCalendar.OPENS_DAY) first.plusDays(1) else first
        return day.atTime(NOTICE_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
    }

    /** The next recap to tell about after [now], and when: this month's if last month's notice time has passed. */
    fun nextNotice(now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<YearMonth, Long> {
        val last = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone).toLocalDate()).minusMonths(1)
        val at = noticeAt(last, zone)
        return if (now < at) last to at else last.plusMonths(1) to noticeAt(last.plusMonths(1), zone)
    }

    /** "October", in the listener's language. */
    fun name(month: YearMonth, locale: Locale = Locale.getDefault()): String =
        month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
}

/** Last month at a glance, for Home's card. */
data class RecapCard(val month: YearMonth, val totalMs: Long, val plays: Int, val topSong: SongPlays?, val topArtist: ArtistPlays?)

class Recaps(private val c: AppContainer) {
    /**
     * The recap card to show now, or null: outside a month's first week, or after a month too quiet to tell a story of
     * (as a light Replay story: under [StoryParts.LIGHT_MS] listened). Counts what Replay counts ("Count history from").
     */
    suspend fun card(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): RecapCard? {
        val month = RecapCalendar.cardMonth(now, zone) ?: return null
        return of(month, zone)
    }

    /** [month]'s recap at a glance, or null when it was too quiet. */
    suspend fun of(month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): RecapCard? {
        val stats = c.imports.replay()
        val (from, to) = RecapCalendar.bounds(month, zone)
        val total = stats.totalMs(from, to)
        if (total <= StoryParts.LIGHT_MS) return null
        return RecapCard(month, total, stats.count(from, to), stats.topSongs(from, to, 1).firstOrNull(), stats.topArtists(from, to, 1).firstOrNull())
    }

    companion object {
        /** The notice for the next recap ([RecapCalendar.nextNotice]); always lined up, it posts only if the switch is on then. */
        fun schedule(context: Context, now: Long = System.currentTimeMillis(), handled: YearMonth? = null) {
            val (month, at) = next(now, handled)
            WorkManager.getInstance(context).enqueueUniqueWork(
                "recap_$month", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RecapWorker>().setInitialDelay((at - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .setInputData(androidx.work.workDataOf(RecapWorker.MONTH to month.toString())).build(),
            )
        }

        /**
         * The notice to line up next: at app start ([handled] null) the calendar's next one; after [handled]'s worker
         * ran, always the month after it. Not the calendar's then: a time zone or clock that moved back can make it
         * name [handled] again, whose unique work is the one running, so KEEP would drop it and the chain would stop.
         */
        fun next(now: Long, handled: YearMonth?, zone: ZoneId = ZoneId.systemDefault()): Pair<YearMonth, Long> =
            if (handled == null) RecapCalendar.nextNotice(now, zone)
            else handled.plusMonths(1).let { it to RecapCalendar.noticeAt(it, zone) }

        /**
         * Whether [month]'s notice goes out: switched on, a month with a recap ([hasRecap]), and later than the last
         * month told ([toldMonth]): a time zone or clock moved back lines the same month up again (as [Rewinds.tells]).
         */
        fun tells(month: YearMonth, on: Boolean, toldMonth: YearMonth?, hasRecap: Boolean): Boolean =
            on && hasRecap && (toldMonth == null || month > toldMonth)

        /** The deep link that opens [month]'s story on Replay. */
        fun link(month: YearMonth) = "vinz://recap/$month"
    }
}

/** The 1st of the month: tells the listener last month's recap is ready (if turned on, and they listened), then lines up the next. */
class RecapWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val month = inputData.getString(MONTH)?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        val prefs = applicationContext.getSharedPreferences("recap", Context.MODE_PRIVATE)
        val told = prefs.getString(TOLD, null)?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        if (month != null && c.settings.current.behavior.recapNotify &&
            Recaps.tells(month, on = true, toldMonth = told, hasRecap = runCatching { c.recaps.of(month) }.getOrNull() != null)
        ) {
            prefs.edit().putString(TOLD, month.toString()).apply()
            Notifications.post(
                applicationContext, Notifications.REPLAY_NEWS, 5200 + month.monthValue,
                tr(R.string.recap_ready_title, RecapCalendar.name(month)), tr(R.string.recap_ready_text), Recaps.link(month),
            )
        }
        // The month after this one, under its own name: never this running work's (see Recaps.next).
        Recaps.schedule(applicationContext, handled = month)
        return Result.success()
    }

    companion object {
        const val MONTH = "month"
        /** The last month whose notice went out ("2026-10"). */
        private const val TOLD = "told_month"
    }
}
