package com.ashe.cinemabox.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.tv.material3.Text
import com.ashe.cinemabox.library.Channel
import com.ashe.cinemabox.library.FileRepository
import com.ashe.cinemabox.ui.theme.*
import kotlinx.coroutines.CancellationException

@Composable
internal fun channelImage(channel: Channel?, files: FileRepository): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, channel?.chatId, channel?.photoFileId) {
        value = null
        channel?.photoFileId?.let { id ->
            try { value = files.thumbnail(id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep the initials if the photo is unavailable. */ }
        }
    }

@Composable
internal fun ChannelArtwork(channel: Channel, files: FileRepository, modifier: Modifier, onClick: (() -> Unit)? = null) {
    val image by channelImage(channel, files)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.focusRing(focused, shape).clip(shape).background(Cine.Raised)
        .then(if (onClick != null) Modifier.clickable(interaction, null, onClick = onClick) else Modifier)) {
        if (image != null) Image(image!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Cine.Raised, Cine.Accent.copy(alpha = .25f)))))
            Text(channel.title.take(2).uppercase(), modifier = Modifier.align(Alignment.Center), fontSize = 44.sp, color = Cine.TextSoft)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))))
        Text(channel.title, Modifier.align(Alignment.BottomStart).padding(16.dp), fontSize = 19.sp,
            fontFamily = Urbanist, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
