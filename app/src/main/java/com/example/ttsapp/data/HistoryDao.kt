package com.example.ttsapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentHistory(limit: Int = MAX_HISTORY_ITEMS): Flow<List<HistoryEntity>>

    /**
     * 文本唯一索引配合 REPLACE 策略：
     * 重复播放同一段文字时覆盖旧记录并刷新时间戳，不产生重复条目。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: HistoryEntity)

    /** 裁剪超出上限的旧记录，调用方负责在事务中执行。 */
    @Query(
        "DELETE FROM history WHERE id NOT IN " +
            "(SELECT id FROM history ORDER BY timestamp DESC LIMIT :limit)"
    )
    suspend fun trimToLimit(limit: Int = MAX_HISTORY_ITEMS)

    @Query("DELETE FROM history")
    suspend fun clearAll()

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    companion object {
        const val MAX_HISTORY_ITEMS = 100
    }
}
