package com.ashe.cinemabox.library

import com.ashe.cinemabox.telegram.TdClient
import com.ashe.cinemabox.telegram.TdException
import com.ashe.cinemabox.telegram.floodWaitSeconds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

enum class MediaKind(val title: String) {
    Video("Videos"), Photo("Fotos"), Audio("Audio");

    fun filter(): TdApi.SearchMessagesFilter = when (this) {
        Video -> TdApi.SearchMessagesFilterVideo()
        Photo -> TdApi.SearchMessagesFilterPhoto()
        Audio -> TdApi.SearchMessagesFilterAudio()
    }
}

data class MediaItem(
    val chatId: Long,
    val messageId: Long,
    val kind: MediaKind,
    /** The video, the largest photo size or the audio track. */
    val fileId: Int,
    val title: String,
    val durationSeconds: Int?,
    val date: Int,
    val sizeBytes: Long,
    val thumbnailFileId: Int?,
    val minithumbnail: ByteArray?,
    /** Re-encoded qualities TDLib offers for some videos; empty for most. */
    val alternatives: List<VideoSource> = emptyList(),
    /** Hashtags of the caption, shown as labels. */
    val tags: List<String> = emptyList(),
    /** Caption text after the title line. */
    val description: String = "",
    /** Telegram album shared with the post's other photos and videos; 0 when sent alone. */
    val albumId: Long = 0,
    /** Performer of an audio track; empty when the file has no such tag. */
    val artist: String = "",
    /** False in channels with "Protect content": the file may be streamed but never kept whole. */
    val canBeSaved: Boolean = false,
    /** TDLib remote.unique_id of the file: stable across sessions, keys identified music. */
    val uniqueId: String = "",
    /** True when the audio file carries its own title or performer tags. */
    val tagged: Boolean = false,
) {
    /** Long videos are listed as movies; their cover is the album photo when the post has one. */
    val isMovie get() = kind == MediaKind.Video && (durationSeconds ?: 0) >= MOVIE_MIN_SECONDS

    companion object {
        const val MOVIE_MIN_SECONDS = 30 * 60
    }
}

/**
 * The whole post around a video: in an album the caption lives on only one of its items,
 * so [title], [tags] and [description] come from whichever item carries it.
 */
data class PostDetails(
    val channel: Channel?,
    val title: String?,
    val tags: List<String>,
    val description: String,
    val viewCount: Int,
    /** Photos, videos and audio sent together with the item, in post order; empty if it is alone. */
    val album: List<MediaItem>,
)

/** [nextFromMessageId] is 0 once the chat has no older media of that kind. */
data class MediaPage(val items: List<MediaItem>, val nextFromMessageId: Long)

data class Account(val savedMessagesChatId: Long, val initial: String, val name: String, val maskedPhone: String)

data class Channel(val chatId: Long, val title: String, val isPublic: Boolean, val memberCount: Int, val photoFileId: Int? = null)

data class SponsoredAd(
    val chatId: Long,
    val messageId: Long,
    val isRecommended: Boolean,
    val canBeReported: Boolean,
    val title: String,
    val text: String,
    val buttonText: String,
    val url: String,
    val sponsorInfo: String,
    val additionalInfo: String,
    /** Image shown on the card: a photo size or the video/animation thumbnail. */
    val imageFileId: Int?,
    /** Full media file; TDLib requires it to be downloaded before the ad is shown. */
    val mediaFileId: Int?,
)

sealed interface ReportOutcome {
    data object Done : ReportOutcome
    data class ChooseOption(val title: String, val options: List<TdApi.ReportOption>) : ReportOutcome
    data class Message(val text: String) : ReportOutcome
}

class LibraryRepository(private val td: TdClient) {
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var blockedUntilMillis = 0L

    /** Honors FLOOD_WAIT: while the server-imposed wait runs, requests fail locally instead of retrying. */
    private suspend fun <T : TdApi.Object> call(request: TdApi.Function<T>): T {
        val remaining = blockedUntilMillis - now()
        if (remaining > 0) throw TdException(429, "FLOOD_WAIT_${(remaining + 999) / 1000}")
        try {
            return td.call(request)
        } catch (e: TdException) {
            floodWaitSeconds(e.message)?.let { blockedUntilMillis = now() + it * 1000 }
            throw e
        }
    }

    suspend fun account(): Account {
        val me = call(TdApi.GetMe())
        // Saved Messages is the private chat with yourself; TDLib must know it before searching.
        call(TdApi.CreatePrivateChat(me.id, false))
        val initial = me.firstName.trim().take(1).uppercase().ifEmpty { "·" }
        val name = listOf(me.firstName, me.lastName).filter { it.isNotBlank() }.joinToString(" ")
        return Account(me.id, initial, name, maskPhone(me.phoneNumber))
    }

    suspend fun page(chatId: Long, kind: MediaKind, fromMessageId: Long): MediaPage {
        val found = call(
            TdApi.SearchChatMessages(chatId, null, "", null, fromMessageId, 0, PAGE_SIZE, kind.filter())
        )
        val items = found.messages.mapNotNull { it.toMediaItem() }
        return MediaPage(if (kind == MediaKind.Video) withCovers(chatId, found.messages, items) else items, found.nextFromMessageId)
    }

    /**
     * Gives movies posted in an album the album's photo as cover (and its caption when the video has none).
     * One photo search per page, only when the page has such movies; a failure leaves the plain items.
     */
    private suspend fun withCovers(chatId: Long, messages: Array<TdApi.Message>, items: List<MediaItem>): List<MediaItem> {
        val albums = items.filter { it.isMovie && it.albumId != 0L }.map { it.albumId }.toSet()
        if (albums.isEmpty()) return items
        val newest = items.filter { it.albumId in albums }.maxOf { it.messageId }
        val photos = try {
            // A negative offset also reaches covers sent right after the video in the same album.
            call(TdApi.SearchChatMessages(chatId, null, "", null, newest, -ALBUM_SPAN, COVER_SCAN, TdApi.SearchMessagesFilterPhoto()))
                .messages
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { return items }
        val covers = photos.filter { it.mediaAlbumId in albums }.sortedBy { it.id }.distinctBy { it.mediaAlbumId }
            .associateBy { it.mediaAlbumId }
        val uncaptioned = messages.filter { caption(it)?.text.isNullOrBlank() }.map { it.id }.toSet()
        return items.map { item ->
            val cover = covers[item.albumId] ?: return@map item
            val photo = (cover.content as TdApi.MessagePhoto).photo
            val sizes = photo.sizes.sortedBy { it.width }
            val shown = sizes.firstOrNull { it.width >= COVER_WIDTH } ?: sizes.lastOrNull() ?: return@map item
            val withImage = item.copy(thumbnailFileId = shown.photo.id, minithumbnail = photo.minithumbnail?.data ?: item.minithumbnail)
            val text = caption(cover)?.takeIf { it.text.isNotBlank() }
            if (item.messageId !in uncaptioned || text == null) withImage
            else withImage.copy(title = title(text, "", item.date), tags = hashtags(text), description = description(text))
        }
    }

    suspend fun message(chatId: Long, messageId: Long): MediaItem? = call(TdApi.GetMessage(chatId, messageId)).toMediaItem()

    /** Loaded once when Detail opens; reads the album neighbours but never marks anything as viewed. */
    suspend fun post(item: MediaItem): PostDetails {
        val message = call(TdApi.GetMessage(item.chatId, item.messageId))
        val album = if (message.mediaAlbumId == 0L) listOf(message) else {
            // An album holds at most 10 items, so 9 on each side always covers it.
            call(TdApi.GetChatHistory(item.chatId, item.messageId, -ALBUM_SPAN, ALBUM_SPAN * 2 + 1, false))
                .messages.filter { it.mediaAlbumId == message.mediaAlbumId }
                .plus(message).distinctBy { it.id }.sortedBy { it.id }
        }
        val caption = caption(message)?.takeIf { it.text.isNotBlank() }
            ?: album.firstNotNullOfOrNull { other -> caption(other)?.takeIf { it.text.isNotBlank() } }
        val channel = try { channel(item.chatId) } catch (e: TdException) { if (e.code == 429) throw e else null }
        return PostDetails(
            channel,
            caption?.let { title(it, "", message.date) }?.takeIf { caption(message)?.text.isNullOrBlank() },
            hashtags(caption), description(caption),
            message.interactionInfo?.viewCount ?: 0,
            album.takeIf { it.size > 1 }?.mapNotNull { it.toMediaItem() }.orEmpty(),
        )
    }

    private fun caption(message: TdApi.Message): TdApi.FormattedText? = when (val c = message.content) {
        is TdApi.MessageVideo -> c.caption
        is TdApi.MessagePhoto -> c.caption
        is TdApi.MessageAudio -> c.caption
        is TdApi.MessageDocument -> c.caption
        else -> null
    }

    /** Text or #hashtag search inside one chat; callers debounce typing (500 ms) before calling. */
    suspend fun search(chatId: Long, kind: MediaKind, query: String): List<MediaItem> =
        call(TdApi.SearchChatMessages(chatId, null, query, null, 0, 0, SEARCH_SIZE, kind.filter()))
            .messages.mapNotNull { it.toMediaItem() }

    /** Channels in the main chat list; only for the library picker in Settings. */
    suspend fun channels(): List<Channel> {
        try { call(TdApi.LoadChats(TdApi.ChatListMain(), CHANNEL_SCAN_LIMIT)) }
        catch (e: TdException) { if (e.code != 404) throw e }
        val ids = call(TdApi.GetChats(TdApi.ChatListMain(), CHANNEL_SCAN_LIMIT)).chatIds
        return ids.toList().mapNotNull { id ->
            try { channel(id) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: TdException) { if (e.code == 429) throw e else null }
        }
    }

    suspend fun channel(chatId: Long): Channel? {
        val chat = try { call(TdApi.GetChat(chatId)) } catch (e: TdException) {
            if (e.code != 400 && e.code != 404) throw e
            // On a cold start GetChat cannot resolve chats not loaded into TDLib yet.
            var restored: TdApi.Chat? = null
            for (list in listOf(TdApi.ChatListMain(), TdApi.ChatListArchive())) {
                while (restored == null) {
                    try { call(TdApi.LoadChats(list, CHANNEL_SCAN_LIMIT)) }
                    catch (loadError: TdException) { if (loadError.code == 404) break else throw loadError }
                    restored = try { call(TdApi.GetChat(chatId)) } catch (lookup: TdException) {
                        if (lookup.code != 400 && lookup.code != 404) throw lookup
                        null
                    }
                }
                if (restored != null) break
            }
            restored ?: throw e
        }
        val type = chat.type as? TdApi.ChatTypeSupergroup ?: return null
        if (!type.isChannel) return null
        val group = call(TdApi.GetSupergroup(type.supergroupId))
        val isPublic = group.usernames?.activeUsernames?.isNotEmpty() == true
        return Channel(chat.id, chat.title, isPublic, group.memberCount, chat.photo?.big?.id)
    }

    /** First sponsored message of a channel, or null; private channels normally have none. */
    suspend fun sponsored(chatId: Long): SponsoredAd? {
        val message = call(TdApi.GetChatSponsoredMessages(chatId)).messages.firstOrNull() ?: return null
        val (text, image, media) = when (val c = message.content) {
            is TdApi.MessageText -> Triple(c.text.text, null, null)
            is TdApi.MessagePhoto -> {
                val sizes = c.photo.sizes.sortedBy { it.width }
                val shown = sizes.firstOrNull { it.width >= THUMBNAIL_WIDTH } ?: sizes.lastOrNull()
                Triple(c.caption.text, shown?.photo?.id, shown?.photo?.id)
            }
            is TdApi.MessageVideo -> Triple(c.caption.text, c.video.thumbnail?.file?.id, c.video.video.id)
            is TdApi.MessageAnimation -> Triple(c.caption.text, c.animation.thumbnail?.file?.id, c.animation.animation.id)
            else -> Triple("", null, null)
        }
        return SponsoredAd(
            chatId, message.messageId, message.isRecommended, message.canBeReported,
            message.title, text, message.buttonText, message.sponsor.url, message.sponsor.info,
            message.additionalInfo, image, media,
        )
    }

    /** TDLib registers sponsored views through viewMessages; call once the whole text is on screen. */
    fun registerSponsoredView(ad: SponsoredAd) {
        background.launch {
            runCatching { call(TdApi.ViewMessages(ad.chatId, longArrayOf(ad.messageId), TdApi.MessageSourceOther(), false)) }
        }
    }

    fun registerSponsoredClick(ad: SponsoredAd) {
        background.launch {
            runCatching { call(TdApi.ClickChatSponsoredMessage(ad.chatId, ad.messageId, false, false)) }
        }
    }

    suspend fun reportSponsored(ad: SponsoredAd, optionId: ByteArray): ReportOutcome =
        when (val result = call(TdApi.ReportChatSponsoredMessage(ad.chatId, ad.messageId, optionId))) {
            is TdApi.ReportSponsoredResultOk -> ReportOutcome.Done
            is TdApi.ReportSponsoredResultOptionRequired -> ReportOutcome.ChooseOption(result.title, result.options.toList())
            is TdApi.ReportSponsoredResultAdsHidden -> ReportOutcome.Message("Los anuncios se ocultaron para tu cuenta.")
            is TdApi.ReportSponsoredResultPremiumRequired -> ReportOutcome.Message("Esta opción requiere Telegram Premium.")
            else -> ReportOutcome.Message("No se pudo enviar el reporte. Inténtalo más tarde.")
        }

    /**
     * Opening a video counts as viewing its message, like the official app: the chat is opened
     * while it plays so the view is registered in the channel. Listed rows are never marked.
     */
    fun startViewing(item: MediaItem) {
        background.launch {
            runCatching {
                call(TdApi.OpenChat(item.chatId))
                call(TdApi.ViewMessages(item.chatId, longArrayOf(item.messageId), TdApi.MessageSourceChatHistory(), false))
            }
        }
    }

    fun stopViewing(item: MediaItem) {
        background.launch { runCatching { call(TdApi.CloseChat(item.chatId)) } }
    }

    private fun TdApi.Message.toMediaItem(): MediaItem? = when (val c = content) {
        is TdApi.MessageVideo -> MediaItem(
            chatId, id, MediaKind.Video, c.video.video.id,
            title(c.caption, c.video.fileName, date),
            c.video.duration, date, c.video.video.size(),
            c.video.thumbnail?.file?.id, c.video.minithumbnail?.data,
            c.alternativeVideos.map { VideoSource(it.video.id, it.video.size(), it.width, it.height, it.codec) },
            hashtags(c.caption), description(c.caption), mediaAlbumId,
        )
        is TdApi.MessagePhoto -> {
            val sizes = c.photo.sizes.sortedBy { it.width }
            val thumb = sizes.firstOrNull { it.width >= THUMBNAIL_WIDTH } ?: sizes.lastOrNull()
            val full = sizes.lastOrNull() ?: return null
            MediaItem(
                chatId, id, MediaKind.Photo, full.photo.id,
                title(c.caption, "", date),
                null, date, full.photo.size(),
                thumb?.photo?.id, c.photo.minithumbnail?.data,
                tags = hashtags(c.caption), description = description(c.caption),
            )
        }
        is TdApi.MessageAudio -> {
            // Title and performer come from the file's ID3 tags; covers fall back to Telegram's external ones.
            val cover = c.audio.albumCoverThumbnail ?: c.audio.externalAlbumCovers.maxByOrNull { it.width }
            MediaItem(
                chatId, id, MediaKind.Audio, c.audio.audio.id,
                c.audio.title.trim().ifBlank { title(c.caption, c.audio.fileName, date) },
                c.audio.duration, date, c.audio.audio.size(),
                cover?.file?.id, c.audio.albumCoverMinithumbnail?.data,
                tags = hashtags(c.caption), description = description(c.caption),
                artist = c.audio.performer.trim(), canBeSaved = canBeSaved,
                uniqueId = c.audio.audio.remote.uniqueId.orEmpty(),
                tagged = c.audio.title.isNotBlank() || c.audio.performer.isNotBlank(),
            )
        }
        else -> null
    }

    private fun title(caption: TdApi.FormattedText?, fileName: String, date: Int): String =
        caption?.text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            ?: fileName.substringBeforeLast('.').trim().takeIf { it.isNotEmpty() }
            ?: formatDate(date)

    private fun hashtags(caption: TdApi.FormattedText?): List<String> =
        HASHTAG.findAll(caption?.text.orEmpty()).map { it.value }.distinct().take(MAX_TAGS).toList()

    /** Lines after the first non-empty one, without the hashtag-only lines already shown as tags. */
    private fun description(caption: TdApi.FormattedText?): String {
        val lines = caption?.text.orEmpty().lines().map { it.trim() }.dropWhile { it.isEmpty() }
        return lines.drop(1).filterNot { line -> line.isNotEmpty() && line.split(' ').all { it.startsWith("#") } }
            .joinToString("\n").trim()
    }

    private fun maskPhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        if (digits.length < 6) return ""
        return "+" + digits.take(3) + " " + digits[3] + "•".repeat(digits.length - 6) + " " + digits.takeLast(2)
    }

    private fun TdApi.File.size() = if (size > 0) size else expectedSize

    private fun now() = System.nanoTime() / 1_000_000

    private companion object {
        /** Well under the 50-per-request cap; pages load only when the user reaches a row's end. */
        const val PAGE_SIZE = 30
        const val SEARCH_SIZE = 20
        const val MAX_TAGS = 6
        val HASHTAG = Regex("#[\\p{L}\\p{N}_]+")
        const val CHANNEL_SCAN_LIMIT = 200
        const val THUMBNAIL_WIDTH = 320
        const val ALBUM_SPAN = 9
        /** Photos read to find covers for one page of videos; the 50-per-request cap. */
        const val COVER_SCAN = 50
        /** Covers fill the whole backdrop, so a larger size than row thumbnails. */
        const val COVER_WIDTH = 800
    }
}

fun formatDate(unixSeconds: Int): String =
    SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("es")).format(Date(unixSeconds * 1000L)).replace(".", "")

fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "$h h ${"%02d".format(m)} min"
        m > 0 -> "$m min"
        else -> "${seconds}s"
    }
}

fun formatCount(count: Int): String = when {
    count >= 1_000_000 -> String.format(Locale.forLanguageTag("es"), "%.1f M", count / 1_000_000.0)
    count >= 1_000 -> String.format(Locale.forLanguageTag("es"), "%.1f mil", count / 1_000.0)
    else -> count.toString()
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.forLanguageTag("es"), "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes shr 20} MB"
    else -> "${(bytes shr 10).coerceAtLeast(1)} KB"
}
