package com.isaver.filemanager.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CustomLocationDao {
    @Query("SELECT * FROM custom_locations ORDER BY sortOrder, createdAt, id") abstract fun observeAll(): Flow<List<CustomLocationEntity>>
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insert(entity: CustomLocationEntity)
    @Update(onConflict = OnConflictStrategy.ABORT) abstract suspend fun update(entity: CustomLocationEntity): Int
    @Query("UPDATE custom_locations SET sortOrder=:order WHERE id=:id") abstract suspend fun updateSortOrder(id:String,order:Int):Int
    @Query("DELETE FROM custom_locations WHERE id = :id") abstract suspend fun deleteById(id: String):Int
    @Query("SELECT * FROM custom_locations WHERE absolutePath = :path LIMIT 1") abstract suspend fun findByPath(path: String): CustomLocationEntity?
    @Query("SELECT * FROM custom_locations WHERE id = :id LIMIT 1") abstract suspend fun findById(id: String): CustomLocationEntity?
    @Query("SELECT COALESCE(MAX(sortOrder) + 1, 0) FROM custom_locations") abstract suspend fun nextSortOrder(): Int
    @Query("SELECT id FROM custom_locations") abstract suspend fun allIds():List<String>

    @Transaction open suspend fun insertAtEnd(entity:CustomLocationEntity){insert(entity.copy(sortOrder=nextSortOrder()))}
    @Transaction open suspend fun reorderAtomically(ids:List<String>){
        require(ids.size==ids.distinct().size); require(ids.toSet()==allIds().toSet())
        ids.forEachIndexed{i,id->check(updateSortOrder(id,i)==1)}
    }
}
