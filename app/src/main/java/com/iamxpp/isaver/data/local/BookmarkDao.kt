package com.iamxpp.isaver.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC, absolutePath ASC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Upsert
    suspend fun upsert(entity: BookmarkEntity)

    @Delete
    suspend fun delete(entity: BookmarkEntity)

    @Query("UPDATE bookmarks SET available = :available WHERE absolutePath = :absolutePath")
    suspend fun setAvailability(absolutePath: String, available: Boolean)

    @Query(
        """
        UPDATE bookmarks SET
            absolutePath = :newPath,
            displayName = :displayName,
            entryType = :entryType,
            device = :device,
            inode = :inode,
            available = 1
        WHERE absolutePath = :oldPath
        """,
    )
    suspend fun relocate(
        oldPath: String,
        newPath: String,
        displayName: String,
        entryType: String,
        device: Long?,
        inode: Long?,
    )
}
