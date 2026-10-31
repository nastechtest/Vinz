package com.vinz.music.ui.intro

import android.content.Context
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.vinz.music.R
import com.vinz.music.ui.theme.Haptics
import com.vinz.music.ui.theme.rememberHaptics
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Vinz's entrance: the whole of it, once per install. The camera pulls away from a planet, through the sky it was
 * sitting in, and the name arrives out of the last of that light. Then the sky is held into a ring at the bottom of the
 * screen — the only way past, and a gesture rather than a button — and the hold ends on a slash of the wordmark and the
 * welcome sound.
 *
 * The scene under the words is [drawIntro], which is drawing and nothing else. Every animated property here is read
 * inside a [graphicsLayer] block, a `drawBehind` or a [Canvas] lambda, all of which run in the draw phase: a frame of
 * the intro invalidates drawing only. The only things that recompose are the three moments the copy changes, each a
 * [derivedStateOf] flipping a boolean.
 */
@Composable
fun VinzIntro(reduceMotion: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    // Animations switched off in the phone's developer options, or "Reduce motion" in Vinz: the same frame, held still.
    val still = reduceMotion || !introAnimationsOn(context)

    // Starts at the first light, not at zero: 0.00-0.15 is black by design, and on a cold start that is the frame
    // people judge the whole app by.
    val clock = remember { mutableStateOf(if (still) Intro.STILL_AT else Intro.SPARK) }
    val hold = remember { mutableStateOf(0f) }
    val holding = remember { mutableStateOf(false) }
    // Tapped through the cinematic: everything is pinned at its resting frame, and the dock is what is left to do.
    var skipped by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    // Skipped outright: the entrance fades to black and the app takes the screen, rather than cutting mid-frame.
    var leaving by remember { mutableStateOf(false) }
    // The entrance is once per install, and it must be once: two endings racing each other would write the flag twice
    // and, worse, hand the screen over twice.
    var done by remember { mutableStateOf(false) }
    val finish = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    val stars = remember { introStars() }
    var dock by remember { mutableStateOf(Offset.Unspecified) }

    // The entrance is the one thing in Vinz that must not be interrupted by a screen timeout: a phone that sleeps
    // halfway through it looks exactly like a phone that crashed on launch.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val sound = remember { IntroSound(context) }
    // The entrance's own sound switch. It exists because an intro is also something you demonstrate, and a room with
    // other people in it is not always a room that wants the bed and the voice.
    var muted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        sound.bed()
        sound.voice()
        onDispose { sound.stop() }
    }

    // One clock for the whole experience: it carries the timeline the scene reads and the ring the finger fills.
    //
    // A 16 ms timer, not withFrameNanos. This loop used to await the composition's frame clock, and on device it never
    // resumed: the entrance sat at t = 0 -- which is pure black by design -- until a tap forced the clock to its resting
    // frame. A timer cannot be starved that way, and a frame still invalidates drawing only, because the clock and the
    // ring are read inside graphicsLayer and draw lambdas.
    LaunchedEffect(Unit) {
        // The clock starts where the picture does: the timeline is unchanged, it has simply already run for the
        // quarter of a second that used to be black.
        val start = System.nanoTime() - (Intro.SPARK * 1_000_000_000f).toLong()
        var last = start
        var ticks = 0
        while (isActive) {
            delay(16)
            val now = System.nanoTime()
            // Clamped: a tick that arrives late (a stall, or the app coming back from the background) must not jump the
            // ring a third of the way round under a finger that did nothing.
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            last = now
            // Held at the dock, not left running: with the ring up, nothing is supposed to move until a hand does.
            // Before this the clock kept counting, so the sky went on drifting and the floats went on growing while
            // the entrance waited -- and after a long wait `spin * t` was large enough to lose its precision.
            if (!skipped && !still) clock.value = min((now - start) / 1_000_000_000f, Intro.DOCK)
            if (holding.value) {
                hold.value = (hold.value + dt / Intro.HOLD).coerceAtMost(1f)
                val tick = (hold.value * 13f).toInt()
                if (tick != ticks) { ticks = tick; haptics.tick() }
                if (hold.value >= 1f && !finishing) { finishing = true; holding.value = false }
            } else if (hold.value > 0f) {
                hold.value = (hold.value - dt / Intro.RELEASE).coerceAtLeast(0f)
                ticks = (hold.value * 13f).toInt()
            }
        }
    }

    // A net under the clock. If the timer never ticks - a device that starves it, a coroutine that never resumes - the
    // entrance would sit on its first frame and the app would look dead, which is precisely the report this screen
    // keeps getting. A second and a half of nothing is not a slow start, it is a stopped clock: the resting frame goes
    // up instead, ring and all, so the entrance can still be finished or skipped.
    LaunchedEffect(Unit) {
        delay(1_500)
        if (clock.value <= Intro.SPARK + 0.02f) {
            skipped = true
            clock.value = Intro.STILL_AT
        }
    }

    // The ending: the ring closes, the screen flares, the wordmark slashes once, and the room goes black again.
    LaunchedEffect(finishing) {
        if (!finishing) return@LaunchedEffect
        sound.sting()
        haptics.click()
        finish.animateTo(1f, tween(if (still) 240 else 1600, easing = LinearEasing))
        if (!done) { done = true; onDone() }
    }

    // Skipped: the picture goes out under a short fade and the app takes the screen. The bed and the voice are ducked
    // rather than cut, so the first page of Vinz arrives out of a room going quiet instead of out of a chopped chord.
    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        scope.launch { sound.quiet() }
        fade.animateTo(1f, tween(if (still) 120 else 280, easing = LinearEasing))
        if (!done) { done = true; onDone() }
    }

    val ready by remember(still) { derivedStateOf { still || clock.value >= Intro.DOCK } }
    // A hand that has done nothing for six seconds at the ring is a hand that has not worked out that the ring is a
    // hold. The label says so, and says what else is available - the entrance is the hold, but it is not the only door.
    var idleAtRing by remember { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (!ready) { idleAtRing = false; return@LaunchedEffect }
        delay(6_000)
        idleAtRing = true
    }
    // The Skip button appears once the first light is on screen, so the very first frame stays clean but nobody is ever
    // a prisoner of the cinematic. Read through a derived state so the picture still only recomposes the once.
    val canSkip by remember(still) { derivedStateOf { still || clock.value >= 0.45f } }
    // Tapping the scene is a way through the cinematic, never through the entrance: it lands on the dock, not past it.
    fun skipCinematic() {
        if (skipped || ready || leaving) return
        skipped = true
        clock.value = Intro.STILL_AT
        scope.launch { sound.quiet() }
    }
    // Skip: past the entrance altogether. Nothing is asked of a hand that does not want to hold anything.
    fun leave() {
        if (leaving || finishing) return
        haptics.click()
        leaving = true
    }
    BackHandler(enabled = !leaving && !finishing) { if (ready) leave() else skipCinematic() }

    val named = stringResource(R.string.intro_welcome)
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            // One name for the whole overlay, so TalkBack says what this is instead of reading the screen out.
            .semantics { paneTitle = named }
            .pointerInput(ready) { if (!ready) detectTapGestures { skipCinematic() } },
    ) {
        // Sized from the height and then kept in by the width, so the words take the same share of the screen on a
        // phone, a tablet and a 4K television instead of the same number of dp on all three.
        val scale = (min(maxHeight.value / 780f, maxWidth.value / 400f)).coerceIn(0.85f, 3.4f)
        val dockRadius = (Intro.DOCK_RADIUS_DP * scale).dp
        val inset = (Intro.DOCK_INSET_DP * scale).dp

        Canvas(Modifier.fillMaxSize()) {
            drawIntro(
                t = clock.value,
                hold = hold.value,
                still = still,
                stars = stars,
                dockCenter = if (dock.isSpecified) dock else dockCentre(size, dockRadius.toPx(), inset.toPx()),
                dockRadius = dockRadius.toPx(),
            )
        }

        // The words. Each reads the clock in its own layer, so they move without recomposing.
        Column(
            Modifier
                .align(Alignment.Center)
                // Decorative: "Vinz", "POWERED BY", "NASTECH RESEARCH" and the taglines are the picture, not controls.
                // Without this TalkBack walked all five of them before it reached either thing it can actually use.
                .clearAndSetSemantics { }
                .graphicsLayer { translationY = -20.dp.toPx() * ramp(clock.value, Intro.DOCK - 0.7f, Intro.DOCK + 0.2f) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Wordmark(clock, still, scale)
            Spacer(Modifier.height((20 * scale).dp))
            Hairline(clock, still, scale)
            Spacer(Modifier.height((18 * scale).dp))
            PoweredBy(clock, still, scale)
            Spacer(Modifier.height((14 * scale).dp))
            Tagline(clock, still, scale)
        }

        Dock(
            hold = hold,
            clock = clock,
            still = still,
            enabled = ready && !finishing && !leaving,
            radius = dockRadius,
            scale = scale,
            haptics = haptics,
            hint = idleAtRing,
            holding = holding,
            onComplete = { if (!finishing && !leaving) { finishing = true; holding.value = false } },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Vinz draws edge to edge, so the 44 dp the ring wants above the bottom of the frame is on top of
                // whatever the system keeps for itself: on a phone with three buttons the ring used to sit under them.
                .navigationBarsPadding()
                .padding(bottom = inset)
                .onGloballyPositioned { c ->
                    val b = c.boundsInParent()
                    dock = b.center
                },
        )

        // How far through the cinematic this is: a hairline of the brand's light along the bottom edge. It is the
        // answer to "how long is this", and it fills from the left, so it also reads as something that ends.
        if (!ready) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .graphicsLayer {
                        scaleX = if (still) 1f else (clock.value / Intro.DOCK).coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        alpha = 0.55f
                    }
                    .background(Brush.horizontalGradient(listOf(Brand.violet, Brand.blue, Brand.cyan))),
            )
        }

        Ending(finish, still, scale, dock)

        // Skip. Not hidden behind the hold and not only on a gesture: from half a second in, one tap is enough to get
        // into the app, and it is a real control (its own label, its own action) rather than something on the picture.
        if (canSkip && !finishing && !leaving) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 10.dp, end = 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.07f))
                    .clickable(onClickLabel = stringResource(R.string.intro_skip_action)) { leave() }
                    .padding(horizontal = (16 * scale).dp, vertical = (8 * scale).dp),
            ) {
                Text(
                    text = stringResource(R.string.intro_skip),
                    color = Color.White.copy(alpha = 0.66f),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = (11 * scale).sp,
                        letterSpacing = (2.2f * scale).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }

        // Sound on / off, beside Skip: quiet for a demo, or a phone whose ringer is up in a meeting.
        if (canSkip && !finishing && !leaving) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 10.dp, start = 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.07f))
                    .clickable(onClickLabel = stringResource(R.string.intro_sound_action)) {
                        muted = !muted
                        sound.mute(muted)
                    }
                    .padding(horizontal = (16 * scale).dp, vertical = (8 * scale).dp),
            ) {
                Text(
                    text = stringResource(if (muted) R.string.intro_sound_off else R.string.intro_sound_on),
                    color = Color.White.copy(alpha = 0.66f),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = (11 * scale).sp,
                        letterSpacing = (2.2f * scale).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }

        // The way out, over everything: skipping fades the entrance to black before the app's first page is shown.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .graphicsLayer { alpha = fade.value },
        )
    }
}

/** The name, out of the light: up a little, a breath of overshoot, and one pass of light across it as it lands. */
@Composable
private fun Wordmark(clock: State<Float>, still: Boolean, scale: Float) {
    val rise = (16 * scale).dp
    Text(
        text = stringResource(R.string.app_name),
        color = Color.White,
        maxLines = 1,
        style = MaterialTheme.typography.displayLarge.copy(
            fontSize = (72 * scale).sp,
            lineHeight = (78 * scale).sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (5 * scale).sp,
        ),
        modifier = Modifier
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                if (still) {
                    alpha = 1f; scaleX = 1f; scaleY = 1f; translationY = 0f
                } else {
                    val t = clock.value
                    val w = easeOut(ramp(t, Intro.WORD, Intro.WORD + 0.85f))
                    val pop = 1f + 0.02f * sin(PI.toFloat() * w)
                    alpha = w
                    scaleX = (1.10f - 0.10f * w) * pop
                    scaleY = scaleX
                    translationY = (1f - w) * rise.toPx()
                }
            }
            .drawWithContent {
                drawContent()
                if (still) return@drawWithContent
                val sweep = ramp(clock.value, Intro.SWEEP, Intro.SWEEP + 0.85f)
                if (sweep <= 0f || sweep >= 1f) return@drawWithContent
                val band = size.width * 0.55f
                val x = sweep * (size.width + band * 2f) - band
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(Color.Transparent, Color(0x66FFFFFF), Color.Transparent),
                        start = Offset(x - band, 0f),
                        end = Offset(x + band, size.height),
                    ),
                    blendMode = BlendMode.SrcAtop,
                )
            },
    )
}

/** The thin line under the name, drawn out from the middle. */
@Composable
private fun Hairline(clock: State<Float>, still: Boolean, scale: Float) {
    Box(
        Modifier
            .size(width = (132 * scale).dp, height = 1.dp)
            .graphicsLayer {
                val w = if (still) 1f else smooth(ramp(clock.value, Intro.WORD + 0.35f, Intro.WORD + 1.15f))
                scaleX = w
                alpha = if (still) 0.55f else 0.55f * w
            }
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.7f), Color.Transparent),
                ),
            ),
    )
}

/** "POWERED BY NASTECH RESEARCH": the second half in the brand's own two lights. */
@Composable
private fun PoweredBy(clock: State<Float>, still: Boolean, scale: Float) {
    val rise = (10 * scale).dp
    val style = MaterialTheme.typography.labelSmall.copy(
        fontSize = (10 * scale).sp,
        letterSpacing = (2.6f * scale).sp,
        fontWeight = FontWeight.Medium,
    )
    Box(
        Modifier.graphicsLayer {
            if (still) {
                alpha = 1f; translationY = 0f
            } else {
                val w = easeOut(ramp(clock.value, Intro.POWERED, Intro.POWERED + 0.70f))
                alpha = w
                translationY = (1f - w) * rise.toPx()
            }
        },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.intro_powered_by),
                color = Color.White.copy(alpha = 0.42f),
                maxLines = 1,
                style = style,
            )
            Spacer(Modifier.height((5 * scale).dp))
            GradientText(
                text = stringResource(R.string.intro_nastech),
                style = style,
                brush = Brush.horizontalGradient(listOf(Brand.violet, Brand.blue, Brand.cyan)),
            )
        }
    }
}

/** Copy that reads only where the brand's gradient is allowed to be: drawn white, then cut back to the gradient. */
@Composable
private fun GradientText(text: String, style: TextStyle, brush: Brush, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        maxLines = 1,
        style = style,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(brush = brush, blendMode = BlendMode.SrcAtop)
            },
    )
}

/** The three lines about what this is, one at a time, under the name. */
@Composable
private fun Tagline(clock: State<Float>, still: Boolean, scale: Float) {
    val lines = listOf(
        stringResource(R.string.intro_line_one),
        stringResource(R.string.intro_line_two),
        stringResource(R.string.intro_line_three),
    )
    val index by remember(still) {
        derivedStateOf {
            when {
                still || clock.value < Intro.LINE_TWO -> 0
                clock.value < Intro.LINE_THREE -> 1
                else -> 2
            }
        }
    }
    val rise = (9 * scale).dp
    Box(Modifier.height((26 * scale).dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text = lines[index],
            color = Color.White.copy(alpha = 0.66f),
            maxLines = 1,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = (14 * scale).sp,
                letterSpacing = (0.4f * scale).sp,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                .graphicsLayer {
                    if (still) {
                        alpha = 1f; translationY = 0f
                    } else {
                        val inAt = when (index) { 0 -> Intro.LINE_ONE; 1 -> Intro.LINE_TWO; else -> Intro.LINE_THREE }
                        val w = easeOut(ramp(clock.value, inAt, inAt + 0.45f))
                        alpha = w
                        translationY = (1f - w) * rise.toPx()
                    }
                },
        )
    }
}

/**
 * The ring: hold it and it fills, and the sky goes with it. Let go and it drains back. It is the only way past the
 * entrance, but a finger is not the only way in — the node is a button for TalkBack, and with reduce motion on a tap is
 * enough, because a ring that fills under a held finger is a movement.
 */
@Composable
private fun Dock(
    hold: State<Float>,
    clock: State<Float>,
    still: Boolean,
    enabled: Boolean,
    radius: Dp,
    scale: Float,
    haptics: Haptics,
    /** Nothing has happened for a while: say what the ring wants, and what else there is. */
    hint: Boolean,
    holding: androidx.compose.runtime.MutableState<Boolean>,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelRes by remember(still, hint) {
        derivedStateOf {
            when {
                hold.value >= 0.82f -> R.string.intro_almost
                hold.value > 0.02f -> R.string.intro_holding
                hint -> R.string.intro_hold_hint
                else -> R.string.intro_hold
            }
        }
    }
    val label = stringResource(labelRes)
    val welcome = stringResource(R.string.intro_welcome)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(radius * 2)
                .graphicsLayer {
                    // The ring arrives last, after the words, and stays.
                    if (still) {
                        alpha = 1f; scaleX = 1f; scaleY = 1f
                    } else {
                        val w = easeOut(ramp(clock.value, Intro.DOCK, Intro.DOCK + 0.85f))
                        alpha = w
                        val pop = 1f + 0.03f * sin(PI.toFloat() * w)
                        scaleX = (0.86f + 0.14f * w) * pop
                        scaleY = scaleX
                    }
                }
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    onClick(label = welcome) { onComplete(); true }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        holding.value = true
                        haptics.tick()
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            holding.value = false
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_monochrome),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier
                    .size(radius * 0.84f)
                    .graphicsLayer {
                        val breathe = if (still) 1f else 1f + 0.06f * sin(clock.value * 1.4f)
                        val grow = 1f + 0.22f * hold.value
                        alpha = 0.72f + 0.28f * hold.value
                        scaleX = breathe * grow
                        scaleY = breathe * grow
                    },
            )
        }
        Spacer(Modifier.height((13 * scale).dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.34f + 0.5f * hold.value),
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = (10 * scale).sp,
                letterSpacing = (2.4f * scale).sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

/** The ending: the ring closes to a point, the screen flares, the name slashes once, and it goes back to black. */
@Composable
private fun Ending(finish: Animatable<Float, AnimationVector1D>, still: Boolean, scale: Float, dock: Offset) {
    // The flare, from the ring that just closed.
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val f = finish.value
                if (f <= 0f || f >= 0.55f) return@drawBehind
                val a = (if (f < 0.09f) f / 0.09f else 1f - ramp(f, 0.09f, 0.55f)).coerceIn(0f, 1f)
                val c = if (dock.isSpecified) dock else center
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.92f * a),
                        0.35f to Brand.cyan.copy(alpha = 0.45f * a),
                        1f to Color.Transparent,
                        center = c,
                        radius = size.maxDimension * (0.35f + 0.75f * f),
                    ),
                    radius = size.maxDimension * (0.35f + 0.75f * f),
                    center = c,
                )
            },
    )
    // The word, once, huge, with one slash of light across it.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(
        text = stringResource(R.string.app_name),
        color = Color.White,
        maxLines = 1,
        style = MaterialTheme.typography.displayLarge.copy(
            fontSize = (124 * scale).sp,
            lineHeight = (132 * scale).sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (6 * scale).sp,
        ),
        modifier = Modifier
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                val f = finish.value
                alpha = ramp(f, 0.05f, 0.16f) * (1f - ramp(f, 0.46f, 0.72f))
                val s = 0.94f + 0.06f * smooth(ramp(f, 0.05f, 0.40f))
                scaleX = s
                scaleY = s
            }
            .drawWithContent {
                drawContent()
                val f = finish.value
                val slash = ramp(f, 0.06f, 0.40f)
                if (slash <= 0f || slash >= 1f) return@drawWithContent
                val band = size.width * 0.30f
                val x = slash * (size.width + band * 2f) - band
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(Color.Transparent, Color(0xCCFFFFFF), Color.Transparent),
                        start = Offset(x - band, 0f),
                        end = Offset(x + band, size.height * 0.35f),
                    ),
                    blendMode = BlendMode.SrcAtop,
                )
            },
    )
    }
    // Back to the black the app starts on.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer { alpha = if (still) ramp(finish.value, 0.15f, 1f) else ramp(finish.value, 0.52f, 1f) },
    )
}

/** Animator duration scale 0 means the phone asked for no animations at all. */
private fun introAnimationsOn(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
}.getOrDefault(true)
