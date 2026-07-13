package com.iamxpp.isaver.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RecentItemDao {
    @Query(
        """
        SELECT * FROM recent_items
        ORDER BY lastActivityAt DESC, absolutePath ASC
        """,
    )
    abstract fun observeRecent(): Flow<List<RecentItemEntity>>

    @Upsert
    abstract suspend fun upsert(entity: RecentItemEntity)

    @Query(
        """
        DELETE FROM recent_items
        WHERE absolutePath NOT IN (
            SELECT absolutePath FROM recent_items
            ORDER BY lastActivityAt DESC, absolutePath ASC
            LIMIT :limit
        )
        """,
    )
    abstract suspend fun deleteBeyondLimit(limit: Int)

    @Query("UPDATE recent_items SET available = :available WHERE absolutePath = :path")
    abstract suspend fun markAvailability(path: String, available: Boolean): Int

    @Transaction
    open suspend fun upsertAndTrim(entity: RecentItemEntity, limit: Int) {
        require(limit > 0)
        upsert(entity)
        deleteBeyondLimit(limit)
    }
}
