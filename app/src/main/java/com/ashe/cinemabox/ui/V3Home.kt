package com.ashe.cinemabox.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.tv.material3.Text
import com.ashe.cinemabox.library.*
import com.ashe.cinemabox.ui.theme.*

class V3Navigation {
    val verticalLists = List(4) { LazyListState() }
    val horizontalLists = mutableMapOf<String, LazyListState>()
    var restoreKey by mutableStateOf<String?>(null)
    var tab by mutableIntStateOf(0)
    var channel by mutableStateOf<Long?>(null)
    var page by mutableIntStateOf(0)
    var musicIndex by mutableIntStateOf(0)
    var favorites by mutableStateOf(false)
    /** Focused card in the catalog; the backdrop drifts against it (v3 parallax). */
    var focusCol by mutableIntStateOf(0)
    var focusRow by mutableIntStateOf(1)

    /** Backdrop offset of the v3 prototype (26 / 22 px per step at 1920 px), bounded to the 1.14 overscan. */
    val parallax get() = androidx.compose.ui.unit.DpOffset((-13 * focusCol.coerceIn(0, 4)).dp, (-11 * (focusRow.coerceIn(0, 3) - 1)).dp)
}

private data class Shelf(val title: String, val items: List<MediaItem>, val row: MediaRow? = null)

@OptIn(ExperimentalFoundationApi::class)
private class ShelfPivot(private val anchor: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - anchor
}

@OptIn(ExperimentalFoundationApi::class)
private object CardPivot : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = 12f
        return when {
            offset < margin -> offset - margin
            offset + size > containerSize - margin -> offset + size - containerSize + margin
            else -> 0f
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CatalogColumn(state: LazyListState, content: LazyListScope.() -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pivot = remember(density) { ShelfPivot(with(density) { 48.dp.toPx() }) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
        LazyColumn(state = state, contentPadding = PaddingValues(top = 12.dp, bottom = 240.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.fillMaxSize(), content = content)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CatalogRail(state: LazyListState = rememberLazyListState(), content: LazyListScope.() -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides CardPivot) {
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun ChannelTile(row: MediaRow, media: List<MediaItem>, files: FileRepository, onClick: () -> Unit) {
    val cover = media.firstOrNull { it.thumbnailFileId != null } ?: media.firstOrNull()
    val image = cover?.let { rememberThumbnail(it, files).value }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(9.dp)
    Box(Modifier.size(110.dp, 58.dp).focusRing(focused, shape).clip(shape).background(Cine.Field)
        .clickable(interaction, null, onClick = onClick)) {
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (cover?.kind == MediaKind.Audio) AudioCover(cover.title, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color(0xFF141426).copy(alpha = if (focused) .4f else .58f)))
        Column(Modifier.align(Alignment.Center).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (row.isChannel) row.title else "Mensajes guardados", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("${media.size} archivos", fontSize = 10.sp, color = Cine.TextSoft)
        }
    }
}

@Composable
fun V3Home(
    home: HomeModel, files: FileRepository, nav: V3Navigation, history: PlaybackHistory?,
    restored: List<MediaItem>, music: MusicSession,
    onOpen: (List<MediaItem>, Int) -> Unit, onAd: (SponsoredAd) -> Unit,
    onSearch: () -> Unit, onSettings: () -> Unit, onMusic: () -> Unit,
) {
    val records = history?.records?.collectAsState()?.value.orEmpty()
    val all = (home.rows.flatMap { it.items } + restored).distinctBy { it.chatId to it.messageId }
    val channels = home.rows.distinctBy { it.chatId }.sortedByDescending { home.channelUseCount(it.chatId) }
    val videos = all.filter { it.kind == MediaKind.Video }
    val audio = all.filter { it.kind == MediaKind.Audio }
    val continuing = (restored + videos).distinctBy { it.chatId to it.messageId }
        .filter { it.kind == MediaKind.Video && resumePosition(history?.record(it)) > 0 }
        .sortedByDescending { history?.record(it)?.updatedAt }
    Box(Modifier.fillMaxSize()) {
        if (nav.tab < 4) {
            // The v3 hero occupies the left half; the library starts at x=880 / 2 dp.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                0f to Cine.Background.copy(alpha = .08f), .35f to Cine.Background.copy(alpha = .35f), .52f to Cine.Background)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Transparent, .42f to Color.Transparent, 1f to Cine.Background.copy(alpha = .9f))))
            // The text drifts a quarter as far as the backdrop, the opposite way, for depth.
            val heroShift by animateDpAsState(-nav.parallax.x * .25f, tween(1000, easing = CubicBezierEasing(.16f, 1f, .3f, 1f)), label = "heroShift")
            home.focused?.let { item ->
                Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 70.dp).width(350.dp)
                    .graphicsLayer { translationX = heroShift.toPx() },
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (item.isMovie) "PELÍCULA" else item.kind.title.uppercase(), fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold,
                        color = Cine.TextSoft, modifier = Modifier.rise(item.chatId to item.messageId, 0f))
                    Text(item.title, fontFamily = Urbanist, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp,
                        lineHeight = 42.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.rise(item.messageId, 1f))
                    Text(listOfNotNull(item.durationSeconds?.let(::formatDuration), item.tags.take(3).joinToString("  ").ifBlank { null }).joinToString("   ·   "),
                        fontSize = 12.sp, color = Cine.TextSoft, modifier = Modifier.rise(item.messageId, 2f))
                    val progress = history?.record(item)
                    if (item.kind == MediaKind.Video && resumePosition(progress) > 0 && progress != null && progress.durationMs > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.width(160.dp).height(3.dp).background(Cine.Text.copy(alpha = .25f), RoundedCornerShape(50))) {
                                Box(Modifier.fillMaxWidth((progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)).fillMaxHeight().background(Cine.Accent, RoundedCornerShape(50)))
                            }
                            Text("Quedan ${((progress.durationMs - progress.positionMs) / 60_000).coerceAtLeast(1)} min", fontSize = 12.sp, color = Cine.TextSoft)
                        }
                    }
                }
            }
        } else Box(Modifier.fillMaxSize().background(Cine.Background))

        when {
            home.fatalError != null -> StateCard("No pudimos conectar", home.fatalError!!, "Reintentar", home::reload, Modifier.align(Alignment.Center))
            home.account == null -> Text("Cargando biblioteca…", modifier = Modifier.align(Alignment.Center))
            nav.tab == 5 -> Explore(home, files, nav, onOpen, onAd, onSettings)
            nav.tab == 4 -> {
                val sorted = audio.filter { !nav.favorites || history?.record(it)?.favorite == true }
                    .sortedByDescending { history?.record(it)?.plays ?: 0 }
                MusicBrowser(sorted, nav, files, history, music, onMusic,
                    onLoadMore = { home.rows.filter { it.kind == MediaKind.Audio }.forEach(home::loadMore) },
                    hasMore = home.rows.any { it.kind == MediaKind.Audio && !it.finished })
            }
            else -> {
                val shelves = when (nav.tab) {
                    1 -> home.rows.filter { it.kind == MediaKind.Video }.map { Shelf(it.title, it.items.filter { it.isMovie }, it) }
                        .filter { it.items.isNotEmpty() }
                    2 -> home.rows.filter { it.kind == MediaKind.Video }.map { Shelf(it.title, it.items.filterNot { it.isMovie }, it) }
                    3 -> videos.mapNotNull { item -> seriesName(item)?.let { it to item } }.groupBy({ it.first }, { it.second })
                        .map { (name, episodes) -> Shelf(name, episodes.sortedWith(compareBy<MediaItem> { episodeOrder(it) }.thenBy { it.title.lowercase() })) }
                    else -> buildList {
                        if (continuing.isNotEmpty()) add(Shelf("Seguir viendo", continuing))
                        if (audio.isNotEmpty()) add(Shelf("Tu música más escuchada", audio.sortedByDescending { history?.record(it)?.plays ?: 0 }))
                        addAll(home.rows.filter { it.kind == MediaKind.Video }.map { Shelf(it.title, it.items, it) })
                    }
                }
                Column(Modifier.padding(start = 432.dp, top = 85.dp).fillMaxSize().rise(nav.tab, 0f)) {
                    CatalogColumn(nav.verticalLists[nav.tab]) {
                    if (nav.tab == 0) item(key = "channels") {
                      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Canales más usados", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                        CatalogRail {
                            itemsIndexed(channels, key = { _, row -> row.chatId }) { _, row ->
                                val media = home.rows.filter { it.chatId == row.chatId }.flatMap { it.items }
                                ChannelTile(row, media, files) {
                                    nav.channel = row.chatId; nav.page = 0; nav.tab = 5
                                }
                            }
                        }
                      }
                    }
                    if (nav.tab == 1 && shelves.isEmpty()) item {
                        Text(if (home.rows.any { it.kind == MediaKind.Video && it.loading }) "Buscando películas…"
                            else "Aún no hay películas: aparecen aquí los videos de 30 minutos o más.",
                            fontSize = 17.sp, color = Cine.TextMuted, modifier = Modifier.padding(16.dp))
                    }
                    if (nav.tab == 3 && shelves.isEmpty()) item {
                        Text("Organiza tus episodios con #serie_nombre en el mensaje.", fontSize = 17.sp,
                            color = Cine.TextMuted, modifier = Modifier.padding(16.dp))
                    }
                        itemsIndexed(shelves, key = { index, shelf -> "${nav.tab}-$index-${shelf.title}" }) { shelfIndex, shelf ->
                            var active by remember { mutableStateOf(false) }
                            val opacity by animateFloatAsState(if (active) 1f else .55f, tween(600), label = "shelfOpacity")
                            Column(Modifier.onFocusChanged { active = it.hasFocus }.graphicsLayer { alpha = opacity }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(shelf.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = Urbanist, modifier = Modifier.padding(start = 8.dp))
                                if (shelf.items.isEmpty()) Text(if (shelf.row?.loading == true) "Cargando…" else "Sin videos", fontSize = 12.sp, color = Cine.TextMuted, modifier = Modifier.padding(8.dp))
                                shelf.row?.error?.let { Text(it, color = Cine.Error, fontSize = 12.sp); CineButton("Reintentar", { home.loadMore(shelf.row) }) }
                                CatalogRail(state = nav.horizontalLists.getOrPut("${nav.tab}-${shelf.title}") { LazyListState() }) {
                                    itemsIndexed(shelf.items, key = { _, it -> "${it.chatId}:${it.messageId}" }) { index, item ->
                                        if (index == 1) shelf.row?.ad?.let { ad ->
                                            SponsoredCard(ad, shelf.row.adImage, 0f, { home.adFullyVisible(ad) }, { onAd(ad) })
                                            Spacer(Modifier.width(14.dp))
                                        }
                                        V3Card(item, files, history?.record(item), wantsFocus = nav.restoreKey == "${item.chatId}:${item.messageId}", onFocus = {
                                            nav.restoreKey = null
                                            nav.focusCol = index
                                            nav.focusRow = shelfIndex + if (nav.tab == 0) 1 else 0
                                            home.pendingFocusId = null
                                            home.rowOf(item)?.let { home.focus(it, item, it.items.indexOf(item)) }
                                            if (index >= shelf.items.size - 4) shelf.row?.let(home::loadMore)
                                        }, onClick = { onOpen(shelf.items, index) })
                                    }
                                    if (shelf.items.size < 2) shelf.row?.ad?.let { ad -> item { SponsoredCard(ad, shelf.row.adImage, 0f, { home.adFullyVisible(ad) }, { onAd(ad) }) } }
                                }
                            }
                        }
                        if (nav.tab == 1 && home.rows.any { it.kind == MediaKind.Video && !it.finished }) item {
                            CineButton("Buscar más películas", { home.rows.filter { it.kind == MediaKind.Video }.forEach(home::loadMore) })
                        }
                        if (nav.tab == 3 && home.rows.any { it.kind == MediaKind.Video && !it.finished }) item {
                            CineButton("Buscar más episodios", { home.rows.filter { it.kind == MediaKind.Video }.forEach(home::loadMore) })
                        }
                    }
                }
            }
        }
        // Header is composed last so it stays above the animated catalog.
        Row(Modifier.fillMaxWidth().height(70.dp).background(Brush.verticalGradient(listOf(Cine.Background, Cine.Background.copy(alpha = .9f))))
            .padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
          Row(Modifier.width(392.dp), verticalAlignment = Alignment.CenterVertically) {
            Brand()
            Spacer(Modifier.width(14.dp))
            if (music.item != null) MiniPlayer(music, files, onMusic, Modifier.weight(1f, fill = false).padding(end = 10.dp))
          }
            listOf("Inicio", "Películas", "Videos", "Series", "Música", "Explorar").forEachIndexed { index, title ->
                V3Tab(title, nav.tab == index, nav.restoreKey == null) { nav.tab = index; nav.restoreKey = null }
            }
            Spacer(Modifier.width(12.dp))
            RoundIconButton(onSearch) { SearchGlyph(it) }
            Spacer(Modifier.width(8.dp))
            RoundIconButton({ if (home.account != null) onSettings() }) { Text(home.account?.initial ?: "⚙", color = it, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun V3Tab(title: String, selected: Boolean, autofocus: Boolean, click: () -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (selected && autofocus) requester.requestFocus() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(title, fontSize = 14.sp, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
        color = if (focused) Cine.OnLight else if (selected) Cine.Text else Cine.TextMuted,
        modifier = Modifier.focusRequester(requester).focusRing(focused, shape).background(if (focused) Cine.Text else Color.Transparent, shape)
            .clickable(interaction, null, onClick = click).padding(horizontal = 10.dp, vertical = 7.dp))
    Box(Modifier.size(14.dp, 2.dp).background(if (selected && !focused) Cine.Text else Color.Transparent, RoundedCornerShape(50)))
    }
}

@Composable
fun V3Tile(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.focusRing(focused, shape).background(if (focused) Cine.Raised else Cine.Field, shape)
        .clickable(interaction, null, onClick = onClick).padding(10.dp), contentAlignment = Alignment.CenterStart) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun V3Card(item: MediaItem, files: FileRepository, record: PlaybackRecord? = null, compact: Boolean = false, wantsFocus: Boolean = false,
           onFocus: () -> Unit = {}, onClick: () -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(wantsFocus) { if (wantsFocus) requester.requestFocus() }
    val image by rememberThumbnail(item, files)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    val audio = item.kind == MediaKind.Audio
    val width = if (compact) 134.dp else if (audio) 150.dp else 200.dp
    val surface by androidx.compose.animation.animateColorAsState(if (focused) Cine.Raised else Cine.Field, tween(220), label = "cardSurface")
    Column(Modifier.width(width).focusRequester(requester).focusRing(focused, shape, outline = false).clip(shape)
        .background(surface).onFocusChanged { if (it.isFocused) onFocus() }
        .clickable(interaction, null, onClick = onClick).padding(7.dp)) {
        Box(Modifier.fillMaxWidth().height(if (compact) 68.dp else if (audio) 136.dp else 105.dp).clip(RoundedCornerShape(6.dp)).background(Cine.Key), contentAlignment = Alignment.Center) {
            if (image != null) Image(image!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else if (audio) AudioCover(item.title, Modifier.fillMaxSize())
            else Text(item.kind.title, color = Cine.TextMuted, fontSize = 22.sp)
            item.durationSeconds?.let { Text(formatDuration(it), fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).background(Color.Black.copy(alpha = .65f), RoundedCornerShape(4.dp)).padding(3.dp)) }
        }
        if (!audio && resumePosition(record) > 0 && record != null && record.durationMs > 0)
            Box(Modifier.fillMaxWidth((record.positionMs.toFloat() / record.durationMs).coerceIn(0f, 1f)).height(2.dp).background(Cine.Accent))
        Column(Modifier.padding(top = 9.dp, start = 1.dp, end = 1.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.title, fontSize = if (compact) 11.sp else 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact) Text(if (!audio && resumePosition(record) > 0 && record != null)
                "Quedan ${((record.durationMs - record.positionMs) / 60_000).coerceAtLeast(1)} min" else if (audio) "Música" else item.kind.title,
                fontSize = 11.sp, color = Cine.TextMuted)
        }
        val indicator by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "cardIndicator")
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(28.dp, 2.dp).graphicsLayer { alpha = indicator }
            .background(Cine.Text, RoundedCornerShape(50)))
    }
}

@Composable
private fun Explore(home: HomeModel, files: FileRepository, nav: V3Navigation, onOpen: (List<MediaItem>, Int) -> Unit, onAd: (SponsoredAd) -> Unit, onSettings: () -> Unit) {
    val channels = home.enabledChannels.sortedByDescending { home.channelUseCount(it.chatId) }
    if (channels.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (home.channelsLoading) Text("Cargando tus canales…")
            else StateCard("Tus canales", home.channelError ?: "Activa los canales que quieres explorar en Ajustes.",
                if (home.channelError != null) "Reintentar" else "Elegir canales",
                if (home.channelError != null) home::reload else onSettings)
        }
        return
    }
    val selected = channels.firstOrNull { it.chatId == nav.channel } ?: channels.firstOrNull()
    LaunchedEffect(selected?.chatId) { if (nav.channel != selected?.chatId) { nav.channel = selected?.chatId; nav.page = 0 } }
    val backdrop by channelImage(selected, files)
    val softFocus = remember {
        if (android.os.Build.VERSION.SDK_INT >= 31) android.graphics.RenderEffect.createBlurEffect(
            40f, 40f, android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect() else null
    }
    androidx.compose.animation.Crossfade(backdrop, animationSpec = tween(450), label = "channelBackdrop") { image ->
        Box(Modifier.fillMaxSize()) {
            if (image != null) Image(image, null, Modifier.fillMaxSize().graphicsLayer { alpha = .24f; renderEffect = softFocus }, contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Cine.Background, Color.Transparent, Cine.Background.copy(alpha = .85f)))))
        }
    }
    val rows = home.rows.filter { it.chatId == selected?.chatId }
    val media = rows.flatMap { it.items }.sortedByDescending { it.date }
    val page = nav.page.coerceAtMost(((media.size - 1).coerceAtLeast(0)) / 8)
    Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 40.dp, top = 100.dp, bottom = 30.dp).rise("explore", 0f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Canales", fontSize = 32.sp, fontFamily = Urbanist, fontWeight = FontWeight.Bold)
            Text("${channels.size} canales activados", color = Cine.TextMuted, fontSize = 12.sp)
            val selectedIndex = channels.indexOfFirst { it.chatId == selected?.chatId }.coerceAtLeast(0)
            Text("↑ ↓  Cambiar biblioteca", color = Cine.TextMuted, fontSize = 11.sp)
            Box(Modifier.fillMaxWidth().height(240.dp).padding(top = 12.dp)) {
                (3 downTo 1).forEach { depth ->
                    channels.getOrNull(selectedIndex + depth)?.let { next ->
                        ChannelArtwork(next, files, Modifier.offset(y = (depth * 22).dp).fillMaxWidth().height(150.dp).graphicsLayer {
                            scaleX = 1f - depth * .05f; scaleY = scaleX; alpha = 1f - depth * .2f
                        })
                    }
                }
                selected?.let { row ->
                    val transition = remember(row.chatId) { Animatable(0f) }
                    LaunchedEffect(row.chatId) { transition.animateTo(1f, tween(500, easing = Motion.Glide)) }
                    ChannelArtwork(row, files, Modifier.fillMaxWidth().height(150.dp)
                        .graphicsLayer { alpha = transition.value; translationY = (1 - transition.value) * 24f }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else {
                                val delta = when (event.key) { Key.DirectionDown -> 1; Key.DirectionUp -> -1; else -> 0 }
                                val target = channels.getOrNull(selectedIndex + delta)
                                if (delta != 0 && target != null) { nav.channel = target.chatId; nav.page = 0; true } else false
                            }
                        }, onClick = { nav.channel = row.chatId })
                }
            }
            Text("${selectedIndex + 1} de ${channels.size}", color = Cine.TextMuted, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            home.channelError?.let { Text(it, color = Cine.Error, fontSize = 11.sp); CineButton("Reintentar canales", home::reload) }
            Text(selected?.title ?: "Biblioteca", fontSize = 24.sp, fontFamily = Urbanist, fontWeight = FontWeight.Bold)
            Text("${media.size} archivos cargados · videos, música y fotos", fontSize = 12.sp, color = Cine.TextMuted)
            if (media.isEmpty()) Text(if (rows.any { it.loading }) "Cargando…" else "No hay archivos en esta biblioteca.", color = Cine.TextMuted)
            repeat(2) { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(4) { c ->
                        val index = page * 8 + r * 4 + c
                        media.getOrNull(index)?.let { item -> V3Card(item, files, compact = true,
                            wantsFocus = nav.restoreKey == "${item.chatId}:${item.messageId}",
                            onFocus = { nav.restoreKey = null }, onClick = { onOpen(media, index) }) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CineButton("‹", { nav.page = (page - 1).coerceAtLeast(0) }, enabled = page > 0)
                Text("${page + 1} / ${maxOf(1, (media.size + 7) / 8)}", fontSize = 12.sp)
                CineButton("›", { nav.page = page + 1 }, enabled = (page + 1) * 8 < media.size)
                if (rows.any { !it.finished }) CineButton(if (rows.any { it.loading }) "Cargando…" else "Cargar más", { rows.forEach(home::loadMore) }, enabled = rows.none { it.loading })
            }
            rows.firstOrNull { it.error != null }?.let { row -> Text(row.error!!, color = Cine.Error, fontSize = 11.sp) }
            rows.firstOrNull { it.ad != null }?.let { row -> SponsoredCard(row.ad!!, row.adImage, 0f, { home.adFullyVisible(row.ad!!) }, { onAd(row.ad!!) }) }
        }
    }
}
