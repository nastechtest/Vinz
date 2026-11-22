package com.vinz.music

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.MixPrefs
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.screens.SettingEntry
import com.vinz.music.ui.screens.SettingsIndex
import com.vinz.music.ui.screens.SettingsSection
import com.vinz.music.ui.screens.StoredSettings
import com.vinz.music.ui.screens.settings.SettingsMap
import com.vinz.music.ui.screens.settings.SettingsPageContent
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsRootContent
import com.vinz.music.ui.screens.settings.SettingsRootEntry
import com.vinz.music.ui.screens.settings.SettingsRootGroup
import com.vinz.music.ui.screens.settings.SettingsRowValue
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.SettingsTarget
import com.vinz.music.ui.screens.settings.index
import com.vinz.music.ui.screens.settings.lookKeys
import com.vinz.music.ui.screens.settings.oldGroups
import com.vinz.music.ui.screens.settings.value
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import com.vinz.music.ui.tour.Tour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.8.4, the map of Settings (plan §2.3): 17 pages in 5 groups, each one tap from the root; every key of before the
 * rework, every tour step and every `Routes.settings(` call site still leads to a page that exists; every line of
 * Settings' search is on a page that exists and draws it. A page's helper adds nothing here: the checks run over
 * [SettingsPageId.entries] and each page's own lists.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h900dp-xhdpi", application = android.app.Application::class)
class SettingsMapTest {
    @get:Rule val compose = createComposeRule()

    private val pages = SettingsPageId.entries

    // ------------------------------------------------------------------ The map

    @Test fun theMapIsTheApprovedOne() {
        fun names(g: SettingsRootGroup) = SettingsMap.groups.first { it.first == g }.second.map { it.title }
        assertEquals(listOf("Make it yours", "Listening", "Your music", "Connections", "App"), SettingsMap.groups.map { it.first.title })
        assertEquals(listOf("Looks", "Theme & colors", "Text & icons", "Player", "Lyrics & Sing", "Home & Library", "Tabs & mini player"), names(SettingsRootGroup.MAKE_IT_YOURS))
        assertEquals(listOf("Playback", "Sound & quality", "Gestures & buttons"), names(SettingsRootGroup.LISTENING))
        assertEquals(listOf("Downloads & data", "Recommendations & history"), names(SettingsRootGroup.YOUR_MUSIC))
        assertEquals(listOf("Car", "Speakers, TVs & browser", "Accounts & sharing"), names(SettingsRootGroup.CONNECTIONS))
        assertEquals(listOf("Language & region", "Backup & about"), names(SettingsRootGroup.APP))
        assertEquals(17, pages.size)
        assertEquals("every page is in a group, once", pages.toSet(), SettingsMap.groups.flatMap { it.second }.toSet())
        // Budget (plan §2.1.7): at most 7 rows a group.
        assertTrue(SettingsMap.groups.all { it.second.size <= 7 })
    }

    @Test fun noTwoRootEntriesShareANameOrAKey() {
        assertEquals("names", pages.size, pages.map { it.title }.toSet().size)
        assertEquals("keys", pages.size, pages.map { it.key }.toSet().size)
        assertTrue("a name is never blank", pages.all { it.title.isNotBlank() })
        // A group's name is not a page's either ("Connections" the group, not a page).
        assertTrue(SettingsRootGroup.entries.none { g -> pages.any { it.title == g.title } })
        // A route's key is plain: nothing a link would have to escape.
        assertTrue(pages.all { Regex("[a-z_]+").matches(it.key) })
    }

    @Test fun everyPageHasItsOwnFile() {
        val dir = File("src/main/java/com/vinz/music/ui/screens/settings")
        for (p in pages) {
            // LYRICS_SING → LyricsSingPage.kt, with `fun LyricsSingPage(` and `lyricsSingIndex` in it.
            val name = p.name.lowercase().split('_').joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
            val file = File(dir, "${name}Page.kt")
            assertTrue("$file", file.isFile)
            val text = file.readText()
            val prefix = name.replaceFirstChar { it.lowercase() }
            for (part in listOf("fun ${name}Page(", "val ${prefix}Index:", "val ${prefix}LookKeys:", "val ${prefix}OldGroups:", "fun ${prefix}Value(")) {
                assertTrue("${file.name} has no `$part`", part in text)
            }
        }
    }

    @Test fun everyRootLineSaysSomething() {
        for (s in listOf(AppSettings(), AppSettings(performanceMode = true, lossless = true, carLyrics = true, contentCountry = "DE", mix = MixPrefs(on = true, auto = false, seconds = 6)))) {
            for (p in pages) assertTrue("${p.title}'s line", p.value(s, true).isNotBlank() && p.value(s, false).isNotBlank())
        }
        // It is live: it follows the settings.
        val six = AppSettings(mix = MixPrefs(on = true, auto = false, seconds = 6))
        assertTrue(SettingsPageId.PLAYBACK.value(six), tr(R.string.root_crossfade_seconds, 6) in SettingsPageId.PLAYBACK.value(six))
        assertTrue(tr(R.string.root_crossfade_off) in SettingsPageId.PLAYBACK.value(AppSettings(mix = MixPrefs(on = false))))
        assertTrue("Germany" in SettingsPageId.LANGUAGE_REGION.value(AppSettings(contentCountry = "DE")))
        assertTrue(tr(R.string.lossless) in SettingsPageId.SOUND_QUALITY.value(AppSettings(lossless = true)))
        // Crossfade is Playback's now, not Sound & quality's.
        assertTrue(tr(R.string.crossfade) !in SettingsPageId.SOUND_QUALITY.value(AppSettings(mix = MixPrefs(on = true))))
    }

    @Test fun resetKeysAreLookSettings() {
        val look = container.settings.lookKinds.keys
        for (p in pages) {
            val unknown = p.lookKeys.filter { it !in look }
            assertTrue("${p.title}: not look settings: $unknown", unknown.isEmpty())
            assertEquals("${p.title}: a key twice", p.lookKeys.size, p.lookKeys.toSet().size)
        }
        // No key is reset by two pages.
        val twice = pages.flatMap { it.lookKeys }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        assertTrue("on two pages' resets: $twice", twice.isEmpty())
        // The old pages' resets are their new pages' (OldTabs still calls it by its old section).
        assertEquals(SettingsPageId.TABS_MINI_PLAYER.lookKeys, SettingsSection.INTERFACE.lookKeys)
        assertTrue("nav_style" in SettingsSection.INTERFACE.lookKeys)
        assertTrue("font_choice" in SettingsPageId.TEXT_ICONS.lookKeys && "corners" in SettingsPageId.TEXT_ICONS.lookKeys)
    }

    // ------------------------------------------------------------------ Old keys, tour steps, call sites

    @Test fun everyOldKeyOpensAPageThatExists() {
        assertEquals("every old key has its alias, and nothing else does", SettingsSection.entries.map { it.key }.toSet(), SettingsMap.aliases.keys)
        for (old in SettingsSection.entries) {
            val target = SettingsMap.resolve(old.key)
            assertNotNull("settings/${old.key}", target)
            assertTrue(target!!.page in pages)
            assertEquals(old.page, target.page)
        }
        assertNull("no such page", SettingsMap.resolve("nothing"))
        assertNull(SettingsMap.resolve(null))
    }

    @Test fun anOldKeyOpensThePageThatHoldsItNow() {
        fun to(key: String, find: String? = null) = SettingsMap.resolve(key, find)
        // Customize Vinz's own page held the theme and Motion.
        assertEquals(SettingsPageId.THEME_COLORS, to("appearance")!!.page)
        // (Theme & colors is rebuilt: the old Motion group is in "Theme and motion" now.)
        assertEquals(SettingsTarget(SettingsPageId.THEME_COLORS, tr(R.string.tl_theme_and_motion)), to("appearance", tr(R.string.motion)))
        assertEquals(SettingsPageId.THEME_COLORS, to("color")!!.page)
        assertEquals(SettingsPageId.THEME_COLORS, to("glass")!!.page)
        assertEquals(SettingsPageId.TEXT_ICONS, to("text")!!.page)
        assertEquals(SettingsPageId.TEXT_ICONS, to("icons")!!.page)
        assertEquals(SettingsPageId.LYRICS_SING, to("lyrics")!!.page)
        assertEquals(SettingsPageId.LYRICS_SING, to("lyrics_look")!!.page)
        // Playback & sound is two pages: a link to one of its groups opens the page that has the group.
        assertEquals(SettingsTarget(SettingsPageId.PLAYBACK), to("sound"))
        // (Playback's group around the Crossfade row has another name now: the page says which.)
        assertEquals(SettingsTarget(SettingsPageId.PLAYBACK, SettingsPageId.PLAYBACK.oldGroups.getValue(tr(R.string.crossfade))), to("sound", tr(R.string.crossfade)))
        assertEquals(SettingsTarget(SettingsPageId.SOUND_QUALITY, tr(R.string.quality)), to("sound", tr(R.string.quality)))
        assertEquals(SettingsTarget(SettingsPageId.SOUND_QUALITY, tr(R.string.sound)), to("sound", tr(R.string.sound)))
        // Devices & connections: Android Auto is Car; its sharing group is Accounts & sharing's.
        assertEquals(SettingsPageId.CAR, to("devices")!!.page)
        assertEquals(SettingsTarget(SettingsPageId.ACCOUNTS_SHARING, tr(R.string.sharing_and_links)), to("devices", tr(R.string.sharing_and_links)))
        assertEquals(SettingsTarget(SettingsPageId.THEME_COLORS, tr(R.string.home_screen_widgets)), to("icons", tr(R.string.home_screen_widgets)))
        // The three accounts were pages; they are groups of one page, and their links open it there.
        assertEquals(SettingsTarget(SettingsPageId.ACCOUNTS_SHARING, tr(R.string.spotify)), to("spotify"))
        assertEquals(SettingsTarget(SettingsPageId.ACCOUNTS_SHARING, "Last.fm"), to("lastfm"))
        assertEquals(SettingsTarget(SettingsPageId.ACCOUNTS_SHARING, tr(R.string.discord)), to("discord"))
        // A link that names its own group keeps it.
        assertEquals(SettingsTarget(SettingsPageId.RECOMMENDATIONS_HISTORY, tr(R.string.hist_title)), to("library", tr(R.string.hist_title)))
        assertEquals(SettingsTarget(SettingsPageId.GESTURES_BUTTONS, tr(R.string.notification_and_car)), to("controls", tr(R.string.notification_and_car)))
        // The licenses are a screen of Backup & about, not a page of the map.
        assertEquals(SettingsTarget(SettingsPageId.BACKUP_ABOUT, licenses = true), to("licenses"))
        assertEquals(false, to("about")!!.licenses)
        // A page's own key, with and without a group; an empty "find" is none.
        for (p in pages) {
            assertEquals(SettingsTarget(p), to(p.key))
            assertEquals(SettingsTarget(p), to(p.key, ""))
        }
        assertEquals(SettingsTarget(SettingsPageId.PLAYER, tr(R.string.cover)), to("player", tr(R.string.cover)))
        // A group the page has renamed since is found by its old name.
        val renamed = SettingsPageId.LANGUAGE_REGION.oldGroups
        assertEquals(SettingsTarget(SettingsPageId.LANGUAGE_REGION, renamed.getValue(tr(R.string.region))), to("language", tr(R.string.region)))
    }

    /** The groups the page's search lines name: what a link's "find" can land on. */
    private fun groupsOn(page: SettingsPageId) = page.index.map { it.group }.filter { it.isNotBlank() }.toSet() + page.oldGroups.values

    private fun parse(route: String): Pair<String, String?> {
        val key = route.removePrefix("settings/").substringBefore('?')
        val find = route.substringAfter("?find=", "").takeIf { it.isNotBlank() }?.let { android.net.Uri.decode(it) }
        return key to find
    }

    @Test fun everyTourStepLandsOnAPageThatExists() {
        val routes = Tour.steps.mapNotNull { it.showMe }.filter { it.startsWith("settings/") }
        assertTrue("the tour's settings steps were read (${routes.size})", routes.size >= 15)
        for (route in routes) {
            val (key, find) = parse(route)
            val target = SettingsMap.resolve(key, find)
            assertNotNull("$route opens no page", target)
            // A step that names a group lands on one the page has.
            if (find != null) assertTrue("$route: ${target!!.page.title} has no group \"${target.find}\"", target!!.find in groupsOn(target.page))
        }
        // The three that name a group, by what they show.
        assertEquals(SettingsPageId.RECOMMENDATIONS_HISTORY, SettingsMap.resolve("library", tr(R.string.tune_radios))!!.page)
        assertEquals(SettingsPageId.PLAYER, SettingsMap.resolve("player", tr(R.string.lock_screen_title))!!.page)
    }

    /**
     * Every `Routes.settings(` in the app names its page in a way this test knows resolves: a key in quotes, a
     * `SettingsSection` or `SettingsPageId` constant's key, or the key of one such taken from a list of them.
     */
    @Test fun everyRoutesSettingsCallSiteResolves() {
        val call = Regex("""Routes\.settings\(\s*([^,)]+)""")
        val sites = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            // Code only: a comment may name the function too.
            val code = f.readLines().filterNot { it.trim().let { l -> l.startsWith("*") || l.startsWith("/*") || l.startsWith("//") } }.joinToString("\n")
            call.findAll(code).map { f.name to it.groupValues[1].trim() }
        }.toList()
        assertTrue("the call sites were read (${sites.size})", sites.size >= 25)
        // A key taken from a value whose type is SettingsSection or SettingsPageId (checked by the compiler; all of
        // either resolve, see above).
        val fromOne = setOf("sec.key", "page.key", "it.key")
        val unknown = ArrayList<String>()
        for ((file, arg) in sites) {
            val quoted = Regex("\"([a-z_]+)\"").matchEntire(arg)?.groupValues?.get(1)
            val section = Regex("""(?:com\.vinz\.music\.ui\.screens\.)?SettingsSection\.(\w+)\.key""").matchEntire(arg)?.groupValues?.get(1)
            val page = Regex("""(?:com\.vinz\.music\.ui\.screens\.settings\.)?SettingsPageId\.(\w+)\.key""").matchEntire(arg)?.groupValues?.get(1)
            val key = when {
                quoted != null -> quoted
                section != null -> SettingsSection.valueOf(section).key
                page != null -> SettingsPageId.valueOf(page).key
                arg in fromOne -> continue
                else -> { unknown += "$file: Routes.settings($arg"; continue }
            }
            assertNotNull("$file: Routes.settings($arg opens no page", SettingsMap.resolve(key))
        }
        assertTrue("Call sites this test can't read (name the page by a constant's key): $unknown", unknown.isEmpty())
        // The app's route hands the key to Settings as it is (new keys and old).
        val main = File("src/main/java/com/vinz/music/MainActivity.kt").readText()
        assertTrue("settings/{section}?find={find}" in main && "SettingsScreen(bottomPadding, key = it.arguments?.getString(\"section\")" in main)
    }

    // ------------------------------------------------------------------ Search

    @Test fun everySearchLineIsOnAPageThatExists() {
        for (p in pages) {
            assertTrue("${p.title} has search lines", p.index.isNotEmpty())
            val elsewhere = p.index.filter { it.page != p }
            assertTrue("In ${p.title}'s list but not on it: ${elsewhere.map { it.title }}", elsewhere.isEmpty())
        }
        for (e in SettingsIndex.entries) {
            val page = e.page ?: continue
            assertTrue(page in pages)
            if (e.route != null) continue
            // Its result opens that very page, at its group.
            val (key, find) = parse(e.opens!!)
            assertEquals(e.title, SettingsTarget(page, e.group.ifBlank { null }), SettingsMap.resolve(key, find))
        }
        // The root's own are found in place; every page is found by its name.
        assertTrue(SettingsIndex.entries.filter { it.page == null }.all { it.opens == null })
        for (p in pages) assertEquals(p.title, 1, SettingsIndex.entries.count { it.isPage && it.page == p })
    }

    @Test fun oldPlacesAreStillFound() {
        fun pagesFor(query: String) = SettingsIndex.search(query).filter { it.isPage }.map { it.page }.toSet()
        // A page of before the rework finds the pages that took its settings over.
        assertEquals(
            setOf(SettingsPageId.LOOKS, SettingsPageId.THEME_COLORS, SettingsPageId.TEXT_ICONS, SettingsPageId.PLAYER, SettingsPageId.LYRICS_SING, SettingsPageId.HOME_LIBRARY, SettingsPageId.TABS_MINI_PLAYER),
            pagesFor("Customize Vinz"),
        )
        assertEquals(setOf(SettingsPageId.PLAYBACK, SettingsPageId.SOUND_QUALITY), pagesFor("Playback & sound"))
        assertEquals(setOf(SettingsPageId.GESTURES_BUTTONS), pagesFor("Controls & gestures"))
        assertEquals(setOf(SettingsPageId.DOWNLOADS_DATA), pagesFor("Downloads, storage & data"))
        assertEquals(setOf(SettingsPageId.RECOMMENDATIONS_HISTORY), pagesFor("Recommendations & Replay"))
        assertEquals(setOf(SettingsPageId.CAR, SettingsPageId.SPEAKERS_TVS_BROWSER, SettingsPageId.ACCOUNTS_SHARING), pagesFor("Devices & connections"))
        assertTrue(SettingsPageId.THEME_COLORS in pagesFor("Glass & backgrounds"))
        assertTrue(SettingsPageId.TEXT_ICONS in pagesFor("Icons & shapes"))
        // What people call casting finds the page that has it.
        assertTrue(pagesFor("chromecast").toString(), SettingsPageId.SPEAKERS_TVS_BROWSER in pagesFor("chromecast"))
        // The page comes before the settings that only match by their page's old name.
        val results = SettingsIndex.search("Playback & sound")
        assertTrue(results.take(2).all { it.isPage })
        // And an old page's name still narrows a search to what was on it.
        val eq = SettingsIndex.search("playback sound equalizer")
        assertTrue(eq.any { it.title == tr(R.string.equalizer) && it.page == SettingsPageId.SOUND_QUALITY })
        // The new names find their pages first.
        for (p in pages) assertEquals(p.title, p, SettingsIndex.search(p.title).first { it.isPage }.page)
        // Where things went: the equalizer and the quality on Sound & quality, crossfade on Playback, sharing on Accounts.
        fun pageOf(id: Int) = SettingsIndex.entries.first { it.title == tr(id) }.page
        assertEquals(SettingsPageId.SOUND_QUALITY, pageOf(R.string.equalizer))
        assertEquals(SettingsPageId.SOUND_QUALITY, pageOf(R.string.lossless_playback))
        assertEquals(SettingsPageId.PLAYBACK, pageOf(R.string.when_the_queue_ends))
        assertEquals(SettingsPageId.ACCOUNTS_SHARING, pageOf(R.string.links_you_share))
        assertEquals(SettingsPageId.CAR, pageOf(R.string.lyrics_in_the_car))
        assertEquals(SettingsPageId.THEME_COLORS, pageOf(R.string.reduce_motion))
        assertEquals(SettingsPageId.THEME_COLORS, pageOf(R.string.home_screen_widgets))
    }

    // ------------------------------------------------------------------ Drawn

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val container by made
    @org.junit.After fun close() = made.closeAfterTest()

    private fun texts(node: SemanticsNode, into: MutableList<String>) {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { into += it.text }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { into += it }
        node.children.forEach { texts(it, into) }
    }

    private fun entry(key: String) = SemanticsMatcher.expectValue(SettingsRootEntry, key)

    @Test fun everyPageOpensFromTheRootInOneTap() {
        val opened = ArrayList<Pair<SettingsPageId, String?>>()
        compose.setContent {
            VinzTheme(AppSettings(), artworkColor = null) {
                SettingsRootContent(
                    0.dp, AppSettings(), dark = true, showBack = false, selected = null,
                    account = {}, onPerformance = {}, onOpen = { p, g -> opened += p to g }, onRoute = {},
                )
            }
        }
        compose.waitForIdle()
        for (p in pages) {
            compose.onNodeWithTag(SettingsTags.PAGE_LIST).performScrollToNode(androidx.compose.ui.test.hasAnyDescendant(entry(p.key)) or entry(p.key))
            val row = compose.onNode(entry(p.key), useUnmergedTree = true)
            val node = row.fetchSemanticsNode()
            assertEquals("the row says the page's state", p.value(AppSettings(), true), node.config[SettingsRowValue])
            assertEquals(SettingsTags.ROW_LINK, node.config[SemanticsProperties.TestTag])
            row.performClick()
            compose.waitForIdle()
            assertEquals("one tap on ${p.title}", p to null, opened.last())
        }
        assertEquals(pages.toList(), opened.map { it.first })
        // The root has no jump chips (its search is for that), and its own switch.
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasTestTag(SettingsTags.JUMP_CHIPS)).fetchSemanticsNodes().size)
    }

    /**
     * 1.8.4 (seen on the A16, 392 dp wide): every page's line on the root fits one line, with Vinz as it comes and with
     * every live state its line can say on, and names no group that's gone (Speakers, TVs & browser said "Speakers & TVs").
     */
    @Config(qualifiers = "w392dp-h4000dp-xxhdpi")
    @Test fun everyRootLineFitsOneLineAndNamesNoGoneGroup() {
        val fresh = AppSettings()
        val live = fresh.copy(
            webRemote = true, handoffAvailable = true, discordPresence = true, mix = fresh.mix.copy(on = true), lossless = true, carLyrics = true, contentCountry = "DE",
        )
        // Group names of before 1.8.4 that no page draws any more (an old link's name for a group, not a group now), and
        // that aren't a setting's own name either ("Crossfade" was a group and is what Playback's line says while it's on).
        val now = SettingsIndex.entries.flatMap { listOf(it.group, it.title, it.shown) }.toSet() + tr(R.string.crossfade)
        val gone = (pages.flatMap { it.oldGroups.keys } + tr(R.string.speakers_tvs)).filter { it !in now }.toSet()
        assertTrue(tr(R.string.speakers_tvs) in gone)
        var s by mutableStateOf(fresh)
        compose.setContent {
            VinzTheme(AppSettings(), artworkColor = null) {
                SettingsRootContent(0.dp, s, dark = true, showBack = false, selected = null, account = {}, onPerformance = {}, onOpen = { _, _ -> }, onRoute = {})
            }
        }
        val cut = ArrayList<String>()
        for (state in listOf(fresh, live)) {
            s = state
            compose.waitForIdle()
            for (p in pages) {
                val line = p.value(state, true)
                // A part of the line that is a gone group's name ("Speakers & TVs · Browser remote on"), not a word in it.
                val parts = line.split(" · ", ", ").map { it.trim() }
                assertTrue("${p.title}: \"$line\" names a group that's gone", parts.none { it in gone })
                val node = compose.onNode(entry(p.key), useUnmergedTree = true).fetchSemanticsNode()
                assertEquals(line, node.config[SettingsRowValue])
                fun walk(n: SemanticsNode) {
                    val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                    val layout = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action
                    if (text == line && layout != null) {
                        val out = ArrayList<androidx.compose.ui.text.TextLayoutResult>()
                        layout.invoke(out)
                        out.firstOrNull()?.let { l -> if (l.lineCount > 1 || (0 until l.lineCount).any { l.isLineEllipsized(it) }) cut += "${p.title}: \"$line\" (${l.lineCount} lines)" }
                    }
                    n.children.forEach { walk(it) }
                }
                walk(node)
            }
        }
        assertTrue("root lines that don't fit one line at 392 dp: $cut", cut.isEmpty())
    }

    /**
     * Lines whose row or group isn't drawn on their page with a fresh install's settings, and why. A line with
     * `needs` is left out by itself (it says what to turn on first).
     */
    private val notDrawnByDefault: Map<Int, String> = mapOf(
        R.string.home_screen_widgets to "only while a Vinz widget is on the home screen",
        // Accounts & sharing: Last.fm's card has no title (its first row names it), and these four show once it's connected.
        R.string.last_fm_scrobbling to "only once Last.fm is connected",
        R.string.first_artist_only to "only once Last.fm is connected",
        R.string.love_when_i_like_a_song to "only once Last.fm is connected",
        R.string.cn_disconnect_lastfm to "only once Last.fm is connected",
        R.string.sign_out_of_discord to "only once Discord is signed in",
    )

    @Test fun everyPageDrawsAndHoldsWhatSearchSaysItDoes() {
        var shown by mutableStateOf<SettingsPageId?>(null)
        compose.setContent {
            val nav = Navigator(rememberNavController(), openPlayer = {})
            CompositionLocalProvider(LocalContainer provides container, LocalNavigator provides nav) {
                VinzTheme(AppSettings(), artworkColor = null) {
                    StoredSettings { shown?.let { p -> key(p) { SettingsPageContent(p, 0.dp, onSearch = {}) } } }
                }
            }
        }
        val settings = AppSettings()
        val skip = notDrawnByDefault.keys.map { tr(it).substringBefore("%1\$s").trim() }.toSet()
        val missing = ArrayList<String>()
        for (p in pages) {
            shown = p
            compose.waitForIdle()
            assertEquals("the page's title is its root entry's name", p.title, compose.onNodeWithTag(SettingsTags.PAGE_TITLE).fetchSemanticsNode().config[SemanticsProperties.Text].first().text)
            assertEquals("its bar has the search button", 1, compose.onAllNodes(androidx.compose.ui.test.hasTestTag(SettingsTags.PAGE_SEARCH)).fetchSemanticsNodes().size)
            val drawn = ArrayList<String>().also { texts(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), it) }
            // A fold shows its groups' names in one line ("Stopping and pausing, How they cross").
            // An old group's heading is drawn in capitals, so the words are compared without their case.
            val words = drawn.flatMap { listOf(it) + it.split(", ") }.map { it.lowercase() }.toSet()
            for (e in p.index) {
                if (e.route != null || e.needs?.on(settings) == false || e.title in skip) continue
                val group = e.group.lowercase()
                val title = e.title.lowercase()
                val there = (group.isNotBlank() && group in words) || title in words || words.any { it.startsWith(title) }
                if (!there) missing += "${p.title} › ${e.group} › ${e.title}"
            }
        }
        assertTrue("In a page's search lines, but neither the row nor its group is drawn on that page: $missing", missing.isEmpty())
    }
}
