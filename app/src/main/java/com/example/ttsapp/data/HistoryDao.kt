package com.example.ttsapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(history: HistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clearAll()

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)
}
