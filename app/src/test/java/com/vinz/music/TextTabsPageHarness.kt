package com.vinz.music

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.components.LocalFrosted
import com.vinz.music.ui.screens.StoredSettings
import com.vinz.music.ui.screens.settings.SettingsPageContent
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsRowValue
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.theme.Backdrop
import com.vinz.music.ui.theme.BackdropLayer
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.LocalWindowLayout
import com.vinz.music.ui.theme.VinzTheme
import com.vinz.music.ui.theme.WindowLayout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * 1.8.4: what the tests of the rebuilt Text & icons and Tabs & mini player pages share. The page is drawn for real: a
 * container of its own, the settings as stored, the page through the map ([SettingsPageContent]). Rows are found by
 * their group and title, and worked as a listener would (a tap, a chip, a pick in its sheet, a slider let go).
 */
abstract class TextTabsPageHarness(private val page: SettingsPageId) {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    protected val container by made

    /**
     * The settings' store is one for the whole test run (its file outlives a test's own container), and these tests
     * change nearly every setting of their page: each starts from Vinz as it comes, and leaves it so.
     */
    private fun asVinzComes() {
        kotlinx.coroutines.runBlocking { container.settings.editNow { it.clear() } }
        val fresh = AppSettings()
        val until = System.currentTimeMillis() + 3_000
        while (stored.let { it.themeMode != fresh.themeMode || it.performanceMode || it.navStyle != fresh.navStyle || it.fontChoice != fresh.fontChoice } && System.currentTimeMillis() < until) {
            runCatching { shadowOf(Looper.getMainLooper()).idle() }
            Thread.sleep(10)
        }
    }

    @org.junit.Before fun fresh() = asVinzComes()
    @org.junit.After fun close() { asVinzComes(); made.closeAfterTest() }

    protected val app: android.app.Application get() = RuntimeEnvironment.getApplication()
    protected val stored: AppSettings get() = container.settings.stored.value
    private var shown = false

    /** The tag of the tabs' reorder list (a list, not one of the five rows: plan §2.1.6). */
    protected val listTag = "settings:list"
    private val rowTags = SettingsTags.rowTypes + listTag

    /** Waits for a stored value (settings are written off the main thread), then for the page to follow. */
    protected fun await(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 8_000
        while (!condition() && System.currentTimeMillis() < until) {
            runCatching { shadowOf(Looper.getMainLooper()).idle() }
            Thread.sleep(10)
        }
        if (!condition()) fail("never happened: $what")
        if (shown) compose.waitForIdle()
    }

    /** [glass]: on a background, as the app's frosted pages are. [wide]: a tablet's window (the tabs can sit down the side). */
    protected fun show(fontScale: Float = 1f, glass: Boolean = false, wide: Boolean = false) {
        compose.setContent {
            val s by container.settings.stored.collectAsState()
            val nav = Navigator(rememberNavController(), openPlayer = {})
            val d = LocalDensity.current
            val window = if (wide) WindowLayout(widthDp = 900, heightDp = 1200, landscape = false) else WindowLayout.PHONE
            CompositionLocalProvider(
                LocalContainer provides container, LocalNavigator provides nav, LocalDensity provides Density(d.density, fontScale),
                LocalWindowLayout provides window,
            ) {
                VinzTheme(s, artworkColor = null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        if (glass) BackdropLayer(Backdrop.SPECTRUM, null, 0.35f, motion = false, modifier = Modifier.fillMaxSize())
                        CompositionLocalProvider(LocalFrosted provides glass) { StoredSettings { SettingsPageContent(page, 0.dp, onSearch = {}) } }
                    }
                }
            }
        }
        shown = true
        compose.waitForIdle()
    }

    // ------------------------------------------------------------------ Reading the page

    private fun tag(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.TestTag)

    private fun texts(n: SemanticsNode, into: MutableList<String> = ArrayList()): List<String> {
        n.config.getOrNull(SemanticsProperties.Text)?.forEach { into += it.text }
        n.children.forEach { texts(it, into) }
        return into
    }

    private fun find(n: SemanticsNode, wanted: String): SemanticsNode? =
        if (tag(n) == wanted) n else n.children.firstNotNullOfOrNull { find(it, wanted) }

    /** A group as drawn: its title ("" without one), its rows top to bottom, and what its card holds outside any row. */
    protected class Drawn(val title: String, val rows: List<SemanticsNode>, val loose: List<String>)

    protected fun groups(): List<Drawn> = compose.onAllNodes(hasTestTag(SettingsTags.GROUP), useUnmergedTree = true).fetchSemanticsNodes().map { g ->
        val card = find(g, SettingsTags.GROUP_CARD)!!
        // The header is what the group holds beside its card.
        val title = g.children.filter { it.id != card.id }.flatMap { texts(it) }.firstOrNull().orEmpty()
        val rows = ArrayList<SemanticsNode>()
        val loose = ArrayList<String>()
        fun walk(n: SemanticsNode) {
            for (c in n.children) {
                when {
                    tag(c) in rowTags -> rows += c
                    c.children.isEmpty() -> loose += texts(c)
                    else -> walk(c)
                }
            }
        }
        walk(card)
        Drawn(title, rows, loose)
    }

    protected fun titleOf(row: SemanticsNode): String = texts(row).firstOrNull().orEmpty()
    protected fun typeOf(row: SemanticsNode): String = tag(row).orEmpty()
    protected fun valueOf(row: SemanticsNode): String = row.config.getOrNull(SettingsRowValue).orEmpty()

    /** "Group › Row" for every row, as the page draws them now. */
    protected fun rowNames(): List<String> = groups().flatMap { g -> g.rows.map { "${g.title} › ${titleOf(it)}" } }

    protected fun rowNode(group: String, title: String): SemanticsNode {
        val g = groups().firstOrNull { it.title == group } ?: throw AssertionError("no group \"$group\" in ${groups().map { it.title }}")
        return g.rows.firstOrNull { titleOf(it) == title } ?: throw AssertionError("no row \"$title\" in $group: ${g.rows.map { titleOf(it) }}")
    }

    private fun isNode(n: SemanticsNode) = SemanticsMatcher("node ${n.id}") { it.id == n.id }

    protected fun row(group: String, title: String): SemanticsNodeInteraction = compose.onNode(isNode(rowNode(group, title)), useUnmergedTree = true)

    // ------------------------------------------------------------------ Working a row

    /** A tap on the row itself (a switch, a pick, a link). */
    protected fun tap(group: String, title: String) {
        row(group, title).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** A tap on the chip that reads [chip] in the row. */
    protected fun chip(group: String, title: String, chip: String) {
        compose.onNode(hasText(chip) and hasAnyAncestor(isNode(rowNode(group, title))), useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    /** The row's slider let go at [value]. */
    protected fun slide(group: String, title: String, value: Float) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and hasAnyAncestor(isNode(rowNode(group, title))), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        compose.waitForIdle()
    }

    protected fun sheetOpen() = compose.onAllNodes(hasTestTag(SettingsTags.SHEET)).fetchSemanticsNodes().isNotEmpty()

    /** Opens the row's sheet and taps the option that reads [option]; the sheet closes. */
    protected fun pick(group: String, title: String, option: String) {
        tap(group, title)
        assertTrue("$title opens a sheet", sheetOpen())
        compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText(option)).performClick()
        compose.waitUntil(8_000) { !sheetOpen() }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------------ Checks every page shares

    /** No text on the page is cut off or ends in "…". */
    protected fun clippedTexts(): List<String> {
        val clipped = ArrayList<String>()
        fun walk(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            val layout = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (text != null && layout != null) {
                val out = ArrayList<TextLayoutResult>()
                layout.invoke(out)
                // Too wide for its room, or ending in "…". (Not hasVisualOverflow: a one-line text with a line height of
                // its own reports a height overflow of a pixel's rounding, which cuts nothing off.)
                out.firstOrNull()?.let { l ->
                    val widest = (0 until l.lineCount).maxOfOrNull { l.getLineRight(it) - l.getLineLeft(it) } ?: 0f
                    val dots = (0 until l.lineCount).any { l.isLineEllipsized(it) }
                    if (widest - l.size.width > 1.5f || dots) clipped += "$text (wider by ${widest - l.size.width} px, ellipsized $dots)"
                }
            }
            n.children.forEach { walk(it) }
        }
        compose.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach { walk(it) }
        return clipped
    }

    /** Plan §2.1.7 and §2.9: at most 6 groups, at most 7 rows in each, only the kit's rows, each with its value. */
    protected fun assertBudgets() {
        val drawn = groups()
        assertTrue("at most 6 groups: ${drawn.map { it.title }}", drawn.size in 1..6)
        for (g in drawn) {
            assertTrue("${g.title}: at most 7 rows, has ${g.rows.map { titleOf(it) }}", g.rows.size in 1..7)
            assertTrue("${g.title}: outside any row: ${g.loose}", g.loose.isEmpty())
            for (r in g.rows) {
                val needsValue = typeOf(r) in setOf(SettingsTags.ROW_PICK, SettingsTags.ROW_SLIDER, SettingsTags.ROW_CHOICE)
                if (needsValue) assertTrue("${g.title} › ${titleOf(r)} says its value", valueOf(r).isNotBlank())
            }
        }
        assertTrue("no group is named as the page", drawn.none { it.title == page.title })
    }

    protected fun save(name: String): Bitmap {
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/settings-pages/${page.key}").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return shot
    }

    protected fun pageList() = compose.onNodeWithTag(SettingsTags.PAGE_LIST)
}
