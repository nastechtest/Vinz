package com.vinz.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertFalse
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.navArgument
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.components.LocalFrosted
import com.vinz.music.ui.screens.CatalogueScreen
import com.vinz.music.ui.screens.settings.SettingsPageContent
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsRowValue
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * What HomeLibraryPageTest and CustomizeHomePageTest share (1.8.4): [ListeningPageHarness] with a graph that holds
 * Settings → Home & Library, Customize Home on its own route (as in the app) and an empty page to come from, and the
 * few checks those two pages need of their own (two rows called "Show", a list in a group).
 */
abstract class HomePagesHarness : ListeningPageHarness(SettingsPageId.HOME_LIBRARY) {
    protected companion object {
        const val PAGE = "page"
        const val ELSEWHERE = "elsewhere"

        /** Customize Home's list of sections: a list in a group, which the kit has no row for. */
        const val SECTIONS = "customizeHome:sections"
    }

    /**
     * The graph, opened at [start]: [PAGE] (Home & Library), [Routes.CATALOGUE] (Customize Home) or [ELSEWHERE].
     * [findInRoute]: Customize Home's destination takes a `find` argument (what the app's graph needs for a search
     * result to open the screen at a group); without it the destination is the plain route, as the app has it today.
     */
    protected fun showAt(start: String, fontScale: Float = 1f, glass: Boolean = false, findInRoute: Boolean = false, over: (AppSettings) -> AppSettings = { it }) {
        this.over = over
        compose.setContent {
            val controller = rememberNavController().also { navController = it }
            val nav = Navigator(controller, openPlayer = {})
            val s by container.settings.stored.collectAsState()
            val shown = this.over(s)
            val d = LocalDensity.current
            CompositionLocalProvider(LocalContainer provides container, LocalNavigator provides nav, LocalDensity provides Density(d.density, fontScale)) {
                VinzTheme(shown, artworkColor = null) {
                    CompositionLocalProvider(LocalAppSettings provides shown, LocalFrosted provides glass) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            NavHost(controller, start) {
                                composable(PAGE) { SettingsPageContent(SettingsPageId.HOME_LIBRARY, 0.dp, onSearch = {}) }
                                if (findInRoute) composable(
                                    Routes.CATALOGUE + "?find={find}",
                                    listOf(navArgument("find") { type = NavType.StringType; nullable = true; defaultValue = null }),
                                ) { CatalogueScreen(0.dp) }
                                else composable(Routes.CATALOGUE) { CatalogueScreen(0.dp) }
                                composable(ELSEWHERE) { Box(Modifier.fillMaxSize()) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    protected fun route(): String? = navController?.currentDestination?.route?.substringBefore('?')

    protected fun groupTitles(): List<String> = compose.onAllNodes(hasTestTag(SettingsTags.GROUP)).fetchSemanticsNodes()
        .map { g -> g.children.first().config[SemanticsProperties.Text].first().text }

    /** A chip of a Show row (or any chip under [title]'s row) by its words; the rows called [title], top to bottom, are told apart by [nth]. */
    protected fun chip(label: String) {
        compose.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick) and hasAnyAncestor(hasTestTag(SettingsTags.ROW_CHOICE))).performClick()
        compose.waitForIdle()
    }

    protected fun valuesOf(title: String): List<String> = compose.onAllNodes(rowIs(title)).fetchSemanticsNodes().map { it.config[SettingsRowValue] }

    /**
     * The groups' sizes, top to bottom: every child of a card is one of the kit's five rows, or the one list
     * ([SECTIONS], which counts as a row).
     */
    protected fun sizes(): List<Int> {
        val cards = compose.onAllNodes(hasTestTag(SettingsTags.GROUP_CARD)).fetchSemanticsNodes()
        assertEquals("a card per group", compose.onAllNodes(hasTestTag(SettingsTags.GROUP)).fetchSemanticsNodes().size, cards.size)
        for (card in cards) {
            val other = card.children.filter { it.config.getOrNull(SemanticsProperties.TestTag).let { tag -> tag !in SettingsTags.rowTypes && tag != SECTIONS } }
            assertTrue("in a card but not a row: ${other.map { it.config }}", other.isEmpty())
        }
        return cards.map { it.children.size }
    }

    private fun layouts(node: SemanticsNode, into: MutableList<Pair<String, TextLayoutResult>>) {
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.let { get ->
            val out = ArrayList<TextLayoutResult>()
            get(out)
            out.firstOrNull()?.let { into += it.layoutInput.text.text to it }
        }
        node.children.forEach { layouts(it, into) }
    }

    /**
     * [ListeningPageHarness.assertNoTextCutOff] for a page with another name: no text is cut off or ends in "…", but
     * the bar's small one-line copy of [barTitle] (the scaffold's; it shows once scrolled).
     */
    protected fun assertNothingCutOff(what: String, barTitle: String) {
        val all = ArrayList<Pair<String, TextLayoutResult>>()
        layouts(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), all)
        assertTrue("$what: the page's text was read (${all.size})", all.size > 10)
        for ((text, l) in all) {
            if (text == barTitle && l.layoutInput.maxLines == 1) continue
            assertFalse("$what: \"$text\" is cut off below", l.didOverflowHeight)
            for (line in 0 until l.lineCount) {
                assertFalse("$what: \"$text\" ends in an ellipsis", l.isLineEllipsized(line))
                assertTrue("$what: \"$text\" is wider than its room", l.getLineRight(line) - l.getLineLeft(line) <= l.size.width + 1f)
            }
        }
    }

    /** ≤ 6 groups, ≤ 7 rows a group, every row with its value in words (a switch or a link may do without). */
    protected fun assertFits(what: String) {
        val sizes = sizes()
        assertTrue("$what: the page is built with the kit (its groups are cards of rows): $sizes", sizes.isNotEmpty() && sizes.all { it > 0 })
        assertTrue("$what: at most 6 groups: $sizes", sizes.size <= 6)
        assertTrue("$what: at most 7 rows a group: $sizes", sizes.all { it <= 7 })
        for (r in rows()) {
            val type = r.config[SemanticsProperties.TestTag]
            if (type == SettingsTags.ROW_SWITCH || type == SettingsTags.ROW_LINK) continue
            assertTrue("$what: a $type without a value: ${r.config}", r.config[SettingsRowValue].isNotBlank())
        }
    }
}
