package com.ashe.cinemabox.music

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** [duration] is the whole track in seconds (what AcoustID expects), not the analysed part. */
data class FingerprintResult(val duration: Int, val fingerprint: String)

/** On-device Chromaprint: decodes the first [MAX_SECONDS] of a local audio file to PCM and fingerprints it. */
object Fingerprinter {
    private const val TAG = "Cinebox.Fingerprint"
    /** Same length fpcalc and AcoustID use by default. */
    const val MAX_SECONDS = 120
    private const val TIMEOUT_US = 10_000L

    private val nativeReady: Boolean by lazy {
        try { System.loadLibrary("cineboxfp"); true }
        catch (e: Throwable) { Log.w(TAG, "Chromaprint library not available", e); false }
    }

    private external fun nativeFingerprint(samples: ShortArray, length: Int, sampleRate: Int, channels: Int): String?

    /** Null on any failure; cancelling the caller stops decoding at the next buffer. */
    suspend fun fingerprint(path: String): FingerprintResult? = withContext(Dispatchers.Default) {
        if (!nativeReady) return@withContext null
        try {
            decode(path)?.let { pcm ->
                nativeFingerprint(pcm.samples, pcm.count, pcm.sampleRate, pcm.channels)
                    ?.let { FingerprintResult(pcm.durationSeconds, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not fingerprint audio: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private class Pcm(val samples: ShortArray, val count: Int, val sampleRate: Int, val channels: Int, val durationSeconds: Int)

    private suspend fun decode(path: String): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null.also { Log.w(TAG, "No audio track") }
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            if (durationUs <= 0) return null.also { Log.w(TAG, "The file does not declare its duration") }
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var samples = ShortArray(sampleRate * channels * MAX_SECONDS)
            var count = 0
            val limitUs = MAX_SECONDS * 1_000_000L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var full = false
            while (!full) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime > limitUs) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // The decoder's output (e.g. HE-AAC doubling the rate) is what Chromaprint must be told.
                    val output = decoder.outputFormat
                    sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = Build.VERSION.SDK_INT >= 24 && output.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if (count == 0) samples = ShortArray(sampleRate * channels * MAX_SECONDS)
                } else if (index >= 0) {
                    val cap = sampleRate * channels * MAX_SECONDS
                    val buffer = decoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && count < cap) {
                        buffer.position(info.offset).limit(info.offset + info.size)
                        val ordered = buffer.order(ByteOrder.nativeOrder())
                        if (samples.size < cap) samples = samples.copyOf(cap)
                        if (floatPcm) {
                            val floats = ordered.asFloatBuffer()
                            val n = minOf(floats.remaining(), cap - count)
                            for (i in 0 until n) samples[count + i] = (floats.get().coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                            count += n
                        } else {
                            val shorts = ordered.asShortBuffer()
                            val n = minOf(shorts.remaining(), cap - count)
                            shorts.get(samples, count, n)
                            count += n
                        }
                    }
                    decoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || count >= cap) full = true
                }
            }
            if (count == 0) return null.also { Log.w(TAG, "Decoded no audio") }
            return Pcm(samples, count, sampleRate, channels, (durationUs / 1_000_000L).toInt())
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
