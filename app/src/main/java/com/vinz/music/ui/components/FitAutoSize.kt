package com.vinz.music.ui.components

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.max

/**
 * [TextAutoSize.StepBased] that answers in one layout where the words fit (1.8.4, the A16's Settings A/B).
 *
 * The tab bar's names get smaller rather than cut off (#237). While the bar slims as a page scrolls its width changes
 * every frame, and StepBased then searches for the size again: five layouts of each name a frame, a tenth of the main
 * thread in a settings page's first fling. Where the words fit at the largest size StepBased would try, every size it
 * tries fits too (StepBased's own search counts on that), so its answer is known without the search: this lays the
 * words out once at that size and, if they fit, gives StepBased's answer; else it asks StepBased. The size is always
 * StepBased's own, so nothing looks different.
 */
@Immutable
class FitAutoSize(
    private val minFontSize: TextUnit,
    private val maxFontSize: TextUnit,
    private val stepSize: TextUnit = 0.25.sp,
    /** What answers where the words don't fit: StepBased (a test counts its calls). */
    private val stepBased: TextAutoSize = TextAutoSize.StepBased(minFontSize, maxFontSize, stepSize),
) : TextAutoSize {

    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        val (largest, answer) = allFitting(minFontSize.toPx(), maxFontSize.toPx(), stepSize.toPx())
        if (largest > 0f && fits(performLayout(constraints, text, largest.toSp()))) return answer.toSp()
        return with(stepBased) { getFontSize(constraints, text) }
    }

    override fun equals(other: Any?): Boolean =
        other is FitAutoSize && other.minFontSize == minFontSize && other.maxFontSize == maxFontSize && other.stepSize == stepSize

    override fun hashCode(): Int = (minFontSize.hashCode() * 31 + maxFontSize.hashCode()) * 31 + stepSize.hashCode()

    companion object {
        /**
         * StepBased's search (foundation 1.12, `AutoSizeStepBased.getFontSize`) run as if every size it lays out fits,
         * with the same float steps: the largest size it would lay out (0 if none) and the size it would then answer,
         * both in px.
         */
        internal fun allFitting(min: Float, max: Float, step: Float): Pair<Float, Float> {
            var low = min
            val high = max
            var mid = (low + high) / 2
            var largest = 0f
            while (high - low >= step) {
                largest = max(largest, mid)
                low = mid
                mid = (low + high) / 2
            }
            var answer = floor(((low - min) / step).toDouble()).toFloat() * step + min
            if (answer + step <= max) {
                largest = max(largest, answer + step)
                answer += step
            }
            return largest to answer
        }

        /** StepBased's own test of a layout (`didOverflow`), for the overflows it knows. */
        internal fun fits(r: TextLayoutResult): Boolean = when (r.layoutInput.overflow) {
            TextOverflow.Clip, TextOverflow.Visible -> !r.didOverflowWidth && !r.didOverflowHeight
            TextOverflow.Ellipsis -> when (r.lineCount) {
                0 -> true
                else -> !r.isLineEllipsized(r.lineCount - 1)
            }
            TextOverflow.StartEllipsis, TextOverflow.MiddleEllipsis -> when (r.lineCount) {
                0 -> true
                1 -> !r.isLineEllipsized(0)
                else -> !r.didOverflowWidth && !r.didOverflowHeight
            }
            else -> false
        }
    }
}
