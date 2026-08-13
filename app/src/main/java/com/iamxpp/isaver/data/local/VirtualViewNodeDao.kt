package com.iamxpp.isaver.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VirtualViewNodeDao {
    @Query(
        """
        SELECT * FROM virtual_view_nodes
        WHERE parentId IS :parentId
        ORDER BY sortOrder, createdAt, id
        """,
    )
    fun observeChildren(parentId: String?): Flow<List<VirtualViewNodeEntity>>

    @Query("SELECT * FROM virtual_view_nodes WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): VirtualViewNodeEntity?

    @Query("SELECT * FROM virtual_view_nodes")
    suspend fun findAll(): List<VirtualViewNodeEntity>

    @Query(
        """
        SELECT * FROM virtual_view_nodes
        WHERE parentId = :parentId AND targetPath = :targetPath AND entryType = :entryType
        LIMIT 1
        """,
    )
    suspend fun findReference(parentId: String, targetPath: String, entryType: String): VirtualViewNodeEntity?

    @Query("SELECT COALESCE(MAX(sortOrder) + 1, 0) FROM virtual_view_nodes WHERE parentId IS :parentId")
    suspend fun nextSortOrder(parentId: String?): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: VirtualViewNodeEntity)

    @Query("UPDATE virtual_view_nodes SET parentId = :parentId, sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun move(id: String, parentId: String?, sortOrder: Int, updatedAt: Long): Int

    @Query("UPDATE virtual_view_nodes SET displayName = :displayName, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, displayName: String, updatedAt: Long): Int

    @Query("UPDATE virtual_view_nodes SET available = :available, updatedAt = :updatedAt WHERE id = :id AND nodeType = 'REAL_REFERENCE'")
    suspend fun setAvailability(id: String, available: Boolean, updatedAt: Long): Int

    @Query("DELETE FROM virtual_view_nodes WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int
}
