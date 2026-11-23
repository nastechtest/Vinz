package com.vinz.music

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.SongPlays
import com.vinz.music.widget.ReplayWeek
import com.vinz.music.widget.ReplayWidgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Replay widgets (1.8.5) drawn through their RemoteViews, as the launcher gets them, over a wallpaper-like gradient:
 * both sizes with a week, nothing played yet, very long names, right to left, White light, and the phone in light and
 * dark mode (the widgets are dark glass either way, like the rest of the family). Sheets in app/build/widget-previews/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xxhdpi", application = android.app.Application::class)
class ReplayWidgetRenderTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val density get() = context.resources.displayMetrics.density

    // Strings keeps the first resources it's given; each test has its own (its language).
    @Before fun words() { Strings.resources = context.resources }

    private val week = ReplayWeek(
        SongPlays("x", 14, 52 * 60_000L, "Blinding Lights", "The Weeknd", null, "After Hours", 200, "UCw", "The Weeknd"),
        ArtistPlays("The Weeknd", 31, 118 * 60_000L, null, "UCw"),
        "https://photo",
    )
    private val long = ReplayWeek(
        SongPlays(
            "y", 3, 11 * 60_000L, "Bohemian Rhapsody (Remastered 2011) - Live at Wembley Stadium, July 1986, Extended Version",
            "Queen, David Bowie, Freddie Mercury, Brian May, Roger Taylor, John Deacon", null, null, 354, null, "Queen",
        ),
        ArtistPlays("Wolfgang Amadeus Mozart and the Vienna Philharmonic Orchestra", 9, 47 * 60_000L, null, null),
    )

    /** A stand-in cover: a rounded square in two colors (as Widgets.squareCover hands it). */
    private fun cover(a: Int, b: Int, round: Float): Bitmap {
        val px = 168
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, px.toFloat(), px.toFloat(), a, b, Shader.TileMode.CLAMP) }
        Canvas(bmp).drawRoundRect(0f, 0f, px.toFloat(), px.toFloat(), px * round, px * round, p)
        return bmp
    }

    private class Shot(val bmp: Bitmap, val root: View)

    private fun render(wide: Boolean, w: ReplayWeek, wDp: Int, hDp: Int, art: Boolean = true, white: Boolean = false): Shot =
        render(if (wide) R.layout.widget_replay else R.layout.widget_replay_small, w, wDp, hDp, art, white)

    private fun render(layout: Int, w: ReplayWeek, wDp: Int, hDp: Int, art: Boolean = true, white: Boolean = false): Shot {
        val px = (wDp * density).toInt()
        val py = (hDp * density).toInt()
        // The launcher's host view, in the phone's direction (left to right, or right to left in Arabic).
        val host = FrameLayout(context).apply { layoutDirection = context.resources.configuration.layoutDirection }
        val rv = ReplayWidgets.views(
            context, layout, w,
            if (art) cover(0xFFE0465A.toInt(), 0xFF4B2BB8.toInt(), 0.14f) else null,
            if (art && w.artistPhoto != null) cover(0xFFF2B33D.toInt(), 0xFF1E8C7E.toInt(), 0.5f) else null,
            hours = false, white = white,
        )
        val v = rv.apply(context, host)
        host.addView(v)
        host.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(py, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, px, py)
        val bmp = Bitmap.createBitmap(px, py, Bitmap.Config.ARGB_8888)
        host.draw(Canvas(bmp))
        return Shot(bmp, v)
    }

    private fun sheet(name: String, shots: List<Shot>) {
        val gap = (16 * density).toInt()
        val width = shots.maxOf { it.bmp.width } + gap * 2
        val height = shots.sumOf { it.bmp.height + gap } + gap
        val sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), intArrayOf(0xFF2B3A55.toInt(), 0xFF4E3B5C.toInt(), 0xFF1E2A30.toInt()), null, Shader.TileMode.CLAMP)
        })
        var y = gap
        for (s in shots) {
            c.drawBitmap(s.bmp, gap.toFloat(), y.toFloat(), null)
            y += s.bmp.height + gap
        }
        val out = File("build/widget-previews").apply { mkdirs() }
        File(out, name).outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(shots.all { it.bmp.getPixel(it.bmp.width / 2, it.bmp.height / 2) != Color.TRANSPARENT }) { "a widget drew nothing" }
    }

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.shown(id: Int) = findViewById<View>(id).visibility == View.VISIBLE
    /** Where view [id] is drawn, in the widget's own pixels. */
    private fun View.box(id: Int) = findViewById<View>(id).let { v -> Rect(0, 0, v.width, v.height).also { (this as android.view.ViewGroup).offsetDescendantRectToMyCoords(v, it) } }
    private fun View.ellipsized(id: Int) = findViewById<TextView>(id).layout.let { it != null && it.getEllipsisCount(it.lineCount - 1) > 0 }

    @Test fun bothSizesWithAWeek() {
        val small = render(false, week, 150, 150)
        val wide = render(true, week, 320, 120)
        val noPhoto = render(true, week, 320, 120, art = false)
        sheet("replay-widgets.png", listOf(small, wide, noPhoto, render(false, week, 150, 150, white = true), render(true, week, 320, 120, white = true)))
        for (s in listOf(small, wide)) {
            assertTrue(s.root.shown(R.id.rp_content))
            assertTrue(!s.root.shown(R.id.rp_empty))
            assertEquals("Blinding Lights", s.root.text(R.id.rp_title))
            assertEquals("The Weeknd", s.root.text(R.id.rp_artist))
            assertEquals("TOP SONG THIS WEEK", s.root.text(R.id.rp_song_label))
        }
        assertEquals("14 plays · 52 min", wide.root.text(R.id.rp_stat))
        assertEquals("The Weeknd", wide.root.text(R.id.rp_artist_name))
        assertEquals("118 min", wide.root.text(R.id.rp_artist_time))
        assertEquals("TOP ARTIST", wide.root.text(R.id.rp_artist_label))
        // The artist sits beside the song, apart from it.
        assertTrue(wide.root.box(R.id.rp_title).right <= wide.root.box(R.id.rp_artist_name).left)
    }

    /** Whether view [id] is drawn whole inside the widget's padding ([padDp]) of [s]. */
    private fun whole(s: Shot, id: Int, padDp: Int = 12): Boolean {
        val b = s.root.box(id)
        return b.height() > 0 && b.top >= 0 && b.bottom <= s.bmp.height - (padDp * density).toInt() + 1
    }

    /**
     * The song widget at the shortest it can be made (110 × 110 dp, its minResize) and just under the 2×2 layout's
     * height: the layout picked for the size shows the cover, label, title and artist whole. The 2×2 layout at 110 dp
     * cut the title and the artist off (about 155 dp of content).
     */
    @Test fun theSongWidgetAtItsShortestShowsItsTitleAndArtist() {
        val xml = File("src/main/res/xml/widget_replay_small_info.xml").readText()
        fun dp(attr: String) = Regex("android:$attr=\"(\\d+)dp\"").find(xml)!!.groupValues[1].toInt()
        val minW = dp("minResizeWidth")
        val minH = dp("minResizeHeight")
        val shortest = ReplayWidgets.layoutFor(wideWidget = false, minWidth = minW, height = minH)
        val shots = listOf(
            render(shortest, week, minW, minH),
            render(shortest, long, minW, minH),
            render(shortest, week, minW, minH, art = false),
            render(shortest, ReplayWeek(), minW, minH),
            render(ReplayWidgets.layoutFor(wideWidget = false, minWidth = minW, height = ReplayWidgets.TALL_DP), week, minW, ReplayWidgets.TALL_DP),
            render(ReplayWidgets.layoutFor(wideWidget = false, minWidth = minW, height = ReplayWidgets.TALL_DP), long, minW, ReplayWidgets.TALL_DP),
            // Short but a little wider, as a 2×1 or 3×1 often is.
            render(ReplayWidgets.layoutFor(wideWidget = false, minWidth = 170, height = minH), week, 170, minH),
        )
        sheet("replay-widgets-shortest.png", shots)
        for ((i, s) in shots.withIndex()) {
            if (i == 3) continue
            for (id in listOf(R.id.rp_song_art, R.id.rp_song_label, R.id.rp_title, R.id.rp_artist)) {
                assertTrue("shot $i: ${context.resources.getResourceEntryName(id)} at ${s.root.box(id)} in ${s.bmp.width}×${s.bmp.height}", whole(s, id))
            }
        }
        assertEquals("Blinding Lights", shots[0].root.text(R.id.rp_title))
        assertEquals("The Weeknd", shots[0].root.text(R.id.rp_artist))
        assertTrue("title ends in …", shots[1].root.ellipsized(R.id.rp_title))
        // Nothing played: the mark and the whole line.
        val empty = shots[3]
        assertTrue(empty.root.shown(R.id.rp_empty) && whole(empty, R.id.rp_mark) && whole(empty, R.id.rp_empty_text))
        assertTrue(!empty.root.ellipsized(R.id.rp_empty_text))
    }

    @Test fun nothingPlayedYet() {
        val small = render(false, ReplayWeek(), 150, 150)
        val wide = render(true, ReplayWeek(), 320, 120)
        sheet("replay-widgets-empty.png", listOf(small, wide))
        for (s in listOf(small, wide)) {
            assertTrue("the friendly line, never a blank widget", s.root.shown(R.id.rp_empty))
            assertTrue(!s.root.shown(R.id.rp_content))
            assertEquals("Play something to see your week", s.root.text(R.id.rp_empty_text))
            assertTrue("Vinz's mark", s.root.shown(R.id.rp_mark))
        }
    }

    @Test fun veryLongNamesAreCutShort() {
        val small = render(false, long, 150, 150)
        val wide = render(true, long, 320, 120)
        sheet("replay-widgets-long.png", listOf(small, wide, render(true, long, 260, 110)))
        for (s in listOf(small, wide)) {
            assertTrue("title ends in …", s.root.ellipsized(R.id.rp_title))
            assertTrue("artist ends in …", s.root.ellipsized(R.id.rp_artist))
            assertTrue("the title stays inside the widget", s.root.box(R.id.rp_title).right <= s.bmp.width)
        }
        // The song's words never run into the artist's.
        val song = wide.root.box(R.id.rp_title)
        val stat = wide.root.box(R.id.rp_stat)
        val divider = wide.root.box(R.id.rp_divider)
        val artist = wide.root.box(R.id.rp_artist_name)
        assertTrue("$song / $divider", song.right <= divider.left && stat.right <= divider.left)
        assertTrue("$artist / $divider", artist.left >= divider.right && artist.right <= wide.bmp.width)
        assertTrue("artist name ends in …", wide.root.ellipsized(R.id.rp_artist_name))
    }

    @Config(qualifiers = "ar-ldrtl-xxhdpi")
    @Test fun rightToLeft() {
        val small = render(false, week, 150, 150)
        val wide = render(true, week, 320, 120)
        sheet("replay-widgets-rtl.png", listOf(small, wide, render(true, ReplayWeek(), 320, 120)))
        assertEquals(View.LAYOUT_DIRECTION_RTL, wide.root.layoutDirection)
        assertEquals("الأغنية الأولى هذا الأسبوع", wide.root.text(R.id.rp_song_label))
        // Mirrored: the song on the right, the artist on its left; the cover at the start (right) of its row.
        assertTrue(wide.root.box(R.id.rp_artist_name).right <= wide.root.box(R.id.rp_divider).left)
        assertTrue(wide.root.box(R.id.rp_title).left >= wide.root.box(R.id.rp_divider).right)
        assertTrue(wide.root.box(R.id.rp_song_art).left > wide.root.box(R.id.rp_title).left)
    }

    @Config(qualifiers = "notnight-xxhdpi")
    @Test fun phoneInLightMode() = mode("replay-widgets-light.png")

    @Config(qualifiers = "night-xxhdpi")
    @Test fun phoneInDarkMode() = mode("replay-widgets-dark.png")

    private fun mode(name: String) {
        val small = render(false, week, 150, 150)
        val wide = render(true, week, 320, 120)
        sheet(name, listOf(small, wide))
        // Dark glass with white words in both modes, like the other Vinz widgets.
        val panel = wide.bmp.getPixel(wide.bmp.width / 2, (4 * density).toInt())
        assertEquals(0xFF1B1B22.toInt(), panel)
        assertEquals(Color.WHITE, wide.root.findViewById<TextView>(R.id.rp_title).currentTextColor)
    }
}
