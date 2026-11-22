package com.vinz.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.PlayerPrefs
import com.vinz.music.data.prefs.FontChoice
import com.vinz.music.data.prefs.PlayerTitleSize
import com.vinz.music.ui.player.PlayerMarquee
import com.vinz.music.ui.player.playerArtistStyle
import com.vinz.music.ui.player.playerHeaderHeight
import com.vinz.music.ui.player.playerTitleStyle
import com.vinz.music.ui.theme.AppFonts
import com.vinz.music.ui.theme.classicTypography
import com.vinz.music.ui.theme.vinzTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #464 (1.8.4.1): the artist line under the player's title was cut off at the bottom in a tall font (Nothing's NDot, as
 * the app's and the titles' font) and with larger text, under the cover and in the header over the lyrics. The title
 * block was a fixed 60 dp: once the two lines needed more, the artist got what was left and was clipped. Drawn here as
 * the player lays it out (a row of [playerHeaderHeight], the title and the artist in the player's own styles and
 * [PlayerMarquee]); OpenDyslexic stands in for a tall font. Pictures go to app/build/player-title-fit/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h760dp-xhdpi")
class PlayerTitleFitTest {
    @get:Rule val compose = createComposeRule()

    private val title = "Start Again"
    private val artist = "Connor Price"

    private class Case(val type: Typography, val settings: AppSettings, val fontScale: Float)
    private var case by mutableStateOf<Case?>(null)
    private var room: Dp? = null

    /** Draws the title block in [type] at [fontScale] and returns the room it was given. */
    private fun show(type: Typography, settings: AppSettings = AppSettings(), fontScale: Float = 1f): Dp {
        val first = case == null
        room = null
        case = Case(type, settings, fontScale)
        if (first) compose.setContent {
            val now = case ?: return@setContent
            val type = now.type
            val settings = now.settings
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, now.fontScale)) {
                MaterialTheme(typography = type) {
                    val titleStyle = playerTitleStyle(MaterialTheme.typography, settings)
                    val artistStyle = playerArtistStyle(MaterialTheme.typography, settings)
                    val height = playerHeaderHeight(title, titleStyle, artist, artistStyle).also { room = it }
                    Box(Modifier.background(Color.Black).padding(16.dp)) {
                        Row(Modifier.width(300.dp).height(height).background(Color(0xFF202040)), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                PlayerMarquee(title, titleStyle, Color.White, false, Modifier.testTag("title"))
                                PlayerMarquee(artist, artistStyle, Color.White.copy(alpha = 0.6f), false, Modifier.testTag("artist"))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        return room!!
    }

    private fun layoutOf(tag: String): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    private fun save(name: String) {
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/player-title-fit").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertWhole(what: String) {
        val t = layoutOf("title")
        val a = layoutOf("artist")
        assertFalse("$what: the title is cut off (${t.size.height} px of ${t.multiParagraph.height})", t.didOverflowHeight)
        assertFalse("$what: the artist is cut off (${a.size.height} px of ${a.multiParagraph.height})", a.didOverflowHeight)
    }

    @Test fun aTallFontKeepsTheArtistWhole() {
        show(vinzTypography(AppFonts.family(FontChoice.DYSLEXIC)))
        save("tall-font")
        assertWhole("tall font")
    }

    @Test fun aTallFontInLargeTitlesKeepsTheArtistWhole() {
        show(vinzTypography(AppFonts.family(FontChoice.DYSLEXIC)), AppSettings(player = PlayerPrefs(titleSize = PlayerTitleSize.LARGE)))
        save("tall-font-large-title")
        assertWhole("tall font, Large title")
    }

    @Test fun largerTextKeepsTheArtistWhole() {
        show(vinzTypography(AppFonts.vinz), fontScale = 1.3f)
        save("outfit-text-130")
        assertWhole("Outfit at 130 %")
    }

    @Test fun classicTypeWithLargerTextKeepsTheArtistWhole() {
        show(classicTypography(AppFonts.vinz), AppSettings(player = PlayerPrefs(titleSize = PlayerTitleSize.LARGE)), fontScale = 1.15f)
        save("classic-large-title-text-115")
        assertWhole("Classic type, Large title, 115 %")
    }

    /** As Vinz comes (Outfit, Medium title, the phone's normal text size) the block is 60 dp, as it always was. */
    @Test fun theDefaultsKeepTheirRoom() {
        assertEquals(60f, show(vinzTypography(AppFonts.vinz)).value, 0.01f)
        save("outfit-default")
        assertWhole("Outfit")
        assertEquals(60f, show(vinzTypography(AppFonts.family(FontChoice.SYSTEM))).value, 0.01f)
        assertEquals(60f, show(classicTypography(AppFonts.vinz)).value, 0.01f)
        assertEquals(60f, show(vinzTypography(AppFonts.vinz), AppSettings(player = PlayerPrefs(titleSize = PlayerTitleSize.SMALL))).value, 0.01f)
        assertEquals(60f, show(vinzTypography(AppFonts.vinz), AppSettings(player = PlayerPrefs(titleSize = PlayerTitleSize.LARGE))).value, 0.01f)
    }
}
