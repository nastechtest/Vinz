package com.vinz.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vinz.music.ui.player.QueueHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #427 (1.8.4): with a large display size and big text, the queue's top row cut its title off and wrapped a button onto
 * two lines. Here the row is laid out as on such a phone: 320 dp wide (280 dp between the player's margins), text at
 * 1.5×, display size 1.3×, with every button showing. Each label stays on one line; one that doesn't fit ends in "…"
 * or sits in a row that scrolls; nothing lies over anything else.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w480dp-h800dp-xhdpi")
class QueueHeaderFitTest {
    @get:Rule val compose = createComposeRule()

    private fun show(title: String, widthDp: Int = 280, fontScale: Float = 1.5f, displaySize: Float = 1.3f) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * displaySize, fontScale)) {
                Box(Modifier.width(widthDp.dp).background(Color.Black)) {
                    QueueHeader(title, dj = false, onDj = {}, onSave = {}, onClear = {}, locked = false, onLock = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
    private fun textOf(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
    private fun layoutOf(n: SemanticsNode): TextLayoutResult =
        mutableListOf<TextLayoutResult>().also { n.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(it) }.first()
    /** The text got the width its one line needs. */
    private fun fits(l: TextLayoutResult) = l.size.width >= l.multiParagraph.maxIntrinsicWidth - 1f
    private fun whole(n: SemanticsNode) = Rect(n.positionInRoot, androidx.compose.ui.geometry.Size(n.size.width.toFloat(), n.size.height.toFloat()))
    private fun cut(n: SemanticsNode) = n.boundsInRoot.let { it.width < whole(n).width - 0.5f || it.height < whole(n).height - 0.5f }
    private fun scrolls(n: SemanticsNode): Boolean = generateSequence(n.parent) { it.parent }.any { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }

    /** The row's rules; returns the title's layout. */
    private fun checkRow(title: String): TextLayoutResult = compose.runOnIdle {
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val nodes = all(root)
        val texts = nodes.filter { textOf(it) != null }
        val lock = nodes.single { it.config.getOrNull(SemanticsProperties.ContentDescription) != null }
        assertEquals("the title, DJ, Save and Clear", 4, texts.size)
        for (t in texts) {
            val name = textOf(t)
            val layout = layoutOf(t)
            assertEquals("\"$name\" is on one line", 1, layout.lineCount)
            if (!fits(layout)) assertTrue("\"$name\" doesn't fit, so it ends in …", layout.isLineEllipsized(0))
            if (cut(t)) assertTrue("\"$name\" is partly out of sight (${t.boundsInRoot} of ${whole(t)}), so its row scrolls", scrolls(t))
        }
        val titleNode = texts.single { textOf(it) == title }
        assertFalse("the title is all in sight", cut(titleNode))
        assertFalse("the lock is all in sight", cut(lock))
        assertTrue("the lock is inside the row", lock.boundsInRoot.right <= root.boundsInRoot.right + 0.5f && lock.boundsInRoot.left >= 0f)
        // What's in sight of each: no two share any space.
        val seen = (texts + lock).map { (textOf(it) ?: "lock") to it.boundsInRoot }.filter { it.second.width > 0f && it.second.height > 0f }
        for (i in seen.indices) for (j in i + 1 until seen.size) {
            val (a, b) = seen[i] to seen[j]
            val shared = a.second.intersect(b.second)
            assertFalse("\"${a.first}\" ${a.second} and \"${b.first}\" ${b.second} overlap", shared.width > 0.5f && shared.height > 0.5f)
        }
        layoutOf(titleNode)
    }

    @Test fun onANarrowScreenWithBigTextEveryLabelHolds() {
        show("Queue")
        val title = checkRow("Queue")
        assertFalse("\"Queue\" has room: it's whole", !fits(title) || title.isLineEllipsized(0))
    }

    @Test fun aLongTitleEndsInDotsAndTheButtonsStayReachable() {
        show("Warteschlange der Wiedergabe")
        val title = checkRow("Warteschlange der Wiedergabe")
        assertTrue("too long for the row: …", title.isLineEllipsized(0))
    }

    @Test fun atTheUsualSizeNothingScrollsOrIsCut() {
        show("Queue", widthDp = 320, fontScale = 1f, displaySize = 1f)
        val title = checkRow("Queue")
        assertFalse(!fits(title) || title.isLineEllipsized(0))
        compose.runOnIdle {
            val nodes = all(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
            for (t in nodes.filter { textOf(it) != null }) assertFalse("\"${textOf(t)}\" is whole", cut(t) || !fits(layoutOf(t)) || layoutOf(t).isLineEllipsized(0))
        }
    }
}
