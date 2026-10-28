package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.ui.components.LocalFrosted
import com.prism.music.ui.screens.ReplayCard
import com.prism.music.ui.screens.ReplayCardData
import com.prism.music.ui.screens.ReplayCardStyle
import com.prism.music.ui.theme.PrismAvatar
import com.prism.music.ui.theme.PrismTheme
import com.prism.music.ui.theme.drawPrismAvatar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every Replay card look, quietest first, as Home draws it (on Home's frosted page), in dark and light, onto
 * app/build/replay-cards/, to choose from. The "cover" is a drawn stand-in (no network here).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h2400dp-xxhdpi")
class ReplayCardsRenderTest {
    @get:Rule val compose = createComposeRule()

    private val data = ReplayCardData(
        year = 2026, total = "86h 40m", months = listOf(310L, 280, 420, 390, 510, 470, 620, 580, 540, 690).map { it * 60_000 },
        topSong = "Billie Jean", topSongArtist = "Michael Jackson", topArtist = "Michael Jackson", yearDone = 0.8f, songCount = 1240,
    )

    private fun cover(): BitmapPainter {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp.asImageBitmap()), Size(400f, 400f)) { drawPrismAvatar(PrismAvatar.DAWN) }
        return BitmapPainter(bmp.asImageBitmap())
    }

    private fun shoot(name: String, dark: Boolean) {
        val art = cover()
        compose.setContent {
            PrismTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT), artworkColor = null) {
                Column(Modifier.width(412.dp).background(MaterialTheme.colorScheme.background).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CompositionLocalProvider(LocalFrosted provides true) {
                        ReplayCardStyle.entries.forEachIndexed { i, s ->
                            Text("${i + 1}. ${s.label}: ${s.note}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
                            ReplayCard(s, data, art) {}
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/replay-cards").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${File(dir, "$name.png").absolutePath}")
    }

    @Test fun dark() = shoot("replay-cards-dark", dark = true)
    @Test fun light() = shoot("replay-cards-light", dark = false)
}
