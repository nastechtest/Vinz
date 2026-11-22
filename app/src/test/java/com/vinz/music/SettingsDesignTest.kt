package com.vinz.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.components.LocalFrosted
import com.vinz.music.ui.screens.CatalogueScreen
import com.vinz.music.ui.screens.SettingsIndex
import com.vinz.music.ui.screens.settings.ConnectTags
import com.vinz.music.ui.screens.settings.SettingsMap
import com.vinz.music.ui.screens.settings.SettingsPageContent
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsRootContent
import com.vinz.music.ui.screens.settings.SettingsRootEntry
import com.vinz.music.ui.screens.settings.SettingsRowValue
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.customizeHomeRoute
import com.vinz.music.ui.screens.settings.lookKeys
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.8.4, the settings rules across every page in one place (plan §2.1 principles 1–7, §2.9). Each `<Page>PageTest`
 * holds its own page to them; this one holds all 17 pages of the map, and Customize Home (a settings page behind a
 * Link row of Home & Library), to the same rules with one reading of each page as it draws with a fresh install's
 * settings, so a page that is added or changed later cannot be left out:
 *
 * - at most 6 groups a page, at most 7 rows a group (a reorder list counts as one row);
 * - a card holds only the kit's five rows, or a list that is listed here with its page ([lists]);
 * - every row is in a group; every row but a switch or a link says its value;
 * - every test tag a page uses is the kit's or listed here with its page ([ownTags]);
 * - no group is called what its page is called; no two search lines are the same;
 * - every page is one tap from the root, and every search line's group is drawn on the page it opens (root → page →
 *   row, or → a sheet or a linked screen → row: never a third page);
 * - a page that has look settings offers "Reset this page";
 * - at 360 dp and text at 1.3×, the page's name fits the top bar.
 *
 * Not checked here (nothing on a page says it to a test): example cards outside a sheet, visible notes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h6000dp-mdpi", application = android.app.Application::class)
class SettingsDesignTest {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val container by made

    /** The settings' store is one per JVM: each test starts from Vinz as it comes, and leaves it so. */
    @Before fun emptyStore() {
        runBlocking { container.settings.editNow { it.clear() } }
        val fresh = AppSettings()
        val until = System.currentTimeMillis() + 5_000
        while (container.settings.stored.value.let { it.themeMode != fresh.themeMode || it.performanceMode || it.navStyle != fresh.navStyle } && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    @After fun close() {
        runCatching { runBlocking { container.settings.editNow { it.clear() } } }
        made.closeAfterTest()
    }

    // ------------------------------------------------------------------ What is listed (each with its page)

    private val customizeHome: String get() = tr(R.string.customize_home)

    /** A list in a group, which the kit has no row for (plan §2.1.6: "lists that grow use the same row height"). One row each. */
    private val lists: Map<String, Map<String, String>> by lazy {
        mapOf(
            SettingsPageId.TABS_MINI_PLAYER.title to mapOf("settings:list" to "Tabs: which tabs, in order, with their names"),
            SettingsPageId.GESTURES_BUTTONS.title to mapOf("gesturesButtons:buttons" to "Notification and car: the buttons, in order"),
            SettingsPageId.LYRICS_SING.title to mapOf("lyricsSing:sourceOrder" to "Sources and timing: the lyric sources, in order, each with its switch"),
            customizeHome to mapOf("customizeHome:sections" to "Sections: Home's sections, in order"),
        )
    }

    /** What else a card may hold, by its tag: a state line where a group has nothing to list yet. One row each. */
    private val cardContent: Map<String, Map<String, String>> by lazy {
        mapOf(SettingsPageId.LOOKS.title to mapOf("settings:looks:state" to "Your Looks, History and editing: the line that says there is nothing here yet"))
    }

    /**
     * Groups that are a grid of looks and not rows (Looks is the page where looks are picked: its cards are the
     * content). The grid carries no test tag (`LookGrid`), so it is known by its group; the group counts as one row.
     */
    private val grids: Map<String, Map<String, String>> by lazy {
        mapOf(
            SettingsPageId.LOOKS.title to mapOf(
                tr(R.string.vinz_s_looks) to "Vinz's own looks, as cards to pick from",
                tr(R.string.your_looks) to "the looks you saved, as cards to pick from",
            ),
        )
    }

    /**
     * Test tags of a page's own that are not rows: content outside the groups, parts of a list, and what its sheets
     * hold. Listed so that a new private kind of content on a page is a decision, not an accident.
     */
    private val ownTags: Map<String, Map<String, String>> by lazy {
        mapOf(
            SettingsPageId.LOOKS.title to mapOf("settings:looks:now" to "the card of the look in use, above the groups"),
            SettingsPageId.PLAYER.title to mapOf(
                "settings:player:preview:live" to "the live small player in the pinned preview",
                "settings:player:preview:plain" to "the still small player in the pinned preview, in Performance mode (1.8.5)",
            ),
            SettingsPageId.LYRICS_SING.title to mapOf("lyricsSing:source:" to "a source's switch inside the source order list (the tag ends in the source's name)"),
            SettingsPageId.BACKUP_ABOUT.title to mapOf(
                "backupAbout:foot" to "the \"not affiliated\" line under the groups",
                "backupAbout:update" to "the update card above the groups, when there is an update",
            ),
            SettingsPageId.SPEAKERS_TVS_BROWSER.title to mapOf(
                ConnectTags.LINE to "a line in one of the page's sheets (a paired browser or device, a code)",
                ConnectTags.TEXT to "a paragraph in one of the page's sheets",
            ),
            SettingsPageId.ACCOUNTS_SHARING.title to mapOf(
                ConnectTags.TEXT to "a paragraph in one of the page's sheets (Spotify's \"How to connect\")",
                ConnectTags.SPOTIFY_CLIENT_ID to "the Client ID field in Spotify's sheet",
                ConnectTags.SPOTIFY_CONNECT to "Connect in Spotify's sheet",
            ),
        )
    }

    /** The kit's own tags. */
    private val kitTags = SettingsTags.rowTypes + setOf(
        SettingsTags.GROUP, SettingsTags.GROUP_CARD, SettingsTags.CHANGED_DOT, SettingsTags.PAGE_LIST, SettingsTags.PAGE_TITLE,
        SettingsTags.PAGE_SEARCH, SettingsTags.PAGE_MORE, SettingsTags.JUMP_CHIPS, SettingsTags.PREVIEW, SettingsTags.RESET_ITEM,
        SettingsTags.RESET_CHANGE, SettingsTags.RESET_CONFIRM, SettingsTags.SHEET, SettingsTags.SHEET_OPTION, SettingsTags.SHEET_SEARCH,
        SettingsTags.PAGE_BAR_TITLE,
    )

    /**
     * No page's name may be cut in the top bar. (Until the kit's fix of 2026-11-10 twelve were, at 360 dp and 1.3× text:
     * the bar now gives its title the room its buttons leave and shrinks it to fit.) No title may be added here.
     */
    private val barTitleKnownToBeCut: Set<String> = emptySet()

    /**
     * A search line's group that is a group's key and not a title on the page: a card without a title (the service's
     * name is on its row), which the page still opens at (`group(null, key = …)`).
     */
    private val untitledGroups: Map<String, Set<String>> by lazy {
        mapOf(SettingsPageId.ACCOUNTS_SHARING.title to setOf("Last.fm", tr(R.string.spotify), tr(R.string.discord)))
    }

    /** A group that is only drawn under a condition a fresh install doesn't meet, with the condition. */
    private val conditionalGroups: Map<String, Map<String, String>> by lazy {
        mapOf(SettingsPageId.THEME_COLORS.title to mapOf(tr(R.string.home_screen_widgets) to "only while a Vinz widget is on the home screen (each widget is a Link row)"))
    }

    /**
     * KNOWN GAPS, reported 2026-11-10 (the test is not to fix a page). Each is exactly one offender; when the page is
     * fixed the test says so and the line goes. Nothing else may be added here without the owner's word.
     */
    private val knownSearchGaps: Map<String, String> = emptyMap()
    /** Since the kit's fix of 2026-11-10 a Show chips row with nothing ticked says "None": no row is without its value. */
    private val knownValuelessRows: Map<String, String> = emptyMap()

    // ------------------------------------------------------------------ Drawing a page

    private class Screen(val name: String, val page: SettingsPageId?, val content: @Composable () -> Unit)

    private val screens: List<Screen> by lazy {
        SettingsPageId.entries.map { p -> Screen(p.title, p) { SettingsPageContent(p, 0.dp, onSearch = {}) } } +
            Screen(customizeHome, null) { CatalogueScreen(0.dp) }
    }

    private var screen by mutableStateOf<Screen?>(null)

    /** The pages' frame, as the page tests have it: a real container, the settings as stored, a navigator with a graph. */
    private fun frame(fontScale: Float) {
        compose.setContent {
            val controller = rememberNavController()
            val nav = Navigator(controller, openPlayer = {})
            val s by container.settings.stored.collectAsState()
            val d = LocalDensity.current
            CompositionLocalProvider(LocalContainer provides container, LocalNavigator provides nav, LocalDensity provides Density(d.density, fontScale)) {
                VinzTheme(s, artworkColor = null) {
                    CompositionLocalProvider(LocalAppSettings provides s, LocalFrosted provides false) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            NavHost(controller, "page") {
                                composable("page") { screen?.let { sc -> key(sc.name) { sc.content() } } }
                                composable(Routes.CATALOGUE) { Box(Modifier.fillMaxSize()) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun tag(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.TestTag)

    private fun texts(n: SemanticsNode, into: MutableList<String> = ArrayList()): List<String> {
        n.config.getOrNull(SemanticsProperties.Text)?.forEach { into += it.text }
        n.children.forEach { texts(it, into) }
        return into
    }

    private fun find(n: SemanticsNode, wanted: String): SemanticsNode? = if (tag(n) == wanted) n else n.children.firstNotNullOfOrNull { find(it, wanted) }

    private fun layouts(node: SemanticsNode, into: MutableList<Pair<String, TextLayoutResult>>) {
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.let { get ->
            val out = ArrayList<TextLayoutResult>()
            get(out)
            out.firstOrNull()?.let { into += it.layoutInput.text.text to it }
        }
        node.children.forEach { layouts(it, into) }
    }

    private val isRow = SemanticsMatcher("is one of the kit's five rows") { tag(it) in SettingsTags.rowTypes }

    /** A page as it draws with a fresh install's settings. */
    private class Seen(
        val name: String,
        val page: SettingsPageId?,
        /** Each group: its title ("" without one) and how many rows it holds (a listed list is one). */
        val groups: List<Pair<String, Int>>,
        /** What a card holds that is neither a row nor a listed list. */
        val notRows: List<String>,
        val rowsOutsideGroups: List<String>,
        val rowsWithoutValue: List<String>,
        /** Every test tag on the page that is not the kit's. */
        val tags: Set<String>,
        val hasReset: Boolean,
        /** Whether the bar's small one-line title was read, and whether it is cut ("…"). */
        val barTitleRead: Boolean,
        val barTitleCut: Boolean,
        /** The bar title's measures, for the report: its room, the width its text wants, lines, ellipsis. */
        val barInfo: String,
    )

    private fun read(sc: Screen): Seen {
        screen = sc
        compose.waitForIdle()
        val listTags = lists[sc.name].orEmpty().keys
        val contentTags = cardContent[sc.name].orEmpty().keys
        val gridGroups = grids[sc.name].orEmpty().keys
        val notRows = ArrayList<String>()
        val noValue = ArrayList<String>()
        val groups = compose.onAllNodes(hasTestTag(SettingsTags.GROUP)).fetchSemanticsNodes().sortedBy { it.positionInRoot.y }.map { g ->
            val card = find(g, SettingsTags.GROUP_CARD) ?: throw AssertionError("${sc.name}: a group without a card")
            val title = g.children.filter { it.id != card.id }.flatMap { texts(it) }.firstOrNull().orEmpty()
            val grid = title in gridGroups
            var gridCards = 0
            for (c in card.children) {
                val t = tag(c)
                when {
                    t in listTags || t in contentTags -> Unit
                    t in SettingsTags.rowTypes -> {
                        val valueless = t != SettingsTags.ROW_SWITCH && t != SettingsTags.ROW_LINK && c.config.getOrNull(SettingsRowValue).isNullOrBlank()
                        if (valueless) noValue += "$title › ${texts(c).firstOrNull()}"
                    }
                    grid && t == null -> gridCards++
                    else -> notRows += "$title › ${t ?: "untagged"} ${texts(c).take(2)}"
                }
            }
            // A listed grid of look cards counts as one row, as a list does.
            title to (card.children.size - gridCards + if (gridCards > 0) 1 else 0)
        }
        val outside = compose.onAllNodes(isRow and !hasAnyAncestor(hasTestTag(SettingsTags.GROUP_CARD))).fetchSemanticsNodes().map { texts(it).firstOrNull().orEmpty() }
        val tags = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { tag(it) }.filter { it !in kitTags }.toSet()
        val all = ArrayList<Pair<String, TextLayoutResult>>()
        layouts(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), all)
        val bar = all.filter { (text, l) -> text == sc.name && l.layoutInput.maxLines == 1 }.map { it.second }
        // Cut = an ellipsis on its line. (Not didOverflowWidth / hasVisualOverflow: true for text given more room than it needs.)
        val cut = bar.any { l -> (0 until l.lineCount).any { l.isLineEllipsized(it) } }
        val barInfo = bar.joinToString { l ->
            "room ${l.size.width}px, wants ${l.multiParagraph.maxIntrinsicWidth.toInt()}px, lines ${l.lineCount}, ellipsis ${(0 until l.lineCount).any { l.isLineEllipsized(it) }}"
        }
        // Last (it opens the ⋯ menu): does the page offer "Reset this page"?
        var reset = false
        if (compose.onAllNodes(hasTestTag(SettingsTags.PAGE_MORE)).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasTestTag(SettingsTags.PAGE_MORE)).performClick()
            compose.waitForIdle()
            reset = compose.onAllNodes(hasTestTag(SettingsTags.RESET_ITEM)).fetchSemanticsNodes().isNotEmpty()
        }
        return Seen(sc.name, sc.page, groups, notRows, outside, noValue, tags, reset, bar.isNotEmpty(), cut, barInfo)
    }

    /** Every page, read once for the whole class (the rules below are each a test of their own, on the same reading). */
    private fun seen(): List<Seen> = atDefaultText ?: run {
        frame(1f)
        screens.map { read(it) }.also { all ->
            atDefaultText = all
            File("build/settings-design").apply { mkdirs() }.resolve("pages.txt").writeText(all.joinToString("\n") { s ->
                "${s.name}: groups ${s.groups}; reset ${s.hasReset}; own tags ${s.tags}; not rows ${s.notRows}; outside ${s.rowsOutsideGroups}; no value ${s.rowsWithoutValue}; bar ${s.barInfo}"
            })
        }
    }

    private companion object {
        var atDefaultText: List<Seen>? = null
    }

    private fun none(what: String, offenders: List<String>) = assertTrue("$what (${offenders.size}):\n" + offenders.joinToString("\n"), offenders.isEmpty())

    // ------------------------------------------------------------------ The rules

    @Test fun everyPageDrawsWithTheKit() {
        val seen = seen()
        assertEquals("the 17 pages of the map, and Customize Home", 18, seen.size)
        none("A page with no group of rows", seen.filter { s -> s.groups.isEmpty() || s.groups.any { it.second == 0 } }.map { "${it.name}: ${it.groups}" })
    }

    /** Principle 7: at most 6 groups a page, at most 7 rows a group, with a fresh install's settings. */
    @Test fun budgets() {
        val seen = seen()
        none("More than 6 groups", seen.filter { it.groups.size > 6 }.map { "${it.name}: ${it.groups.size} groups ${it.groups.map { g -> g.first }}" })
        none("More than 7 rows in a group", seen.flatMap { s -> s.groups.filter { it.second > 7 }.map { "${s.name} › ${it.first}: ${it.second} rows" } })
    }

    /** Principle 6: five row types, nothing else (and the lists that are listed here). */
    @Test fun onlyTheFiveRows() {
        val seen = seen()
        none("In a group's card, but not one of the five rows or a listed list", seen.flatMap { s -> s.notRows.map { "${s.name}: $it" } })
        none("A row outside every group", seen.flatMap { s -> s.rowsOutsideGroups.map { "${s.name}: $it" } })
        // A listed list is there (a line for a list that is gone would hide the next thing that takes its tag).
        none("A list that is listed here but not drawn", lists.flatMap { (page, tags) -> tags.keys.filter { t -> seen.none { it.name == page && t in it.tags } }.map { "$page: $it" } })
        none("A list listed for a page that isn't one", lists.keys.filter { name -> seen.none { it.name == name } })
    }

    /** Principle 2: every row tells its state. Only a switch or a link may do without a value line. */
    @Test fun everyRowSaysItsValue() {
        val found = seen().flatMap { s -> s.rowsWithoutValue.map { "${s.name}: $it" } }
        none("A row without its value in words", found - knownValuelessRows.keys)
        // A known gap fixed meanwhile (another helper is on the kit): noted, not failed, so merges can land in any order.
        File("build/settings-design").apply { mkdirs() }.resolve("stale-value-gaps.txt").writeText((knownValuelessRows.keys - found.toSet()).joinToString("\n"))
    }

    /** What is listed as card content or a grid is drawn where it is listed (a stale line would hide the next thing there). */
    @Test fun whatIsListedIsThere() {
        val seen = seen()
        none("Card content listed but not drawn", cardContent.flatMap { (page, tags) -> tags.keys.filter { t -> seen.none { it.name == page && t in it.tags } }.map { "$page: $it" } })
        none("A grid listed for a group that is not drawn", grids.flatMap { (page, gs) -> gs.keys.filter { g -> seen.none { s -> s.name == page && s.groups.any { it.first == g } } }.map { "$page › $it" } })
    }

    /** Every test tag a page draws with is the kit's, a listed list, or listed with what it is. */
    @Test fun everyOwnTagIsListed() {
        val seen = seen()
        val wrong = seen.flatMap { s ->
            val known = lists[s.name].orEmpty().keys + cardContent[s.name].orEmpty().keys + ownTags[s.name].orEmpty().keys
            s.tags.filter { t -> known.none { t == it || (it.endsWith(":") && t.startsWith(it)) } }.map { "${s.name}: \"$it\"" }
        }
        none("A test tag of a page's own that is not listed in this test (a row? use the kit's; else list it with what it is)", wrong)
        none("Tags listed for a page that isn't one", ownTags.keys.filter { name -> seen.none { it.name == name } })
    }

    /** Principle 5: no group title repeats its page. */
    @Test fun noGroupIsCalledWhatItsPageIs() {
        none("A group called what its page is called", seen().flatMap { s -> s.groups.filter { it.first == s.name }.map { "${s.name} › ${it.first}" } })
        none("A search line whose group is its page's name", SettingsIndex.entries.filter { it.group.isNotBlank() && it.group == it.pageTitle }.map { "${it.pageTitle} › ${it.group} › ${it.title}" })
    }

    /** Principle 4: one place, one name. */
    @Test fun noTwoSearchLinesAreTheSame() {
        val twice = SettingsIndex.entries.groupBy { Triple(it.pageTitle, it.group, it.title) }.filterValues { it.size > 1 }.keys
        none("Two search lines with the same page, group and title", twice.map { "${it.first} › ${it.second} › ${it.third}" })
    }

    /** Principle 1, first tap: the root has a row for every page, and the row opens it. */
    @Test fun everyPageIsOneTapFromTheRoot() {
        val opened = ArrayList<Pair<SettingsPageId, String?>>()
        compose.setContent {
            val s by container.settings.stored.collectAsState()
            VinzTheme(s, artworkColor = null) {
                CompositionLocalProvider(LocalContainer provides container, LocalAppSettings provides s, LocalFrosted provides false) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        SettingsRootContent(0.dp, s, dark = true, showBack = false, selected = null, account = {}, onPerformance = {}, onOpen = { p, g -> opened += p to g }, onRoute = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        val wrong = ArrayList<String>()
        for (p in SettingsPageId.entries) {
            val rows = compose.onAllNodes(SemanticsMatcher.expectValue(SettingsRootEntry, p.key)).fetchSemanticsNodes()
            if (rows.size != 1) { wrong += "${p.title}: ${rows.size} rows on the root"; continue }
            if (tag(rows.single()) != SettingsTags.ROW_LINK || p.title !in texts(rows.single())) wrong += "${p.title}: its root row is not a Link row with its name"
            opened.clear()
            compose.onNode(SemanticsMatcher.expectValue(SettingsRootEntry, p.key)).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            if (opened != listOf(p to null)) wrong += "${p.title}: its root row opened $opened"
            // The route that tap takes resolves to the page itself.
            if (SettingsMap.resolve(p.key)?.page != p) wrong += "${p.title}: settings/${p.key} opens ${SettingsMap.resolve(p.key)?.page}"
        }
        none("A page that is not one tap from the root", wrong)
        // The root keeps to the budgets too (Performance mode's card and the five groups).
        val cards = compose.onAllNodes(hasTestTag(SettingsTags.GROUP_CARD)).fetchSemanticsNodes()
        assertTrue("the root: at most 6 groups (${cards.size})", cards.size in 1..6)
        assertTrue("the root: at most 7 rows a group ${cards.map { it.children.size }}", cards.all { it.children.size in 1..7 })
        assertTrue("the root: only rows in its cards", cards.all { c -> c.children.all { tag(it) in SettingsTags.rowTypes } })
    }

    /**
     * Principle 1, the taps after the first: every search line names a page of the map and a group that page draws, so
     * the setting is on that page (root → page → row), or in a sheet or on the screen a row of that group opens (one
     * more), never a page further. Customize Home's lines open that screen, which Home & Library links to.
     */
    @Test fun everySearchLineOpensAGroupItsPageDraws() {
        val drawn = seen().associate { s -> s.name to s.groups.map { it.first }.filter { it.isNotBlank() }.toSet() }
        val wrong = ArrayList<String>()
        for (e in SettingsIndex.entries) {
            val page = e.page ?: continue // the root's own lines (the account, Performance mode): found in place
            val line = "${e.pageTitle} › ${e.group} › ${e.title}"
            if (e.route != null) {
                if (e.routeTitle != customizeHome || page != SettingsPageId.HOME_LIBRARY) wrong += "$line: a line with a route that is not Customize Home's (${e.route})"
                else if (e.route != customizeHomeRoute(e.group.ifBlank { null })) wrong += "$line: opens ${e.route}, not Customize Home at its group"
                else if (e.group.isNotBlank() && e.group !in drawn.getValue(customizeHome)) wrong += "$line: Customize Home draws no group \"${e.group}\" (${drawn.getValue(customizeHome)})"
                continue
            }
            val target = SettingsMap.resolve(page.key, e.group.ifBlank { null })
            if (target == null || target.page != page || target.licenses) { wrong += "$line: settings/${page.key} resolves to $target"; continue }
            val at = target.find ?: continue // the page itself
            val known = drawn.getValue(page.title) + untitledGroups[page.title].orEmpty() + conditionalGroups[page.title].orEmpty().keys
            if (at !in known) wrong += "$line: ${page.title} draws no group \"$at\" with a fresh install's settings (${drawn.getValue(page.title)})"
        }
        val gaps = knownSearchGaps.keys
        none("A search line that does not open a group its page draws", wrong.filter { w -> gaps.none { w.startsWith("$it:") } })
        File("build/settings-design").apply { mkdirs() }.resolve("stale-search-gaps.txt").writeText(gaps.filter { g -> wrong.none { it.startsWith("$g:") } }.joinToString("\n"))
        // And Customize Home is a Link row of Home & Library: root → Home & Library → Customize Home → row.
        assertTrue("Home & Library links to Customize Home", File("src/main/java/com/vinz/music/ui/screens/settings/HomeLibraryPage.kt").readText().contains("nav.go(Routes.CATALOGUE)"))
    }

    /** A page with look settings of its own offers "Reset this page". */
    @Test fun aPageWithLookSettingsOffersReset() {
        val seen = seen()
        none("Look settings but no \"Reset this page\"", SettingsPageId.entries.filter { p -> p.lookKeys.isNotEmpty() && !seen.first { it.page == p }.hasReset }.map { it.title })
    }

    /** At 360 dp and text at 1.3×: the page's name fits the bar (it shows there once the page has scrolled). */
    @Test fun thePagesNameFitsTheBarAtLargeText() {
        frame(1.3f)
        val large = screens.map { read(it) }
        none("The bar's title could not be read", large.filter { !it.barTitleRead }.map { it.name })
        val cut = large.filter { it.barTitleCut }.map { it.name }.toSet()
        File("build/settings-design").mkdirs()
        File("build/settings-design").resolve("bar-titles-1.3.txt").writeText(
            "cut: $cut\nexempt but no longer cut (take out): ${barTitleKnownToBeCut - cut}\n" + large.joinToString("\n") { "${it.name}: ${it.barInfo}" },
        )
        none("A page's name is cut (\"…\") in the top bar at 360 dp and 1.3× text", large.filter { it.barTitleCut && it.name !in barTitleKnownToBeCut }.map { "${it.name}: ${it.barInfo}" })
    }

    /** At the usual text size too (the same temporary exemption: one kit issue). */
    @Test fun thePagesNameFitsTheBar() {
        val seen = seen()
        none("The bar's title could not be read", seen.filter { !it.barTitleRead }.map { it.name })
        File("build/settings-design").apply { mkdirs() }.resolve("bar-titles-1.0.txt").writeText(seen.joinToString("\n") { "${it.name}: ${it.barInfo}" })
        none("A page's name is cut in the top bar at the usual text size", seen.filter { it.barTitleCut && it.name !in barTitleKnownToBeCut }.map { "${it.name}: ${it.barInfo}" })
    }
}
