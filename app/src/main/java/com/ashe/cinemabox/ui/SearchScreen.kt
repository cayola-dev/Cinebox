package com.ashe.cinemabox.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.ashe.cinemabox.library.FileRepository
import com.ashe.cinemabox.library.LibraryRepository
import com.ashe.cinemabox.library.MediaItem
import com.ashe.cinemabox.library.formatDuration
import com.ashe.cinemabox.telegram.TdException
import com.ashe.cinemabox.telegram.tdErrorMessage
import com.ashe.cinemabox.ui.theme.Brand
import com.ashe.cinemabox.ui.theme.Cine
import com.ashe.cinemabox.ui.theme.KeyButton
import com.ashe.cinemabox.ui.theme.focusRing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val KEYS = "abcdefghijklmnñopqrstuvwxyz.-#"
private const val DEBOUNCE_MS = 500L

/** Survives opening a result and coming back, so the query and results stay as they were. */
class SearchModel {
    var query by mutableStateOf("")
    var results by mutableStateOf(emptyList<MediaItem>())
    var searchedFor by mutableStateOf("")
    var searching by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var pendingFocusId by mutableStateOf<Long?>(null)
}

/** 05 · Buscador: text or #hashtag search across Saved Messages and the chosen channels. */
@Composable
fun SearchScreen(
    model: SearchModel,
    home: HomeModel,
    library: LibraryRepository,
    files: FileRepository,
    onOpen: (results: List<MediaItem>, index: Int) -> Unit,
) {
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (model.pendingFocusId == null) runCatching { firstKey.requestFocus() } }

    LaunchedEffect(model.query) {
        val query = model.query.trim()
        if (query.length < 2) {
            model.results = emptyList()
            model.searchedFor = ""
            model.error = null
            return@LaunchedEffect
        }
        if (query == model.searchedFor) return@LaunchedEffect
        delay(DEBOUNCE_MS)
        model.searching = true
        model.error = null
        try {
            // Sequential, one request per library row, so typing never fans out into a burst.
            val found = mutableListOf<MediaItem>()
            for (row in home.rows) found += library.search(row.chatId, row.kind, query)
            model.results = found.distinctBy { it.chatId to it.messageId }.sortedByDescending { it.date }
            model.searchedFor = query
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            model.error = if (e is TdException) tdErrorMessage(e.code, e.message) else "No se pudo buscar."
        } finally {
            model.searching = false
        }
    }

    Box(Modifier.fillMaxSize().background(Cine.Background).padding(horizontal = 48.dp, vertical = 27.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Brand()
            Text("Buscador", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Cine.TextMuted)
        }
        Row(Modifier.fillMaxSize().padding(top = 56.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.width(282.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(44.dp).background(Cine.Field, RoundedCornerShape(8.dp))
                        .border(1.dp, Cine.Border, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SearchGlyph(Cine.TextMuted)
                    Text(model.query.ifEmpty { "" }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Box(Modifier.size(2.dp, 20.dp).background(Cine.Accent, RoundedCornerShape(1.dp)))
                }
                KEYS.chunked(6).forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { i, char ->
                            KeyButton(char.toString(), { model.query += char },
                                (if (rowIndex == 0 && i == 0) Modifier.focusRequester(firstKey) else Modifier).weight(1f))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyButton("Espacio", { if (model.query.isNotEmpty() && !model.query.endsWith(" ")) model.query += " " }, Modifier.weight(1f))
                    KeyButton("Borrar", { model.query = model.query.dropLast(1) }, Modifier.weight(1f))
                }
            }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                val tags = home.suggestedTags
                if (tags.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Etiquetas sugeridas", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Cine.TextMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            tags.forEach { tag -> FocusableTag(tag) { model.query = tag } }
                        }
                    }
                }
                ResultsHeader(model)
                if (model.results.isNotEmpty()) {
                    LazyVerticalGrid(
                        GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(6.dp),
                    ) {
                        itemsIndexed(model.results, key = { _, it -> "${it.chatId}-${it.messageId}" }) { index, item ->
                            ResultCard(item, files, wantsFocus = model.pendingFocusId == item.messageId,
                                onFocused = { if (model.pendingFocusId == item.messageId) model.pendingFocusId = null }) {
                                model.pendingFocusId = item.messageId
                                onOpen(model.results, index)
                            }
                        }
                    }
                }
            }
        }
        HintsBar(listOf("OK" to "Escribir", "Atrás" to "Volver"), atBottom = true)
    }
}

@Composable
private fun ResultsHeader(model: SearchModel) {
    val query = model.query.trim()
    when {
        query.length < 2 -> Text("Escribe al menos dos letras o elige una etiqueta.", fontSize = 14.sp, color = Cine.TextMuted)
        model.error != null -> Text(model.error!!, fontSize = 14.sp, color = Cine.Error)
        model.searching || model.searchedFor != query -> Text("Buscando “$query”…", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        else -> Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Resultados para “$query”", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.rise(query, 0f))
            Text("· ${model.results.size} ${if (model.results.size == 1) "medio" else "medios"}", fontSize = 16.sp,
                fontWeight = FontWeight.Medium, color = Cine.TextMuted, modifier = Modifier.rise(query, 1f))
        }
    }
}

@Composable
private fun FocusableTag(tag: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.height(28.dp).focusRing(focused, shape)
            .background(if (focused) Cine.Text else Color(0xFF1E1E24), shape)
            .border(1.dp, if (focused) Cine.Text else Cine.Border, shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(tag, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (focused) Cine.OnLight else Cine.Text) }
}

@Composable
private fun ResultCard(item: MediaItem, files: FileRepository, wantsFocus: Boolean, onFocused: () -> Unit, onClick: () -> Unit) {
    val requester = remember { FocusRequester() }
    if (wantsFocus) LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    if (focused) LaunchedEffect(Unit) { onFocused() }
    val shape = RoundedCornerShape(6.dp)
    val image by rememberThumbnail(item, files)
    Column(
        Modifier.focusRequester(requester).clickable(interaction, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusRing(focused, shape).clip(shape).background(Color(0xFF1B1B20))) {
            image?.let { Image(it, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (image == null && item.kind == com.ashe.cinemabox.library.MediaKind.Audio) AudioCover(item.title, Modifier.fillMaxSize())
            item.durationSeconds?.let {
                Text(formatDuration(it), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .background(Color(0xD90A0A0C), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
        }
        Text(item.title, fontSize = 12.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
            color = if (focused) Cine.Text else Cine.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
