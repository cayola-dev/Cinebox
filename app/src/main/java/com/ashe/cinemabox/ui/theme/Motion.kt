package com.ashe.cinemabox.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Curves from the v2 prototype (CSS cubic-bezier values). */
object Motion {
    /** Rows and cards gliding into place. */
    val Glide = CubicBezierEasing(0.2f, 0.9f, 0.1f, 1f)
    /** Text rising into view. */
    val Rise = CubicBezierEasing(0.2f, 0.9f, 0.2f, 1f)
    /** Focus pop with a small overshoot. */
    val Pop = CubicBezierEasing(0.3f, 1.4f, 0.5f, 1f)
    /** Backdrop wipe. */
    val Wipe = CubicBezierEasing(0.7f, 0f, 0.2f, 1f)
    val LensX = CubicBezierEasing(0.34f, 1.5f, 0.55f, 1f)
    val LensY = CubicBezierEasing(0.34f, 1.35f, 0.55f, 1f)
    val LensSize = CubicBezierEasing(0.3f, 1.3f, 0.5f, 1f)
}

/**
 * The single accent frame that travels between focused elements. Focused elements report their
 * unscaled bounds; the host draws the frame 5% larger (matching their scale) plus a small gap.
 */
class FocusLens {
    internal var owner: Any? by mutableStateOf(null)
    internal var target: Rect? by mutableStateOf(null)
    internal var radius: Float by mutableStateOf(0f)

    internal fun claim(id: Any, bounds: Rect, cornerRadius: Float) {
        owner = id
        target = bounds
        radius = cornerRadius
    }

    internal fun release(id: Any) {
        if (owner === id) owner = null
    }
}

val LocalFocusLens = staticCompositionLocalOf<FocusLens?> { null }

/** Reports this element to the lens while [focused]; place it before any scale modifier. */
@Composable
fun Modifier.lensTarget(focused: Boolean, shape: Shape): Modifier {
    val lens = LocalFocusLens.current ?: return this
    val id = remember { Any() }
    val density = LocalDensity.current
    DisposableEffect(lens, id) { onDispose { lens.release(id) } }
    LaunchedEffect(focused) { if (!focused) lens.release(id) }
    if (!focused) return this
    return onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInRoot()
        val corner = (shape as? CornerBasedShape)?.topStart?.toPx(Size(bounds.width, bounds.height), density) ?: 0f
        lens.claim(id, bounds, corner)
    }
}

@Composable
fun FocusLensHost(content: @Composable BoxScope.() -> Unit) {
    val lens = remember { FocusLens() }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalFocusLens provides lens) { content() }
        LensFrame(lens)
    }
}

@Composable
private fun LensFrame(lens: FocusLens) {
    val density = LocalDensity.current
    val gap = with(density) { 2.dp.toPx() }
    val x = remember { Animatable(0f) }
    val y = remember { Animatable(0f) }
    val w = remember { Animatable(0f) }
    val h = remember { Animatable(0f) }
    val r = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val visible = lens.owner != null && lens.target != null
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(300), label = "lensAlpha")

    LaunchedEffect(lens.target, lens.radius) {
        val t = lens.target ?: return@LaunchedEffect
        val tx = t.left - t.width * 0.025f - gap
        val ty = t.top - t.height * 0.025f - gap
        val tw = t.width * 1.05f + gap * 2
        val th = t.height * 1.05f + gap * 2
        val tr = lens.radius * 1.05f + gap
        if (!placed) {
            x.snapTo(tx); y.snapTo(ty); w.snapTo(tw); h.snapTo(th); r.snapTo(tr)
            placed = true
            return@LaunchedEffect
        }
        coroutineScope {
            launch { x.animateTo(tx, tween(440, easing = Motion.LensX)) }
            launch { y.animateTo(ty, tween(500, easing = Motion.LensY)) }
            launch { w.animateTo(tw, tween(380, easing = Motion.LensSize)) }
            launch { h.animateTo(th, tween(420, easing = Motion.LensSize)) }
            launch { r.animateTo(tr, tween(320, easing = FastOutSlowInEasing)) }
        }
    }

    val breath = rememberInfiniteTransition(label = "lensBreath")
    val pulse by breath.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "pulse",
    )
    val stroke = with(density) { 2.dp.toPx() }
    val spread = with(density) { 6.dp.toPx() }
    Box(
        Modifier.fillMaxSize().drawBehind {
            if (alpha <= 0f || !placed) return@drawBehind
            // Breathing halo: expands and fades twice per cycle, like the prototype's box-shadow pulse.
            val phase = if (pulse < 0.5f) pulse * 2f else (1f - pulse) * 2f
            val halo = spread * phase
            drawRoundRect(
                color = Cine.Accent.copy(alpha = 0.5f * (1f - phase) * alpha),
                topLeft = Offset(x.value - halo, y.value - halo),
                size = Size(w.value + halo * 2, h.value + halo * 2),
                cornerRadius = CornerRadius(r.value + halo),
                style = Stroke(width = stroke),
            )
            drawRoundRect(
                color = Cine.Accent.copy(alpha = alpha),
                topLeft = Offset(x.value, y.value),
                size = Size(w.value, h.value),
                cornerRadius = CornerRadius(r.value),
                style = Stroke(width = stroke),
            )
        },
    )
}
