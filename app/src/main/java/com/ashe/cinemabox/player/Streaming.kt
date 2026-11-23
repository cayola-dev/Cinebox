package com.ashe.cinemabox.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.ashe.cinemabox.library.FileProgress
import com.ashe.cinemabox.library.FileRepository
import com.ashe.cinemabox.telegram.TdException
import com.ashe.cinemabox.telegram.floodWaitSeconds
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * ExoPlayer's buffer is what bounds the download: it stops reading at [MAX_BUFFER_MS] ahead of
 * playback, and [TdDataSource] only downloads a short window past the last read.
 */
object StreamingLoadControl {
    const val MIN_BUFFER_MS = 20_000
    const val MAX_BUFFER_MS = 60_000
    const val START_BUFFER_MS = 2_000
    const val REBUFFER_MS = 5_000

    @OptIn(UnstableApi::class)
    fun build(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
        .build()
}

/** Retries a load only after the wait a FLOOD_WAIT error asks for; other errors keep Media3's backoff. */
@OptIn(UnstableApi::class)
class FloodAwareRetryPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        var cause: Throwable? = info.exception
        while (cause != null) {
            if (cause is TdException) floodWaitSeconds(cause.message)?.let { return it * 1000 }
            cause = cause.cause
        }
        return super.getRetryDelayMsFor(info)
    }
}

@OptIn(UnstableApi::class)
fun buildStreamingPlayer(context: Context, files: FileRepository, fileId: Int, bitrate: Long?, startPositionMs: Long = 0,
                         downloadAll: Boolean = false): ExoPlayer =
    ExoPlayer.Builder(context)
        .setLoadControl(StreamingLoadControl.build())
        .setMediaSourceFactory(
            ProgressiveMediaSource.Factory(files.dataSourceFactory())
                .setLoadErrorHandlingPolicy(FloodAwareRetryPolicy())
        )
        .build().apply {
            files.openPlayback(fileId)
            setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            setMediaItem(MediaItem.fromUri(TdDataSource.uri(fileId, bitrate, downloadAll)), startPositionMs)
            prepare()
            playWhenReady = true
        }

/**
 * Average download speed over the last [WINDOW_MS], counted only while TDLib is actively
 * downloading so an idle, already-full window does not read as a slow network.
 */
class SpeedMeter {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    var bytesPerSecond: Double? = null
        private set

    fun add(progress: FileProgress, nowMs: Long = System.nanoTime() / 1_000_000) {
        if (!progress.active) {
            samples.clear()
            return
        }
        // downloadedSize can drop when TDLib restarts a part; start a new measurement then.
        if (samples.isNotEmpty() && progress.downloadedSize < samples.last().second) samples.clear()
        samples.addLast(nowMs to progress.downloadedSize)
        while (samples.size > 2 && nowMs - samples.first().first > WINDOW_MS) samples.removeFirst()
        val (firstTime, firstBytes) = samples.first()
        val elapsed = nowMs - firstTime
        if (elapsed >= MIN_SPAN_MS) bytesPerSecond = (progress.downloadedSize - firstBytes) * 1000.0 / elapsed
    }

    private companion object {
        const val WINDOW_MS = 10_000L
        const val MIN_SPAN_MS = 2_000L
    }
}
