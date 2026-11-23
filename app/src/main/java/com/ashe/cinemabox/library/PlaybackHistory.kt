package com.ashe.cinemabox.library

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Entity(primaryKeys = ["accountId", "chatId", "messageId"])
data class PlaybackRecord(
    val accountId: Long,
    val chatId: Long,
    val messageId: Long,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
    val favorite: Boolean = false,
    val plays: Int = 0,
)

@Dao
interface PlaybackDao {
    @Query("SELECT * FROM PlaybackRecord WHERE accountId = :account ORDER BY updatedAt DESC")
    suspend fun all(account: Long): List<PlaybackRecord>
    @Upsert suspend fun put(record: PlaybackRecord)
}

@Database(entities = [PlaybackRecord::class], version = 1, exportSchema = false)
abstract class PlaybackDatabase : RoomDatabase() {
    abstract fun playback(): PlaybackDao
}

/** Scoped to the Telegram account; writes survive disposal of the playback screen. */
class PlaybackHistory(context: Context, private val accountId: Long) {
    private val dao = database(context).playback()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<List<PlaybackRecord>>(emptyList())
    val records = mutable.asStateFlow()
    private val ready = scope.async { mutex.withLock { mutable.value = dao.all(accountId) } }
    fun record(item: MediaItem) = records.value.firstOrNull { it.chatId == item.chatId && it.messageId == item.messageId }
    fun save(item: MediaItem, position: Long, duration: Long) = update(item) {
        it.copy(positionMs = position.coerceAtLeast(0), durationMs = duration.coerceAtLeast(0), updatedAt = System.currentTimeMillis())
    }
    fun played(item: MediaItem) = update(item) { it.copy(plays = it.plays + 1, updatedAt = System.currentTimeMillis()) }
    fun favorite(item: MediaItem) = update(item) { it.copy(favorite = !it.favorite) }
    private fun update(item: MediaItem, change: (PlaybackRecord) -> PlaybackRecord) {
        scope.launch {
            ready.await()
            mutex.withLock {
                val next = change(record(item) ?: PlaybackRecord(accountId, item.chatId, item.messageId))
                dao.put(next)
                mutable.value = (mutable.value.filterNot { it.chatId == item.chatId && it.messageId == item.messageId } + next)
                    .sortedByDescending { it.updatedAt }
            }
        }
    }
    companion object {
        @Volatile private var instance: PlaybackDatabase? = null
        private fun database(context: Context): PlaybackDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, PlaybackDatabase::class.java, "playback.db")
                .build().also { instance = it }
        }
    }
}

fun resumePosition(record: PlaybackRecord?): Long = record?.let {
    if (it.positionMs < 5_000 || (it.durationMs > 0 && it.positionMs >= it.durationMs - 10_000)) 0L else it.positionMs
} ?: 0L

/** Explicit series tags only: a normal film must never be guessed into a fictional series. */
fun seriesName(item: MediaItem): String? = item.tags.firstOrNull {
    it.removePrefix("#").startsWith("serie_", ignoreCase = true)
}?.removePrefix("#")?.substring(6)?.replace('_', ' ')?.takeIf { it.isNotBlank() }

fun episodeOrder(item: MediaItem): Long {
    val match = Regex("(?:t|s)(\\d{1,4})e(\\d{1,4})", RegexOption.IGNORE_CASE)
        .find(item.tags.joinToString(" ") + " " + item.title) ?: return Long.MAX_VALUE
    return match.groupValues[1].toLong() * 10_000 + match.groupValues[2].toLong()
}
