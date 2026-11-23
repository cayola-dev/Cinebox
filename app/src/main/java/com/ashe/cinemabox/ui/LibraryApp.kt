package com.ashe.cinemabox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ashe.cinemabox.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private enum class Origin { Home, Search }
private sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Music : Screen
    class Detail(val item: MediaItem, val label: String, val origin: Origin, val queue: List<MediaItem>) : Screen
    class Player(val detail: Detail, val start: Long) : Screen
    /** [returnTo] is the Detail an album photo was opened from; Back goes there instead of [origin]. */
    class Photo(val photos: List<MediaItem>, val index: Int, val origin: Origin, val returnTo: Detail? = null) : Screen
    class Sponsored(val ad: SponsoredAd) : Screen
    class Settings(val channelsBefore: Set<Long>) : Screen
}

@Composable
fun LibraryApp(library: LibraryRepository, files: FileRepository, settings: LibrarySettings,
               identifier: com.ashe.cinemabox.music.MusicIdentifier, onLogOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val home = remember { HomeModel(library, files, settings, scope).also { it.reload() } }
    val nav = remember { V3Navigation() }
    val search = remember { SearchModel() }
    val music = remember { MusicSession(context.applicationContext, files, library, identifier) }
    val accountId = home.account?.savedMessagesChatId
    val history = remember(accountId) { accountId?.let { PlaybackHistory(context, it) } }
    val records = history?.records?.collectAsState()?.value.orEmpty()
    SideEffect { music.history = history }
    DisposableEffect(music) { onDispose { music.stop() } }
    LaunchedEffect(music) { while (true) { music.tick(); delay(500) } }
    var restored by remember(accountId) { mutableStateOf(emptyList<MediaItem>()) }
    // Resolve stored IDs through TDLib: file IDs are session-specific and must never be persisted.
    val resumeIds = records.filter { resumePosition(it) > 0 || it.favorite || it.plays > 0 }.take(40).map { it.chatId to it.messageId }
    LaunchedEffect(accountId, resumeIds) {
        val found = mutableListOf<MediaItem>()
        for ((chat, message) in resumeIds) {
            if (chat != accountId && chat !in settings.channelIds) continue
            try { library.message(chat, message)?.takeIf { it.kind != MediaKind.Photo }?.let(found::add) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Deleted or inaccessible messages are omitted. */ }
        }
        restored = found
    }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var photoIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { files.trimCache() }
    fun goHome() { home.focused?.let(home::returnTo); screen = Screen.Home }
    fun openItem(list: List<MediaItem>, index: Int, origin: Origin) {
        val item = list[index]
        nav.restoreKey = "${item.chatId}:${item.messageId}"
        settings.recordUse(item.chatId)
        when (item.kind) {
            MediaKind.Photo -> {
                val photos = list.filter { it.kind == MediaKind.Photo }
                photoIndex = photos.indexOf(item)
                screen = Screen.Photo(photos, photoIndex, origin)
            }
            MediaKind.Audio -> { music.start(list, item); screen = Screen.Music }
            MediaKind.Video -> screen = Screen.Detail(item, home.rowOf(item)?.label ?: item.kind.title, origin, list.filter { it.kind == MediaKind.Video })
        }
    }
    fun backTo(origin: Origin, item: MediaItem) {
        if (origin == Origin.Search) { search.pendingFocusId = item.messageId; screen = Screen.Search }
        else { home.returnTo(item); screen = Screen.Home }
    }
    fun play(detail: Screen.Detail, start: Long) {
        music.suspendForVideo()
        screen = Screen.Player(detail, start)
    }
    val current = screen
    Box(Modifier.fillMaxSize()) {
        if ((current is Screen.Home && nav.tab < 4) || current is Screen.Detail) {
            StageBackdrop(if (current is Screen.Detail) current.item else home.focused, if (current is Screen.Home) home.wipe else Wipe.None, files,
                if (current is Screen.Detail) androidx.compose.ui.unit.DpOffset(0.dp, (-10).dp) else nav.parallax)
            if (current is Screen.Detail) StageShades(.4f, 1f, .55f)
        }
        when (current) {
            Screen.Home -> V3Home(home, files, nav, history, restored, music,
                onOpen = { list, index -> openItem(list, index, Origin.Home) },
                onAd = { library.registerSponsoredClick(it); screen = Screen.Sponsored(it) },
                onSearch = { screen = Screen.Search },
                onSettings = { music.stop(); screen = Screen.Settings(settings.channelIds) },
                onMusic = { screen = Screen.Music })
            is Screen.Detail -> {
                BackHandler { backTo(current.origin, current.item) }
                DetailScreen(current.item, current.label, library, files, resumePosition(history?.record(current.item)),
                    onStart = { play(current, 0) },
                    onOpenAlbum = { album, index ->
                        val target = album[index]
                        when (target.kind) {
                            MediaKind.Photo -> {
                                val photos = album.filter { it.kind == MediaKind.Photo }
                                photoIndex = photos.indexOf(target)
                                screen = Screen.Photo(photos, photoIndex, current.origin, returnTo = current)
                            }
                            MediaKind.Video -> screen = Screen.Detail(target, current.label, current.origin,
                                album.filter { it.kind == MediaKind.Video })
                            MediaKind.Audio -> { music.start(album, target); screen = Screen.Music }
                        }
                    },
                    onPlay = { play(current, resumePosition(history?.record(current.item))) })
            }
            is Screen.Player -> key(current.detail.item.chatId, current.detail.item.messageId) {
                val item = current.detail.item
                val close = { screen = current.detail }
                BackHandler(onBack = close)
                DisposableEffect(item.chatId, item.messageId) {
                    library.startViewing(item)
                    onDispose { library.stopViewing(item) }
                }
                val queue = current.detail.queue
                val index = queue.indexOfFirst { it.chatId == item.chatId && it.messageId == item.messageId }
                fun adjacent(offset: Int) {
                    val target = queue.getOrNull(index + offset) ?: return
                    screen = Screen.Player(Screen.Detail(target, home.rowOf(target)?.label ?: current.detail.label, current.detail.origin, queue), 0)
                }
                PlayerScreen(item, current.detail.label, files, close, current.start,
                    onProgress = { position, duration -> history?.save(item, position, duration) },
                    onPrevious = if (index > 0) ({ adjacent(-1) }) else null,
                    onNext = if (index in 0 until queue.lastIndex) ({ adjacent(1) }) else null)
            }
            Screen.Music -> {
                BackHandler { goHome() }
                if (music.item != null) MusicPlayerScreen(music, files, history,
                    home.rowOf(music.item!!)?.let { if (it.isChannel) it.title else "Mensajes guardados" } ?: "Música")
                else LaunchedEffect(Unit) { goHome() }
            }
            is Screen.Photo -> {
                BackHandler { current.returnTo?.let { screen = it } ?: backTo(current.origin, current.photos[photoIndex]) }
                PhotoViewer(current.photos, current.index, files) { photoIndex = it }
            }
            is Screen.Sponsored -> {
                BackHandler { goHome() }
                SponsoredScreen(current.ad, library, onBack = ::goHome)
            }
            is Screen.Settings -> {
                BackHandler {
                    if (settings.channelIds != current.channelsBefore) { screen = Screen.Home; home.reload() } else goHome()
                }
                SettingsScreen(library, files, settings, identifier, home.account, onLogOut)
            }
            Screen.Search -> {
                BackHandler { goHome() }
                SearchScreen(search, home, library, files) { results, index -> openItem(results, index, Origin.Search) }
            }
        }
    }
    // Player ownership changes immediately; exit animations must never keep an old decoder alive.
    LaunchedEffect(current is Screen.Player) { if (current !is Screen.Player) music.resumeFromVideo() }
}
