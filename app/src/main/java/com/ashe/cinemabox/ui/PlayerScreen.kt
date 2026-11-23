package com.ashe.cinemabox.ui

import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import com.ashe.cinemabox.ui.theme.Motion
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import com.ashe.cinemabox.library.FileProgress
import com.ashe.cinemabox.library.FileRepository
import com.ashe.cinemabox.library.VideoSource
import com.ashe.cinemabox.library.formatSize
import com.ashe.cinemabox.player.SpeedMeter
import com.ashe.cinemabox.player.buildStreamingPlayer
import com.ashe.cinemabox.library.MediaItem
import com.ashe.cinemabox.library.MediaKind
import com.ashe.cinemabox.ui.theme.Cine
import com.ashe.cinemabox.ui.theme.CineButton
import com.ashe.cinemabox.ui.theme.focusRing
import kotlinx.coroutines.delay

private const val SEEK_MS = 10_000L
private const val HIDE_CONTROLS_MS = 4_000L
private const val SLOW_WARNING_AFTER_MS = 5_000L

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(item: MediaItem, label: String, files: FileRepository, onClose: () -> Unit,
    startPositionMs: Long = 0L, onProgress: (Long, Long) -> Unit = { _, _ -> },
    onPrevious: (() -> Unit)? = null, onNext: (() -> Unit)? = null) {
    val context = LocalContext.current
    val source = remember(item.chatId, item.messageId) {
        if (item.kind == MediaKind.Video) files.chooseSource(item) else VideoSource(item.fileId, item.sizeBytes, 0, 0, null)
    }
    val bitrate = remember(source) {
        val seconds = item.durationSeconds?.takeIf { it > 0 }
        if (seconds != null && source.sizeBytes > 0) source.sizeBytes / seconds else null
    }
    val player = remember(source.fileId) { buildStreamingPlayer(context, files, source.fileId, bitrate, startPositionMs) }
    val saveProgress by rememberUpdatedState(onProgress)
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf((item.durationSeconds ?: 0) * 1000L) }
    var downloaded by remember { mutableStateOf<FileProgress?>(null) }
    var speed by remember { mutableStateOf<Double?>(null) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var debug by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInput by remember { mutableLongStateOf(0L) }
    val playFocus = remember { FocusRequester() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) controlsVisible = true
            }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
            }
            override fun onPlayerError(e: PlaybackException) { error = playbackMessage(e) }
        }
        player.addListener(listener)
        onDispose {
            saveProgress(player.currentPosition, player.duration.takeIf { it > 0 } ?: durationMs)
            player.removeListener(listener)
            player.release()
            files.closePlayback(source.fileId)
        }
    }
    LaunchedEffect(player) {
        var ticks = 0
        while (true) {
            positionMs = player.currentPosition
            bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
            player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { durationMs = it }
            if (++ticks % 10 == 0) saveProgress(positionMs, durationMs)
            delay(500)
        }
    }
    LaunchedEffect(source.fileId) {
        val meter = SpeedMeter()
        files.progress(source.fileId).collect {
            downloaded = it
            meter.add(it)
            meter.bytesPerSecond?.let { measured ->
                speed = measured
                files.lastMeasuredSpeed = measured
            }
        }
    }
    LaunchedEffect(isPlaying, lastInput) {
        if (isPlaying) {
            delay(HIDE_CONTROLS_MS)
            controlsVisible = false
        } else controlsVisible = true
    }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    val view = LocalView.current
    DisposableEffect(isPlaying) {
        view.keepScreenOn = isPlaying
        onDispose { view.keepScreenOn = false }
    }

    var flashes by remember { mutableStateOf(emptyList<SeekFlash>()) }
    var flashCounter by remember { mutableIntStateOf(0) }
    fun seekBy(delta: Long) {
        val target = (player.currentPosition + delta).coerceIn(0, maxOf(durationMs, 0))
        player.seekTo(target)
        positionMs = target
        flashes = flashes + SeekFlash(++flashCounter, if (delta > 0) "+10 s" else "−10 s", if (delta > 0) 1 else -1)
    }
    fun toggle() = if (player.isPlaying) player.pause() else {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    error?.let { message ->
        PlaybackError(message, onClose)
        return
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            lastInput = System.nanoTime()
            when (event.key) {
                Key.MediaPlayPause -> { toggle(); true }
                Key.MediaPlay -> { player.play(); true }
                Key.MediaPause -> { player.pause(); true }
                Key.MediaFastForward -> { seekBy(SEEK_MS); true }
                Key.MediaRewind -> { seekBy(-SEEK_MS); true }
                Key.Menu, Key.Info -> { debug = !debug; true }
                Key.Back, Key.Escape -> false
                else -> if (!controlsVisible) {
                    controlsVisible = true
                    true // First press only reveals the controls.
                } else false
            }
        },
    ) {
        if (item.kind == MediaKind.Video) {
            AndroidView(
                factory = { SurfaceView(it).also(player::setVideoSurfaceView) },
                modifier = Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true),
            )
        } else {
            AudioArtwork(item, files)
        }
        val slowNow = speed != null && bitrate != null && downloaded?.active == true && speed!! < bitrate
        var slow by remember { mutableStateOf(false) }
        // The first samples of a fresh download are always low; only warn about sustained slowness.
        LaunchedEffect(slowNow) {
            if (slowNow) delay(SLOW_WARNING_AFTER_MS)
            slow = slowNow
        }
        if (slow) {
            Text("Tu conexión va más lenta que el video; puede pausarse", fontSize = 13.sp, color = Cine.Text,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 27.dp)
                    .background(Color(0xD9251B0C), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        if (debug) {
            DebugPanel(files, source, item, bitrate, speed, bufferedMs, positionMs, durationMs,
                Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 27.dp))
        }
        if (buffering) {
            Text("Cargando…", fontSize = 16.sp, color = Cine.TextSoft, modifier = Modifier.align(Alignment.Center)
                .background(Color(0xBF0E0E11), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        val controlsAlpha by animateFloatAsState(if (controlsVisible) 1f else 0f, tween(500), label = "controls")
        val controlsLift by animateFloatAsState(if (controlsVisible) 0f else 1f, tween(600, easing = Motion.Glide), label = "lift")
        val liftPx = with(LocalDensity.current) { 20.dp.toPx() }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .graphicsLayer { alpha = controlsAlpha; translationY = controlsLift * liftPx }
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color(0xBF0A0A0C), 1f to Color(0xF20A0A0C)))
                .padding(start = 48.dp, end = 48.dp, bottom = 27.dp, top = 80.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Cine.TextSoft)
                Text(item.title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.25).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ProgressBar(positionMs, durationMs, player.bufferedPosition)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatClock(positionMs), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
                    Legend(Cine.Accent, "Reproducido")
                    Legend(Color(0xFF9C9BA3), "En búfer")
                    Legend(Color(0xFF3A3A42), "Pendiente")
                }
                Text(formatClock(durationMs), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                RoundControl(42, controlsVisible && onPrevious != null, { onPrevious?.invoke() }) { Text("|‹", color = Cine.Text) }
                RoundControl(52, controlsVisible, { seekBy(-SEEK_MS) }) { SeekLabel("−10") }
                RoundControl(52, controlsVisible, ::toggle, Modifier.focusRequester(playFocus)) { focused ->
                    PlayPauseIcon(isPlaying, if (focused) Cine.OnLight else Cine.Text)
                }
                RoundControl(52, controlsVisible, { seekBy(SEEK_MS) }) { SeekLabel("+10") }
                RoundControl(42, controlsVisible && onNext != null, { onNext?.invoke() }) { Text("›|", color = Cine.Text) }
            }
        }
        flashes.forEach { flash ->
            key(flash.key) {
                SeekFlashText(flash, Modifier.align(Alignment.BottomCenter).padding(bottom = 115.dp)) {
                    flashes = flashes.filterNot { it.key == flash.key }
                }
            }
        }
        HintsBar(
            listOf("◀ ▶" to "Elegir", "OK" to if (isPlaying) "Pausa" else "Reproducir", "Atrás" to "Salir"),
            atBottom = false, visible = controlsVisible && !debug,
        )
    }
}

private class SeekFlash(val key: Int, val text: String, val side: Int)

/** The prototype's "+10 s" pop: grows in, then floats up and fades over 800 ms. */
@Composable
private fun SeekFlashText(flash: SeekFlash, modifier: Modifier, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(800, easing = Motion.Rise))
        onDone()
    }
    val density = LocalDensity.current
    Text(
        flash.text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Cine.Accent,
        modifier = modifier.graphicsLayer {
            val p = progress.value
            val inPhase = (p / 0.2f).coerceAtMost(1f)
            alpha = if (p < 0.2f) inPhase else 1f - (p - 0.2f) / 0.8f
            val s = if (p < 0.2f) 0.8f + 0.26f * inPhase else 1.06f - 0.06f * ((p - 0.2f) / 0.8f)
            scaleX = s
            scaleY = s
            translationX = flash.side * with(density) { 100.dp.toPx() }
            translationY = with(density) { if (p < 0.2f) (6.dp * (1 - inPhase)).toPx() else (-24.dp * ((p - 0.2f) / 0.8f)).toPx() }
        },
    )
}

@Composable
private fun AudioArtwork(item: MediaItem, repository: FileRepository) {
    val cover by produceState(item.minithumbnail?.let { decodeArtwork(it) }, item.thumbnailFileId) {
        item.thumbnailFileId?.let { id -> runCatching { repository.thumbnail(id) }.getOrNull()?.let { value = it } }
    }
    Box(Modifier.fillMaxSize().background(Cine.Background)) {
        Box(Modifier.align(Alignment.Center).offset(y = (-60).dp).size(220.dp).clip(RoundedCornerShape(14.dp)).background(Cine.Raised),
            contentAlignment = Alignment.Center) {
            cover?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: AudioCover(item.title, Modifier.fillMaxSize())
        }
    }
}

private fun decodeArtwork(data: ByteArray) =
    android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()

@Composable
private fun ProgressBar(positionMs: Long, durationMs: Long, bufferedPosition: Long) {
    val played = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0xFF3A3A42), RoundedCornerShape(3.dp)))
        if (durationMs > 0) {
            val start = played
            val end = (bufferedPosition.toFloat() / durationMs).coerceIn(start, 1f)
            Row(Modifier.fillMaxWidth()) {
                if (start > 0f) Spacer(Modifier.weight(start))
                if (end > start) Box(Modifier.weight(end - start).height(5.dp).background(Color(0xFF9C9BA3), RoundedCornerShape(3.dp)))
                if (end < 1f) Spacer(Modifier.weight(1f - end))
            }
        }
        Box(Modifier.fillMaxWidth(played.coerceAtLeast(0.001f)).height(5.dp).background(Cine.Accent, RoundedCornerShape(3.dp)))
        Row(Modifier.fillMaxWidth()) {
            if (played > 0f) Spacer(Modifier.weight(played))
            Box(Modifier.offset(x = (-7).dp).size(14.dp).background(Cine.Text, CircleShape))
            if (played < 1f) Spacer(Modifier.weight(1f - played))
        }
    }
}

@Composable
private fun DebugPanel(
    files: FileRepository,
    source: VideoSource,
    item: MediaItem,
    bitrate: Long?,
    speed: Double?,
    bufferedMs: Long,
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier,
) {
    var aheadBytes by remember { mutableStateOf<Long?>(null) }
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    val currentPosition by rememberUpdatedState(positionMs)
    val currentDuration by rememberUpdatedState(durationMs)
    LaunchedEffect(source.fileId) {
        var tick = 0
        while (true) {
            if (currentDuration > 0 && source.sizeBytes > 0) {
                val bytePosition = (source.sizeBytes.toDouble() * currentPosition / currentDuration).toLong()
                aheadBytes = runCatching { files.downloadedAhead(source.fileId, bytePosition) }.getOrNull()
            }
            if (tick++ % 5 == 0) cacheBytes = runCatching { files.cacheSize() }.getOrNull()
            delay(1000)
        }
    }
    fun mbps(bytesPerSecond: Double?) = bytesPerSecond?.let { "%.2f Mbps".format(it * 8 / 1_000_000) } ?: "—"
    val quality = if (source.fileId == item.fileId) "original" else "alternativa ${source.width}×${source.height} ${source.codec}"
    Column(
        modifier.background(Color(0xD90A0A0C), RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(
            "Calidad" to quality,
            "Bitrate del video" to mbps(bitrate?.toDouble()),
            "Velocidad de red" to mbps(speed),
            "Buffer del reproductor" to "%.1f s".format(bufferedMs / 1000.0),
            "Descargado por delante" to (aheadBytes?.let { "%.1f MB".format(it / 1_048_576.0) } ?: "—"),
            "Caché" to (cacheBytes?.let(::formatSize) ?: "—"),
        ).forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(label, fontSize = 12.sp, color = Cine.TextMuted, modifier = Modifier.width(150.dp))
                Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Cine.TextMuted)
    }
}

@Composable
private fun RoundControl(
    size: Int,
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier.size(size.dp).focusRing(focused && visible, CircleShape)
            .background(if (focused) Cine.Text else Color(0xE625252B), CircleShape)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides if (focused) Cine.OnLight else Cine.Text) {
            content(focused)
        }
    }
}

@Composable
private fun SeekLabel(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 15.sp,
            color = androidx.tv.material3.LocalContentColor.current)
        Text("s", fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 12.sp,
            color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.8f))
    }
}

@Composable
private fun PlayPauseIcon(playing: Boolean, color: Color) {
    if (playing) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(2) { Box(Modifier.size(6.dp, 20.dp).background(color, RoundedCornerShape(2.dp))) }
        }
    } else {
        Text("▶", fontSize = 22.sp, color = color, modifier = Modifier.offset(x = 2.dp))
    }
}

@Composable
private fun PlaybackError(message: String, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Cine.Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("No se pudo reproducir", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(message, fontSize = 14.sp, color = Cine.TextMuted)
            Spacer(Modifier.height(7.dp))
            CineButton("Volver", onClose, Modifier.focusRequester(focus))
        }
    }
}

private fun playbackMessage(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
        "Este televisor no puede decodificar el formato de este archivo."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
        "El formato del archivo no es compatible."
    else -> "Revisa la conexión e inténtalo de nuevo."
}

fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
