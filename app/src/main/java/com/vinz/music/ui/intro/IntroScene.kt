package com.vinz.music.ui.intro

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The first launch's scene: the planet the camera pulls away from, the sky it pulls out into, the orbits it leaves
 * behind, the spark the name arrives out of, and the light the sky gives up when it is held into the dock.
 *
 * It is all geometry — not one bitmap — and every length is measured from the frame it is drawn into, so the same code
 * draws a 1080p phone and a 4K screen: the planet is a radius, not a picture, and the stars are circles at 2160p
 * exactly as they are at 1080p. Nothing here reads composition state; [drawIntro] is a function of one clock, so a
 * frame invalidates drawing and nothing else.
 */
internal object Intro {
    /** The beats, in seconds from the first frame. The words on the screen are timed from these numbers too. */
    const val SPARK = 0.15f
    const val PLANET = 0.85f
    const val PULL_FROM = 1.90f
    const val PULL_TO = 5.30f
    const val ORBITS = 2.90f
    const val ORBITS_DRAWN = 6.10f
    const val WORD = 6.60f
    const val SWEEP = 7.05f
    const val POWERED = 7.50f
    const val LINE_ONE = 8.10f
    const val LINE_TWO = 9.20f
    const val LINE_THREE = 10.30f
    const val DOCK = 11.10f
    /** What "reduce motion" draws: every layer at the end of its entrance, nothing travelling. */
    const val STILL_AT = 12.90f

    /** How long the ring takes to fill under a held finger, and how long it takes to drain when let go. */
    const val HOLD = 1.70f
    const val RELEASE = 0.45f

    /** The dock sits this far above the bottom of the screen, and is this wide. Both are read by the layout. */
    const val DOCK_INSET_DP = 44f
    const val DOCK_RADIUS_DP = 42f

    /** A star takes this much of the hold to fall into the dock, once its own turn comes. */
    const val COLLECT = 0.22f
}

/** The two ends of the brand's light, and the cyan the mark's rim is lit with. */
internal object Brand {
    val violet = Color(0xFF8B10FA)
    val blue = Color(0xFF1633E8)
    val cyan = Color(0xFF02A6FC)
    val rim = Color(0xFF9BEBFF)
}

/**
 * One point of the sky. [start] is where it sat at the first frame, [drift] how fast it travels outwards, [collectAt]
 * the point in the hold at which it leaves for the dock — spread through the whole hold, so the sky empties as one
 * continuous movement rather than in a single gulp.
 */
internal class Star(
    val angle: Float,
    val start: Float,
    val drift: Float,
    val spin: Float,
    val sizeDp: Float,
    val alpha: Float,
    val twinkle: Float,
    val phase: Float,
    val layer: Int,
    val collectAt: Float,
) {
    /** Where it is at [t] seconds, on a sky of radius [reach] around [centre]. */
    fun at(t: Float, centre: Offset, reach: Float): Offset {
        val d = distance(t)
        val a = angle + spin * t
        return Offset(centre.x + cos(a) * d * reach, centre.y + sin(a) * d * reach)
    }

    fun distance(t: Float): Float = (start + drift * t) % 1f

    /** How far this star is into its fall into the dock at [hold] (0 = still in the sky, 1 = gone). */
    fun collected(hold: Float): Float =
        if (hold <= collectAt) 0f else ((hold - collectAt) / Intro.COLLECT).coerceIn(0f, 1f)

    /** Fades in as it leaves the middle and out as it reaches the edge, so the wrap-around is never seen. */
    fun life(t: Float): Float {
        val d = distance(t)
        return smooth((d / 0.10f).coerceIn(0f, 1f)) * (1f - smooth(((d - 0.80f) / 0.20f).coerceIn(0f, 1f)))
    }
}

/**
 * The sky: 620 grains of dust, 240 stars and 64 near ones with four-point flares — 924 points, each with its own
 * speed, twinkle and moment to fall into the dock. Seeded, so every install sees the same sky.
 */
internal fun introStars(seed: Int = 20261030): List<Star> {
    val random = Random(seed)
    val stars = ArrayList<Star>(940)
    fun add(count: Int, layer: Int, sizeFrom: Float, sizeTo: Float, alphaFrom: Float, alphaTo: Float, driftFrom: Float, driftTo: Float) {
        repeat(count) { i ->
            stars += Star(
                angle = random.nextFloat() * 2f * PI.toFloat(),
                start = random.nextFloat(),
                drift = driftFrom + random.nextFloat() * (driftTo - driftFrom),
                spin = if (layer == 0) 0f else 0.004f + random.nextFloat() * 0.010f,
                sizeDp = sizeFrom + random.nextFloat() * (sizeTo - sizeFrom),
                alpha = alphaFrom + random.nextFloat() * (alphaTo - alphaFrom),
                twinkle = 0.5f + random.nextFloat() * 2.2f,
                phase = random.nextFloat() * 2f * PI.toFloat(),
                layer = layer,
                // The nearer a star is, the earlier it leaves: the sky empties from the front. The last one is away
                // before the ring closes, so the hold ends on an empty sky rather than on stars still in flight.
                collectAt = ((i / count.toFloat()) * 0.70f + random.nextFloat() * 0.08f) * (1f - layer * 0.10f),
            )
        }
    }
    add(620, 0, 0.45f, 0.9f, 0.10f, 0.26f, 0.006f, 0.024f)
    add(240, 1, 0.8f, 1.5f, 0.30f, 0.70f, 0.014f, 0.045f)
    add(64, 2, 1.1f, 2.1f, 0.70f, 1.00f, 0.020f, 0.070f)
    return stars
}

/**
 * Draws the whole scene for the frame at [t] seconds with the dock hold at [hold] (0…1). [dockCenter] and
 * [dockRadius] are where the layout put the button, so the stars have somewhere true to fall; [still] is the phone
 * asking for no animation, and then every layer is drawn at the end of its entrance and nothing travels.
 */
internal fun DrawScope.drawIntro(
    t: Float,
    hold: Float,
    still: Boolean,
    stars: List<Star>,
    dockCenter: Offset,
    dockRadius: Float,
) {
    val centre = Offset(size.width / 2f, size.height * 0.44f)
    val min = size.minDimension
    val reach = (size.width * size.width + size.height * size.height).let { sqrt(it) } / 2f

    drawNebulae(t, centre, min)
    drawSky(t, still, stars, centre, reach, hold, dockCenter)
    if (!still) {
        drawOrbits(t, centre, min)
        drawPlanet(t, centre, min)
    }
    if (still || t > Intro.PULL_TO - 0.30f) drawSpark(t, centre, min, hold, still)
    // Edges fall away, so the words sit in the middle of the dark rather than on a picture.
    drawRect(
        Brush.radialGradient(
            0f to Color.Transparent,
            0.68f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.72f),
            center = centre,
            radius = reach,
        ),
    )
    // The dock's own lake of light: the sky it has swallowed. Above the vignette, so nothing dims what it has taken.
    if (hold > 0f) drawWell(hold, dockCenter, dockRadius)
    drawDock(t, hold, still, dockCenter, dockRadius)
}

/** Dust, stars and near stars: travelling outwards, twinkling, and — when held — falling into the dock. */
private fun DrawScope.drawSky(
    t: Float,
    still: Boolean,
    stars: List<Star>,
    centre: Offset,
    reach: Float,
    hold: Float,
    dockCenter: Offset,
) {
    val appear = if (still) 1f else smooth(ramp(t, 0.35f, 1.60f))
    if (appear <= 0.01f) return
    stars.forEach { star ->
        val here = star.at(t, centre, reach)
        val q = if (still) 0f else star.collected(hold)
        if (q >= 1f) return@forEach
        val ease = q * q * q * q
        val at = if (q <= 0f) here else {
            val dx = dockCenter.x - here.x
            val dy = dockCenter.y - here.y
            val bend = sin(PI.toFloat() * q) * 0.16f * minOf(1f, reach / 900f)
            Offset(
                here.x + dx * ease - dy * bend,
                here.y + dy * ease + dx * bend,
            )
        }
        val twinkle = 0.62f + 0.38f * sin(star.phase + t * star.twinkle)
        val a = (star.alpha * star.life(if (still) 0.35f else t) * twinkle * appear * (1f - ease * 0.9f)).coerceIn(0f, 1f)
        if (a < 0.012f) return@forEach
        val r = star.sizeDp.dp.toPx()
        when (star.layer) {
            2 -> {
                // A near star keeps a streak behind it while the camera is moving, and loses it once it lands.
                val tail = if (still || q > 0f) null else star.at(t - 0.035f, centre, reach)
                if (tail != null) {
                    drawLine(
                        color = Color.White.copy(alpha = a * 0.30f),
                        start = tail,
                        end = at,
                        strokeWidth = r * 1.1f,
                        cap = StrokeCap.Round,
                    )
                }
                drawGlow(at, r * 7f, Color.White.copy(alpha = a * 0.16f))
                drawCircle(Color.White.copy(alpha = a), radius = r, center = at)
                val flare = r * 4.2f * (0.8f + 0.4f * sin(star.phase + t * star.twinkle))
                drawLine(Color.White.copy(alpha = a * 0.55f), Offset(at.x - flare, at.y), Offset(at.x + flare, at.y), r * 0.45f, StrokeCap.Round)
                drawLine(Color.White.copy(alpha = a * 0.55f), Offset(at.x, at.y - flare * 0.8f), Offset(at.x, at.y + flare * 0.8f), r * 0.45f, StrokeCap.Round)
            }
            1 -> {
                drawCircle(Color.White.copy(alpha = a), radius = r, center = at)
            }
            else -> drawCircle(Color.White.copy(alpha = a), radius = r * 0.6f, center = at)
        }
    }
}

/** Two nebulae, one at each end of the brand's light, breathing very slowly behind everything. */
private fun DrawScope.drawNebulae(t: Float, centre: Offset, min: Float) {
    val breath = 1f + 0.06f * sin(t * 0.45f)
    drawGlow(
        Offset(centre.x - min * 0.26f, centre.y - min * 0.22f),
        min * 0.86f * breath,
        Brand.violet.copy(alpha = 0.10f),
    )
    drawGlow(
        Offset(centre.x + min * 0.30f, centre.y + min * 0.20f),
        min * 0.72f * breath,
        Brand.cyan.copy(alpha = 0.08f),
    )
    drawGlow(centre, min * 0.55f, Brand.blue.copy(alpha = 0.05f))
}

/**
 * The planet: out of the dark at more than a screen wide, then away as the camera pulls back — an exponential, so it
 * leaves fast and then hangs as a disc before it becomes the spark. Its bands are latitude bands of a sphere, drawn
 * narrower and thinner towards the poles, so it reads as round even while it is only a dot.
 */
private fun DrawScope.drawPlanet(t: Float, centre: Offset, min: Float) {
    val r = introPlanetRadius(t, min)
    if (r < 1.2f) return
    val reveal = ramp(t, Intro.PLANET, Intro.PLANET + 0.60f)
    if (reveal <= 0f) return
    // The rings only open once the planet is far enough away to see them around it.
    val pulled = smooth(ramp(t, Intro.PULL_FROM, Intro.PULL_TO)) > 0.55f
    val c = Offset(centre.x, centre.y)

    // The air around it: a ring of light at the limb rather than a wash over the whole disc.
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.Transparent,
            0.72f to Color.Transparent,
            0.94f to Brand.cyan.copy(alpha = 0.10f * reveal),
            1f to Color.Transparent,
            center = c,
            radius = r * 1.34f,
        ),
        radius = r * 1.34f,
        center = c,
    )
    drawCircle(
        brush = Brush.radialGradient(
            0f to Brand.violet.copy(alpha = 0.16f * reveal),
            0.55f to Brand.violet.copy(alpha = 0.06f * reveal),
            1f to Color.Transparent,
            center = c,
            radius = r * 2.1f,
        ),
        radius = r * 2.1f,
        center = c,
    )

    // Back half of the ring system, so the planet's own rings pass behind it.
    if (pulled) drawRings(c, r, t, reveal, front = false)

    drawCircle(
        brush = Brush.radialGradient(
            0f to Color(0xFF9A72FF),
            0.30f to Color(0xFF5233B4),
            0.62f to Color(0xFF1D1345),
            1f to Color(0xFF070510),
            center = Offset(c.x - r * 0.34f, c.y - r * 0.40f),
            radius = r * 1.55f,
        ),
        radius = r,
        center = c,
        alpha = reveal,
    )

    clipPath(Path().apply { addOval(Rect(center = c, radius = r)) }) {
        val bands = 13
        for (i in 0 until bands) {
            val v = (i + 0.5f) / bands
            val k = (v - 0.5f) * 2f
            val half = sqrt((1f - k * k).coerceAtLeast(0f))
            val h = r * 0.16f * half
            if (h < 0.8f) continue
            val wobble = sin(t * 0.55f + i * 1.9f) * r * 0.05f
            val tone = if (i % 3 == 0) Color.White else Color.Black
            drawOval(
                color = tone.copy(alpha = (if (i % 3 == 0) 0.055f else 0.075f) * reveal),
                topLeft = Offset(c.x - r * half + wobble, c.y + k * r - h / 2f),
                size = Size(r * half * 2f, h),
            )
        }
        // One storm, brighter than the rest, drifting along its own band.
        drawOval(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, Brand.rim.copy(alpha = 0.22f * reveal), Color.Transparent),
                startX = c.x - r,
                endX = c.x + r,
            ),
            topLeft = Offset(c.x - r * 0.42f + sin(t * 0.3f) * r * 0.18f, c.y + r * 0.10f),
            size = Size(r * 0.84f, r * 0.13f),
        )
        // The night side, so the light has somewhere to come from.
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.80f),
                0.62f to Color.Black.copy(alpha = 0.34f),
                1f to Color.Transparent,
                center = Offset(c.x + r * 0.62f, c.y + r * 0.66f),
                radius = r * 2.05f,
            ),
            radius = r,
            center = c,
        )
    }

    // The light the sphere is lit by, on its upper left.
    drawGlow(
        Offset(c.x - r * 0.42f, c.y - r * 0.46f),
        r * 0.95f,
        Color(0xFFD9C6FF).copy(alpha = 0.20f * reveal),
    )

    // The lit limb: the brand's two lights on one thin edge.
    drawCircle(
        brush = Brush.linearGradient(
            listOf(Brand.rim.copy(alpha = 0.85f * reveal), Brand.violet.copy(alpha = 0.35f * reveal), Color.Transparent),
            start = Offset(c.x - r, c.y - r),
            end = Offset(c.x + r * 0.55f, c.y + r),
        ),
        radius = r - r * 0.006f,
        center = c,
        style = Stroke(width = (r * 0.014f).coerceIn(1f, 9f)),
    )

    if (pulled) drawRings(c, r, t, reveal, front = true)
}

/**
 * How wide the planet is at [t]: more than a screen to begin with, then an exponential away, so it leaves fast and then
 * hangs as a disc before it becomes the spark. Exposed because the fall-off is the shape of the whole act.
 */
internal fun introPlanetRadius(t: Float, min: Float): Float =
    min * 1.10f * exp(-3.4f * smooth(ramp(t, Intro.PULL_FROM, Intro.PULL_TO)))

/** Two thin rings on a tilt, the near half drawn over the planet after it. */
private fun DrawScope.drawRings(c: Offset, r: Float, t: Float, reveal: Float, front: Boolean) {
    val open = smooth(ramp(t, Intro.PULL_FROM + 0.9f, Intro.PULL_TO))
    if (open <= 0f) return
    rotate(-19f, c) {
        for (i in 0 until 2) {
            val rr = r * (1.55f + i * 0.32f) * (0.92f + 0.08f * open)
            val stroke = (r * 0.016f).coerceIn(1f, 5f)
            val brush = Brush.sweepGradient(
                listOf(
                    Color.Transparent,
                    Brand.rim.copy(alpha = 0.35f * reveal * open),
                    Brand.violet.copy(alpha = 0.22f * reveal * open),
                    Color.Transparent,
                    Color.Transparent,
                ),
                center = c,
            )
            val top = Offset(c.x - rr, c.y - rr * 0.30f)
            val size = Size(rr * 2f, rr * 0.60f)
            if (front) {
                drawArc(brush, 0f, 180f, false, top, size, style = Stroke(stroke))
            } else {
                drawArc(brush, 180f, 180f, false, top, size, style = Stroke(stroke))
            }
        }
    }
}

/** Three orbits, drawn on by a travelling head, around where the planet has shrunk to. */
private fun DrawScope.drawOrbits(t: Float, centre: Offset, min: Float) {
    val drawn = smooth(ramp(t, Intro.ORBITS, Intro.ORBITS_DRAWN))
    if (drawn <= 0f) return
    val fade = 1f - 0.55f * smooth(ramp(t, Intro.WORD, Intro.WORD + 1.6f))
    for (i in 0 until 3) {
        val rr = min * (0.20f + i * 0.115f)
        val tilt = -17f + i * 8f
        val sweep = 360f * drawn
        rotate(tilt, centre) {
            val top = Offset(centre.x - rr, centre.y - rr * 0.40f)
            val size = Size(rr * 2f, rr * 0.80f)
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Brand.violet.copy(alpha = 0.30f * fade), Brand.cyan.copy(alpha = 0.34f * fade), Color.Transparent),
                    center = centre,
                ),
                startAngle = -90f - sweep * 0.5f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = top,
                size = size,
                style = Stroke(width = 0.9f.dp.toPx()),
            )
            // The head: where the orbit has been drawn up to.
            val head = Math.toRadians((-90f - sweep * 0.5f + sweep).toDouble())
            val hx = centre.x + rr * cos(head).toFloat()
            val hy = centre.y + rr * 0.80f * sin(head).toFloat()
            if (drawn < 0.999f) {
                drawGlow(Offset(hx, hy), 10f.dp.toPx(), Brand.cyan.copy(alpha = 0.5f * fade))
                drawCircle(Color.White.copy(alpha = 0.85f * fade), radius = 1.5f.dp.toPx(), center = Offset(hx, hy))
            }
        }
    }
}

/** What the planet becomes: a four-point spark, breathing, that the name comes up out of. */
private fun DrawScope.drawSpark(t: Float, centre: Offset, min: Float, hold: Float, still: Boolean) {
    val born = smooth(ramp(t, Intro.PULL_TO - 0.55f, Intro.PULL_TO + 0.15f))
    val leave = if (still) 0.55f else 1f - 0.45f * smooth(ramp(t, Intro.WORD + 0.45f, Intro.WORD + 1.9f))
    val a = born * leave * (1f - hold * 0.65f)
    if (a <= 0.01f) return
    val pulse = if (still) 1f else 0.88f + 0.12f * sin(t * 1.6f)
    val reach = min * 0.055f * pulse
    drawGlow(centre, reach * 1.8f, Brand.cyan.copy(alpha = 0.30f * a))
    drawGlow(centre, reach * 0.9f, Color.White.copy(alpha = 0.55f * a))
    val w = 1.4f.dp.toPx()
    drawLine(Color.White.copy(alpha = a), Offset(centre.x - reach, centre.y), Offset(centre.x + reach, centre.y), w, StrokeCap.Round)
    drawLine(Color.White.copy(alpha = a), Offset(centre.x, centre.y - reach), Offset(centre.x, centre.y + reach), w, StrokeCap.Round)
    drawLine(Color.White.copy(alpha = a * 0.45f), Offset(centre.x - reach * 0.5f, centre.y - reach * 0.5f), Offset(centre.x + reach * 0.5f, centre.y + reach * 0.5f), w, StrokeCap.Round)
    drawLine(Color.White.copy(alpha = a * 0.45f), Offset(centre.x - reach * 0.5f, centre.y + reach * 0.5f), Offset(centre.x + reach * 0.5f, centre.y - reach * 0.5f), w, StrokeCap.Round)
    drawCircle(Color.White.copy(alpha = a), radius = reach * 0.10f, center = centre)
}

/**
 * The ring: it arrives after the words, and fills under a held finger. Drawn here rather than over the button so that
 * the ring and the stars falling into it are the same drawing, at the same place, in every resolution.
 */
private fun DrawScope.drawDock(t: Float, hold: Float, still: Boolean, c: Offset, r: Float) {
    val arrive = if (still) 1f else easeOut(ramp(t, Intro.DOCK, Intro.DOCK + 0.85f))
    if (arrive <= 0.01f) return
    val stroke = (2.4f * density).coerceAtMost(r * 0.10f)
    val pop = if (still) 1f else 1f + 0.03f * sin(PI.toFloat() * arrive)
    val rr = r * (0.86f + 0.14f * arrive) * pop
    drawCircle(Color.White.copy(alpha = 0.13f * arrive), radius = rr - stroke / 2f, center = c, style = Stroke(stroke))
    if (hold <= 0f) return
    drawCircle(Brand.cyan.copy(alpha = 0.18f * hold), radius = rr * (0.55f + 0.45f * hold), center = c)
    drawArc(
        brush = Brush.sweepGradient(listOf(Brand.violet, Brand.blue, Brand.cyan, Brand.violet), center = c),
        startAngle = -90f,
        sweepAngle = 360f * hold,
        useCenter = false,
        topLeft = Offset(c.x - rr + stroke / 2f, c.y - rr + stroke / 2f),
        size = Size(rr * 2f - stroke, rr * 2f - stroke),
        style = Stroke(width = stroke * 1.6f, cap = StrokeCap.Round),
    )
    val a = Math.toRadians((-90f + 360f * hold).toDouble())
    val head = Offset(c.x + (rr - stroke / 2f) * cos(a).toFloat(), c.y + (rr - stroke / 2f) * sin(a).toFloat())
    drawCircle(Color.White.copy(alpha = 0.9f), radius = stroke * 0.9f, center = head)
}

/** The light the hold has gathered: it grows with the ring, under the dock. */
private fun DrawScope.drawWell(hold: Float, dockCentre: Offset, dockRadius: Float) {
    val e = smooth(hold)
    drawGlow(dockCentre, dockRadius * (1.7f + 2.3f * e), Brand.violet.copy(alpha = 0.06f + 0.15f * e))
    drawGlow(dockCentre, dockRadius * (0.8f + 1.0f * e), Brand.cyan.copy(alpha = 0.07f + 0.17f * e))
}

private fun DrawScope.drawGlow(center: Offset, radius: Float, color: Color) {
    if (radius <= 0f || color.alpha <= 0.002f) return
    drawCircle(
        brush = Brush.radialGradient(0f to color, 0.55f to color.copy(alpha = color.alpha * 0.35f), 1f to Color.Transparent, center = center, radius = radius),
        radius = radius,
        center = center,
    )
}

/** Where the dock sits, from the frame and the inset the layout uses — one formula for the button and its stars. */
internal fun dockCentre(size: Size, dockRadius: Float, inset: Float): Offset =
    Offset(size.width / 2f, size.height - inset - dockRadius)

internal fun ramp(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)
internal fun smooth(x: Float): Float = x * x * (3f - 2f * x)
internal fun easeOut(x: Float): Float { val v = 1f - x; return 1f - v * v * v }
