package com.ashe.cinemabox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.ashe.cinemabox.library.*
import com.ashe.cinemabox.ui.theme.Cine
import com.ashe.cinemabox.ui.theme.CineButton
import com.ashe.cinemabox.ui.theme.focusRing
import kotlinx.coroutines.CancellationException

/**
 * 04 · Detalle: drawn over the shared stage backdrop, so the cover carries over from Home.
 * The post (channel, full caption, album) loads on open; [onOpenAlbum] opens one of its other items.
 */
@Composable
fun DetailScreen(
    item: MediaItem,
    label: String,
    library: LibraryRepository,
    files: FileRepository,
    resumeMs: Long = 0L,
    onStart: () -> Unit = {},
    onOpenAlbum: (List<MediaItem>, Int) -> Unit = { _, _ -> },
    onPlay: () -> Unit,
) {
    val play = remember { FocusRequester() }
    LaunchedEffect(item.messageId) { runCatching { play.requestFocus() } }
    val key = "detail-${item.chatId}-${item.messageId}"
    // null while loading; the plain item is shown if the post cannot be read.
    val post by produceState<PostDetails?>(null, item.chatId, item.messageId) {
        value = try { library.post(item) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { PostDetails(null, null, item.tags, item.description, 0, emptyList()) }
    }
    val details = post
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 32.dp)) {
            if (details != null) {
                val shown = item.copy(
                    title = details.title ?: item.title,
                    tags = details.tags.ifEmpty { item.tags },
                )
                InfoPanel(
                    shown, label, big = true,
                    extraMeta = listOfNotNull(details.viewCount.takeIf { it > 0 }?.let { "${formatCount(it)} vistas" }),
                    source = { SourceLine(details.channel, label, files) },
                )
                if (details.description.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(details.description, fontSize = 14.sp, color = Color(0xFFC9C8CF), lineHeight = 20.sp,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(560.dp).weight(1f, fill = false).rise(key, 6f))
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.rise(key, 7f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CineButton(if (resumeMs > 0) "Reanudar · ${formatClock(resumeMs)}" else "Reproducir", onPlay, Modifier.focusRequester(play)) { color -> PlayGlyph(color) }
                if (resumeMs > 0) CineButton("Desde el inicio", onStart)
            }
            val album = details?.album.orEmpty()
            if (album.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                AlbumStrip(album, item, files, Modifier.rise(key, 8f), onOpenAlbum)
            }
        }
        HintsBar(listOf("OK" to "Reproducir", "Atrás" to "Volver"), atBottom = false)
    }
}

/** Channel avatar, name and subscribers; Saved Messages and unknown chats fall back to [label]. */
@Composable
private fun SourceLine(channel: Channel?, label: String, files: FileRepository) {
    if (channel == null) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Cine.TextSoft, maxLines = 1)
        return
    }
    val image by channelImage(channel, files)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Cine.Raised), contentAlignment = Alignment.Center) {
            image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Text(channel.title.take(1).uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Cine.TextSoft)
        }
        Column {
            Text(channel.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 480.dp))
            if (channel.memberCount > 0) {
                Text("${formatCount(channel.memberCount)} suscriptores", fontSize = 11.sp, color = Cine.TextMuted)
            }
        }
    }
}

/** "En esta publicación": every photo and video sent together with the current one. */
@Composable
private fun AlbumStrip(
    album: List<MediaItem>,
    current: MediaItem,
    files: FileRepository,
    modifier: Modifier,
    onOpen: (List<MediaItem>, Int) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("En esta publicación · ${album.size}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Cine.TextSoft)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(album, key = { _, it -> it.messageId }) { index, entry ->
                AlbumThumb(entry, files, isCurrent = entry.messageId == current.messageId) { onOpen(album, index) }
            }
        }
    }
}

@Composable
private fun AlbumThumb(item: MediaItem, files: FileRepository, isCurrent: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    val image by rememberThumbnail(item, files)
    Box(
        Modifier.size(128.dp, 72.dp).focusRing(focused, shape).clip(shape).background(Color(0xFF1B1B20))
            .clickable(interaction, indication = null, enabled = !isCurrent, onClick = onClick),
    ) {
        image?.let { Image(it, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (item.kind == MediaKind.Video) {
            Box(Modifier.align(Alignment.Center).size(26.dp).background(Color(0xB30A0A0C), CircleShape), contentAlignment = Alignment.Center) {
                PlayGlyph(Cine.Text, 11.dp)
            }
        }
        val badge = if (isCurrent) "Viendo" else item.durationSeconds?.let(::formatDuration)
        badge?.let {
            Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(if (isCurrent) Cine.Accent.copy(alpha = .9f) else Color(0xD90A0A0C), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                color = if (isCurrent) Cine.OnLight else Cine.Text)
        }
    }
}

@Composable
fun PlayGlyph(color: Color, size: androidx.compose.ui.unit.Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        val path = Path().apply {
            moveTo(this@Canvas.size.width * 0.25f, this@Canvas.size.height * 0.17f)
            lineTo(this@Canvas.size.width * 0.83f, this@Canvas.size.height * 0.5f)
            lineTo(this@Canvas.size.width * 0.25f, this@Canvas.size.height * 0.83f)
            close()
        }
        drawPath(path, color)
    }
}
