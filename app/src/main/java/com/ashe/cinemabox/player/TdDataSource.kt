package com.ashe.cinemabox.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.ashe.cinemabox.telegram.TdClient
import com.ashe.cinemabox.telegram.TdException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

/**
 * Streams a TDLib file while it downloads. Uri: `tdfile://<fileId>?bitrate=<bytes per second>`.
 *
 * Downloads a sliding window ahead of the read position instead of the whole file. ExoPlayer stops
 * reading once its own buffer (see [StreamingLoadControl]) is full, so while paused or well
 * buffered the window stops growing and the network goes idle.
 */
@OptIn(UnstableApi::class)
class TdDataSource(private val td: TdClient) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var fileId = 0
    private var position = 0L
    private var bytesRemaining = 0L
    private var size = 0L
    private var window = 0L
    /** Download to the end of the file instead of a sliding window (only for content that may be saved). */
    private var downloadAll = false
    private var lowWater = 0L
    /** Bytes known to be downloaded contiguously from [position]; avoids a TDLib query per read. */
    private var known = 0L
    private var lastQueryPosition = -1L
    private var requestedEnd = 0L
    private var opened = false
    private var raf: RandomAccessFile? = null
    private var rafPath: String? = null
    private var scope: CoroutineScope? = null
    private val changed = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var latest: TdApi.File? = null
    @Volatile private var closed = false

    override fun open(dataSpec: DataSpec): Long = try {
        openFile(dataSpec)
    } catch (error: Exception) {
        close()
        throw error
    }

    private fun openFile(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        fileId = dataSpec.uri.host?.toIntOrNull() ?: throw IOException("Unsupported uri")
        val bitrate = dataSpec.uri.getQueryParameter("bitrate")?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_BITRATE
        downloadAll = dataSpec.uri.getQueryParameter("full") == "1"
        window = (bitrate * WINDOW_SECONDS).coerceIn(MIN_WINDOW, MAX_WINDOW)
        lowWater = (bitrate * REFILL_BELOW_SECONDS).coerceIn(MIN_LOW_WATER, window / 2)
        position = dataSpec.position
        closed = false
        known = 0
        lastQueryPosition = -1
        requestedEnd = 0
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { watch ->
            watch.launch {
                td.fileUpdates.filter { it.id == fileId }.collect {
                    latest = it
                    changed.trySend(Unit)
                }
            }
        }
        latest = blocking { td.call(TdApi.GetFile(fileId)) }
        size = latest!!.size
        if (size > 0 && position > size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        // A seek lands here too: reuse what is cached at the target, then request only what is missing.
        refresh(startChunk = (bitrate * START_SECONDS).coerceAtLeast(START_CHUNK))
        val available = if (size > 0) size - position else C.LENGTH_UNSET.toLong()
        bytesRemaining = when {
            dataSpec.length == C.LENGTH_UNSET.toLong() -> available
            available == C.LENGTH_UNSET.toLong() -> dataSpec.length
            else -> minOf(dataSpec.length, available)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        while (true) {
            if (closed || Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val stale = known < lowWater && position - lastQueryPosition >= REQUERY_STEP
            if (known <= 0 || stale) refresh(window)
            val path = latest?.local?.path.orEmpty()
            if (known > 0 && path.isNotEmpty()) {
                var wanted = minOf(length.toLong(), known)
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) wanted = minOf(wanted, bytesRemaining)
                val file = fileAt(path)
                file.seek(position)
                val read = file.read(buffer, offset, wanted.toInt())
                if (read > 0) {
                    position += read
                    known -= read
                    if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
                    bytesTransferred(read)
                    return read
                }
                known = 0
            }
            val local = latest?.local
            if (local != null && local.isDownloadingCompleted) return C.RESULT_END_OF_INPUT
            if (local != null && !local.canBeDownloaded && !local.isDownloadingActive) {
                throw IOException("File can no longer be downloaded")
            }
            val update = blocking { withTimeoutOrNull(WAIT_MILLIS) { changed.receive() } }
            if (update == null) latest = blocking { td.call(TdApi.GetFile(fileId)) }
            lastQueryPosition = -1 // Force a fresh prefix query after waiting.
        }
    }

    /** Re-reads the cached prefix at [position] and extends the download if it runs short. */
    private fun refresh(startChunk: Long) {
        known = blocking { td.call(TdApi.GetFileDownloadedPrefixSize(fileId, position)) }.size
        lastQueryPosition = position
        val local = latest?.local
        if (local?.isDownloadingCompleted == true || (known >= lowWater && !downloadAll)) return
        val start = position + known
        val end = if (downloadAll && size > 0) size
            else if (size > 0) minOf(start + startChunk, size) else start + startChunk
        if (end <= start) return
        // Skip if an active request already covers this range; otherwise replace it (seeks included).
        if (local?.isDownloadingActive == true && requestedEnd >= end && local.downloadOffset <= start) return
        latest = blocking { td.call(TdApi.DownloadFile(fileId, PRIORITY, start, end - start, false)) }
        requestedEnd = end
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closed = true
        changed.trySend(Unit)
        scope?.cancel()
        scope = null
        try {
            raf?.close()
        } finally {
            raf = null
            rafPath = null
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun fileAt(path: String): RandomAccessFile {
        if (rafPath != path) {
            raf?.close()
            raf = RandomAccessFile(path, "r")
            rafPath = path
        }
        return raf!!
    }

    private fun <T> blocking(block: suspend () -> T): T = try {
        runBlocking { kotlinx.coroutines.withTimeout(30_000) { block() } }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw IOException("Timed out waiting for Telegram", e)
    } catch (e: TdException) {
        throw IOException(e.message, e)
    } catch (e: InterruptedException) {
        throw InterruptedIOException()
    }

    class Factory(private val td: TdClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = TdDataSource(td)
    }

    companion object {
        const val PRIORITY = 32
        /** Disk read-ahead beyond what ExoPlayer has already buffered in memory. */
        const val WINDOW_SECONDS = 15L
        const val REFILL_BELOW_SECONDS = 5L
        const val START_SECONDS = 5L
        const val MIN_WINDOW = 4L shl 20
        const val MAX_WINDOW = 32L shl 20
        const val MIN_LOW_WATER = 1L shl 20
        const val START_CHUNK = 2L shl 20
        const val REQUERY_STEP = 256L shl 10
        const val DEFAULT_BITRATE = 500_000L
        private const val WAIT_MILLIS = 1000L

        fun uri(fileId: Int, bitrate: Long?, downloadAll: Boolean = false): Uri =
            Uri.parse("tdfile://$fileId?" + listOfNotNull(bitrate?.let { "bitrate=$it" }, "full=1".takeIf { downloadAll }).joinToString("&"))
    }
}
