package com.vinz.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.vinz.music.data.lyrics.LyricLine
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.LockLayout
import com.vinz.music.data.prefs.PlayerBackground
import com.vinz.music.data.prefs.ScrubberColor
import com.vinz.music.data.prefs.ScrubberStyle
import com.vinz.music.data.prefs.ScrubberThumb
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.lockscreen.LockBarLook
import com.vinz.music.ui.lockscreen.LockLyricLines
import com.vinz.music.ui.lockscreen.LockOutput
import com.vinz.music.ui.lockscreen.LockPlayerGround
import com.vinz.music.ui.lockscreen.LockPlayerLook
import com.vinz.music.ui.lockscreen.LockScreenActions
import com.vinz.music.ui.lockscreen.LockScreenContent
import com.vinz.music.ui.lockscreen.LockScreenLayout
import com.vinz.music.ui.lockscreen.LockScreenRules
import com.vinz.music.ui.lockscreen.LockTags
import com.vinz.music.ui.lockscreen.rememberLockPlayerLook
import com.vinz.music.ui.player.ArtworkMesh
import com.vinz.music.ui.player.LyricsLook
import com.vinz.music.ui.player.coverGradientOf
import com.vinz.music.ui.player.scrubberFill
import com.vinz.music.ui.player.withWords
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.ui.theme.VinzTheme
import com.vinz.music.ui.theme.boxBlur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.8.4, D53 (Reddit): the lock screen with the in-app player's look, and a heart on the Lyrics layout.
 *
 * With the look off every layout must draw exactly what 1.8.3.1 drew: the pictures in `src/test/resources/lockscreen/`
 * were made by this test on 1.8.3.1's code. If the lock screen is changed on purpose, look at
 * `build/lockscreen/look/off-*.png` and copy them over those. Pictures with the look on: `build/lockscreen/look/on-*.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
class LockScreenPlayerLookTest {
    @get:Rule val compose = createComposeRule()

    /** A made-up cover, for the picture only: two colors and a disc. */
    private fun picture(): Bitmap {
        val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 256f, 0xFF1B1464.toInt(), 0xFFFF9A5A.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 256f, 256f, p)
        p.shader = null
        p.color = 0xFFFFD27A.toInt()
        canvas.drawCircle(128f, 150f, 45f, p)
        return b
    }

    private val lines = listOf("First test line", "Second test line", "Third test line", "Fourth test line").mapIndexed { i, text ->
        LyricLine(i * 5_000L, text, (i + 1) * 5_000L).withWords()
    }

    private val dark = AppSettings(themeMode = ThemeMode.DARK)

    private fun content(
        layout: LockLayout,
        showLike: Boolean = true,
        liked: Boolean = false,
        repeat: Int? = null,
        look: LockPlayerLook? = null,
    ): LockScreenContent {
        val art = picture()
        val clock = mutableStateOf(7_000L)
        return LockScreenContent(
            title = "Test title", artist = "Test artist",
            frost = boxBlur(art, 3).asImageBitmap(),
            cover = { m, shape -> Image(art.asImageBitmap(), null, m.clip(shape), contentScale = ContentScale.Crop) },
            coverShape = RoundedCornerShape(14.dp),
            playing = true, liked = liked, showLike = showLike,
            output = LockOutput(VinzIcons.Headphones, "Test headphones"),
            position = { 84_000L }, durationMs = 202_000L, volume = { 0.6f }, time = "9:41", date = "WEDNESDAY, NOVEMBER 4",
            lyrics = if (layout == LockLayout.LYRICS) { m -> LockLyricLines(lines, clock, LyricsLook.Classic, null, m) } else null,
            repeat = repeat,
            look = look,
        )
    }

    private val noActions = LockScreenActions({}, {}, {}, {}, {}, {}, {}, {})

    private fun show(settings: AppSettings = dark, screen: @Composable () -> Unit) {
        compose.setContent {
            VinzTheme(settings, artworkColor = null) {
                Box(Modifier.fillMaxSize().background(Color.Black).padding(top = 28.dp, bottom = 20.dp)) { screen() }
            }
        }
        compose.waitForIdle()
    }

    private fun shot(name: String): Bitmap {
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/lockscreen/look").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return shot
    }

    /** How many pixels differ. */
    private fun differing(a: Bitmap, b: Bitmap): Int {
        assertEquals("width", a.width, b.width)
        assertEquals("height", a.height, b.height)
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        return pa.indices.count { pa[it] != pb[it] }
    }

    private fun golden(layout: LockLayout): Bitmap {
        val file = File("src/test/resources/lockscreen/off-${layout.name.lowercase()}.png")
        assertTrue("no picture of 1.8.3.1's ${layout.name} layout at $file", file.exists())
        return BitmapFactory.decodeFile(file.path)
    }

    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    private fun assertOnlyTheControlsTakeATouch(what: String) {
        val touchable = SemanticsMatcher("takes a touch") { node ->
            listOf(SemanticsActions.OnClick, SemanticsActions.OnLongClick, SemanticsActions.SetProgress, SemanticsActions.ScrollBy, SemanticsActions.SetText)
                .any { it in node.config }
        }
        val nodes = compose.onAllNodes(touchable, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$what: nothing can be touched?", nodes.isNotEmpty())
        for (n in nodes) {
            val tag = n.config.getOrNull(SemanticsProperties.TestTag)
            assertTrue("$what: something else can be touched: tag=$tag ${n.config}", tag in LockTags.touchable)
        }
    }

    // ---------------------------------------------------------------- The look off: as 1.8.3.1

    private fun offIsAsBefore(layout: LockLayout) {
        show { LockScreenLayout(layout, content(layout, showLike = layout != LockLayout.LYRICS), noActions) }
        val now = shot("off-${layout.name.lowercase()}")
        assertEquals("$layout with the look off: pixels that differ from 1.8.3.1's", 0, differing(golden(layout), now))
    }

    // The Lyrics layout is drawn without the heart here (Like not shown): with it, 1.8.4 adds the heart on purpose.
    @Test fun offCoverIsAsBefore() = offIsAsBefore(LockLayout.COVER)
    @Test fun offFullIsAsBefore() = offIsAsBefore(LockLayout.FULL)
    @Test fun offLyricsIsAsBefore() = offIsAsBefore(LockLayout.LYRICS)
    @Test fun offQuietIsAsBefore() = offIsAsBefore(LockLayout.QUIET)

    /** Performance mode with the look off: the Quiet layout, plain black, exactly as before. */
    @Test fun offInPerformanceModeIsQuietAsBefore() {
        val s = AppSettings(themeMode = ThemeMode.DARK, performanceMode = true).effective()
        val layout = LockScreenRules.layoutFor(LockLayout.FULL, s.performanceMode, hideSong = false)
        assertEquals(LockLayout.QUIET, layout)
        var look: LockPlayerLook? = LockPlayerLook({}, LockBarLook(ScrubberStyle.BOLD))
        show(s) {
            look = rememberLockPlayerLook(s, null, hidden = false)
            LockScreenLayout(layout, content(layout, look = look), noActions)
        }
        assertNull("no player's look with the setting off", look)
        assertEquals("pixels that differ from 1.8.3.1's Quiet", 0, differing(golden(LockLayout.QUIET), shot("off-performance")))
    }

    // ---------------------------------------------------------------- The look on

    /** The player's look from made-up art: the same drawing the lock screen uses, without the network. */
    @Composable
    private fun lookOf(s: AppSettings, hidden: Boolean = false, wave: FloatArray? = null): LockPlayerLook {
        val art = remember { picture() }
        // As the player's mesh is: a few cells of the cover's colors, smoothed into a small texture.
        val mesh = remember { ArtworkMesh(Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(art, 6, 6, true), 32, 32, true).asImageBitmap()) }
        val blur = remember { boxBlur(art, 3).asImageBitmap() }
        val gradient = remember { coverGradientOf(art) }
        val fill = scrubberFill(s.scrubberColor, null)
        return LockPlayerLook(
            ground = { LockPlayerGround(s, mesh, blur, gradient, hidden) },
            bar = LockBarLook(s.scrubberStyle, s.scrubberThickness, fill, s.scrubberThumb, wave),
        )
    }

    private fun onDiffersFromOff(layout: LockLayout) {
        val s = dark.copy(view = dark.view.copy(lockScreenPlayerLook = true))
        show(s) { LockScreenLayout(layout, content(layout, showLike = layout != LockLayout.LYRICS, look = lookOf(s)), noActions) }
        val on = shot("on-${layout.name.lowercase()}")
        val changed = differing(golden(layout), on)
        // The background is the whole screen: far more than a line's worth of pixels.
        assertTrue("$layout with the player's look: only $changed pixels differ from the look off", changed > on.width * on.height / 4)
        assertOnlyTheControlsTakeATouch("$layout with the player's look")
        // The line is still the one thing to seek with, read and set the same way.
        assertEquals(1, tagged(LockTags.SEEK).size)
        assertTrue(SemanticsActions.SetProgress in tagged(LockTags.SEEK).single().config)
    }

    @Test fun onCoverTakesThePlayersLook() = onDiffersFromOff(LockLayout.COVER)
    @Test fun onFullTakesThePlayersLook() = onDiffersFromOff(LockLayout.FULL)
    @Test fun onLyricsTakesThePlayersLook() = onDiffersFromOff(LockLayout.LYRICS)
    @Test fun onQuietTakesThePlayersLook() = onDiffersFromOff(LockLayout.QUIET)

    /** The real wiring: the setting on gives a look made from the player's settings; off gives none. */
    @Test fun theLookFollowsThePlayersSettings() {
        var s by mutableStateOf(dark)
        var look: LockPlayerLook? = null
        show(dark) {
            look = rememberLockPlayerLook(s, null, hidden = false)
            LockScreenLayout(LockLayout.COVER, content(LockLayout.COVER, look = look), noActions)
        }
        assertNull("off by default", look)
        assertFalse(AppSettings().view.lockScreenPlayerLook)
        val off = shot("wired-off")
        assertEquals("off: as 1.8.3.1", 0, differing(golden(LockLayout.COVER), off))

        s = dark.copy(
            view = dark.view.copy(lockScreenPlayerLook = true), player = dark.player.copy(background = PlayerBackground.THEME),
            scrubberStyle = ScrubberStyle.THUMB, scrubberColor = ScrubberColor.WHITE, scrubberThickness = 1.5f, scrubberThumb = ScrubberThumb.RING,
        )
        compose.waitForIdle()
        val bar = look!!.bar
        assertEquals(ScrubberStyle.THUMB, bar.style)
        assertEquals(1.5f, bar.thick)
        assertEquals(ScrubberThumb.RING, bar.knob)
        assertTrue("the player's bar color", bar.fill is SolidColor)
        assertTrue(differing(off, shot("wired-theme")) > off.width * off.height / 4)

        // "With the style": a dot where the player's style has a knob, none where it doesn't.
        assertEquals(ScrubberThumb.DOT, LockBarLook(ScrubberStyle.RIBBONS).knob)
        assertEquals(ScrubberThumb.NONE, LockBarLook(ScrubberStyle.HAIRLINE).knob)
    }

    /** Every background and every bar style the player has, drawn on the lock screen (pictures to look at). */
    @Test fun everyBackgroundAndBarStyleDraws() {
        val on = dark.copy(view = dark.view.copy(lockScreenPlayerLook = true))
        var s by mutableStateOf(on)
        var wave by mutableStateOf<FloatArray?>(null)
        show(on) { LockScreenLayout(LockLayout.COVER, content(LockLayout.COVER, look = lookOf(s, wave = wave)), noActions) }
        val grounds = PlayerBackground.entries.associateWith { bg ->
            s = on.copy(player = on.player.copy(background = bg))
            compose.waitForIdle()
            shot("ground-${bg.name.lowercase()}")
        }
        // Black is black behind the clock; White is the mesh here (the lock screen is drawn for a dark ground).
        assertEquals(0xFF000000.toInt(), grounds.getValue(PlayerBackground.BLACK).getPixel(4, 40))
        assertEquals(0, differing(grounds.getValue(PlayerBackground.MESH), grounds.getValue(PlayerBackground.WHITE)))
        val different = listOf(PlayerBackground.MESH, PlayerBackground.BLUR, PlayerBackground.THEME, PlayerBackground.BLACK, PlayerBackground.GRADIENT)
        for (a in different) for (b in different) if (a < b) {
            assertTrue("$a and $b look the same", differing(grounds.getValue(a), grounds.getValue(b)) > 1000)
        }
        val bars = ScrubberStyle.entries.associateWith { style ->
            s = on.copy(scrubberStyle = style, player = on.player.copy(background = PlayerBackground.BLACK))
            wave = if (style == ScrubberStyle.WAVE) FloatArray(100) { 0.2f + 0.8f * ((it * 7) % 10) / 10f } else null
            compose.waitForIdle()
            shot("bar-${style.name.lowercase()}")
        }
        for (a in ScrubberStyle.entries) for (b in ScrubberStyle.entries) if (a < b) {
            assertTrue("$a and $b bars look the same", differing(bars.getValue(a), bars.getValue(b)) > 20)
        }
    }

    /**
     * The phone is locked: nothing on the lock screen may run on its own. With everything that moves on the player turned
     * up (a lively mesh, a moving Appearance background, Ribbons, a song playing), the screen comes to rest, and five
     * seconds on not one pixel has changed.
     */
    @Test fun nothingKeepsMoving() {
        val lively = dark.copy(
            view = dark.view.copy(lockScreenPlayerLook = true), meshSpeed = 1f, backdropMotion = true, reduceMotion = false,
            scrubberStyle = ScrubberStyle.RIBBONS,
            // Aurora: light fields that drift on the player.
            backdrops = mapOf(com.vinz.music.ui.theme.PLAYER_BACKDROP to "AURORA"),
        )
        assertFalse(com.vinz.music.ui.lockscreen.appearanceFromCover(lively))
        // "Now playing" and the frost from what's playing come from the cover: they're never drawn live here.
        assertTrue(com.vinz.music.ui.lockscreen.appearanceFromCover(lively.copy(backdrops = mapOf(com.vinz.music.ui.theme.PLAYER_BACKDROP to "ARTWORK"))))
        var s by mutableStateOf(lively)
        // The test's clock by hand: with it advancing by itself, Compose's tests switch endless animations off, and a
        // drifting background would look still here. (Shown failing with the mesh given the player's speed.)
        compose.mainClock.autoAdvance = false
        show(lively) { LockScreenLayout(LockLayout.COVER, content(LockLayout.COVER, look = lookOf(s)), noActions) }
        for (bg in listOf(PlayerBackground.MESH, PlayerBackground.APPEARANCE, PlayerBackground.GRADIENT, PlayerBackground.BLUR)) {
            s = lively.copy(player = lively.player.copy(background = bg))
            compose.waitForIdle()
            // What starts with a new background (a fade of under a second) has finished.
            compose.mainClock.advanceTimeBy(3_000)
            compose.waitForIdle()
            val first = shot("still-${bg.name.lowercase()}")
            compose.mainClock.advanceTimeBy(5_000)
            compose.waitForIdle()
            assertEquals("$bg: pixels that moved in five seconds", 0, differing(first, shot("still-${bg.name.lowercase()}-later")))
        }
    }

    /** A hidden song shows nothing made from its cover: black where the cover's colors would be, as the frost gives way. */
    @Test fun aHiddenSongKeepsItsCoverOutOfTheBackground() {
        val on = dark.copy(view = dark.view.copy(lockScreenPlayerLook = true))
        var s by mutableStateOf(on)
        show(on) { Box(Modifier.fillMaxSize()) { lookOf(s, hidden = true).ground() } }
        for (bg in listOf(PlayerBackground.MESH, PlayerBackground.BLUR, PlayerBackground.GRADIENT, PlayerBackground.WHITE, PlayerBackground.BLACK)) {
            s = on.copy(player = on.player.copy(background = bg))
            compose.waitForIdle()
            val img = shot("hidden-${bg.name.lowercase()}")
            val px = IntArray(img.width * img.height).also { img.getPixels(it, 0, img.width, 0, 0, img.width, img.height) }
            assertTrue("$bg: something of the cover shows behind a hidden song", px.all { it == 0xFF000000.toInt() })
        }
    }

    // ---------------------------------------------------------------- The heart on the Lyrics layout

    @Test fun theLyricsLayoutHasAHeartWhenLikeIsShown() {
        var liked by mutableStateOf(false)
        var likes = 0
        var others = 0
        val actions = LockScreenActions(
            onCover = { others++ }, onLike = { likes++; liked = !liked }, onPrevious = { others++ }, onPlayPause = { others++ }, onNext = { others++ },
            onSeek = { others++ }, onVolume = { others++ }, onVolumeDone = { others++ }, onRepeat = { others++ },
        )
        show { LockScreenLayout(LockLayout.LYRICS, content(LockLayout.LYRICS, liked = liked, repeat = Player.REPEAT_MODE_ALL), actions) }
        assertEquals("one heart on the Lyrics layout", 1, tagged(LockTags.LIKE).size)
        assertOnlyTheControlsTakeATouch("Lyrics with the heart")

        // It fits a 360 dp phone beside the line and the repeat button, on none of them.
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val heart = tagged(LockTags.LIKE).single().boundsInRoot
        assertTrue("the heart is on the screen ($heart in $root)", heart.left >= root.left && heart.right <= root.right && heart.width > 0)
        for (tag in listOf(LockTags.REPEAT, LockTags.SEEK, LockTags.NEXT, LockTags.PLAY, LockTags.PREVIOUS, LockTags.COVER)) {
            for (other in tagged(tag)) assertTrue("the heart and $tag apart ($heart, ${other.boundsInRoot})", !heart.deflate(1f).overlaps(other.boundsInRoot.deflate(1f)))
        }

        // A tap likes the song, through the one action every layout's heart has, and nothing else.
        val like = tr(R.string.widget_like)
        val unlike = tr(R.string.lock_screen_unlike)
        fun label() = tagged(LockTags.LIKE).single().config.getOrNull(SemanticsActions.OnClick)?.label
        assertEquals(like, label())
        compose.onAllNodes(hasTestTag(LockTags.LIKE), useUnmergedTree = true)[0].performClick()
        compose.waitForIdle()
        assertEquals("liked once", 1, likes)
        assertEquals("nothing else happened", 0, others)
        assertEquals("the heart now offers to take it back", unlike, label())
        shot("lyrics-heart")
    }

    @Test fun noHeartOnTheLyricsLayoutWhenLikeIsNotShown() {
        show { LockScreenLayout(LockLayout.LYRICS, content(LockLayout.LYRICS, showLike = false, repeat = Player.REPEAT_MODE_ALL), noActions) }
        assertEquals(0, tagged(LockTags.LIKE).size)
        assertEquals(1, tagged(LockTags.REPEAT).size)
        assertOnlyTheControlsTakeATouch("Lyrics without the heart")
    }

    @Test fun theOtherLayoutsHeartsAreWhereTheyWere() {
        // Cover and Full cover have one beside the title, Quiet has none: as before.
        var layout by mutableStateOf(LockLayout.COVER)
        var likes = 0
        val actions = LockScreenActions({}, { likes++ }, {}, {}, {}, {}, {}, {})
        val contents = LockLayout.entries.associateWith { content(it) }
        show { LockScreenLayout(layout, contents.getValue(layout), actions) }
        for ((next, hearts) in listOf(LockLayout.COVER to 1, LockLayout.FULL to 1, LockLayout.QUIET to 0)) {
            layout = next
            likes = 0
            compose.waitForIdle()
            assertEquals("$next: hearts", hearts, tagged(LockTags.LIKE).size)
            if (hearts > 0) {
                compose.onAllNodes(hasTestTag(LockTags.LIKE), useUnmergedTree = true)[0].performClick()
                assertEquals("$next: the same action", 1, likes)
            }
            assertOnlyTheControlsTakeATouch(next.name)
        }
    }

    /** What can be touched on the lock screen is a list that only grows on purpose. */
    @Test fun theTouchableSetIsUnchanged() {
        assertEquals(
            setOf("lockCover", "lockLike", "lockPrevious", "lockPlay", "lockNext", "lockSeek", "lockVolume", "lockRepeat"),
            LockTags.touchable,
        )
    }
}
