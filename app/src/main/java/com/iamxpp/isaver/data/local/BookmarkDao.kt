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
}
