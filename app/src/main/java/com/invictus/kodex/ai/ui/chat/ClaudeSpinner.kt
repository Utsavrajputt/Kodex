package com.invictus.kodex.ai.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** The terracotta of Claude's own spinner. */
val ClaudeSpinnerColor = Color(0xFFD97757)

/**
 * A "lobed" shape in polar form: r(θ) = floor + (1 - floor) · |cos(lobes·θ / 2)|^sharp.
 * More lobes = more petals, higher [sharp] = thinner spikes, higher [floor] = rounder body.
 */
private class SpinnerShape(val lobes: Int, val sharp: Float, val floor: Float) {
    fun radius(theta: Float): Float =
        floor + (1f - floor) * abs(cos(lobes * theta / 2f)).pow(sharp)
}

/** The shapes the spinner morphs through, in order (it loops back to the first). */
private val SpinnerShapes = listOf(
    SpinnerShape(lobes = 4, sharp = 1.7f, floor = 0.12f),  // four-point spark
    SpinnerShape(lobes = 6, sharp = 0.8f, floor = 0.36f),  // soft flower
    SpinnerShape(lobes = 8, sharp = 2.3f, floor = 0.10f),  // starburst
    SpinnerShape(lobes = 5, sharp = 0.7f, floor = 0.46f),  // rounded pentagon-ish blob
    SpinnerShape(lobes = 3, sharp = 0.9f, floor = 0.38f),  // trefoil
    SpinnerShape(lobes = 12, sharp = 3.0f, floor = 0.26f), // asterisk burst
    SpinnerShape(lobes = 2, sharp = 0.6f, floor = 0.52f),  // squashed oval
)

private const val SHAPE_HOLD_MS = 1500   // time per shape (hold + morph)
private const val SAMPLES = 96

/** Smooth in-out curve used for the morph (holds near the ends, flows through the middle). */
private val MorphEasing = Easing { t -> t * t * t * (t * (t * 6f - 15f) + 10f) }

/**
 * Claude-style activity spinner: one solid shape that keeps morphing (spark → flower → starburst → …),
 * slowly turns and breathes. Everything animates in the draw phase, so it never recomposes.
 */
@Composable
fun ClaudeSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    color: Color = ClaudeSpinnerColor,
) {
    val transition = rememberInfiniteTransition(label = "claudeSpinner")
    val n = SpinnerShapes.size
    val phase: State<Float> = transition.animateFloat(
        initialValue = 0f,
        targetValue = n.toFloat(),
        animationSpec = infiniteRepeatable(tween(SHAPE_HOLD_MS * n, easing = LinearEasing)),
        label = "morph",
    )
    val spin: State<Float> = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "spin",
    )
    val breathe: State<Float> = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val path = remember { Path() }
    Canvas(modifier.size(size)) {
        val p = phase.value
        val idx = p.toInt().coerceIn(0, n - 1)
        val a = SpinnerShapes[idx]
        val b = SpinnerShapes[(idx + 1) % n]
        // First 40 % of each slot holds the shape, the rest morphs into the next.
        val frac = ((p - idx - 0.4f) / 0.6f).coerceIn(0f, 1f)
        val t = MorphEasing.transform(frac)
        val rot = spin.value
        val scale = 0.86f + 0.14f * MorphEasing.transform(breathe.value)
        val maxR = this.size.minDimension / 2f * scale
        val c = center
        path.reset()
        for (i in 0..SAMPLES) {
            val theta = (2 * PI * i / SAMPLES).toFloat()
            val r = (a.radius(theta) * (1f - t) + b.radius(theta) * t) * maxR
            val x = c.x + r * cos(theta + rot)
            val y = c.y + r * sin(theta + rot)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
    }
}

/** Claude's own pool of "thinking" words. */
private val ThinkingWords = listOf(
    "Thinking", "Pondering", "Fathoming", "Cogitating", "Ruminating", "Musing", "Contemplating",
    "Mulling", "Deliberating", "Percolating", "Brewing", "Noodling", "Simmering", "Marinating",
    "Churning", "Computing", "Conjuring", "Crafting", "Synthesizing", "Unravelling", "Wrangling",
    "Spelunking", "Divining", "Cerebrating", "Reticulating", "Manifesting", "Ideating",
    "Philosophising", "Pontificating", "Stewing", "Forging", "Hatching", "Concocting",
    "Deciphering", "Gestating", "Incubating", "Orchestrating", "Puzzling", "Scheming",
    "Tinkering", "Weaving", "Whirring", "Cooking", "Clauding",
)

/**
 * Text with a light sweep running across it (the shimmer Claude puts on its status line). The gradient is
 * drawn over the glyphs only, in the draw phase.
 */
fun Modifier.shimmerText(base: Color, highlight: Color, periodMs: Int = 1700): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x: State<Float> = transition.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing)),
        label = "shimmerX",
    )
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = size.width.coerceAtLeast(1f)
            val start = x.value * w
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(base, base, highlight, base, base),
                    start = Offset(start - w * 0.35f, 0f),
                    end = Offset(start + w * 0.35f, 0f),
                ),
                blendMode = BlendMode.SrcIn,
            )
        }
}

/** A status line with a shimmer. */
@Composable
fun ShimmerText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = 1,
) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val highlight = MaterialTheme.colorScheme.onSurface
    Text(
        text,
        style = style,
        color = base,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.shimmerText(base, highlight),
    )
}

/**
 * "Thinking…" that changes its word every couple of seconds ("Pondering…", "Fathoming…", …), each swap
 * sliding and fading, the way Claude's mobile app does. [seed] keeps the order stable for one message.
 */
@Composable
fun ThinkingLabel(
    modifier: Modifier = Modifier,
    seed: Long = 0L,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val words = remember(seed) {
        // Always open with "Thinking", then a shuffled run of the rest.
        listOf(ThinkingWords.first()) + ThinkingWords.drop(1).shuffled(Random(seed))
    }
    var index by remember(seed) { mutableIntStateOf(0) }
    LaunchedEffect(seed) {
        while (true) {
            delay(2300)
            index = (index + 1) % words.size
        }
    }
    AnimatedContent(
        targetState = words[index],
        transitionSpec = {
            (fadeIn(tween(350, delayMillis = 120)) + slideInVertically(tween(350, delayMillis = 120)) { it / 2 }) togetherWith
                (fadeOut(tween(220)) + slideOutVertically(tween(220)) { -it / 2 })
        },
        modifier = modifier,
        label = "thinkingWord",
    ) { word ->
        ShimmerText(text = "$word…", style = style)
    }
}
