package com.ashe.cinemabox.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.ashe.cinemabox.library.FileRepository
import com.ashe.cinemabox.library.MediaItem
import com.ashe.cinemabox.library.formatDate
import com.ashe.cinemabox.library.formatDuration
import com.ashe.cinemabox.library.formatSize
import com.ashe.cinemabox.ui.theme.Cine
import com.ashe.cinemabox.ui.theme.CineButton
import com.ashe.cinemabox.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Direction of the last D-pad move; the new backdrop is revealed from that side. */
enum class Wipe { Right, Left, Down, Up, None }

private class BackdropLayer(val key: Int, val item: MediaItem, val wipe: Wipe)

/** Full-screen cover of the focused item: wipes in from the move direction and drifts slowly. */
@Composable
fun StageBackdrop(item: MediaItem?, wipe: Wipe, files: FileRepository, parallax: androidx.compose.ui.unit.DpOffset = androidx.compose.ui.unit.DpOffset.Zero) {
    var layers by remember { mutableStateOf(emptyList<BackdropLayer>()) }
    var counter by remember { mutableIntStateOf(0) }
    LaunchedEffect(item?.chatId, item?.messageId) {
        val next = item ?: return@LaunchedEffect
        // Holding a direction should not decode a cover for every intermediate card.
        delay(90)
        val top = layers.lastOrNull()
        if (top != null && top.item.chatId == next.chatId && top.item.messageId == next.messageId) return@LaunchedEffect
        layers = layers.takeLast(1) + BackdropLayer(++counter, next, if (top == null) Wipe.None else wipe)
        delay(650)
        layers = layers.takeLast(1)
    }
    val drift = rememberInfiniteTransition(label = "drift")
    val driftP by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse), label = "driftP")
    val driftPx = with(LocalDensity.current) { 18.dp.toPx() }
    // v3 parallax: the whole stage glides against the focus with a long ease-out, overscanned to 1.14.
    val glide = tween<Dp>(1400, easing = androidx.compose.animation.core.CubicBezierEasing(.16f, 1f, .3f, 1f))
    val shiftX by androidx.compose.animation.core.animateDpAsState(parallax.x, glide, label = "parallaxX")
    val shiftY by androidx.compose.animation.core.animateDpAsState(parallax.y, glide, label = "parallaxY")
    Box(Modifier.fillMaxSize().background(Cine.Background)) {
      Box(Modifier.fillMaxSize().graphicsLayer {
          translationX = shiftX.toPx(); translationY = shiftY.toPx(); scaleX = PARALLAX_SCALE; scaleY = PARALLAX_SCALE
      }) {
        layers.forEachIndexed { index, layer ->
            key(layer.key) {
                BackdropImage(layer, animate = index == layers.lastIndex && layer.wipe != Wipe.None, files) {
                    scaleX = 1.02f + 0.08f * driftP
                    scaleY = scaleX
                    translationX = -driftPx * driftP
                }
            }
        }
      }
    }
}

private const val PARALLAX_SCALE = 1.14f

@Composable
private fun BackdropImage(
    layer: BackdropLayer,
    animate: Boolean,
    files: FileRepository,
    drift: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit,
) {
    val image by rememberThumbnail(layer.item, files)
    val reveal = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (animate) reveal.animateTo(1f, tween(600, easing = Motion.Glide)) }
    Box(
        Modifier.fillMaxSize()
            .drawWithContent {
                val p = reveal.value
                val w = size.width
                val h = size.height
                when (layer.wipe) {
                    Wipe.Right -> clipRect(left = w * (1 - p)) { this@drawWithContent.drawContent() }
                    Wipe.Left -> clipRect(right = w * p) { this@drawWithContent.drawContent() }
                    Wipe.Down -> clipRect(top = h * (1 - p)) { this@drawWithContent.drawContent() }
                    Wipe.Up -> clipRect(bottom = h * p) { this@drawWithContent.drawContent() }
                    Wipe.None -> drawContent()
                }
            }
            .graphicsLayer {
                val s = 1.05f - 0.05f * reveal.value
                scaleX = s
                scaleY = s
            }
            .background(Color(0xFF1B1B20)),
    ) {
        image?.let {
            Image(it, null, Modifier.fillMaxSize().graphicsLayer(drift), contentScale = ContentScale.Crop)
        }
        if (image == null && layer.item.kind == com.ashe.cinemabox.library.MediaKind.Audio)
            AudioCover(layer.item.title, Modifier.fillMaxHeight().width(480.dp).graphicsLayer(drift))
    }
}

/** The three shades of the prototype; their strength changes between Home, Detail and Player. */
@Composable
fun StageShades(dim: Float, left: Float, bottom: Float) {
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim }.background(Color(0x590E0E11)))
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = left }.background(
            Brush.horizontalGradient(0f to Cine.Background, 0.32f to Color(0xD90E0E11), 0.72f to Color(0x1A0E0E11))
        )
    )
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = bottom }.background(
            Brush.verticalGradient(0.34f to Color.Transparent, 0.66f to Cine.Background, 1f to Cine.Background)
        )
    )
}

/** Render-layer fade and lift, with bounded staggering and no per-frame blur allocation. */
@Composable
fun Modifier.rise(key: Any, step: Float): Modifier {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay((step.coerceIn(0f, 8f) * 35).toLong())
        progress.animateTo(1f, tween(420, easing = Motion.Rise))
    }
    val lift = with(LocalDensity.current) { 14.dp.toPx() }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1 - p) * lift
        // Alpha and translation stay on the render layer; no full-surface blur allocation per frame.
    }
}

/** Source line, title (word by word), meta and tags of the focused item. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InfoPanel(
    item: MediaItem,
    label: String,
    big: Boolean,
    modifier: Modifier = Modifier,
    extraMeta: List<String> = emptyList(),
    source: (@Composable () -> Unit)? = null,
) {
    val key = "$big-${item.chatId}-${item.messageId}"
    val words = item.title.split(' ').filter { it.isNotEmpty() }
    Column(modifier.width(if (big) 550.dp else 480.dp), verticalArrangement = Arrangement.spacedBy(if (big) 13.dp else 9.dp)) {
        Box(Modifier.rise(key, 0f)) {
            source?.invoke() ?: Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Cine.TextSoft, maxLines = 1)
        }
        // Detail: one text block, so titles without spaces (Chinese, Japanese) also stop at two lines with "…".
        if (big) Text(
            item.title, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold,
            fontFamily = com.ashe.cinemabox.ui.theme.Urbanist,
            letterSpacing = (-0.75).sp, lineHeight = 44.sp, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.rise(key, 1f),
        ) else FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), maxLines = 3) {
            words.forEachIndexed { i, word ->
                Text(
                    word, fontSize = if (big) 48.sp else 40.sp, fontWeight = FontWeight.ExtraBold,
                    fontFamily = com.ashe.cinemabox.ui.theme.Urbanist,
                    letterSpacing = if (big) (-1).sp else (-0.75).sp, lineHeight = if (big) 50.sp else 42.sp,
                    modifier = Modifier.rise(key, 1 + i * 0.6f),
                )
            }
        }
        val after = 2 + words.size * 0.6f
        Text(
            listOfNotNull(item.durationSeconds?.let(::formatDuration), formatDate(item.date),
                item.sizeBytes.takeIf { it > 0 }?.let(::formatSize)).plus(extraMeta).joinToString("  ·  "),
            fontSize = 14.sp, color = Cine.TextSoft, modifier = Modifier.rise(key, after),
        )
        if (item.tags.isNotEmpty()) {
            FlowRow(Modifier.rise(key, after + 1), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp), maxLines = 2) {
                item.tags.forEach { TagChip(it) }
            }
        }
    }
}

@Composable
fun TagChip(text: String, height: Dp = 24.dp) {
    Box(
        Modifier.height(height).background(Color(0xD925252B), RoundedCornerShape(height / 2))
            .border(1.dp, Color(0xFF3A3A42), RoundedCornerShape(height / 2)).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}

/** Remote-control hints: bottom-right on Home, top-right elsewhere. */
@Composable
fun BoxScope.HintsBar(hints: List<Pair<String, String>>, atBottom: Boolean, visible: Boolean = false) {
    Row(
        Modifier.align(if (atBottom) Alignment.BottomEnd else Alignment.TopEnd)
            .padding(horizontal = 48.dp, vertical = 27.dp)
            .graphicsLayer { alpha = if (visible) 1f else 0f }
            .background(Color(0xD10E0E11), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { (key, text) ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(key, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Color(0xFF2A2A31), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                Text(text, fontSize = 12.sp, color = Color(0xFFC9C8CF))
            }
        }
    }
}

/** Shows the inline minithumbnail at once, then swaps in the downloaded thumbnail. */
@Composable
fun rememberThumbnail(item: MediaItem, files: FileRepository): State<ImageBitmap?> =
    produceState(remember(item.chatId, item.messageId) { item.minithumbnail?.let(::decodeBytes) }, item.chatId, item.messageId) {
        value = item.minithumbnail?.let(::decodeBytes)
        val fileId = item.thumbnailFileId ?: return@produceState
        runCatching { files.thumbnail(fileId) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()?.let { value = it }
    }

private fun decodeBytes(data: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()

// --- 08 · Estados ------------------------------------------------------------------------------

/** A · Loading row: grey placeholders that fade along the row and pulse gently. */
@Composable
fun SkeletonRows(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val glow by pulse.animateFloat(0.75f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow")
    Column(
        modifier.fillMaxWidth().background(Color(0xFF141418), RoundedCornerShape(10.dp)).padding(horizontal = 20.dp, vertical = 16.dp)
            .graphicsLayer { alpha = glow },
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(170.dp, 17.dp).background(Color(0xFF24242A), RoundedCornerShape(4.dp)))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(6) { i ->
                Column(Modifier.graphicsLayer { alpha = 1f - i * 0.12f }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(150.dp, 84.5.dp).background(Color(0xFF202026), RoundedCornerShape(6.dp)))
                    Box(Modifier.size(110.dp, 12.dp).background(Color(0xFF202026), RoundedCornerShape(3.dp)))
                }
            }
        }
    }
}

/** B · Empty library and C · Connection error. */
@Composable
fun StateCard(title: String, message: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(title, message) { runCatching { focus.requestFocus() } }
    Column(
        modifier.fillMaxWidth().background(Color(0xFF141418), RoundedCornerShape(10.dp)).padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.rise(title, 0f))
        Text(message, fontSize = 14.sp, color = Cine.TextMuted, modifier = Modifier.rise(title, 1f))
        Spacer(Modifier.height(7.dp))
        CineButton(action, onAction, Modifier.focusRequester(focus).rise(title, 2f))
    }
}
