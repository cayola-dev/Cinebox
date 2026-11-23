package com.ashe.cinemabox.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.ashe.cinemabox.library.Account
import com.ashe.cinemabox.library.Channel
import com.ashe.cinemabox.library.FileRepository
import com.ashe.cinemabox.library.LibraryRepository
import com.ashe.cinemabox.library.LibrarySettings
import com.ashe.cinemabox.library.MediaItem
import com.ashe.cinemabox.library.MediaKind
import com.ashe.cinemabox.library.SponsoredAd
import com.ashe.cinemabox.library.formatDuration
import com.ashe.cinemabox.telegram.TdException
import com.ashe.cinemabox.telegram.tdErrorMessage
import com.ashe.cinemabox.ui.theme.Brand
import com.ashe.cinemabox.ui.theme.Cine
import com.ashe.cinemabox.ui.theme.CineButton
import com.ashe.cinemabox.ui.theme.Motion
import com.ashe.cinemabox.ui.theme.focusRing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val SAVED = "Mensajes guardados"

// Layout of the v2 prototype at 1920×1080 px, halved to dp.
private val ROW_Y = 274.dp
private val ROW_PITCH = 150.dp
private val CARD_W = 165.dp
private val CARD_H = 93.dp
private val CARD_GAP = 14.dp
private val EDGE = 48.dp

sealed interface RowEntry {
    data class Media(val item: MediaItem, val index: Int) : RowEntry
    data class Ad(val ad: SponsoredAd) : RowEntry
}

class MediaRow(val chatId: Long, val kind: MediaKind, val title: String, val source: String, val isChannel: Boolean) {
    val key = "$chatId-$kind"
    val label get() = "$title · $source"
    val listState = LazyListState()
    var items by mutableStateOf(emptyList<MediaItem>())
    var nextFrom by mutableLongStateOf(0L)
    var loading by mutableStateOf(false)
    var finished by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var ad by mutableStateOf<SponsoredAd?>(null)
    var adImage by mutableStateOf<ImageBitmap?>(null)
    var focusedIndex by mutableIntStateOf(0)

    /** Media in order, with the channel's sponsored message (if any) always in second place. */
    val entries: List<RowEntry>
        get() {
            val media = items.mapIndexed { index, item -> RowEntry.Media(item, index) }
            val sponsored = ad ?: return media
            return media.toMutableList<RowEntry>().apply { add(minOf(1, size), RowEntry.Ad(sponsored)) }
        }
}

class HomeTab(val title: String, val includes: (MediaRow) -> Boolean)

/** Screen state; lives as long as the session (it is rebuilt after each reconnection). */
class HomeModel(
    private val library: LibraryRepository,
    private val files: FileRepository,
    private val settings: LibrarySettings,
    private val scope: CoroutineScope,
) {
    var account by mutableStateOf<Account?>(null)
    var enabledChannels by mutableStateOf(emptyList<Channel>())
    var channelError by mutableStateOf<String?>(null)
    var channelsLoading by mutableStateOf(true)
    var fatalError by mutableStateOf<String?>(null)
    var focused by mutableStateOf<MediaItem?>(null)
    var focusedLabel by mutableStateOf("")
    var rows by mutableStateOf(emptyList<MediaRow>())
    var tabs by mutableStateOf(listOf(HomeTab("Inicio") { true }))
    var selectedTab by mutableIntStateOf(0)
    var focusedRowKey by mutableStateOf<String?>(null)
    var topBarFocused by mutableStateOf(false)
    var wipe by mutableStateOf(Wipe.None)
    /** Card to focus when Home is shown again after closing another screen. */
    var pendingFocusId by mutableStateOf<Long?>(null)
    var initialFocusDone = false
    private val viewedAds = mutableSetOf<Pair<Long, Long>>()
    private var loadJob: Job? = null
    private val pages = mutableListOf<Job>()
    private val pageLimit = kotlinx.coroutines.sync.Semaphore(3)

    val tabRows get() = rows.filter(tabs.getOrElse(selectedTab) { tabs.first() }.includes)
        .filter { it.items.isNotEmpty() || it.error != null || it.ad != null }
    val focusedRowIndex get() = if (topBarFocused) 0 else tabRows.indexOfFirst { it.key == focusedRowKey }.coerceAtLeast(0)
    val isEmpty get() = rows.all { it.finished && it.items.isEmpty() && it.error == null && it.ad == null }
    // Wait for every row's first page so row order and the initial focus never jump.
    val isFirstLoad get() = account == null || rows.any { it.items.isEmpty() && !it.finished && it.error == null }

    /** Hashtags of everything loaded, most frequent first; used as search suggestions. */
    val suggestedTags get() = rows.flatMap { it.items }.flatMap { it.tags }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(6).map { it.key }

    fun reload() {
        loadJob?.cancel()
        pages.forEach { it.cancel() }
        pages.clear()
        loadJob = scope.launch {
            fatalError = null
            channelError = null
            channelsLoading = true
            enabledChannels = emptyList()
            focused = null
            initialFocusDone = false
            pendingFocusId = null
            focusedRowKey = null
            rows = emptyList()
            val current = try {
                library.account()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fatalError = errorText(e)
                channelsLoading = false
                return@launch
            }
            settings.bindAccount(current.savedMessagesChatId)
            account = current
            val channels = settings.channelIds.mapNotNull { id ->
                try { library.channel(id).also { if (it == null) channelError = "Un canal activado ya no está disponible. Revisa tus canales en Ajustes." } } catch (e: CancellationException) { throw e }
                catch (e: Exception) { channelError = errorText(e); null }
            }
                .sortedBy { it.title.lowercase() }
            enabledChannels = channels
            channelsLoading = false
            rows = MediaKind.entries.map { MediaRow(current.savedMessagesChatId, it, it.title, SAVED, isChannel = false) } +
                channels.flatMap { channel -> MediaKind.entries.map { kind -> MediaRow(channel.chatId, kind, channel.title, kind.title, isChannel = true) } }
            tabs = listOf(HomeTab("Inicio") { true }, HomeTab("Guardados") { !it.isChannel }) +
                channels.map { channel -> HomeTab(channel.title) { it.chatId == channel.chatId } }
            selectedTab = selectedTab.coerceIn(0, tabs.lastIndex)
            account = current
            rows.forEach { row ->
                loadMore(row)
            }
            rows.filter { it.isChannel }.distinctBy { it.chatId }.forEach(::loadSponsored)
        }
    }

    fun selectTab(index: Int) {
        if (index == selectedTab) return
        selectedTab = index
        focusedRowKey = null
        tabRows.firstOrNull()?.let { row -> row.items.firstOrNull()?.let { focus(row, it, 0) } }
    }

    fun loadMore(row: MediaRow) {
        if (account == null || row.loading || row.finished) return
        row.loading = true
        row.error = null
        pages += scope.launch {
            try {
                pageLimit.acquire()
                val page = try { library.page(row.chatId, row.kind, row.nextFrom) } finally { pageLimit.release() }
                row.items = (row.items + page.items).distinctBy { it.chatId to it.messageId }
                row.nextFrom = page.nextFromMessageId
                row.finished = page.nextFromMessageId == 0L
                if (focused == null) {
                    tabRows.firstOrNull { it.items.isNotEmpty() }?.let { focus(it, it.items.first(), 0) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                row.error = errorText(e)
            } finally {
                row.loading = false
            }
        }
    }

    /** Sponsored messages are shown whenever TDLib returns one; there is no way to turn them off. */
    private fun loadSponsored(row: MediaRow) {
        scope.launch {
            try {
                val ad = library.sponsored(row.chatId) ?: return@launch
                if (ad.imageFileId != null || ad.mediaFileId != null) row.adImage = files.sponsoredImage(ad)
                rows.filter { it.chatId == row.chatId }.forEach { target -> target.ad = ad; target.adImage = row.adImage }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The row itself keeps working; the ad is requested again on the next reload.
            }
        }
    }

    fun adFullyVisible(ad: SponsoredAd) {
        if (viewedAds.add(ad.chatId to ad.messageId)) library.registerSponsoredView(ad)
    }

    /** Remembers focus and derives the backdrop wipe direction from the move. */
    fun focus(row: MediaRow, item: MediaItem, index: Int) {
        val rowsNow = tabRows
        val oldRow = rowsNow.indexOfFirst { it.key == focusedRowKey }
        val newRow = rowsNow.indexOf(row)
        wipe = when {
            oldRow < 0 -> Wipe.Right
            newRow > oldRow -> Wipe.Down
            newRow < oldRow -> Wipe.Up
            index < row.focusedIndex -> Wipe.Left
            else -> Wipe.Right
        }
        row.focusedIndex = index
        focusedRowKey = row.key
        focused = item
        focusedLabel = row.label
    }

    fun rowOf(item: MediaItem) = rows.firstOrNull { it.chatId == item.chatId && it.kind == item.kind }
    fun channelUseCount(chatId: Long) = settings.useCount(chatId)

    fun returnTo(item: MediaItem) {
        val row = rowOf(item) ?: return
        val index = row.items.indexOfFirst { it.messageId == item.messageId }
        if (index < 0) return
        focus(row, item, index)
        pendingFocusId = item.messageId
        val entry = row.entries.indexOfFirst { it is RowEntry.Media && it.item.messageId == item.messageId }
        if (entry >= 0 && row.listState.layoutInfo.visibleItemsInfo.none { it.index == entry }) {
            row.listState.requestScrollToItem(entry)
        }
    }

    private fun errorText(e: Exception) =
        if (e is TdException) tdErrorMessage(e.code, e.message) else "No se pudo cargar la biblioteca."
}

@Composable
fun HomeScreen(
    model: HomeModel,
    files: FileRepository,
    onOpen: (row: MediaRow, index: Int) -> Unit,
    onOpenAd: (SponsoredAd) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        TopBar(model, onSearch, onSettings)
        when {
            model.fatalError != null -> StateCard("No pudimos conectar", model.fatalError!!, "Reintentar", model::reload,
                Modifier.align(Alignment.Center).padding(horizontal = 48.dp))
            model.isFirstLoad -> SkeletonRows(Modifier.padding(start = 48.dp, end = 48.dp, top = ROW_Y - 20.dp))
            model.isEmpty -> StateCard(
                "Todavía no hay videos",
                "Sube videos a un canal privado o a tus Mensajes guardados y aparecerán aquí.",
                "Actualizar", model::reload, Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
            )
            model.tabRows.isEmpty() -> Column(Modifier.padding(start = 48.dp, top = ROW_Y + 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Aún no hay nada aquí", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.rise(model.selectedTab, 0f))
                Text("Sube videos a este canal y aparecerán aquí.", fontSize = 14.sp, color = Cine.TextMuted, modifier = Modifier.rise(model.selectedTab, 1f))
            }
            else -> {
                model.focused?.let { InfoPanel(it, model.focusedLabel, big = false, Modifier.padding(start = 48.dp, top = 75.dp)) }
                RowsStack(model, files, onOpen, onOpenAd)
            }
        }
        HintsBar(
            if (model.topBarFocused) listOf("◀ ▶" to "Pestañas", "OK" to "Abrir", "▼" to "Catálogo")
            else listOf("▲ ▼ ◀ ▶" to "Explorar", "OK" to "Ver detalle", "▲" to "Menú"),
            atBottom = true,
        )
    }
}

@Composable
private fun TopBar(model: HomeModel, onSearch: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 27.dp).height(32.dp)
            .onFocusChanged { model.topBarFocused = it.hasFocus },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Brand()
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            model.tabs.forEachIndexed { index, tab ->
                TabPill(tab.title, selected = index == model.selectedTab) { model.selectTab(index) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundIconButton(onSearch) { color -> SearchGlyph(color) }
            RoundIconButton(onSettings) { color ->
                Text(model.account?.initial ?: "", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
            }
        }
    }
}

@Composable
private fun TabPill(title: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.focusRing(focused, shape)
            .background(if (focused) Cine.Text else Color.Transparent, shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(title, fontSize = 14.sp, maxLines = 1,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
            color = when { focused -> Cine.OnLight; selected -> Cine.Text; else -> Cine.TextMuted })
        if (selected && !focused) {
            Box(Modifier.align(Alignment.BottomCenter).offset(y = 7.dp).size(14.dp, 2.dp).background(Cine.Text, RoundedCornerShape(1.dp)))
        }
    }
}

@Composable
fun RoundIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable (Color) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier.size(32.dp).focusRing(focused, CircleShape)
            .background(if (focused) Cine.Text else Cine.Raised, CircleShape)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content(if (focused) Cine.OnLight else Cine.Text) }
}

@Composable
fun SearchGlyph(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(15.dp)) {
        val stroke = size.width * 0.1f
        drawCircle(color, radius = size.width * 0.27f, center = androidx.compose.ui.geometry.Offset(size.width * 0.42f, size.height * 0.42f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.62f, size.height * 0.62f),
            androidx.compose.ui.geometry.Offset(size.width * 0.88f, size.height * 0.88f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** The focused row stays at a fixed height; rows below fade (0.55, 0.25) and rows above vanish. */
@Composable
private fun RowsStack(model: HomeModel, files: FileRepository, onOpen: (MediaRow, Int) -> Unit, onOpenAd: (SponsoredAd) -> Unit) {
    val rows = model.tabRows
    val focusedRow = model.focusedRowIndex
    Box(Modifier.fillMaxSize()) {
        rows.forEachIndexed { index, row ->
            if (index < focusedRow - 1 || index > focusedRow + 3) return@forEachIndexed
            key(row.key) {
                val target = ROW_Y + ROW_PITCH * (index - focusedRow)
                val y by animateFloatAsState(target.value, tween(560, easing = Motion.Glide), label = "rowY")
                val alpha by animateFloatAsState(
                    when {
                        index < focusedRow -> 0f
                        index == focusedRow -> 1f
                        index == focusedRow + 1 -> 0.55f
                        else -> 0.25f
                    },
                    tween(420), label = "rowAlpha",
                )
                val density = LocalDensity.current
                MediaRowView(
                    row, model, files,
                    isFirstRow = index == 0,
                    countsAdViews = index == focusedRow || index == focusedRow + 1,
                    showCounter = index == focusedRow && !model.topBarFocused,
                    onOpen = onOpen, onOpenAd = onOpenAd,
                    modifier = Modifier.offset { IntOffset(0, with(density) { y.dp.roundToPx() }) }.graphicsLayer { this.alpha = alpha },
                )
            }
        }
    }
}

/** Keeps the focused card within the first three slots, like the prototype's column offset. */
@OptIn(ExperimentalFoundationApi::class)
private class SlotBringIntoView(private val startPx: Float, private val maxLeadPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset < startPx -> offset - startPx
        offset > startPx + maxLeadPx -> offset - (startPx + maxLeadPx)
        else -> 0f
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun MediaRowView(
    row: MediaRow,
    model: HomeModel,
    files: FileRepository,
    isFirstRow: Boolean,
    countsAdViews: Boolean,
    showCounter: Boolean,
    onOpen: (MediaRow, Int) -> Unit,
    onOpenAd: (SponsoredAd) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val spec = remember(density) {
        with(density) { SlotBringIntoView(EDGE.toPx(), ((CARD_W + CARD_GAP) * 2).toPx()) }
    }
    val fadeEnd = with(density) { (EDGE - 4.dp).toPx() }
    Column(modifier.fillMaxWidth().onFocusChanged { if (it.hasFocus) model.focusedRowKey = row.key },
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.padding(start = EDGE), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("· ${row.source}", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Cine.TextMuted, maxLines = 1)
            if (showCounter && row.items.isNotEmpty()) {
                Text("${row.focusedIndex + 1} / ${row.items.size}${if (row.finished) "" else "+"}", fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, color = Cine.TextMuted, modifier = Modifier.padding(start = 8.dp))
            }
        }
        row.error?.let {
            Row(Modifier.padding(start = EDGE), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(it, fontSize = 13.sp, color = Cine.Error)
                CineButton("Reintentar", { model.loadMore(row) })
            }
        }
        val entries = row.entries
        if (entries.isNotEmpty()) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                LazyRow(
                    state = row.listState,
                    contentPadding = PaddingValues(horizontal = EDGE, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.focusRestorer()
                        // Cards that slide past the left edge fade out instead of being cut.
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fadeEnd),
                                blendMode = BlendMode.DstIn, size = size.copy(width = fadeEnd))
                        },
                ) {
                    items(entries, key = { entry ->
                        when (entry) {
                            is RowEntry.Media -> entry.item.messageId
                            is RowEntry.Ad -> "ad-${entry.ad.messageId}"
                        }
                    }) { entry ->
                        when (entry) {
                            is RowEntry.Ad -> SponsoredCard(entry.ad, row.adImage, fadeEnd,
                                onFullyVisible = { if (countsAdViews) model.adFullyVisible(entry.ad) },
                                onClick = { onOpenAd(entry.ad) })
                            is RowEntry.Media -> {
                                val item = entry.item
                                val wantsFocus = model.pendingFocusId == item.messageId ||
                                    (model.pendingFocusId == null && !model.initialFocusDone && isFirstRow && entry.index == 0)
                                MediaCard(item, files, wantsFocus, highlighted = row.key == model.focusedRowKey && row.focusedIndex == entry.index,
                                    onFocused = {
                                        model.focus(row, it, entry.index)
                                        model.initialFocusDone = true
                                        if (model.pendingFocusId == it.messageId) model.pendingFocusId = null
                                        // Next page only once the user reaches the end of the row.
                                        if (entry.index >= row.items.size - 4) model.loadMore(row)
                                    },
                                    onClick = { onOpen(row, entry.index) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaCard(
    item: MediaItem,
    files: FileRepository,
    wantsFocus: Boolean,
    highlighted: Boolean,
    onFocused: (MediaItem) -> Unit,
    onClick: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    if (wantsFocus) LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    val image by rememberThumbnail(item, files)
    Column(
        Modifier.width(CARD_W)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused(item) }
            .clickable(interaction, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(CARD_W, CARD_H).focusRing(focused, shape).clip(shape).background(Color(0xFF1B1B20))) {
            image?.let { Image(it, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Text(item.kind.title, fontSize = 11.sp, color = Cine.TextFaint, modifier = Modifier.align(Alignment.Center))
            item.durationSeconds?.let {
                Text(formatDuration(it), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .background(Color(0xD90A0A0C), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
        }
        Text(item.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (focused || highlighted) FontWeight.Bold else FontWeight.SemiBold,
            color = if (focused || highlighted) Cine.Text else Cine.TextMuted)
    }
}

/** The whole text is always laid out (no truncation) so a fully visible card means a valid view. */
@Composable
fun SponsoredCard(ad: SponsoredAd, image: ImageBitmap?, leftEdgePx: Float, onFullyVisible: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.width(300.dp)
            .onGloballyPositioned { coordinates ->
                val visible = coordinates.boundsInWindow()
                val size = coordinates.size
                val whole = size.width > 0 && visible.width >= size.width - 1 && visible.height >= size.height - 1
                if (whole && visible.left >= leftEdgePx) onFullyVisible()
            }
            .focusRing(focused, shape)
            .background(if (focused) Color(0xFF25252B) else Color(0xE616161A), shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            image?.let {
                Image(it, null, Modifier.size(60.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (ad.isRecommended) "Recomendado" else "Patrocinado", fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, color = Cine.Accent)
                Text(ad.title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (ad.text.isNotBlank()) Text(ad.text, fontSize = 10.sp, color = Cine.TextSoft, lineHeight = 13.sp)
            }
        }
        if (ad.additionalInfo.isNotBlank()) Text(ad.additionalInfo, fontSize = 9.sp, color = Cine.TextMuted)
        Text(ad.buttonText.ifBlank { "Abrir" }, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = if (focused) Cine.OnLight else Cine.Text,
            modifier = Modifier.background(if (focused) Cine.Text else Cine.Raised, RoundedCornerShape(5.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
