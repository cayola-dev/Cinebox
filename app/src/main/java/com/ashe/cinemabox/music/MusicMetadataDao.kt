package com.ashe.cinemabox.music

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface MusicMetadataDao {
    @Query("SELECT * FROM MusicMetadata WHERE uniqueId = :uniqueId")
    suspend fun get(uniqueId: String): MusicMetadata?

    /** Only identified songs: they are shown for every track in the lists, not just the playing one. */
    @Query("SELECT * FROM MusicMetadata WHERE source = 'acoustid'")
    suspend fun identified(): List<MusicMetadata>

    @Upsert
    suspend fun put(metadata: MusicMetadata)

    /** "Volver a identificar": forget failed attempts so those songs are analysed again. */
    @Query("DELETE FROM MusicMetadata WHERE source != 'acoustid'")
    suspend fun forgetAttempts(): Int
}
