package com.vinz.music.ui.screens

import com.vinz.music.R
import com.vinz.music.tr
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.vinz.music.data.model.hiRes
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ArtworkChange
import com.vinz.music.data.prefs.ArtworkSwatch
import com.vinz.music.data.prefs.ColorSource
import com.vinz.music.data.prefs.ContrastLevel
import com.vinz.music.data.prefs.LightStyle
import com.vinz.music.data.prefs.SurfaceTint
import com.vinz.music.ui.components.ColorPickerSheet
import com.vinz.music.ui.components.VinzSlider
import com.vinz.music.ui.components.VinzTextButton
import com.vinz.music.ui.components.Segmented
import com.vinz.music.ui.components.frostFill
import com.vinz.music.ui.theme.ColorMath
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.LocalIsDark
import com.vinz.music.ui.theme.LocalUi
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.ui.theme.VinzTokens
import com.vinz.music.ui.theme.SpectrumPresets
import com.vinz.music.ui.theme.ThemeColors
import com.vinz.music.ui.theme.isNeutralSeed
import com.vinz.music.ui.theme.pickSwatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The light alone: a progress line and a switch. */
@Composable
internal fun LightStrip(c: ThemeColors) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(shape).background(c.scheme.background).border(1.dp, c.line, shape).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Brush.horizontalGradient(*c.run.toTypedArray())))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(18.dp).clip(CircleShape).border(2.dp, Brush.sweepGradient(c.ring), CircleShape))
    }
}

/** What Vinz does with an accent, when it doesn't use it exactly as picked. */
internal fun accentNote(c: Int, exact: Boolean): String? = when {
    isNeutralSeed(c) -> tr(R.string.white_black_and_greys_give_vinz)
    !exact -> tr(R.string.shown_softened_lighter_on_dark_pages)
    ColorMath.contrast(c, 0xFF0E0E12.toInt()) < 4.5 || ColorMath.contrast(c, 0xFFFAFAFB.toInt()) < 4.5 ->
        tr(R.string.made_a_little_lighter_or_darker)
    else -> null
}

/** The White preset's name as it shows: white light on dark pages, black on light ones. */
fun whiteName(dark: Boolean) = if (dark) tr(R.string.white) else tr(R.string.black)

/** Your own light's colors in order: tap one to change (or remove) it, + to add one, and a way to flip the order. */
@Composable
internal fun StopEditor(stops: List<Int>, onEdit: (Int) -> Unit, onReverse: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(tr(R.string.your_own_light_s_colors_left), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            stops.forEachIndexed { i, c ->
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Color(c)).border(1.dp, scheme.onSurface.copy(alpha = 0.18f), CircleShape)
                        .semantics { contentDescription = tr(R.string.color, i + 1, ColorMath.toHex(c)) }
                        .clickable { onEdit(i) },
                )
            }
            if (stops.size < 7) Box(
                Modifier.size(38.dp).clip(CircleShape).border(1.5.dp, scheme.onSurface.copy(alpha = 0.3f), CircleShape).clickable { onEdit(stops.size) },
                contentAlignment = Alignment.Center,
            ) { Icon(VinzIcons.Add, tr(R.string.add_a_color), Modifier.size(20.dp)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.tap_a_color_to_change_or), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            VinzTextButton(onReverse) { Text(tr(R.string.reverse)) }
        }
    }
}
