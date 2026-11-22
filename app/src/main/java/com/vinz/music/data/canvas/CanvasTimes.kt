package com.vinz.music.data.canvas

/**
 * A song's way from "the player asked for its loop" to "the loop's first frame drawn" (1.8.7), step by step, for the
 * live test and [ClipRepository.lastTimes]. Each step is counted from the end of the step before, so the steps add up to
 * the whole wait, to the millisecond. Cheap: a clock read per step, a few songs remembered, nothing stored or sent.
 *
 * The steps, in order (a step that didn't happen is left out):
 * - cover: the player looking the song's animated cover up before it asked for the loop;
 * - wait: waiting for the song's lock (the video being found ahead, or another asker working on the same song);
 * - find: which video, which seconds (the details say what of it took long);
 * - fetch: the video's player answer and the fragments (or the kept file);
 * - decode: the frames read and checked ([ClipRefiner]; a second part tried counts here too);
 * - write: the row updated;
 * - handed: from the repository's answer to the loop's player being given it (the screen redrawn, a player made);
 * - ready: the player preparing the file until the decoder put the first frame out;
 * - drawn: that frame reaching the screen.
 */
class CanvasTimes(private val clock: () -> Long = System::nanoTime) {

    /** One song's wait. Steps come from several threads, one after another (never two at once for one run). */
    inner class Run internal constructor(val songId: String) {
        internal val began = clock()
        private val marks = ArrayList<Pair<String, Long>>()
        private val notes = LinkedHashMap<String, String>()
        @Volatile var ended = false; private set

        /** The step [name] ends now (a step that comes again, as a second part's fetch, adds to the first). */
        fun step(name: String) = synchronized(this) { if (!ended) marks += name to clock() }

        /** A few words about a step, shown after its time ("network: answer 210 ms, ..."); a step noted again keeps both. */
        fun note(name: String, text: String) = synchronized(this) {
            if (!ended) notes[name] = notes[name]?.let { "$it; then $text" } ?: text
        }

        /** The run is over: [how] says how ("shown", "no loop"). The line it leaves is [last]. */
        /** No loop was asked for, and why (a line for the diagnostics log, not shown in the app). */
        fun endNotAsked(why: String?) = end("no loop asked ($why)")

        fun end(how: String) {
            val line = synchronized(this) {
                if (ended) return
                ended = true
                line(how)
            }
            last = line
        }

        /** The steps so far, as one line: "6812 ms to shown: cover 2400, find 0, fetch 812 (network), ...". */
        fun line(how: String = "so far"): String = synchronized(this) {
            // Each step's end, in whole ms from the start; the steps are the gaps between them, so they add up exactly.
            val totals = LinkedHashMap<String, Long>()
            var before = 0L
            for ((name, at) in marks) {
                val end = (at - began) / 1_000_000
                totals[name] = (totals[name] ?: 0L) + (end - before)
                before = end
            }
            val again = marks.groupingBy { it.first }.eachCount()
            buildString {
                append(before).append(" ms to ").append(how)
                if (totals.isNotEmpty()) append(": ")
                append(totals.entries.joinToString(", ") { (name, ms) ->
                    val n = again[name] ?: 1
                    val note = notes[name]
                    "$name $ms" + (if (n > 1) " (x$n)" else "") + (note?.let { " ($it)" } ?: "")
                })
            }
        }
    }

    private val runs = LinkedHashMap<String, Run>()
    private val byUrl = LinkedHashMap<String, Run>()

    /** The last run that ended. */
    @Volatile var last: String? = null; private set

    /**
     * The player asks for [songId]'s picture: a new run, or the one still open for the song if it began less than a
     * minute ago (the player asked again before it showed: the wait carries on from the first ask).
     */
    fun asked(songId: String): Run = synchronized(this) {
        runs[songId]?.takeIf { !it.ended && clock() - it.began < OPEN_NS }?.let { return it }
        val run = Run(songId)
        runs.remove(songId)
        runs[songId] = run
        while (runs.size > KEPT) runs.remove(runs.keys.first())
        run
    }

    /** [run]'s loop is [url]: what the loop's player does with that address next ([shown]) belongs to it. */
    fun handed(run: Run, url: String) = synchronized(this) {
        byUrl.remove(url)
        byUrl[url] = run
        while (byUrl.size > KEPT) byUrl.remove(byUrl.keys.first())
    }

    /** A step of the run [url] was handed to, if one is open (the loop's player knows the address, not the song). */
    fun step(url: String, name: String) { open(url)?.step(name) }

    /** [url]'s first frame is on the screen: its run ends. */
    fun shown(url: String) {
        val run = open(url) ?: return
        run.step("drawn")
        run.end("shown")
    }

    private fun open(url: String): Run? = synchronized(this) { byUrl[url]?.takeIf { !it.ended } }

    companion object {
        /** Songs remembered at once (the player's, the ones prepared ahead). */
        private const val KEPT = 8
        /** An ask older than this is not carried on (a song played again later is a new wait). */
        private const val OPEN_NS = 60_000_000_000L
    }
}
