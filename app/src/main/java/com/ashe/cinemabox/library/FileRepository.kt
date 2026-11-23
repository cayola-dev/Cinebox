package com.ashe.cinemabox.library

import android.graphics.BitmapFactory
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.datasource.DataSource
import com.ashe.cinemabox.player.TdDataSource
import com.ashe.cinemabox.telegram.TdClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

/** One downloadable encoding of a video: the original or a TDLib alternative quality. */
data class VideoSource(val fileId: Int, val sizeBytes: Long, val width: Int, val height: Int, val codec: String?)

data class FileProgress(
    val downloadOffset: Long,
    val prefixEnd: Long,
    val size: Long,
    val downloadedSize: Long,
    val active: Boolean,
    val completed: Boolean,
)

class FileRepository(private val td: TdClient) {
    private val thumbnails = object : LruCache<Int, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: ImageBitmap) = value.width * value.height * 4
    }
    private val playbackLock = Any()
    private val activeFiles = mutableMapOf<Int, Int>()
    private var maintenance: Job? = null
    fun openPlayback(fileId: Int) = synchronized(playbackLock) {
        activeFiles[fileId] = (activeFiles[fileId] ?: 0) + 1
    }
    private fun hasPlayback() = synchronized(playbackLock) { activeFiles.isNotEmpty() }
    /** New readers wait for earlier cancellation/eviction to finish, on Media3's loader thread. */
    private fun enqueueMaintenance(action: suspend () -> Unit): Deferred<Unit> = synchronized(playbackLock) {
        val previous = maintenance
        background.async {
            previous?.join()
            action()
        }.also { maintenance = it }
    }
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Last network speed measured during playback, in bytes per second; used to pick a quality. */
    @Volatile var lastMeasuredSpeed: Double? = null

    suspend fun thumbnail(fileId: Int): ImageBitmap? {
        thumbnails.get(fileId)?.let { return it }
        val path = downloadCompletely(fileId, THUMBNAIL_PRIORITY) ?: return null
        val bitmap = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        } ?: return null
        thumbnails.put(fileId, bitmap)
        return bitmap
    }

    /** Full photo, downsampled so a large original never exceeds what the screen can show. */
    suspend fun photo(fileId: Int, maxWidth: Int, maxHeight: Int): ImageBitmap? {
        val path = downloadCompletely(fileId, PLAYBACK_PRIORITY) ?: return null
        return withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth || bounds.outHeight / (sample * 2) >= maxHeight) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }

    /** Downloads the ad's full media first (TDLib requires it before showing), then its card image. */
    suspend fun sponsoredImage(ad: SponsoredAd): ImageBitmap? {
        ad.mediaFileId?.let { downloadCompletely(it, THUMBNAIL_PRIORITY) ?: return null }
        return ad.imageFileId?.let { thumbnail(it) }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun dataSourceFactory(): DataSource.Factory = DataSource.Factory {
        val source = TdDataSource(td)
        object : DataSource by source {
            override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                val barrier = synchronized(playbackLock) { maintenance }
                try { runBlocking { barrier?.join() } }
                catch (_: InterruptedException) { throw java.io.InterruptedIOException() }
                return source.open(dataSpec)
            }
        }
    }

    fun progress(fileId: Int): Flow<FileProgress> =
        td.fileUpdates.filter { it.id == fileId }.map {
            FileProgress(
                it.local.downloadOffset, it.local.downloadOffset + it.local.downloadedPrefixSize, it.size,
                it.local.downloadedSize, it.local.isDownloadingActive, it.local.isDownloadingCompleted,
            )
        }

    /** Current state first (from TDLib), then every download update. */
    fun watch(fileId: Int): Flow<FileProgress> = kotlinx.coroutines.flow.flow {
        val file = td.call(TdApi.GetFile(fileId))
        emit(FileProgress(
            file.local.downloadOffset, file.local.downloadOffset + file.local.downloadedPrefixSize, file.size,
            file.local.downloadedSize, file.local.isDownloadingActive, file.local.isDownloadingCompleted,
        ))
        emitAll(progress(fileId))
    }

    suspend fun downloadedAhead(fileId: Int, offset: Long): Long =
        td.call(TdApi.GetFileDownloadedPrefixSize(fileId, offset)).size

    /** Path of the downloaded part of a file, or null while nothing is on disk. */
    suspend fun localPath(fileId: Int): String? =
        td.call(TdApi.GetFile(fileId)).local.path.takeIf { it.isNotEmpty() }

    /** Exact size of downloaded files; the "fast" variant is approximate and badly undercounts. */
    suspend fun cacheSize(): Long = td.call(TdApi.GetStorageStatistics(0)).size

    /**
     * Picks the best encoding the measured speed can sustain. Without a measurement, or when the
     * message has no alternatives, the original is used.
     */
    fun chooseSource(item: MediaItem): VideoSource {
        val original = VideoSource(item.fileId, item.sizeBytes, 0, 0, null)
        val speed = lastMeasuredSpeed ?: return original
        val duration = item.durationSeconds?.takeIf { it > 0 } ?: return original
        val candidates = listOf(original) + item.alternatives.filter { it.sizeBytes > 0 && canDecode(it) }
        val fitting = candidates.filter { it.sizeBytes / duration <= speed * SPEED_MARGIN }
        return fitting.maxByOrNull { it.sizeBytes } ?: candidates.minBy { it.sizeBytes }
    }

    /** Stops a playback download and trims the cache; only safe once no player has the file open. */
    fun closePlayback(fileId: Int) {
        synchronized(playbackLock) {
            val count = (activeFiles[fileId] ?: 1) - 1
            if (count > 0) activeFiles[fileId] = count else activeFiles.remove(fileId)
            enqueueMaintenance {
                val reused = synchronized(playbackLock) { activeFiles.containsKey(fileId) }
                if (!reused) runCatching { td.call(TdApi.CancelDownloadFile(fileId, false)) }
                trimCacheNow()
            }
        }
    }

    /** Deletes downloaded media; only offered in Settings, where no player has a file open. */
    suspend fun clearCache() {
        val task = synchronized(playbackLock) {
            check(activeFiles.isEmpty()) { "Detén la reproducción antes de limpiar la caché." }
            enqueueMaintenance {
              td.call(
            TdApi.OptimizeStorage(
                0, 0, 0, 0,
                arrayOf(TdApi.FileTypeVideo(), TdApi.FileTypeAudio(), TdApi.FileTypePhoto()),
                longArrayOf(), longArrayOf(), false, 0,
            )
              )
              thumbnails.evictAll()
            }
        }
        task.await()
    }

    fun trimCache() {
        enqueueMaintenance { trimCacheNow() }
    }

    private suspend fun trimCacheNow() {
        runCatching {
            if (hasPlayback()) return
            if (cacheSize() <= CACHE_LIMIT_BYTES) return
            if (hasPlayback()) return
            // Size-only limit: ttl and count are effectively disabled so nothing else gets deleted.
            td.call(
                TdApi.OptimizeStorage(
                    CACHE_LIMIT_BYTES, Int.MAX_VALUE, Int.MAX_VALUE, 0,
                    arrayOf(TdApi.FileTypeVideo(), TdApi.FileTypeAudio(), TdApi.FileTypePhoto()),
                    longArrayOf(), longArrayOf(), false, 0,
                )
            )
        }
    }

    private suspend fun downloadCompletely(fileId: Int, priority: Int): String? {
        val file = try {
            td.call(TdApi.DownloadFile(fileId, priority, 0, 0, true))
        } catch (cancelled: CancellationException) {
            // Leaving the screen should not keep queued downloads alive.
            withContext(NonCancellable) {
                runCatching { td.call(TdApi.CancelDownloadFile(fileId, true)) }
            }
            throw cancelled
        }
        return file.local.path.takeIf { file.local.isDownloadingCompleted && it.isNotEmpty() }
    }

    private fun canDecode(source: VideoSource): Boolean {
        val mime = when (source.codec?.lowercase()) {
            "h264", "avc" -> MediaFormat.MIMETYPE_VIDEO_AVC
            "h265", "hevc" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            "av1", "av01" -> MediaFormat.MIMETYPE_VIDEO_AV1
            "vp9" -> MediaFormat.MIMETYPE_VIDEO_VP9
            else -> return false
        }
        val format = MediaFormat.createVideoFormat(mime, source.width.coerceAtLeast(1), source.height.coerceAtLeast(1))
        return runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null }
            .getOrDefault(false)
    }

    companion object {
        const val THUMBNAIL_PRIORITY = 1
        const val PLAYBACK_PRIORITY = 32
        /** Default cap; Settings (phase 5) will make it configurable. */
        const val CACHE_LIMIT_BYTES = 5L * 1024 * 1024 * 1024
        private const val SPEED_MARGIN = 0.8
    }
}
