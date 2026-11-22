package com.vinz.music

import com.vinz.music.data.prefs.SettingsRepository
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.lookKeys
import com.vinz.music.ui.screens.settings.playerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1.8.4, principle 8 of the settings rules ("nothing goes missing", plan §2.1, §2.9, §2.11): **every stored setting has
 * a home.** For every key in `Settings.kt` exactly one of these holds:
 *
 * - a page of Settings writes it (the root, one of the 17 pages, or a screen one of them links to: Customize Home,
 *   Screen backgrounds, the Equalizer), or
 * - it is on [allowed], with the reason it has no row.
 *
 * **Nothing here is a copied list of what a page holds.** What a page claims is read from the code: `Settings.kt` says
 * which keys each setter writes, and a page claims the keys of the setters its own files call ([homes]). So the test
 * fails when
 * - a key is added to `Settings.kt` and no page calls its setter (it has no home),
 * - a page drops the control of a key (the key loses its home),
 * - a page starts writing a key that is listed as state, or that another page holds (one place, principle 4).
 *
 * What it cannot see: that the setter is called from a row a listener can reach. Each `<Page>PageTest` works its own
 * rows for that; this test is the net under them, across all pages.
 *
 * A new setting: give it a row on a page of the map (then this passes by itself). Only state that is not a setting
 * goes on [allowed].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SettingsReachTest {
    private val prefs by lazy { SettingsRepository(RuntimeEnvironment.getApplication(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }

    // ------------------------------------------------------------------ The homes

    /** A place in Settings with controls: [owner] is the root entry it belongs to (a linked screen counts as its page). */
    private class Home(val name: String, val owner: String, val page: SettingsPageId?, val files: List<String>, val linkedBy: Pair<String, String>? = null)

    private val root = "Settings (the root)"
    private val settingsDir = "ui/screens/settings"

    /** `LYRICS_SING` → `LyricsSing`, as the page's file and composable are named (SettingsMapTest holds them to it). */
    private fun fileName(p: SettingsPageId) = p.name.lowercase().split('_').joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }

    private val homes: List<Home> by lazy {
        val pages = SettingsPageId.entries.map { p ->
            val files = listOf("Page", "Parts").map { "$settingsDir/${fileName(p)}$it.kt" }.filter { File(main, it).isFile }
            Home(p.title, p.title, p, files)
        }
        listOf(Home(root, root, null, listOf("$settingsDir/SettingsRoot.kt"))) + pages + listOf(
            // Screens behind a Link row of a page (root → page → the screen: two taps, then the row).
            Home("Customize Home", SettingsPageId.HOME_LIBRARY.title, SettingsPageId.HOME_LIBRARY, listOf("ui/screens/CatalogueScreen.kt"), "$settingsDir/HomeLibraryPage.kt" to "Routes.CATALOGUE"),
            Home("Screen backgrounds", SettingsPageId.THEME_COLORS.title, SettingsPageId.THEME_COLORS, listOf("ui/screens/BackgroundsScreen.kt"), "$settingsDir/ThemeColorsPage.kt" to "Routes.BACKGROUNDS"),
            Home("Equalizer", SettingsPageId.SOUND_QUALITY.title, SettingsPageId.SOUND_QUALITY, listOf("ui/screens/EqualizerScreen.kt"), "$settingsDir/SoundQualityPage.kt" to "Routes.EQ"),
        )
    }

    /** Merged choices that more than one page uses: a page claims what the functions it calls write, not the whole file. */
    private val helpers = mapOf(
        "SoundChoices" to "ui/screens/SoundChoices.kt",
        "LookChoices" to "ui/screens/LookChoices.kt",
        "HomeChoices" to "ui/screens/HomeChoices.kt",
    )

    /** Setters that take the setting's name: the key they write is the one whose [String] the calling file names. */
    private val byName = mapOf(
        "setGesture" to listOf(
            "cover_swipe_left", "cover_swipe_right", "cover_swipe_up", "cover_double_tap", "cover_long_press",
            "mini_swipe_left", "mini_swipe_right", "mini_swipe_up", "mini_swipe_down",
        ).associateWith { it },
        "setCoverShape" to mapOf("songs" to "cover_shape_songs", "albums" to "cover_shape_albums", "artists" to "cover_shape_artists", "player" to "cover_shape_player"),
    )

    /** A control that reaches its setter through another class: [evidence] in the page's [file], and [setter] called in [through]. */
    private class Indirect(val key: String, val page: SettingsPageId, val file: String, val evidence: String, val through: String, val setter: String, val why: String)

    private val indirect = listOf(
        Indirect(
            "kept_playlists", SettingsPageId.DOWNLOADS_DATA, "$settingsDir/DownloadsDataPage.kt", "c.keptPlaylists.set(",
            "download/PlaylistDownloads.kt", "setKeptPlaylist", "the Playlists kept downloaded sheet ticks through KeptPlaylists (it also starts the download)",
        ),
    )

    /** Keys two root entries write, each with why that is one setting in two places and not two settings. */
    private val shared: Map<String, Pair<Set<String>, String>> by lazy {
        mapOf(
            "performance_mode" to (setOf(root, SettingsPageId.THEME_COLORS.title) to
                "its switch is the root's third row; Theme & colors shows the same switch on its notice while Performance mode pauses the page's looks"),
        )
    }

    // ------------------------------------------------------------------ The allow-list (plan §2.11, the ledger)

    private enum class Why { ELSEWHERE, TV, DEAD, LEGACY, ACCOUNT, FLAG, WRITTEN_WITH }

    /**
     * A key with no row in Settings, and why. [where]: for [Why.ELSEWHERE] and [Why.TV] the file with its control and
     * the setter that file calls (checked); for [Why.WRITTEN_WITH] the setting whose control writes it along.
     */
    private class Allowed(val key: String, val why: Why, val reason: String, val where: Pair<String, String>? = null, val with: String? = null)

    private val allowed: List<Allowed> = listOf(
        // State that is set where it is used, by design (plan §2.11: "state, not settings").
        Allowed("local_sort", Why.ELSEWHERE, "a remembered sort: Local files' own sort menu, like every other sort menu", "LocalMusicScreen.kt" to "setLocalSort"),
        Allowed("app_volume", Why.ELSEWHERE, "a level, not a setting: the player's volume slider (its choice, volume_control, has a row on Player)", "PlayerControls.kt" to "setAppVolume"),
        Allowed("queue_locked", Why.ELSEWHERE, "the queue's state: the lock button in the queue's header", "PlayerQueue.kt" to "setQueueLocked"),
        Allowed("dj_flow", Why.ELSEWHERE, "picked where DJ mode is switched on: the DJ chips in the queue", "PlayerQueue.kt" to "setDjFlow"),
        // Vinz TV's own settings pages (the phone's map leaves the TV's screens alone, plan §2.2).
        Allowed("tv_ambient_minutes", Why.TV, "TV pages: Settings on the TV", "TvSettingsPages.kt" to "setTvAmbientMinutes"),
        Allowed("tv_lyrics_scale", Why.TV, "TV pages: Settings on the TV", "TvSettingsPages.kt" to "setTvLyricsScale"),
        Allowed("tv_backdrop_dim", Why.TV, "TV pages: Settings on the TV", "TvSettingsPages.kt" to "setTvBackdropDim"),
        Allowed("tv_home_layout", Why.TV, "TV pages: the TV Home's editor", "TvHomeEditor.kt" to "setTvHomeLayout"),
        // Dead keys: stored, never written by the app any more (left as they are; taking them out is a later clean-up).
        Allowed("nerd_stats", Why.DEAD, "the stats panel opens from the ⋯ menu; nothing reads or writes the stored switch"),
        Allowed("cast_mark", Why.DEAD, "the cast mark is no longer a choice; still travels in Looks"),
        Allowed("lyrics_everywhere", Why.DEAD, "nothing reads it; still travels in Looks and is put back by Lyrics & Sing's reset"),
        // Seeds of the 1.6.3 migration to offline_gb: read once, never written.
        Allowed("cache_size_mb", Why.LEGACY, "the song cache's size before 1.6.3, read once to seed offline_gb"),
        Allowed("smart_download_gb", Why.LEGACY, "Smart Downloads' space before 1.6.3, read once to seed offline_gb"),
        // The sign-in: set by signing in and out (the account card on the root and on Accounts & sharing), never as a row.
        Allowed("cookie", Why.ACCOUNT, "the YouTube Music sign-in"),
        Allowed("visitor_data", Why.ACCOUNT, "YouTube's visitor id (a token)"),
        Allowed("data_sync_id", Why.ACCOUNT, "the account's id for requests (a token)"),
        Allowed("auth_user", Why.ACCOUNT, "which account of the session: the account card's switcher"),
        Allowed("account_name", Why.ACCOUNT, "a copy of the account's name"),
        Allowed("account_email", Why.ACCOUNT, "a copy of the account's email"),
        Allowed("account_avatar", Why.ACCOUNT, "a copy of the account's picture"),
        Allowed("account_saved_at", Why.ACCOUNT, "when the sign-in was saved (a stamp)"),
        // One-time flags.
        Allowed("onboarded", Why.FLAG, "the first start is done"),
        Allowed("new_install_look", Why.FLAG, "the first start wrote a new install's own defaults: Vinz's Look keeps them on (1.8.7.6)"),
        Allowed("tv_welcomed", Why.FLAG, "the TV's welcome was shown"),
        // Written by another setting's control, never alone.
        Allowed("performance_offered", Why.WRITTEN_WITH, "Home's Performance mode card was answered", with = "performance_mode"),
        Allowed("font_stamp", Why.WRITTEN_WITH, "when a font was last imported, so the app reloads it", with = "font_choice"),
        Allowed("app_font_name", Why.WRITTEN_WITH, "the imported app font's name: part of the Font pick", with = "font_choice"),
        Allowed("lyrics_font_name", Why.WRITTEN_WITH, "the imported lyrics font's name: part of the Lyrics font pick", with = "lyrics_font"),
    )

    /**
     * Look settings that are in no page's `<prefix>LookKeys`, each with the home that puts it back another way: its
     * "Reset this page" goes through the setter, so the key must be one that home writes. Customize Home and
     * Recommendations & history reset every row by its setter (they have no LookKeys).
     */
    private val lookResetBySetter: Map<String, String> by lazy {
        mapOf(
            "home_header" to "Customize Home",
            "home_header_height" to "Customize Home",
            "replay_theme_colors" to SettingsPageId.RECOMMENDATIONS_HISTORY.title,
            "replay_story_font" to SettingsPageId.RECOMMENDATIONS_HISTORY.title,
        )
    }

    /** Look settings no reset has to put back: state, not a row (each is on [allowed] too, with its reason). */
    private val lookNotReset = setOf("cast_mark", "app_font_name")

    // ------------------------------------------------------------------ Reading the code

    private val main = File("src/main/java/com/vinz/music")

    private fun stripComments(text: String) = text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("//[^\n]*"), " ")

    private val read = HashMap<String, String>()
    private fun code(path: String): String = read.getOrPut(path) { stripComments(File(main, path).readText()) }

    /** Every main source file by its name (for the allow-list's "its control is in …"). */
    private val byFileName: Map<String, File> by lazy { main.walkTopDown().filter { it.isFile && it.extension == "kt" }.associateBy { it.name } }

    /** Every function in [text] by name, with its body (to the brace that closes it; an expression body runs to the next `fun`). */
    private fun functions(text: String): List<Pair<String, String>> {
        val starts = Regex("""\bfun\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""").findAll(text).toList()
        return starts.mapIndexed { i, m ->
            val limit = starts.getOrNull(i + 1)?.range?.first ?: text.length
            val open = text.indexOf('{', m.range.last)
            if (open < 0 || open >= limit) return@mapIndexed m.groupValues[1] to text.substring(m.range.last, limit)
            var depth = 0
            var end = open
            while (end < text.length) {
                when (text[end]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) break } }
                end++
            }
            m.groupValues[1] to text.substring(m.range.last, minOf(end + 1, text.length))
        }
    }

    /** What `Settings.kt` holds: every stored name, and for each setter the names it writes. */
    private class Stored(val keys: Set<String>, val setters: Map<String, Set<String>>, val writtenByAnything: Set<String>)

    private fun isSetter(name: String) = name.startsWith("set") || name == "resetMenu"

    private fun parseSettings(text: String): Stored {
        val fields = Regex("""(?m)^\s*val (\w+) = \w*[pP]references[kK]ey(?:<[^>]*>)?\("([a-z0-9_]+)"\)""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        val write = Regex("""\[K\.(\w+)]\s*=(?!=)|\.remove\(K\.(\w+)\)""")
        val setters = HashMap<String, Set<String>>()
        val any = HashSet<String>()
        for ((name, body) in functions(text)) {
            val keys = write.findAll(body).mapNotNull { fields[it.groupValues[1].ifEmpty { it.groupValues[2] }] }.toSet()
            any += keys
            // Only a setter gives a page its claim: signing in and out, a Look being applied and a backup write keys too.
            if (isSetter(name) && keys.isNotEmpty()) setters[name] = setters[name].orEmpty() + keys
        }
        return Stored(fields.values.toSet(), setters, any)
    }

    private val settingsText by lazy { stripComments(File(main, "data/prefs/Settings.kt").readText()) }
    private val stored by lazy { parseSettings(settingsText) }

    private val word = Regex("""\b[A-Za-z_]\w*\b""")

    /** The repository's setters [text] names (a call or a reference). */
    private fun settersIn(text: String, s: Stored): Set<String> = word.findAll(text).map { it.value }.filter { it in s.setters || it in byName }.toSet()

    /** What each helper's functions write, read once. */
    private fun helperWrites(s: Stored): Map<String, Map<String, Set<String>>> =
        helpers.mapValues { (_, path) -> functions(code(path)).groupBy({ it.first }, { settersIn(it.second, s) }).mapValues { it.value.flatten().toSet() } }

    /** The stored names [text] writes: through the setters it calls, the helpers' functions it calls, and setters by name. */
    private fun keysWritten(text: String, s: Stored, helperFns: Map<String, Map<String, Set<String>>>): Set<String> {
        val called = settersIn(text, s).toMutableSet()
        for ((helper, fns) in helperFns) Regex("""\b$helper\.(\w+)""").findAll(text).forEach { called += fns[it.groupValues[1]].orEmpty() }
        val keys = called.flatMap { s.setters[it].orEmpty() }.toMutableSet()
        for ((setter, names) in byName) if (setter in called) keys += names.filterKeys { "\"$it\"" in text }.values
        return keys
    }

    /** Key → the homes that write it. */
    private fun claims(s: Stored, texts: Map<Home, String>): Map<String, Set<Home>> {
        val helperFns = helperWrites(s)
        val out = HashMap<String, MutableSet<Home>>()
        for ((home, text) in texts) keysWritten(text, s, helperFns).forEach { out.getOrPut(it) { LinkedHashSet() } += home }
        for (i in indirect) out.getOrPut(i.key) { LinkedHashSet() } += homes.first { it.page == i.page && it.linkedBy == null }
        return out
    }

    private val homeTexts: Map<Home, String> by lazy { homes.associateWith { h -> h.files.joinToString("\n") { code(it) } } }

    // ------------------------------------------------------------------ The rule

    /**
     * Everything wrong with where the settings live, one line each (empty: every key has exactly one home).
     * [keys]: every stored name; [claimed]: key → the root entries that write it.
     */
    private fun problems(keys: Set<String>, claimed: Map<String, Set<String>>): List<String> {
        val out = ArrayList<String>()
        val allow = allowed.associateBy { it.key }
        assertEquals("a key twice on the allow-list", allowed.size, allow.size)
        for (k in keys.sorted()) {
            val owners = claimed[k].orEmpty()
            val a = allow[k]
            when {
                owners.isEmpty() && a == null -> out += "$k: NO HOME. No page of Settings writes it and it is not on the allow-list. Give it a row on a page of the map (or, if it is state and not a setting, an allow-list line with the reason)."
                owners.isNotEmpty() && a != null && a.why != Why.WRITTEN_WITH -> out += "$k: both claimed (by $owners) and allow-listed (${a.why}: ${a.reason}). It has a control now: take it off the allow-list."
                owners.isEmpty() && a?.why == Why.WRITTEN_WITH -> out += "$k: allow-listed as written with ${a.with}, but no page writes it."
                owners.size > 1 && a == null -> {
                    val known = shared[k]
                    if (known == null) out += "$k: claimed by two root entries $owners. One place (principle 4): keep one control, or list it as shared with the reason."
                    else if (known.first != owners) out += "$k: listed as shared by ${known.first}, but written by $owners."
                }
            }
        }
        for (a in allowed) {
            if (a.key !in keys) out += "${a.key}: on the allow-list, but no such key is stored any more. Take the line out."
            if (a.why == Why.WRITTEN_WITH && claimed[a.with].isNullOrEmpty()) out += "${a.key}: said to be written with ${a.with}, which no page writes."
        }
        for ((k, v) in shared) if (claimed[k].orEmpty().size < 2) out += "$k: listed as shared by ${v.first}, but only ${claimed[k].orEmpty()} writes it. Take it off the shared list."
        return out
    }

    private fun owners(c: Map<String, Set<Home>>): Map<String, Set<String>> = c.mapValues { (_, hs) -> hs.map { it.owner }.toSet() }

    // ------------------------------------------------------------------ Tests

    /** The reading of `Settings.kt` is whole: it finds every key the repository has, and a setter for each. */
    @Test fun theCodeIsReadWhole() {
        assertEquals("the keys read from Settings.kt are the repository's", prefs.allKeyNames, stored.keys)
        assertTrue("setters read: ${stored.setters.size}", stored.setters.size > 300)
        val named = byName.values.flatMap { it.values }.toSet()
        assertEquals("keys no function writes by their own name are exactly the ones set by name (a new one: add it to byName)", named, stored.keys - stored.writtenByAnything)
        // What only something other than a setter writes (signing in and out) is on the allow-list as the account's.
        val noSetter = stored.keys - stored.setters.values.flatten().toSet() - named
        assertEquals("keys without a setter are the account's", emptySet<String>(), noSetter - allowed.filter { it.why == Why.ACCOUNT }.map { it.key }.toSet())
        // The by-name setters are there, and so is every file of every home.
        for (setter in byName.keys) assertTrue("$setter is in Settings.kt", Regex("""\bfun $setter\(""").containsMatchIn(settingsText))
        for (h in homes) {
            assertTrue("${h.name} has its files", h.files.isNotEmpty() && h.files.all { File(main, it).isFile })
            h.linkedBy?.let { (file, route) -> assertTrue("${h.name} is opened from $file ($route)", route in code(file)) }
        }
        for ((name, path) in helpers) assertTrue("$name is in $path", Regex("""\bobject $name\b""").containsMatchIn(code(path)))
    }

    @Test fun everyStoredSettingHasExactlyOneHome() {
        val claimed = claims(stored, homeTexts)
        val wrong = problems(prefs.allKeyNames, owners(claimed))
        writeSummary(claimed)
        assertTrue("Settings without a home, or with two (${wrong.size}):\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** The test of the test: a key added to `Settings.kt` with no row is reported, by the same reading and the same rule. */
    @Test fun aNewKeyWithoutAHomeIsReported() {
        val added = settingsText + "\n    val madeUp = booleanPreferencesKey(\"made_up_setting\")\n    fun setMadeUp(v: Boolean) = edit { it[K.madeUp] = v }\n"
        val s = parseSettings(added)
        assertEquals(setOf("made_up_setting"), s.keys - prefs.allKeyNames)
        assertEquals(setOf("made_up_setting"), s.setters["setMadeUp"])
        // No page calls its setter: it is reported, and it is the only thing reported.
        val homeless = problems(s.keys, owners(claims(s, homeTexts)))
        assertEquals(homeless.toString(), 1, homeless.size)
        assertTrue(homeless.single(), homeless.single().startsWith("made_up_setting: NO HOME"))
        // A page that calls the setter gives it its home.
        val playback = homes.first { it.page == SettingsPageId.PLAYBACK }
        val withRow = homeTexts + (playback to homeTexts.getValue(playback) + "\nSwitchRow(title, s.madeUp) { prefs.setMadeUp(it) }")
        assertEquals(emptyList<String>(), problems(s.keys, owners(claims(s, withRow))))
        assertEquals(setOf(playback.owner), owners(claims(s, withRow))["made_up_setting"])
        // Two pages with a row for it are one too many.
        val car = homes.first { it.page == SettingsPageId.CAR }
        val twice = problems(s.keys, owners(claims(s, withRow + (car to homeTexts.getValue(car) + "\nprefs.setMadeUp(true)"))))
        assertTrue(twice.toString(), twice.size == 1 && "claimed by two root entries" in twice.single())
        // A row for a key that is listed as state is reported too.
        val locked = problems(prefs.allKeyNames, owners(claims(stored, homeTexts + (car to homeTexts.getValue(car) + "\nprefs.setQueueLocked(true)"))))
        assertTrue(locked.toString(), locked.size == 1 && locked.single().startsWith("queue_locked: both claimed"))
        // And a key that only exists in a comment or behind a setter nobody calls stays homeless.
        assertTrue(problems(s.keys, owners(claims(s, homeTexts + (car to homeTexts.getValue(car) + stripComments("\n// prefs.setMadeUp(true)"))))).isNotEmpty())
    }

    /** The allow-list's reasons hold: what is "set elsewhere" is set there, what is "dead" is written by nothing. */
    @Test fun theAllowListSaysWhatIsTrue() {
        val allMain = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Settings.kt" }.toList()
        fun writersOf(key: String) = stored.setters.filterValues { key in it }.keys
        for (a in allowed) {
            assertTrue("${a.key}: a reason", a.reason.isNotBlank())
            when (a.why) {
                Why.ELSEWHERE, Why.TV -> {
                    val (file, setter) = a.where ?: throw AssertionError("${a.key}: name the file with its control")
                    assertTrue("${a.key}: $setter writes it", a.key in stored.setters[setter].orEmpty())
                    val f = byFileName[file] ?: throw AssertionError("${a.key}: no file $file")
                    assertTrue("${a.key}: its control is in $file ($setter)", Regex("""\b$setter\b""").containsMatchIn(stripComments(f.readText())))
                }
                Why.DEAD, Why.LEGACY -> {
                    val setters = writersOf(a.key)
                    val callers = allMain.filter { f -> stripComments(f.readText()).let { t -> setters.any { Regex("""\b$it\b""").containsMatchIn(t) } } }.map { it.name }
                    assertTrue("${a.key} is listed as ${a.why}, but $callers writes it ($setters): it is alive, give it a home", callers.isEmpty())
                }
                Why.WRITTEN_WITH -> {
                    val with = a.with ?: throw AssertionError("${a.key}: name the setting it is written with")
                    assertTrue("$with is a stored setting", with in prefs.allKeyNames)
                    assertTrue("${a.key}: a setter writes it", writersOf(a.key).isNotEmpty())
                }
                Why.ACCOUNT, Why.FLAG -> Unit
            }
        }
        for (i in indirect) {
            assertTrue("${i.key}: ${i.evidence} in ${i.file}", i.evidence in code(i.file))
            assertTrue("${i.key}: ${i.through} calls ${i.setter}", Regex("""\b${i.setter}\b""").containsMatchIn(code(i.through)) && i.key in stored.setters[i.setter].orEmpty())
            assertTrue(i.why.isNotBlank())
        }
        // What the plan allow-lists by name (§2.11) is here, so that nobody "fixes" one of them by deleting its line.
        val keys = allowed.map { it.key }.toSet()
        assertTrue(keys.containsAll(listOf("local_sort", "app_volume", "queue_locked", "dj_flow", "nerd_stats", "cast_mark", "lyrics_everywhere", "cache_size_mb", "smart_download_gb")))
        assertEquals(setOf("tv_ambient_minutes", "tv_lyrics_scale", "tv_backdrop_dim", "tv_home_layout"), allowed.filter { it.why == Why.TV }.map { it.key }.toSet())
    }

    /** Player keeps a list of its settings in the code ([playerSettings]): the reading of its files agrees with it. */
    @Test fun thePlayersOwnListAgrees() {
        val read = claims(stored, homeTexts).filterValues { hs -> hs.any { it.page == SettingsPageId.PLAYER } }.keys
        assertEquals("Player's keys, read from its files, against playerSettings", playerSettings.flatMap { it.keys }.toSet(), read)
    }

    /**
     * Look settings and "Reset this page": every look key is put back by exactly one page (it is in that page's
     * `<prefix>LookKeys`, and that page is the one that holds it), or is listed with the home that resets it by its
     * setter, or is state.
     */
    @Test fun everyLookSettingIsResetByThePageThatHoldsIt() {
        val look = prefs.lookKinds.keys
        val claimed = claims(stored, homeTexts)
        val dead = allowed.filter { it.why == Why.DEAD }.map { it.key }.toSet()
        val wrong = ArrayList<String>()
        val inPages = SettingsPageId.entries.flatMap { p -> p.lookKeys.map { it to p } }.groupBy({ it.first }, { it.second })
        for (k in look.sorted()) {
            val pages = inPages[k].orEmpty()
            val bySetter = lookResetBySetter[k]
            when {
                pages.size > 1 -> wrong += "$k: in the LookKeys of ${pages.map { it.title }} (one page resets it)"
                pages.size == 1 && (bySetter != null || k in lookNotReset) -> wrong += "$k: in ${pages.single().title}'s LookKeys and listed here as reset another way"
                pages.size == 1 -> {
                    val holders = claimed[k].orEmpty().map { it.owner }.toSet()
                    if (k !in dead && pages.single().title !in holders) wrong += "$k: ${pages.single().title} resets it, but its control is on $holders"
                }
                bySetter != null -> {
                    val home = homes.firstOrNull { it.name == bySetter }
                    if (home == null || home !in claimed[k].orEmpty()) wrong += "$k: said to be reset by its setter on $bySetter, which does not write it"
                }
                k in lookNotReset -> if (allowed.none { it.key == k }) wrong += "$k: listed as not reset, but it is not on the allow-list"
                else -> wrong += "$k: a look setting that no page's \"Reset this page\" puts back (in no <prefix>LookKeys). Add it to the LookKeys of the page that holds it (${claimed[k].orEmpty().map { it.owner }.toSet()})."
            }
        }
        for (k in lookResetBySetter.keys + lookNotReset) if (k !in look) wrong += "$k: listed here as a look setting, but it is not one"
        // A page's LookKeys are its own: every one is a key that page holds (or a dead key it still puts back).
        for (p in SettingsPageId.entries) for (k in p.lookKeys) {
            if (k !in dead && claimed[k].orEmpty().none { it.owner == p.title }) wrong += "$k: in ${p.title}'s LookKeys, but nothing on ${p.title} writes it"
        }
        assertTrue("Look settings and Reset this page (${wrong.size}):\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    // ------------------------------------------------------------------ The counts (build/settings-reach/summary.txt)

    private fun writeSummary(claimed: Map<String, Set<Home>>) {
        val all = prefs.allKeyNames
        val lines = ArrayList<String>()
        val allow = allowed.associateBy { it.key }
        val held = claimed.filterKeys { allow[it]?.why != Why.WRITTEN_WITH }
        lines += "Stored keys: ${all.size}. Claimed by a page: ${held.size}. Allow-listed: ${allowed.size}. Neither: ${(all - held.keys - allow.keys).sorted()}"
        for (h in homes) {
            val keys = held.filterValues { h in it }.keys.sorted()
            lines += "${h.name}${if (h.linkedBy != null) " (behind a link on ${h.owner})" else ""}: ${keys.size}  $keys"
        }
        lines += "By root entry: " + held.values.flatMap { hs -> hs.map { it.owner }.toSet() }.groupingBy { it }.eachCount().toString()
        lines += "Shared: ${shared.keys}"
        for (w in Why.entries) lines += "Allow-list, $w: ${allowed.count { it.why == w }}  ${allowed.filter { it.why == w }.map { it.key }}"
        lines += "Look keys: ${prefs.lookKinds.size}; in a page's LookKeys: ${SettingsPageId.entries.sumOf { it.lookKeys.size }}; reset by setter: ${lookResetBySetter.keys}; not reset: $lookNotReset"
        File("build/settings-reach").apply { mkdirs() }.resolve("summary.txt").writeText(lines.joinToString("\n"))
    }
}
