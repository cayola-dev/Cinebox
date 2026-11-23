package com.ashe.cinemabox.music

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * What Cinebox knows about one audio file, keyed by TDLib's remote.unique_id (stable across sessions).
 * Failed attempts are stored too ([source] "archivo" or "mensaje") so a song is never analysed twice.
 */
@Entity
data class MusicMetadata(
    @PrimaryKey val uniqueId: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val coverPath: String?,
    /** "acoustid", "archivo" (ID3 tags) or "mensaje" (message text / file name). */
    val source: String,
    val identifiedAt: Long,
) {
    val identified get() = source == SOURCE_ACOUSTID

    companion object {
        const val SOURCE_ACOUSTID = "acoustid"
        const val SOURCE_FILE = "archivo"
        const val SOURCE_MESSAGE = "mensaje"
    }
}

@Database(entities = [MusicMetadata::class], version = 1, exportSchema = false)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun metadata(): MusicMetadataDao

    companion object {
        @Volatile private var instance: MusicDatabase? = null
        fun get(context: Context): MusicDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MusicDatabase::class.java, "music.db")
                .build().also { instance = it }
        }
    }
}
