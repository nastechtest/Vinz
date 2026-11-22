package com.vinz.music

import com.vinz.music.ui.screens.SettingsIndex
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings search (#73) only finds what [SettingsIndex] lists: every setting row and group on the settings pages has to
 * be there, so a new setting without a line in the index fails here. Since 1.7.9 the pages read their words from
 * strings.xml (`tr(R.string.…)`), so each row's words are looked up there (in English, as the tests run).
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class SettingsIndexTest {
    /**
     * 1.8.4: the pages are one file each under `ui/screens/settings/` (`<Page>Page.kt`, some with a `<Page>Parts.kt`),
     * next to the kit. Every file there is read, and SettingsScreen.kt (the root's notes and the "Customize this" sheet,
     * which draws the pages' own rows). The old pages' code is gone, so there is nothing else a row could be drawn by.
     */
    private val pageFiles: List<File> =
        File("src/main/java/com/vinz/music/ui/screens/settings").listFiles().orEmpty().filter { it.extension == "kt" }.sortedBy { it.name }
    private val sources = pageFiles.map { it.readText() } +
        // Not the "Customize this" sheet's own two rows (a Home section's Style and Hide this section): they are that
        // section's, not settings, and only that sheet has them. Its other rows are the pages' own, read in their files.
        File("src/main/java/com/vinz/music/ui/screens/SettingsScreen.kt").readText().substringBefore("fun CustomizeSheet(")

    /**
     * The rows, by the composable that draws them: the kit's five and every page's own row built on them (`SheetRow`,
     * `CoverShapeRow`, `FontRow`…: whatever is called `…Row` and takes its title first), and the old kit's `Item`, which
     * the root's Performance mode note still is.
     */
    private val rowCalls = """\w*Row|Item"""

    /** The English strings (strings.xml and, until they're folded in, a release's own strings_*.xml): name → text. */
    private val strings: Map<String, String> by lazy {
        File("src/main/res/values").listFiles().orEmpty().filter { it.name.startsWith("strings") && it.name.endsWith(".xml") }.sortedBy { it.name }
            .flatMap { Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(it.readText()) }
            .associate { it.groupValues[1] to I18nConvert.xmlUnescape(it.groupValues[2]) }
    }

    private fun rows(calls: String, from: List<String> = sources) = from.flatMap { src ->
        Regex("""\b(?:$calls)\(\s*tr\(R\.string\.(\w+)""").findAll(src).map { m ->
            // "Space to use: %1$s" is listed by its fixed start.
            strings.getValue(m.groupValues[1]).substringBefore("%1\$s").trim().removeSuffix(":")
        }.toList()
    }.filter { it.isNotBlank() }.distinct()

    @Test
    fun everyRowIsSearchable() {
        val rows = rows(rowCalls)
        assertTrue("the pages' rows were read (${rows.size})", rows.size > 250)
        val missing = rows.filter { row -> SettingsIndex.entries.none { it.title == row || row.startsWith(it.title) } }
        assertTrue("Not in SettingsIndex: $missing", missing.isEmpty())
        // The kit's rows are read, and the pages' own rows built on them.
        for (id in listOf(R.string.content_country, R.string.performance_mode, R.string.what_s_on_the_player, R.string.songs_covers)) {
            assertTrue(com.vinz.music.tr(id), com.vinz.music.tr(id) in rows)
        }
    }

    @Test
    fun everyGroupIsSearchable() {
        // The pages' groups (`group(...)` in a page's scaffold, `SettingsGroup(...)`). Not the "Customize this" sheet's
        // in SettingsScreen.kt: a search result opens a page at its group, never the sheet, and the sheet names its
        // groups for the part tapped ("Visualizer", "Lyrics"), whose rows are found on their pages' groups.
        // The kit's own files draw whatever title a page hands them (`SettingsGroup(e.title, …)`): no group of their own.
        val kit = setOf("SettingsRows.kt", "SettingsGroup.kt", "SettingsPageScaffold.kt", "PickSheet.kt")
        val pages = pageFiles.filter { it.name !in kit }.map { it.readText() }
        val groups = (rows("group|SettingsGroup", pages) + namedGroups(pages)).distinct()
        assertTrue("the pages' groups were read (${groups.size})", groups.size > 60)
        val missing = groups.filter { g -> SettingsIndex.entries.none { it.group == g } }
        assertTrue("Groups not in SettingsIndex: $missing", missing.isEmpty())
    }

    /**
     * The groups whose title isn't written `tr(R.string.…)` in the call itself: a page's groups object
     * (`group(PlayerGroups.layout)`, its `val layout get() = tr(R.string.layout)`), a title kept in a `val` of the file
     * (`group(coverTitle)`), or one shown only in a state (`group(if (…) tr(R.string.spotify) else null, …)`). Each is read
     * back to its string; a title that can't be (anything else but `null`) fails, so no group's title escapes the check.
     */
    private fun namedGroups(pages: List<String>): List<String> {
        val all = pages.joinToString("\n")
        fun text(name: String) = strings.getValue(name).substringBefore("%1\$s").trim().removeSuffix(":")
        val props = Regex("""object (\w+)\s*\{(.*?)\n}""", RegexOption.DOT_MATCHES_ALL).findAll(all).flatMap { o ->
            Regex("""val (\w+) get\(\) = tr\(R\.string\.(\w+)\)""").findAll(o.groupValues[2]).map { "${o.groupValues[1]}.${it.groupValues[1]}" to it.groupValues[2] }
        }.toMap()
        // Comments name `group(...)` without drawing one.
        val code = pages.map { it.lines().filterNot { l -> l.trimStart().let { t -> t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") } }.joinToString("\n") }
        return code.flatMap { src ->
            val vals = Regex("""val (\w+) = tr\(R\.string\.(\w+)\)""").findAll(src).associate { it.groupValues[1] to it.groupValues[2] }
            Regex("""\b(?:group|SettingsGroup)\((?!\s*tr\(R\.string\.)""").findAll(src).mapNotNull { m ->
                val arg = firstArgument(src, m.range.last + 1)
                when {
                    arg == "null" -> null
                    // The root's five groups (SettingsMap.groups) list pages, not settings: the pages are found by their names.
                    arg == "group.title" && "SettingsMap.groups.forEachIndexed { i, (group, pages) ->" in src -> null
                    "tr(R.string." in arg -> Regex("""tr\(R\.string\.(\w+)\)""").findAll(arg).map { text(it.groupValues[1]) }.toList()
                    arg in props -> listOf(text(props.getValue(arg)))
                    arg in vals -> listOf(text(vals.getValue(arg)))
                    else -> throw AssertionError("a group's title that this test can't read: group($arg")
                }
            }.flatten().toList()
        }
    }
    /** The call's first argument, from [from] (just past its "("), up to the comma or ")" that ends it. */
    private fun firstArgument(src: String, from: Int): String {
        var depth = 0
        for (i in from until src.length) {
            when (src[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth == 0) return src.substring(from, i).trim() else depth--
                ',' -> if (depth == 0) return src.substring(from, i).trim()
            }
        }
        return src.substring(from).trim()
    }
    /** Every page of the map is among the files read, and the old pages' code is gone (nothing left to read it from). */
    @Test
    fun everyPageFileIsReadAndNoOldPageIsLeft() {
        val read = pageFiles.map { it.name }
        for (p in com.vinz.music.ui.screens.settings.SettingsPageId.entries) {
            val name = p.name.lowercase().split('_').joinToString("") { it.replaceFirstChar { c -> c.uppercase() } } + "Page.kt"
            assertTrue("$name is read: $read", name in read)
        }
        assertTrue("old pages' files: $read", read.none { it.startsWith("Old") || it == "SettingsInterim.kt" })
    }
    /** A setting that's hidden says what to turn on first, and stops saying it once that's on. */
    @Test
    fun hiddenSettingsSayWhatToTurnOn() {
        fun entry(id: Int) = SettingsIndex.entries.first { it.title == com.vinz.music.tr(id) }
        val plain = com.vinz.music.data.prefs.AppSettings(frostedGlass = false)
        val frost = entry(R.string.glass_frost).needs
        assertTrue("Frost's hint", frost != null && !frost.on(plain))
        assertTrue(frost!!.on(plain.copy(frostedGlass = true)))
        assertTrue(frost.on(plain.copy(liquidGlass = true)))
        for (id in listOf(R.string.lock_screen_layout_label, R.string.lock_screen_show, R.string.lock_screen_paused_label, R.string.lock_screen_hide, R.string.lock_screen_allow, R.string.lock_screen_allow_step, R.string.lock_screen_app_info)) {
            // By its group too: other pages have a "Layout". (1.8.4: the lock screen's rows are in Player's Modes group.)
            val needs = SettingsIndex.entries.first { it.title == com.vinz.music.tr(id) && it.group == com.vinz.music.tr(R.string.settings_player_modes) }.needs
            assertTrue(com.vinz.music.tr(id), needs != null && !needs.on(plain))
            assertTrue(com.vinz.music.tr(id), needs!!.on(plain.copy(behavior = plain.behavior.copy(lockScreen = true))))
        }
        // The lock screen's own switch isn't hidden by anything.
        assertTrue(entry(R.string.lock_screen_title).needs == null)
    }

    /**
     * The row is where its search result opens. Since 1.8.4 that is Customize Home only (plan §2.6: Home & Library
     * links to that screen and no longer repeats the row), in its Options group.
     */
    @Test
    fun quickPicksFromIsWhereItsResultOpens() {
        val e = SettingsIndex.entries.single { it.title == com.vinz.music.tr(R.string.quick_picks_from) }
        org.junit.Assert.assertEquals(com.vinz.music.tr(R.string.customize_home), e.pageTitle)
        org.junit.Assert.assertEquals(com.vinz.music.tr(R.string.options), e.group)
        assertTrue(e.opens, e.opens!!.startsWith(com.vinz.music.ui.Routes.CATALOGUE))
        val screen = File("src/main/java/com/vinz/music/ui/screens/CatalogueScreen.kt").readText().replace("\r", "")
        val group = screen.substringAfter("group(tr(R.string.options))").substringBefore("\n    }\n")
        assertTrue("the Options group was read", group.length < 4000 && "R.string.customize_button_on_home" in group)
        assertTrue("Quick picks from isn't in that group", "ChoiceRow(\n                tr(R.string.quick_picks_from)" in group)
        // Home & Library's file names it in its search lines only (which Customize Home's lines are among).
        val named = File("src/main/java/com/vinz/music/ui/screens/settings/HomeLibraryPage.kt").readLines().filter { "R.string.quick_picks_from)" in it }
        assertTrue("and no longer drawn on Home & Library: $named", named.isNotEmpty() && named.all { "onHome(" in it })
    }

    /**
     * 1.8.4: "Speed dial: your plays first" is only on Customize Home (its own page, not one of Settings'), so its result
     * opens that page: it used to open Home & Library, which has no such row.
     */
    @Test
    fun speedDialYoursFirstOpensThePageItIsOn() {
        val row = "R.string.speed_dial_your_plays_first)"
        assertTrue("the row is on Customize Home", row in File("src/main/java/com/vinz/music/ui/screens/CatalogueScreen.kt").readText())
        // A page's file also holds its search lines (1.8.4): those name the row without drawing it.
        assertTrue("and on no settings page", sources.none { src -> src.lineSequence().any { row in it && "SettingEntry(" !in it } })
        val e = SettingsIndex.entries.single { it.title == com.vinz.music.tr(R.string.speed_dial_your_plays_first) }
        // At its group: the route carries it, and Customize Home opens there.
        org.junit.Assert.assertEquals(com.vinz.music.ui.Routes.CATALOGUE + "?find=" + com.vinz.music.tr(R.string.options), e.opens)
        org.junit.Assert.assertEquals(com.vinz.music.tr(R.string.customize_home), e.pageTitle)
        // Still found by the page's name, and still says what to turn off first.
        assertTrue(SettingsIndex.search("customize home speed dial").contains(e))
        assertTrue(e.needs != null)
        // Every other result opens a settings page at its group, as before (1.8.4: the page of the map it's on now).
        val haptics = SettingsIndex.entries.first { it.title == "Haptics" }
        org.junit.Assert.assertEquals(com.vinz.music.ui.Routes.settings("gestures_buttons", haptics.group), haptics.opens)
        org.junit.Assert.assertEquals(null, SettingsIndex.entries.first { it.page == null }.opens)
    }

    /**
     * 1.8.4: no setting is listed twice. A double is two entries that make the same result: the same page, the same
     * group and the same words shown ("Buttons in the row" was there twice, so a search showed two identical rows).
     * The same title in two groups of a page (Speakers' and Handoff's "Pairing code") is two settings, not a double.
     */
    @Test
    fun noSettingIsListedTwice() {
        val doubles = SettingsIndex.entries.groupBy { Triple(it.pageTitle, it.group, it.shown) }.filter { it.value.size > 1 }
        assertTrue("Listed more than once in SettingsIndex: ${doubles.map { (k, v) -> "${k.first} › ${k.second} › ${k.third} ×${v.size}" }}", doubles.isEmpty())
        // What each of the two had as search words still finds the one that's left.
        val row = com.vinz.music.tr(R.string.buttons_in_the_row)
        assertTrue(SettingsIndex.search("audio only").any { it.title == row })
        assertTrue(SettingsIndex.search("bottom row lyrics").any { it.title == row })
    }

    @Test
    fun findsBySynonymAndTitle() {
        assertTrue(SettingsIndex.search("vibration").any { it.title == "Haptics" })
        assertTrue(SettingsIndex.search("crossfade").first().title.startsWith("Crossfade"))
        assertTrue(SettingsIndex.search("eq").any { it.title == "Equalizer" })
        assertTrue(SettingsIndex.search("zzzz").isEmpty())
    }
}
