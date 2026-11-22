package com.vinz.music.ui.screens

import com.vinz.music.R
import com.vinz.music.tr
import com.vinz.music.ui.components.VinzSlider
import com.vinz.music.ui.components.VinzSwitch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import com.vinz.music.data.prefs.CrossfadeCurve
import com.vinz.music.data.prefs.NORMALIZE_TARGETS
import kotlin.math.roundToInt
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer

@Composable
internal fun CurveSample(curve: CrossfadeCurve) {
    val incoming = MaterialTheme.colorScheme.primary
    val outgoing = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val steps = 40
        fun path(pick: (Pair<Float, Float>) -> Float) = androidx.compose.ui.graphics.Path().apply {
            for (i in 0..steps) {
                val p = i.toFloat() / steps
                val x = p * size.width
                val y = size.height - pick(curve.gains(p)) * (size.height - 4f) - 2f
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawPath(path { it.second }, outgoing, style = stroke)
        drawPath(path { it.first }, incoming, style = stroke)
    }
}

/** "1×", "0.85×", "1.25×". */
fun speedText(speed: Float): String = "%.2f".format(speed).trimEnd('0').trimEnd('.') + "×"

/** "As recorded", "+3", "−2" (semitones). */
fun pitchText(semitones: Int): String = when {
    semitones == 0 -> tr(R.string.as_recorded)
    semitones > 0 -> "+$semitones"
    else -> "−${-semitones}"
}