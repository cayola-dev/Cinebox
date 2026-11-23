package com.ashe.cinemabox.music

import android.content.Context
import android.util.Log
import com.ashe.cinemabox.BuildConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A recording AcoustID recognised, with the album cover saved in app storage when one exists. */
data class AcoustIdMatch(
    val title: String,
    val artist: String,
    val album: String?,
    val albumType: String?,
    val releaseGroupId: String?,
    val coverPath: String?,
)

/** [NotFound] is a real answer and is remembered; [Unavailable] (no network, timeout, bad JSON) is retried later. */
sealed interface LookupResult {
    data class Found(val match: AcoustIdMatch) : LookupResult
    data object NotFound : LookupResult
    data object Unavailable : LookupResult
}

/** AcoustID lookup and Cover Art Archive download. Never throws to the caller (except cancellation). */
class AcoustIdClient(context: Context) {
    private val coverDir = File(context.applicationContext.filesDir, "covers")
    private val http = OkHttpClient.Builder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val rate = Mutex()
    private var lastLookupAt = 0L

    suspend fun lookup(fingerprint: FingerprintResult): LookupResult {
        if (BuildConfig.ACOUSTID_KEY.isEmpty()) return LookupResult.Unavailable.also { Log.w(TAG, "ACOUSTID_KEY is not configured") }
        return try {
            val body = FormBody.Builder()
                .add("client", BuildConfig.ACOUSTID_KEY)
                .add("duration", fingerprint.duration.toString())
                .add("fingerprint", fingerprint.fingerprint)
                .add("meta", "recordings releasegroups compress")
                .build()
            val text = throttled { execute(Request.Builder().url(LOOKUP_URL).post(body).build()) { it.body?.string() } }
                ?: return LookupResult.Unavailable
            val response = json.decodeFromString(LookupResponse.serializer(), text)
            if (response.status != "ok") {
                Log.w(TAG, "AcoustID error: ${response.error?.message ?: response.status}")
                return LookupResult.Unavailable
            }
            val best = response.results.maxByOrNull { it.score }
            val recording = best?.recordings?.firstOrNull { !it.title.isNullOrBlank() }
            if (best == null || best.score < MIN_SCORE || recording == null) {
                Log.i(TAG, "No match (best score ${best?.score ?: 0.0})")
                return LookupResult.NotFound
            }
            val group = recording.releasegroups.firstOrNull()
            val match = AcoustIdMatch(
                title = recording.title!!.trim(),
                artist = recording.artists.mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }.joinToString(", "),
                album = group?.title,
                albumType = group?.type,
                releaseGroupId = group?.id,
                coverPath = group?.id?.let { cover(it) },
            )
            Log.i(TAG, "Match ${"%.2f".format(best.score)}: \"${match.title}\" · ${match.artist} · ${match.album} · cover ${match.coverPath}")
            LookupResult.Found(match)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Lookup failed: ${e.javaClass.simpleName}: ${e.message}")
            LookupResult.Unavailable
        }
    }

    /** front-250 of the release group, cached on disk; null when the archive has none (404) or on failure. */
    private suspend fun cover(releaseGroupId: String): String? {
        val target = File(coverDir, "$releaseGroupId.jpg")
        if (target.isFile && target.length() > 0) return target.absolutePath
        return try {
            execute(Request.Builder().url("$COVER_URL/$releaseGroupId/front-250").build()) { response ->
                val bytes = response.body?.bytes() ?: return@execute null
                coverDir.mkdirs()
                val partial = File(coverDir, "$releaseGroupId.part")
                partial.writeBytes(bytes)
                if (partial.renameTo(target)) target.absolutePath else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cover download failed: ${e.message}")
            null
        }
    }

    /** At most three AcoustID requests per second, as the service asks. */
    private suspend fun <T> throttled(block: suspend () -> T): T = rate.withLock {
        val wait = lastLookupAt + MIN_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastLookupAt = System.currentTimeMillis()
        block()
    }

    /** Runs [request]; [read] gets successful responses only. 404 gives null, other HTTP errors throw. */
    private suspend fun <T> execute(request: Request, read: (Response) -> T?): T? = withContext(Dispatchers.IO) {
        val call = http.newCall(request)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            when {
                                it.isSuccessful -> continuation.resume(read(it))
                                it.code == 404 -> continuation.resume(null)
                                else -> continuation.resumeWithException(IOException("HTTP ${it.code}"))
                            }
                        } catch (e: Exception) { continuation.resumeWithException(e) }
                    }
                }
            })
        }
    }

    @Serializable private data class LookupResponse(val status: String = "", val results: List<Result> = emptyList(), val error: ApiError? = null)
    @Serializable private data class ApiError(val code: Int = 0, val message: String = "")
    @Serializable private data class Result(val id: String = "", val score: Double = 0.0, val recordings: List<Recording> = emptyList())
    @Serializable private data class Recording(val id: String = "", val title: String? = null, val artists: List<Artist> = emptyList(),
                                               val releasegroups: List<ReleaseGroup> = emptyList())
    @Serializable private data class Artist(val id: String = "", val name: String? = null)
    @Serializable private data class ReleaseGroup(val id: String = "", val title: String? = null, val type: String? = null)

    private companion object {
        const val TAG = "Cinebox.AcoustID"
        const val LOOKUP_URL = "https://api.acoustid.org/v2/lookup"
        const val COVER_URL = "https://coverartarchive.org/release-group"
        const val USER_AGENT = "CineBox/1.0 (uso personal)"
        const val TIMEOUT_SECONDS = 10L
        const val MIN_INTERVAL_MS = 334L
        const val MIN_SCORE = 0.8
    }
}
