package com.vinz.music.ui.splash

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinz.music.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The first launch's splash (1.8): the mark arriving out of a starfield, the name under it, two lines about what Vinz
 * is, and a sound like a room opening. It plays once per install - the flag is SettingsRepository.splashSeen - and it
 * is the only thing shown before the welcome screen.
 *
 * Everything is drawn, not recomposed, frame by frame: one clock, and every animated property read inside a
 * [graphicsLayer] block or a [Canvas] lambda. Those reads happen in the draw phase, so a frame invalidates drawing only
 * - 140 stars and a vector mark cost no recomposition. The one exception is which tagline is showing, which is a
 * [derivedStateOf] and so recomposes exactly once.
 */
@Composable
fun VinzSplash(reduceMotion: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Animations turned off in the phone's developer options, or "Reduce motion" in Vinz: the same scene, held still.
    val still = reduceMotion || !animationsOn(context)
    val length = if (still) 1.9f else 3.6f

    val clock = remember { mutableStateOf(0f) }
    val fade = remember { Animatable(1f) }
    var leaving by remember { mutableStateOf(false) }
    val sound = remember { SplashSound(context) }

    LaunchedEffect(Unit) { sound.play() }
    DisposableEffect(Unit) { onDispose { sound.stop() } }

    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            val seconds = withFrameNanos { it - start } / 1_000_000_000f
            clock.value = seconds
            if (seconds >= length) break
        }
        leaving = true
    }
    // The scene fades before it hands the screen back, so the first page is never seen arriving underneath it.
    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        fade.animateTo(0f, tween(if (still) 180 else 320, easing = LinearOutSlowInEasing))
        onDone()
    }

    // Held still: every property is drawn at the end of its entrance, and the second tagline never comes up.
    val scene: State<Float> = remember(still) { if (still) mutableStateOf(FINAL_SECOND) else clock }
    val secondLine by remember(still) { derivedStateOf { !still && scene.value >= 2.42f } }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer { alpha = fade.value }
            // Tapping anywhere leaves: the splash is an entrance, never a gate.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Skip",
            ) { leaving = true }
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.Center,
    ) {
        Starfield(scene, still)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Mark(scene, still, Modifier.size(168.dp))
            Spacer(Modifier.height(26.dp))
            Wordmark(scene, still)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.height(34.dp), contentAlignment = Alignment.Center) {
                if (secondLine) {
                    Tagline(TAGLINE_TWO, scene, 2.52f, 99f, still, Modifier.padding(horizontal = 40.dp))
                } else {
                    Tagline(TAGLINE_ONE, scene, 1.85f, 2.24f, still, Modifier.padding(horizontal = 40.dp))
                }
            }
        }
    }
}

/**
 * The mark: up out of the dark with a breath of overshoot, and one pass of light across it as it lands. The light is a
 * band in [BlendMode.SrcAtop] over an offscreen layer, so it only exists where the mark's own pixels are - not as a
 * streak across the black.
 */
@Composable
private fun Mark(clock: State<Float>, still: Boolean, modifier: Modifier) {
    val painter = painterResource(R.drawable.ic_launcher_foreground)
    Box(
        modifier
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                if (still) {
                    scaleX = 1f; scaleY = 1f; rotationZ = 0f; alpha = 1f
                } else {
                    val t = clock.value
                    val rise = easeOut(ramp(t, 0.28f, 1.20f))
                    val pop = 1f + 0.022f * sin(PI.toFloat() * rise)
                    scaleX = (0.80f + 0.20f * rise) * pop
                    scaleY = scaleX
                    rotationZ = -9f * (1f - rise)
                    alpha = ramp(t, 0.20f, 0.62f)
                }
            }
            .drawWithContent {
                drawContent()
                if (still) return@drawWithContent
                val sweep = ramp(clock.value, 1.02f, 1.66f)
                if (sweep <= 0f || sweep >= 1f) return@drawWithContent
                val band = size.width * 0.5f
                val x = sweep * (size.width + band * 2f) - band
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color(0x59FFFFFF), Color.Transparent),
                        start = Offset(x - band, 0f),
                        end = Offset(x + band, size.height),
                    ),
                    blendMode = BlendMode.SrcAtop,
                )
            },
    ) {
        Image(painter = painter, contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun Wordmark(clock: State<Float>, still: Boolean) {
    val rise = with(LocalDensity.current) { 14.dp.toPx() }
    Text(
        text = "Vinz",
        color = Color.White,
        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp),
        modifier = Modifier.graphicsLayer {
            if (still) {
                alpha = 1f; translationY = 0f
            } else {
                val w = easeOut(ramp(clock.value, 1.15f, 1.75f))
                alpha = w
                translationY = (1f - w) * rise
            }
        },
    )
}

@Composable
private fun Tagline(text: String, clock: State<Float>, inAt: Float, outAt: Float, still: Boolean, modifier: Modifier) {
    val rise = with(LocalDensity.current) { 8.dp.toPx() }
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.72f),
        style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.5.sp),
        textAlign = TextAlign.Center,
        modifier = modifier.graphicsLayer {
            if (still) {
                alpha = 1f; translationY = 0f
            } else {
                val t = clock.value
                val w = easeOut(ramp(t, inAt, inAt + 0.40f))
                alpha = w * (1f - smooth(ramp(t, outAt, outAt + 0.30f)))
                translationY = (1f - w) * rise
            }
        },
    )
}

/** 140 dots on their way out from the middle, each with its own speed, size and twinkle, over two faint nebulae. */
@Composable
private fun Starfield(clock: State<Float>, still: Boolean) {
    val stars = remember {
        val random = Random(20261030)
        List(140) {
            Star(
                angle = random.nextFloat() * 2f * PI.toFloat(),
                near = random.nextFloat(),                 // 0 = close to the middle, 1 = at the edge of the frame
                radius = 0.5f + random.nextFloat() * 1.3f, // dp
                speed = 0.010f + random.nextFloat() * 0.030f,
                phase = random.nextFloat() * 2f * PI.toFloat(),
                twinkle = 0.7f + random.nextFloat() * 2.4f,
            )
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = clock.value
        // Two nebulae: the brand's own two ends of light, sitting still while the dots travel through them.
        drawNebula(center = Offset(size.width * 0.30f, size.height * 0.34f), radius = size.minDimension * 0.62f, color = Color(0xFF8B10FA))
        drawNebula(center = Offset(size.width * 0.72f, size.height * 0.64f), radius = size.minDimension * 0.55f, color = Color(0xFF02A6FC))
        val cx = size.width / 2f
        val cy = size.height / 2f
        val reach = size.minDimension * 0.62f
        val px = density
        val fadeIn = if (still) 1f else ramp(t, 0f, 0.9f)
        stars.forEach { star ->
            val travel = if (still) star.near else (star.near + t * star.speed) % 1f
            val d = travel * reach
            val edge = 1f - smooth(((travel - 0.72f) / 0.28f).coerceIn(0f, 1f))
            val middle = smooth((travel / 0.10f).coerceIn(0f, 1f))
            val twinkle = 0.55f + 0.45f * sin(star.phase + t * star.twinkle)
            val a = (fadeIn * edge * middle * twinkle * 0.9f).coerceIn(0f, 1f)
            if (a > 0.01f) {
                drawCircle(
                    color = Color.White.copy(alpha = a),
                    radius = star.radius * px,
                    center = Offset(cx + cos(star.angle) * d, cy + sin(star.angle) * d),
                )
            }
        }
    }
}

private fun DrawScope.drawNebula(center: Offset, radius: Float, color: Color) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.16f), color.copy(alpha = 0.05f), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

private class Star(val angle: Float, val near: Float, val radius: Float, val speed: Float, val phase: Float, val twinkle: Float)

/** Just past the second tagline: what "reduce motion" draws instead of animating. */
private const val FINAL_SECOND = 2.7f
private const val TAGLINE_ONE = "Your music. Your universe."
private const val TAGLINE_TWO = "Every song is a star."

private fun ramp(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)
private fun easeOut(x: Float): Float { val v = 1f - x; return 1f - v * v * v }
private fun smooth(x: Float): Float = x * x * (3f - 2f * x)

/** Animator duration scale 0 means the phone asked for no animations at all. */
private fun animationsOn(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
}.getOrDefault(true)

/**
 * The sound: a room opening, once. Silent on a phone set to silent, and never able to fail a launch - every call is
 * contained, and a player that can't be made simply means the splash is quiet.
 */
private class SplashSound(private val context: Context) {
    private var player: MediaPlayer? = null
    private var source: AssetFileDescriptor? = null

    fun play() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio?.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        runCatching {
            val afd = context.resources.openRawResourceFd(R.raw.vinz_splash_space)
            source = afd
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener { stop() }
            p.prepareAsync()
            player = p
        }.onFailure { stop() }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { source?.close() }
        source = null
    }
}
