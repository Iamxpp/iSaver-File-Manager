package com.iamxpp.isaver.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrashItemDao {
    @Query("SELECT * FROM trash_items ORDER BY deletedAt DESC, id ASC")
    fun observeAll(): Flow<List<TrashItemEntity>>

    @Query("SELECT * FROM trash_items WHERE id = :id LIMIT 1")
    suspend fun find(id: String): TrashItemEntity?

    @Upsert
    suspend fun upsert(entity: TrashItemEntity)

    @Delete
    suspend fun delete(entity: TrashItemEntity)

    @Query("UPDATE trash_items SET state = 'NEEDS_REVIEW' WHERE state = 'PENDING'")
    suspend fun markPendingForReview(): Int
}
