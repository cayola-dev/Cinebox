package com.ashe.cinemabox.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.OptIn
import androidx.compose.animation.core.*
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import com.ashe.cinemabox.library.*
import com.ashe.cinemabox.player.buildStreamingPlayer
import com.ashe.cinemabox.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** One audio player for the entire authenticated UI, independent of the current route. */
@OptIn(UnstableApi::class)
class MusicSession(private val context: Context, private val files: FileRepository, private val library: LibraryRepository,
                   val identifier: com.ashe.cinemabox.music.MusicIdentifier) {
    var item by mutableStateOf<MediaItem?>(null); private set
    var queue by mutableStateOf<List<MediaItem>>(emptyList()); private set
    var playing by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var position by mutableLongStateOf(0L); private set
    var duration by mutableLongStateOf(0L); private set
    /** Cover embedded in the playing file's ID3 tags; ExoPlayer reads it while streaming, no extra download. */
    var artwork by mutableStateOf<ImageBitmap?>(null); private set
    var history: PlaybackHistory? = null
    private var player: ExoPlayer? = null
    private var resumeAfterVideo = false
    private var suspendedPosition = 0L
    private var counted = false
    private var lastSaved = 0L

    fun start(tracks: List<MediaItem>, selected: MediaItem) {
        stop()
        queue = tracks.filter { it.kind == MediaKind.Audio }.distinctBy { it.chatId to it.messageId }
        item = selected
        artwork = null
        duration = (selected.durationSeconds ?: 0) * 1000L
        createPlayer(selected, 0L)
    }
    private fun createPlayer(track: MediaItem, start: Long, countPlay: Boolean = true) {
        error = null; buffering = true; counted = !countPlay
        library.startViewing(track)
        identifier.onPlay(track)
        // A playing song is downloaded whole (when its channel allows saving), so replays never hit the network.
        player = buildStreamingPlayer(context, files, track.fileId,
            track.durationSeconds?.takeIf { it > 0 }?.let { track.sizeBytes / it }, start, downloadAll = track.canBeSaved).also { active ->
            active.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (player !== active) return
                    playing = isPlaying
                    if (isPlaying && !counted) { history?.played(track); counted = true }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (player !== active) return
                    buffering = state == Player.STATE_BUFFERING
                    if (state == Player.STATE_ENDED) next(1)
                }
                override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                    if (player !== active) return
                    metadata.artworkData?.let(::decodeArtwork)?.let { artwork = it }
                }
                override fun onPlayerError(e: PlaybackException) {
                    if (player === active) { error = "No se pudo reproducir esta pista. Vuelve a intentarlo."; buffering = false }
                }
            })
        }
    }
    fun tick() {
        player?.let {
            position = it.currentPosition
            if (it.duration > 0 && it.duration != C.TIME_UNSET) duration = it.duration
            if (playing && kotlin.math.abs(position - lastSaved) >= 5000) {
                item?.let { track -> history?.save(track, position, duration) }; lastSaved = position
            }
        }
    }
    fun toggle() {
        val active = player
        if (active == null) item?.let { createPlayer(it, suspendedPosition, false) }
        else if (active.isPlaying) active.pause() else {
            if (active.playbackState == Player.STATE_ENDED) active.seekTo(0)
            active.play()
        }
    }
    fun seek(delta: Long) { player?.seekTo((position + delta).coerceIn(0, duration.coerceAtLeast(0))) }
    fun next(direction: Int) {
        val current = item ?: return
        val index = queue.indexOfFirst { it.chatId == current.chatId && it.messageId == current.messageId }
        val target = queue.getOrNull(index + direction)
        if (target != null) { val tracks = queue; start(tracks, target) } else player?.pause()
    }
    fun suspendForVideo() {
        resumeAfterVideo = player?.playWhenReady == true
        suspendedPosition = player?.currentPosition ?: position
        releasePlayer()
    }
    fun resumeFromVideo() {
        if (resumeAfterVideo) item?.let { createPlayer(it, suspendedPosition, false) }
        resumeAfterVideo = false
    }
    private fun releasePlayer() {
        identifier.stop()
        tick()
        item?.let { track ->
            history?.save(track, position, duration)
            library.stopViewing(track)
        }
        val old = player
        player = null
        old?.release()
        if (old != null) item?.let { files.closePlayback(it.fileId) }
        playing = false; buffering = false
    }
    fun stop() {
        releasePlayer(); item = null; queue = emptyList(); position = 0; duration = 0; artwork = null
        resumeAfterVideo = false; error = null; lastSaved = 0
    }
}

@Composable
fun MusicBrowser(tracks: List<MediaItem>, nav: V3Navigation, files: FileRepository, history: PlaybackHistory?, music: MusicSession, onPlayer: () -> Unit,
                 onLoadMore: () -> Unit, hasMore: Boolean) {
    val known by music.identifier.known.collectAsState()
    val tracks = remember(tracks, known) { tracks.map { it.withIdentity(known) } }
    val index = nav.musicIndex.coerceIn(0, tracks.lastIndex.coerceAtLeast(0))
    val selected = tracks.getOrNull(index)
    val selectedCover = selected?.let { rememberTrackCover(it, files, music) }
    Box(Modifier.fillMaxSize()) {
    MusicBackdrop(selected, selectedCover, brightness = .42f, blur = 30.dp)
    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 85.dp, bottom = 25.dp).rise("music-browser", 0f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CineButton(if (nav.favorites) "♥ Favoritos" else "♡ Favoritos", { nav.favorites = true; nav.musicIndex = 0 })
            CineButton("Más escuchadas", { nav.favorites = false; nav.musicIndex = 0 })
            if (hasMore) CineButton("Cargar más música", onLoadMore)
        }
        if (selected == null) {
            Text(if (nav.favorites) "Marca una canción con ♥ para guardarla aquí." else "No hay música en las bibliotecas elegidas.",
                fontSize = 20.sp, color = Cine.TextMuted, modifier = Modifier.padding(top = 100.dp))
        } else {
            // +1 when the list moved down (next track), -1 when it moved up; drives every transition below.
            var previousIndex by remember { mutableIntStateOf(index) }
            var direction by remember { mutableIntStateOf(1) }
            if (index != previousIndex) { direction = if (index > previousIndex) 1 else -1; previousIndex = index }
            Row(Modifier.fillMaxSize().padding(top = 25.dp), verticalAlignment = Alignment.CenterVertically) {
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                Box(Modifier.width(240.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionUp -> if (index > 0) { nav.musicIndex = index - 1; true } else false
                            Key.DirectionDown -> if (index < tracks.lastIndex) { nav.musicIndex = index + 1; true } else false
                            else -> false
                        }
                    }.clickable(interaction, null) { music.start(tracks, selected); onPlayer() }) {
                    for (delta in -3..3) tracks.getOrNull(index + delta)?.let { track ->
                      key(track.chatId, track.messageId) {
                        val radians = Math.toRadians(delta * 12.0)
                        val targetX = (24 + 500 * (1 - kotlin.math.cos(radians))).toFloat()
                        val targetY = (140 + 500 * kotlin.math.sin(radians)).toFloat()
                        val x by animateFloatAsState(targetX, tween(500, easing = Motion.Glide), label = "arcX")
                        val y by animateFloatAsState(targetY, tween(500, easing = Motion.Glide), label = "arcY")
                        // v3 arc: the centre card is larger, the rest fade with distance; new cards fade in at the ends.
                        val fade = remember { Animatable(0f) }
                        LaunchedEffect(delta) { fade.animateTo(1f - kotlin.math.abs(delta) * .25f, tween(450, easing = Motion.Glide)) }
                        val scale by animateFloatAsState(if (delta == 0) 1.06f else 1f - kotlin.math.abs(delta) * .04f, tween(500, easing = Motion.Glide), label = "arcScale")
                        val tilt by animateFloatAsState(delta * 3f, tween(500, easing = Motion.Glide), label = "arcTilt")
                        val cover = rememberTrackCover(track, files, music)
                        Row(Modifier.offset { IntOffset(x.dp.roundToPx(), y.dp.roundToPx()) }.width(205.dp)
                            .graphicsLayer { alpha = fade.value; scaleX = scale; scaleY = scale; rotationZ = tilt; transformOrigin = TransformOrigin(0f, .5f) }
                            .focusRing(focused && delta == 0, RoundedCornerShape(12.dp))
                            .background(if (delta == 0) Color.White.copy(alpha = .1f) else Color.Transparent, RoundedCornerShape(12.dp)).padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Box(Modifier.size(58.dp).clip(RoundedCornerShape(8.dp))) {
                                if (cover != null) Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else AudioCover(track.title, Modifier.fillMaxSize())
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(track.title, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = if (delta == 0) Cine.Text else Cine.TextMuted, fontWeight = FontWeight.SemiBold)
                                if (track.artist.isNotBlank()) Text(track.artist, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Cine.TextMuted)
                            }
                        }
                      }
                    }
                }
                // Text block slides in from the side the list is moving to; the buttons stay put so focus never jumps.
                Column(Modifier.weight(1f).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    androidx.compose.animation.AnimatedContent(selected, label = "trackInfo", transitionSpec = {
                        (androidx.compose.animation.slideInVertically(tween(420, easing = Motion.Glide)) { it / 3 * direction } +
                            androidx.compose.animation.fadeIn(tween(320, 80))) togetherWith
                            (androidx.compose.animation.slideOutVertically(tween(300, easing = Motion.Glide)) { -it / 4 * direction } +
                                androidx.compose.animation.fadeOut(tween(180)))
                    }, contentKey = { it.chatId to it.messageId }) { track ->
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(track.title, fontFamily = Urbanist, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (track.artist.isNotBlank()) Text(track.artist, fontSize = 14.sp, color = Cine.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${track.durationSeconds?.let(::formatDuration) ?: "Audio"} · ${history?.record(track)?.plays ?: 0} reproducciones", color = Cine.TextMuted, fontSize = 12.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CineButton("Reproducir", { music.start(tracks, selected); onPlayer() }) { color -> PlayGlyph(color) }
                        CineButton(if (history?.record(selected)?.favorite == true) "♥" else "♡", { history?.favorite(selected) })
                    }
                }
                // Design: the tab's record always turns slowly (6 s per turn); faster while this track plays.
                val playingThis = music.playing && music.item?.messageId == selected.messageId && music.item?.chatId == selected.chatId
                // mcCoverR / mcCoverL: each new record slides in with a slight turn and grows to full size.
                val swap = remember(selected.chatId, selected.messageId) { Animatable(0f) }
                LaunchedEffect(swap) { swap.animateTo(1f, tween(520, easing = Motion.Glide)) }
                Vinyl(selectedCover, selected.title, true, Modifier.size(260.dp).graphicsLayer {
                    val rest = 1f - swap.value
                    alpha = swap.value
                    translationY = rest * 70.dp.toPx() * direction
                    rotationZ = rest * 5f * direction
                    scaleX = .9f + .1f * swap.value; scaleY = scaleX
                }, if (playingThis) 200f else 60f)
            }
        }
    }
    }
}

/** 06 · Música (v3): turntable deck on the left; title, waveform, progress, controls and queue on the right. */
@Composable
fun MusicPlayerScreen(music: MusicSession, files: FileRepository, history: PlaybackHistory?, source: String) {
    val known by music.identifier.known.collectAsState()
    val analyzingId by music.identifier.analyzing.collectAsState()
    val item = music.item?.withIdentity(known) ?: return
    val album = known[item.uniqueId]?.album
    val analyzing = analyzingId != null && analyzingId == item.uniqueId
    val cover = rememberTrackCover(item, files, music)
    val index = music.queue.indexOfFirst { it.chatId == item.chatId && it.messageId == item.messageId }
    val upcoming = if (index >= 0) music.queue.drop(index + 1).take(3) else emptyList()
    var focusedControl by remember { mutableIntStateOf(2) }
    val main = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { main.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        MusicBackdrop(item, cover, brightness = .5f, blur = 20.dp)
        Turntable(cover, item.title, music.playing, Modifier.padding(start = 60.dp, top = 90.dp).size(350.dp))
        Column(Modifier.fillMaxSize().padding(start = 440.dp, end = 48.dp, top = 54.dp).rise("music-player", 0f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                EqualizerBars(music.playing)
                Text(listOfNotNull("Reproduciendo", source, if (index >= 0) "${index + 1} de ${music.queue.size}" else null).joinToString(" · "),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFC9C9DC), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // 6C: when AcoustID answers, title, artist and album cross-fade to the identified ones (300 ms).
            androidx.compose.animation.Crossfade(Triple(item.title, item.artist, album), animationSpec = tween(300), label = "identity") { (title, artist, albumName) ->
                Column {
                    Text(title, fontFamily = Urbanist, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 38.sp, letterSpacing = (-.5).sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text(if (music.buffering) "Cargando audio…" else listOfNotNull(artist.ifBlank { null }, albumName).joinToString(" · ").ifBlank { "Tu biblioteca de música" },
                        fontSize = 16.sp, color = Color(0xFFC9C9DC), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
            val progress = if (music.duration > 0) (music.position.toFloat() / music.duration).coerceIn(0f, 1f) else 0f
            Box(Modifier.padding(top = 18.dp)) {
                Waveform(progress, music.playing, scanning = analyzing)
                androidx.compose.animation.AnimatedVisibility(analyzing, Modifier.align(Alignment.CenterEnd),
                    enter = androidx.compose.animation.fadeIn(tween(400)), exit = androidx.compose.animation.fadeOut(tween(400))) {
                    AnalyzingLabel()
                }
            }
            music.error?.let { Text(it, fontSize = 13.sp, color = Cine.Error, modifier = Modifier.padding(top = 8.dp)) }
            ProgressTrack(progress, Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatClock(music.position), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFD4D4E4))
                Text(formatClock(music.duration), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFD4D4E4))
            }
            val favorite = history?.record(item)?.favorite == true
            val controls = listOf(
                Triple(MusicIcon.Previous, "Canción anterior") { music.next(-1) },
                Triple(MusicIcon.Rewind, "Retroceder 10 s") { music.seek(-10_000) },
                Triple(if (music.playing) MusicIcon.Pause else MusicIcon.Play, if (music.playing) "Pausa" else "Reproducir") {
                    if (music.error != null) music.start(music.queue, item) else music.toggle()
                },
                Triple(MusicIcon.Forward, "Adelantar 10 s") { music.seek(10_000) },
                Triple(MusicIcon.Next, "Siguiente canción") { music.next(1) },
                Triple(if (favorite) MusicIcon.HeartFull else MusicIcon.Heart, if (favorite) "Quitar de favoritas" else "Agregar a favoritas") { history?.favorite(item); Unit },
            )
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                controls.forEachIndexed { i, (icon, _, action) ->
                    ControlButton(icon, big = i == 2, onFocused = { focusedControl = i }, onClick = action,
                        modifier = if (i == 2) Modifier.focusRequester(main) else Modifier)
                }
            }
            Text(controls.getOrNull(focusedControl)?.second.orEmpty(), fontSize = 12.sp, color = Color(0xFFC9C9DC),
                modifier = Modifier.height(15.dp).padding(top = 0.dp).offset(y = 7.dp))
            if (upcoming.isNotEmpty()) {
                Text("A continuación", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    upcoming.forEach { track -> QueueCard(track.withIdentity(known), files, music) { music.start(music.queue, track) } }
                }
            }
        }
        // ±10 s feedback over the controls, as in the prototype's flashes.
        SeekFlash(music)
        StorageStatusBar(item, files, Modifier.align(Alignment.BottomStart).padding(start = 60.dp, end = 48.dp, bottom = 22.dp).fillMaxWidth())
    }
}

/** Glass deck with the record, tonearm and two corner knobs. */
@Composable
private fun Turntable(cover: ImageBitmap?, seed: String, playing: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.background(Color.White.copy(alpha = .05f), shape).border(1.dp, Color.White.copy(alpha = .1f), shape)) {
        Vinyl(cover, seed, playing, Modifier.offset(15.dp, 40.dp).size(270.dp), degreesPerSecond = 200f)
        val angle by animateFloatAsState(if (playing) 24f else 7f, tween(900, easing = CubicBezierEasing(.3f, 1.2f, .4f, 1f)), label = "tonearm")
        // Arm hangs from its pivot (top centre of the 5 dp bar) and swings onto the record while playing.
        Box(Modifier.offset(318.5.dp, 36.dp).size(5.dp, 215.dp).graphicsLayer {
            rotationZ = angle; transformOrigin = TransformOrigin(.5f, 0f)
        }) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFFB9B9C6), Color(0xFFEFEFF4), Color(0xFFB9B9C6))), CircleShape))
            Box(Modifier.align(Alignment.BottomCenter).offset(y = 20.dp).size(17.dp, 26.dp).background(Color(0xFFD8D8E0), RoundedCornerShape(3.dp)))
        }
        Box(Modifier.offset(306.dp, 21.dp).size(30.dp).background(
            Brush.radialGradient(listOf(Color(0xFFF2F2F7), Color(0xFF9A9AA8)), center = Offset.Unspecified), CircleShape))
        Box(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 13.dp).size(28.dp).border(1.dp, Color.White.copy(alpha = .18f), CircleShape))
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 13.dp).size(28.dp).border(1.dp, Color.White.copy(alpha = .18f), CircleShape))
    }
}

private enum class MusicIcon(val path: String, val filled: Boolean = true) {
    Previous("M5 5h2.6v14H5z M19 5v14L9 12z"),
    Next("M16.4 5H19v14h-2.6z M5 5v14l10-7z"),
    Rewind("M11.5 6v12L3 12z M21 6v12l-8.5-6z"),
    Forward("M3 6v12l8.5-6z M12.5 6v12L21 12z"),
    Play("M7 4l13 8-13 8z"),
    Pause("M6 5h4v14H6z M14 5h4v14h-4z"),
    Heart("M12 20.5s-7.5-4.6-9.6-9.2A5.2 5.2 0 0 1 12 6.4a5.2 5.2 0 0 1 9.6 4.9c-2.1 4.6-9.6 9.2-9.6 9.2z", filled = false),
    HeartFull("M12 20.5s-7.5-4.6-9.6-9.2A5.2 5.2 0 0 1 12 6.4a5.2 5.2 0 0 1 9.6 4.9c-2.1 4.6-9.6 9.2-9.6 9.2z");

    val parsed by lazy { androidx.compose.ui.graphics.vector.PathParser().parsePathString(path).toPath() }
}

/** Draws a 24×24 design icon scaled to [size]. */
@Composable
private fun MusicGlyph(icon: MusicIcon, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val scale = this.size.width / 24f
        withTransform({ scale(scale, scale, Offset.Zero) }) {
            if (icon.filled) drawPath(icon.parsed, color)
            else drawPath(icon.parsed, color, style = Stroke(2f, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun ControlButton(icon: MusicIcon, big: Boolean, onFocused: () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val background = when {
        focused -> Cine.Text
        big -> Color.White.copy(alpha = .16f)
        else -> Color.White.copy(alpha = .06f)
    }
    Box(modifier.size(if (big) 48.dp else 36.dp).focusRing(focused, CircleShape).background(background, CircleShape)
        .then(if (focused) Modifier else Modifier.border(1.dp, Color.White.copy(alpha = .12f), CircleShape))
        .clickable(interaction, null, onClick = onClick), contentAlignment = Alignment.Center) {
        MusicGlyph(icon, if (focused) Color(0xFF14141F) else Cine.Text, if (big) 18.dp else 13.dp)
    }
}

@Composable
private fun QueueCard(track: MediaItem, files: FileRepository, music: MusicSession, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    val cover = rememberTrackCover(track, files, music)
    Row(Modifier.size(150.dp, 52.dp).focusRing(focused, shape).background(if (focused) Cine.Text else Color.White.copy(alpha = .07f), shape)
        .border(1.dp, Color.White.copy(alpha = .1f), shape).clickable(interaction, null, onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2C2C44))) {
            cover?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: AudioCover(track.title, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (focused) Color(0xFF14141F) else Cine.Text)
            Text(listOfNotNull(track.artist.ifBlank { null }, track.durationSeconds?.let { formatClock(it * 1000L) }).joinToString(" · "),
                fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (focused) Color(0xFF3B3B58) else Cine.TextMuted)
        }
    }
}

@Composable
private fun ProgressTrack(progress: Float, modifier: Modifier) {
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val y = size.height / 2
        val stroke = 3.dp.toPx()
        drawLine(Color.White.copy(alpha = .2f), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
        drawLine(Cine.Accent, Offset(0f, y), Offset(size.width * progress, y), stroke, StrokeCap.Round)
        drawCircle(Cine.Text, 5.5.dp.toPx(), Offset(size.width * progress, y))
    }
}

/** Seconds since this composable started, advanced only while [running]; read in the draw phase. */
@Composable
private fun rememberClock(running: Boolean): MutableFloatState {
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (running) {
            var previous = withFrameNanos { it }
            while (true) withFrameNanos { now -> seconds.floatValue += (now - previous) / 1_000_000_000f; previous = now }
        }
    }
    return seconds
}

/** The prototype's mcEq pulse: 0.25 → 1 → 0.25 over [period] seconds, shifted by [delay]. */
private fun pulse(time: Float, period: Float, delay: Float): Float {
    val phase = ((time - delay) / period).mod(1f)
    return .25f + .75f * (.5f - .5f * kotlin.math.cos(phase * 2 * Math.PI.toFloat()))
}

/** 36 bars like the design; played bars are white. Decorative: it does not analyse the audio. */
@Composable
private fun Waveform(progress: Float, playing: Boolean, modifier: Modifier = Modifier, scanning: Boolean = false) {
    val clock = rememberClock(playing || scanning)
    // A soft accent band sweeps the bars while the song is being identified, and fades away after.
    val scan by animateFloatAsState(if (scanning) 1f else 0f, tween(500), label = "scan")
    Canvas(modifier.width(323.dp).height(35.dp)) {
        val bar = 2.5.dp.toPx()
        val step = bar + 3.dp.toPx()
        for (i in 0 until 36) {
            val full = (10 + ((i * 37) % 50) / 2f).dp.toPx()
            val height = full * pulse(clock.floatValue, .7f + (i % 5) * .13f, (i % 7) * .07f)
            val x = i * step + bar / 2
            val color = if (i / 36f < progress) Cine.Text else Cine.Text.copy(alpha = .28f)
            drawLine(color, Offset(x, size.height / 2 - height / 2), Offset(x, size.height / 2 + height / 2), bar, StrokeCap.Round)
        }
        if (scan > 0f) {
            val center = ((clock.floatValue / 1.8f) % 1f) * (size.width + 120.dp.toPx()) - 60.dp.toPx()
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Cine.Accent.copy(alpha = .45f * scan), Color.Transparent),
                startX = center - 60.dp.toPx(), endX = center + 60.dp.toPx()))
        }
    }
}

/** "Analizando…" with a gentle pulse; shown next to the waveform while AcoustID is consulted. */
@Composable
private fun AnalyzingLabel() {
    val pulse = rememberInfiniteTransition(label = "analyzing")
    val alpha by pulse.animateFloat(.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "analyzingAlpha")
    Text("Analizando…", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Cine.Accent,
        modifier = Modifier.offset(x = 92.dp).graphicsLayer { this.alpha = alpha })
}

@Composable
private fun EqualizerBars(playing: Boolean) {
    val clock = rememberClock(playing)
    Canvas(Modifier.size(14.dp, 13.dp)) {
        val bar = 2.5.dp.toPx()
        listOf(.9f, .7f, 1.1f, .8f).forEachIndexed { i, period ->
            val height = size.height * pulse(clock.floatValue, period, i * .12f)
            val x = i * (bar + 2.dp.toPx())
            drawRect(Cine.Accent, Offset(x, size.height - height), Size(bar, height))
        }
    }
}

/** Shows "+10 s" / "−10 s" briefly when the position jumps by a seek. */
@Composable
private fun BoxScope.SeekFlash(music: MusicSession) {
    var flash by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var last by remember { mutableLongStateOf(music.position) }
    LaunchedEffect(music.position) {
        val jump = music.position - last
        last = music.position
        if (kotlin.math.abs(jump) in 8_000..12_000) flash = ((flash?.first ?: 0) + 1) to (if (jump > 0) "+10 s" else "−10 s")
    }
    flash?.let { (id, text) ->
        key(id) {
            val p = remember { Animatable(0f) }
            LaunchedEffect(Unit) { p.animateTo(1f, tween(800, easing = LinearEasing)); flash = null }
            Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.TopStart)
                .offset(x = if (text.startsWith("+")) 620.dp else 520.dp, y = 330.dp)
                .graphicsLayer {
                    alpha = if (p.value < .2f) p.value / .2f else 1f - (p.value - .2f) / .8f
                    translationY = -24.dp.toPx() * p.value
                })
        }
    }
}

/**
 * Header pill of the v3 design: spinning cover, title and artist, then play/pause and stop.
 * Takes the width it is given and ellipsizes the text instead of clipping the controls.
 */
@Composable
fun MiniPlayer(music: MusicSession, files: FileRepository, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val known by music.identifier.known.collectAsState()
    val item = music.item?.withIdentity(known) ?: return
    val cover = rememberTrackCover(item, files, music)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fg = if (focused) Cine.OnLight else Cine.Text
    Row(modifier.height(32.dp).background(Color(0x80181826), CircleShape).border(1.dp, Color.White.copy(alpha = .12f), CircleShape)
        .padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.weight(1f, fill = false).height(26.dp).focusRing(focused, CircleShape)
            .background(if (focused) Cine.Text else Color.Transparent, CircleShape)
            .clickable(interaction, null, onClick = onOpen).padding(end = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            val spin = rememberSpin(music.playing, 90f)
            Box(Modifier.size(26.dp).graphicsLayer { rotationZ = spin.floatValue }.border(1.5.dp, Color(0xFF0C0C11), CircleShape).clip(CircleShape)) {
                if (cover != null) Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else AudioCover(item.title, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text(item.title, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (music.buffering) "Cargando…" else item.artist.ifBlank { "Música" },
                    fontSize = 11.sp, lineHeight = 13.sp, color = fg.copy(alpha = .7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        MiniRoundButton(music::toggle) { color -> if (music.playing) PauseGlyph(color) else PlayGlyph(color, 11.dp) }
        MiniRoundButton(music::stop) { color -> StopGlyph(color) }
    }
}

@Composable
private fun MiniRoundButton(onClick: () -> Unit, content: @Composable (Color) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(Modifier.size(26.dp).focusRing(focused, CircleShape).background(if (focused) Cine.Text else Color.Transparent, CircleShape)
        .clickable(interaction, null, onClick = onClick), contentAlignment = Alignment.Center) {
        content(if (focused) Cine.OnLight else Cine.Text)
    }
}

@Composable
private fun PauseGlyph(color: Color) {
    Canvas(Modifier.size(11.dp)) {
        val bar = size.width * .3f
        drawRoundRect(color, Offset(size.width * .12f, 0f), Size(bar, size.height), CornerRadius(1.dp.toPx()))
        drawRoundRect(color, Offset(size.width * .58f, 0f), Size(bar, size.height), CornerRadius(1.dp.toPx()))
    }
}

@Composable
private fun StopGlyph(color: Color) {
    Canvas(Modifier.size(9.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(1.5.dp.toPx())) }
}

/**
 * Full-screen cover of the track, blurred and darkened like the v3 Música backdrop.
 * Without RenderEffect (before Android 12) the cover is shown faint instead of blurred.
 */
@Composable
private fun MusicBackdrop(item: MediaItem?, cover: ImageBitmap?, brightness: Float, blur: Dp) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val effect = remember(blur, density) {
        if (android.os.Build.VERSION.SDK_INT >= 31) with(density) { blur.toPx() }.let {
            android.graphics.RenderEffect.createBlurEffect(it, it, android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect()
        } else null
    }
    val filter = remember(brightness) {
        ColorFilter.colorMatrix(ColorMatrix().apply {
            setToSaturation(1.3f)
            timesAssign(ColorMatrix().apply { setToScale(brightness, brightness, brightness, 1f) })
        })
    }
    Box(Modifier.fillMaxSize().background(Cine.Background)) {
        androidx.compose.animation.Crossfade(item?.let { it.chatId to it.messageId } to cover, animationSpec = tween(700), label = "musicBackdrop") { (_, image) ->
            val layer = Modifier.fillMaxSize().graphicsLayer {
                scaleX = 1.2f; scaleY = 1.2f
                renderEffect = effect
                alpha = if (effect != null) 1f else .35f
            }
            if (image != null) Image(image, null, layer, contentScale = ContentScale.Crop, colorFilter = filter)
            else if (item != null) AudioCover(item.title, layer.graphicsLayer { alpha = .6f })
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Cine.Background.copy(alpha = .55f), .3f to Color.Transparent,
            .75f to Color.Transparent, 1f to Cine.Background.copy(alpha = .8f))))
    }
}

/** Cover priority: AcoustID's album art, then the art inside the playing file, then Telegram's thumbnail. */
@Composable
private fun rememberTrackCover(item: MediaItem, files: FileRepository, music: MusicSession): ImageBitmap? {
    val thumbnail by rememberThumbnail(item, files)
    val known by music.identifier.known.collectAsState()
    val coverPath = known[item.uniqueId]?.coverPath
    val identified by produceState<ImageBitmap?>(null, coverPath) {
        value = coverPath?.let { path ->
            withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
        }
    }
    val playing = music.item?.let { it.chatId == item.chatId && it.messageId == item.messageId } == true
    return identified ?: music.artwork?.takeIf { playing } ?: thumbnail
}

/** Applies what AcoustID identified (title, artist) over the file's and the message's data. */
fun MediaItem.withIdentity(known: Map<String, com.ashe.cinemabox.music.MusicMetadata>): MediaItem =
    known[uniqueId]?.let { copy(title = it.title ?: title, artist = it.artist ?: artist) } ?: this

/** Rotation read in the draw phase, so turning never recomposes the screen. */
@Composable
private fun rememberSpin(playing: Boolean, degreesPerSecond: Float): MutableFloatState {
    val degrees = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, degreesPerSecond) {
        if (playing) {
            var previous = withFrameNanos { it }
            while (true) withFrameNanos { now ->
                degrees.floatValue = (degrees.floatValue + (now - previous) / 1_000_000_000f * degreesPerSecond) % 360f; previous = now
            }
        }
    }
    return degrees
}

private fun decodeArtwork(data: ByteArray): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= ARTWORK_MAX_PX && bounds.outHeight / (sample * 2) >= ARTWORK_MAX_PX) sample *= 2
    BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

private const val ARTWORK_MAX_PX = 512

@Composable
private fun Vinyl(cover: ImageBitmap?, seed: String, playing: Boolean, modifier: Modifier, degreesPerSecond: Float = 200f) {
    val degrees = rememberSpin(playing, degreesPerSecond)
    Box(modifier.graphicsLayer { rotationZ = degrees.floatValue }.clip(CircleShape).background(Color(0xFF101016)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            for (ring in 1..28) drawCircle(Color.White.copy(alpha = .055f), size.minDimension * (.2f + ring * .01f), style = Stroke(1f))
            drawLine(Color.White.copy(alpha = .08f), Offset.Zero, Offset(size.width, size.height), size.width * .14f)
        }
        Box(Modifier.fillMaxSize(.36f).clip(CircleShape).background(Cine.Accent), contentAlignment = Alignment.Center) {
            cover?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: AudioCover(seed, Modifier.fillMaxSize())
            Box(Modifier.size(7.dp).background(Color(0xFFD8D8E0), CircleShape))
        }
    }
}

private enum class StorageState { Checking, Downloading, Partial, Saved, StreamOnly }

private enum class StatusIcon(val path: String) {
    Download("M12 4v11 M7.5 10.5 12 15l4.5-4.5 M5 19.5h14"),
    Saved("M4 13.5h16v5.5H4z M7.5 16.25h.01 M6 13.5l2-8h8l2 8 M9.5 9.5l2 2 3.5-4"),
    Cloud("M7.5 18.5h9.5a4 4 0 0 0 .6-7.95 5.8 5.8 0 0 0-11.2 1.2A3.4 3.4 0 0 0 7.5 18.5z");

    val parsed by lazy { androidx.compose.ui.graphics.vector.PathParser().parsePathString(path).toPath() }
}

/**
 * Footer of the music player: whether the song is kept on this TV or still coming from the internet.
 * A travelling wave plays while TDLib downloads; a short pop marks the moment it is saved.
 */
@Composable
private fun StorageStatusBar(item: MediaItem, files: FileRepository, modifier: Modifier) {
    val progress by produceState<FileProgress?>(null, item.fileId) {
        try { files.watch(item.fileId).collect { value = it } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* No status is shown if TDLib cannot report the file. */ }
    }
    val p = progress
    val state = when {
        p == null -> StorageState.Checking
        p.completed -> StorageState.Saved
        !item.canBeSaved -> StorageState.StreamOnly
        p.active -> StorageState.Downloading
        else -> StorageState.Partial
    }
    if (state == StorageState.Checking) return
    val fraction = if (p != null && p.size > 0) (p.downloadedSize.toFloat() / p.size).coerceIn(0f, 1f) else 0f
    val shownFraction by animateFloatAsState(fraction, tween(600, easing = Motion.Glide), label = "storedFraction")
    val shape = RoundedCornerShape(50)
    Row(modifier.height(34.dp).background(Color.White.copy(alpha = .06f), shape).border(1.dp, Color.White.copy(alpha = .1f), shape)
        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.animation.Crossfade(state, animationSpec = tween(400), label = "storageIcon") { shown ->
            val pop = remember { Animatable(if (shown == StorageState.Saved) .6f else 1f) }
            LaunchedEffect(shown) { if (shown == StorageState.Saved) pop.animateTo(1f, spring(.45f, 380f)) }
            Box(Modifier.size(18.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }, contentAlignment = Alignment.Center) {
                val (icon, tint) = when (shown) {
                    StorageState.Saved -> StatusIcon.Saved to Cine.Accent
                    StorageState.StreamOnly -> StatusIcon.Cloud to Cine.TextSoft
                    else -> StatusIcon.Download to Cine.Text
                }
                Canvas(Modifier.fillMaxSize()) {
                    val scale = size.width / 24f
                    withTransform({ scale(scale, scale, Offset.Zero) }) {
                        drawPath(icon.parsed, tint, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
        }
        Text(when (state) {
            StorageState.Saved -> "Guardada en este dispositivo · se reproduce sin internet"
            StorageState.StreamOnly -> "Solo en línea · este canal protege su contenido"
            StorageState.Downloading -> "Descargando para escucharla sin conexión · ${(fraction * 100).toInt()} %"
            else -> "${(fraction * 100).toInt()} % guardado · se completa al reproducirla"
        }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (state == StorageState.Saved) Cine.Text else Color(0xFFC9C9DC),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        if (state == StorageState.Downloading || state == StorageState.Partial) {
            ActivityWave(state == StorageState.Downloading, shownFraction, Modifier.width(220.dp).height(18.dp))
        } else if (state == StorageState.Saved) {
            Text(formatSize(item.sizeBytes), fontSize = 12.sp, color = Cine.TextMuted)
        }
    }
}

/** Bars that ripple left to right while [running]; the part already stored is drawn brighter. */
@Composable
private fun ActivityWave(running: Boolean, stored: Float, modifier: Modifier) {
    val clock = rememberClock(running)
    Canvas(modifier) {
        val bars = 40
        val bar = 2.dp.toPx()
        val step = size.width / bars
        val t = clock.floatValue
        for (i in 0 until bars) {
            // A crest travels across the bars once every 1.6 s.
            val crest = .5f + .5f * kotlin.math.sin((i / bars.toFloat() * 2f - t / 1.6f * 2f) * Math.PI.toFloat() * 2f)
            val height = size.height * (if (running) .2f + .8f * crest * crest else .25f)
            val x = i * step + step / 2
            val color = if (i / bars.toFloat() < stored) Cine.Accent else Cine.Text.copy(alpha = .3f)
            drawLine(color, Offset(x, size.height / 2 - height / 2), Offset(x, size.height / 2 + height / 2), bar, StrokeCap.Round)
        }
    }
}
