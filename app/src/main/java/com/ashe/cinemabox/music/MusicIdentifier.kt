package com.ashe.cinemabox.music

import android.content.Context
import android.util.Log
import com.ashe.cinemabox.library.FileRepository
import com.ashe.cinemabox.library.LibrarySettings
import com.ashe.cinemabox.library.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Identifies the song that is playing (6A fingerprint + 6B AcoustID) and remembers the outcome in Room.
 * Only the playing track is ever analysed, using what playback already downloaded; changing track cancels it.
 */
class MusicIdentifier(context: Context, private val files: FileRepository, private val settings: LibrarySettings) {
    private val dao = MusicDatabase.get(context).metadata()
    private val client = AcoustIdClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val mutableKnown = MutableStateFlow<Map<String, MusicMetadata>>(emptyMap())
    /** Identified songs by unique id; the UI shows these names wherever the track appears. */
    val known: StateFlow<Map<String, MusicMetadata>> = mutableKnown.asStateFlow()
    private val mutableAnalyzing = MutableStateFlow<String?>(null)
    /** Unique id of the track being analysed right now, for the "Analizando…" effect. */
    val analyzing: StateFlow<String?> = mutableAnalyzing.asStateFlow()

    init {
        scope.launch {
            try { mutableKnown.value = dao.identified().associateBy { it.uniqueId } }
            catch (e: Exception) { Log.w(TAG, "Could not read saved metadata: ${e.message}") }
        }
    }

    fun onPlay(track: MediaItem) {
        stop()
        val id = track.uniqueId.takeIf { it.isNotEmpty() } ?: return
        job = scope.launch { identify(track, id) }
    }

    fun stop() {
        job?.cancel()
        job = null
        mutableAnalyzing.value = null
    }

    /** Deletes failed attempts (source ≠ acoustid) so those songs are analysed again. */
    suspend fun forgetAttempts(): Int = dao.forgetAttempts().also { Log.i(TAG, "Forgot $it failed attempts") }

    private suspend fun identify(track: MediaItem, id: String) {
        try {
            dao.get(id)?.let { saved ->
                Log.i(TAG, "\"${track.title}\": already known (${saved.source}), no analysis")
                if (saved.identified) mutableKnown.value = mutableKnown.value + (id to saved)
                return
            }
            if (!settings.autoIdentify) return
            mutableAnalyzing.value = id
            val seconds = track.durationSeconds ?: 0
            val needed = if (seconds > Fingerprinter.MAX_SECONDS) track.sizeBytes * Fingerprinter.MAX_SECONDS / seconds else track.sizeBytes
            while (files.downloadedAhead(track.fileId, 0) < needed) delay(POLL_MS)
            val path = files.localPath(track.fileId) ?: return
            val complete = files.downloadedAhead(track.fileId, 0) >= track.sizeBytes
            val print = Fingerprinter.fingerprint(path)
            if (print == null) {
                // A half-downloaded file can fail to decode for reasons that go away; only a whole file is final.
                if (complete) saveAttempt(track, id)
                return
            }
            // Without the whole file the container may guess the duration from its size; TDLib knows the real one.
            val duration = if (!complete && seconds > 0) seconds else print.duration
            Log.i(TAG, "\"${track.title}\": looking up (${duration}s)")
            when (val result = client.lookup(print.copy(duration = duration))) {
                is LookupResult.Found -> {
                    val metadata = MusicMetadata(id, result.match.title, result.match.artist.ifEmpty { null },
                        result.match.album, result.match.coverPath, MusicMetadata.SOURCE_ACOUSTID, System.currentTimeMillis())
                    dao.put(metadata)
                    mutableKnown.value = mutableKnown.value + (id to metadata)
                }
                LookupResult.NotFound -> saveAttempt(track, id)
                LookupResult.Unavailable -> Log.i(TAG, "\"${track.title}\": AcoustID unavailable, will retry next time")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Identification failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            if (mutableAnalyzing.value == id) mutableAnalyzing.value = null
        }
    }

    /** Keeps the file's or the message's data and records the attempt so the song is not analysed again. */
    private suspend fun saveAttempt(track: MediaItem, id: String) {
        val source = if (track.tagged) MusicMetadata.SOURCE_FILE else MusicMetadata.SOURCE_MESSAGE
        dao.put(MusicMetadata(id, track.title, track.artist.ifEmpty { null }, null, null, source, System.currentTimeMillis()))
        Log.i(TAG, "\"${track.title}\": not identified, kept data from $source")
    }

    private companion object {
        const val TAG = "Cinebox.Identify"
        const val POLL_MS = 2_000L
    }
}
