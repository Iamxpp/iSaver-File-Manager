package com.isaver.filemanager.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OperationTaskDao {
    @Query("SELECT * FROM operation_tasks ORDER BY updatedAt DESC, id ASC")
    fun observeAll(): Flow<List<OperationTaskEntity>>

    @Upsert
    suspend fun upsert(entity: OperationTaskEntity)

    @Query("SELECT * FROM operation_tasks WHERE id = :id LIMIT 1")
    suspend fun find(id: String): OperationTaskEntity?

    @Query("SELECT COUNT(*) FROM operation_tasks")
    suspend fun count(): Int

    @Query(
        """
        UPDATE operation_tasks
        SET state = 'NEEDS_REVIEW',
            message = :message,
            updatedAt = :updatedAt
        WHERE state IN ('QUEUED', 'RUNNING', 'PAUSED', 'CANCELLING')
        """,
    )
    suspend fun markInterruptedForReview(message: String, updatedAt: Long): Int

    @Query(
        """
        DELETE FROM operation_tasks
        WHERE state IN ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED')
        """,
    )
    suspend fun deleteFinished(): Int
}
