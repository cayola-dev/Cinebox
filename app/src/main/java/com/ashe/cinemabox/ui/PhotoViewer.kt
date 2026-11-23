package com.ashe.cinemabox.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.ashe.cinemabox.library.FileRepository
import com.ashe.cinemabox.library.MediaItem
import com.ashe.cinemabox.library.formatDate
import com.ashe.cinemabox.ui.theme.Cine
import kotlinx.coroutines.CancellationException

@Composable
fun PhotoViewer(
    photos: List<MediaItem>,
    startIndex: Int,
    repository: FileRepository,
    onIndexChange: (Int) -> Unit,
) {
    var index by remember { mutableIntStateOf(startIndex) }
    val item = photos[index]
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidth = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeight = with(density) { configuration.screenHeightDp.dp.roundToPx() }

    var failed by remember(item.chatId, item.messageId) { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(item.minithumbnail?.let(::decodePreview), item.chatId, item.messageId) {
        value = item.minithumbnail?.let(::decodePreview)
        item.thumbnailFileId?.let { id -> runCatching { repository.thumbnail(id) }.getOrNull()?.let { value = it } }
        try {
            repository.photo(item.fileId, screenWidth, screenHeight)?.let { value = it } ?: run { failed = true }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }

    fun move(delta: Int) {
        val next = (index + delta).coerceIn(0, photos.lastIndex)
        if (next != index) {
            index = next
            onIndexChange(next)
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionRight, Key.MediaNext -> { move(1); true }
                    Key.DirectionLeft, Key.MediaPrevious -> { move(-1); true }
                    else -> false
                }
            },
    ) {
        image?.let { Image(it, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        if (failed) {
            Text("No se pudo cargar la foto. Revisa la conexión.", fontSize = 16.sp, color = Cine.TextSoft,
                modifier = Modifier.align(Alignment.Center)
                    .background(Color(0xBF0E0E11), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xD90A0A0C)))
                .padding(start = 48.dp, end = 48.dp, bottom = 27.dp, top = 48.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Fotos · ${formatDate(item.date)}", fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, color = Cine.TextSoft)
                Text(item.title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("‹  ${index + 1} / ${photos.size}  ›", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Cine.TextMuted)
        }
    }
}

private fun decodePreview(data: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
