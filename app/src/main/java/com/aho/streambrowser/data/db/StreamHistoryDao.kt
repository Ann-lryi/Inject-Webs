package com.aho.streambrowser.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface StreamHistoryDao {
    @Query("SELECT * FROM stream_history ORDER BY timestamp DESC LIMIT 500")
    fun observeAll(): Flow<List<StreamHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: StreamHistoryEntity)

    @Query("DELETE FROM stream_history WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM stream_history")
    suspend fun count(): Int

    @Query("SELECT * FROM stream_history ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecent(): List<StreamHistoryEntity>
}
