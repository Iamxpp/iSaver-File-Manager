package com.iamxpp.isaver.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomLocationDao {
    @Query("SELECT * FROM custom_locations ORDER BY sortOrder, createdAt, id") fun observeAll(): Flow<List<CustomLocationEntity>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entity: CustomLocationEntity)
    @Update(onConflict = OnConflictStrategy.ABORT) suspend fun update(entity: CustomLocationEntity)
    @Query("DELETE FROM custom_locations WHERE id = :id") suspend fun deleteById(id: String)
    @Query("SELECT * FROM custom_locations WHERE absolutePath = :path LIMIT 1") suspend fun findByPath(path: String): CustomLocationEntity?
    @Query("SELECT * FROM custom_locations WHERE id = :id LIMIT 1") suspend fun findById(id: String): CustomLocationEntity?
    @Query("SELECT COALESCE(MAX(sortOrder) + 1, 0) FROM custom_locations") suspend fun nextSortOrder(): Int
}
