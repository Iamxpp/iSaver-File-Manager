package com.iamxpp.isaver.locations

import android.database.sqlite.SQLiteConstraintException
import com.iamxpp.isaver.data.local.CustomLocationDao
import com.iamxpp.isaver.data.local.CustomLocationEntity
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface CustomLocationResult { data object Success:CustomLocationResult; data object InvalidName:CustomLocationResult; data object DuplicatePath:CustomLocationResult; data object NotFound:CustomLocationResult }

class CustomLocationRepository(
    private val dao: CustomLocationDao,
    private val idFactory: () -> LocationId,
    private val clock: () -> Long,
) {
    fun observeAll(): Flow<List<StorageLocation.Direct>> = dao.observeAll().map { rows -> rows.map(::toLocation) }

    suspend fun add(displayName:String,path:RootPath):CustomLocationResult {
        val name=displayName.trim(); if(name.isEmpty()) return CustomLocationResult.InvalidName
        if(dao.findByPath(path.value)!=null) return CustomLocationResult.DuplicatePath
        val now=clock()
        return try { dao.insert(CustomLocationEntity(idFactory().value,name,path.value,dao.nextSortOrder(),now,now)); CustomLocationResult.Success }
        catch(_:SQLiteConstraintException){ CustomLocationResult.DuplicatePath }
    }

    suspend fun update(id:LocationId,displayName:String,path:RootPath):CustomLocationResult {
        val name=displayName.trim(); if(name.isEmpty()) return CustomLocationResult.InvalidName
        val current=dao.findById(id.value)?:return CustomLocationResult.NotFound
        val duplicate=dao.findByPath(path.value); if(duplicate!=null&&duplicate.id!=id.value)return CustomLocationResult.DuplicatePath
        return try { dao.update(current.copy(displayName=name,absolutePath=path.value,updatedAt=clock())); CustomLocationResult.Success }
        catch(_:SQLiteConstraintException){CustomLocationResult.DuplicatePath}
    }
    suspend fun remove(id:LocationId)=dao.deleteById(id.value)
    suspend fun reorder(ids:List<LocationId>){ ids.forEachIndexed{i,id->dao.findById(id.value)?.let{dao.update(it.copy(sortOrder=i,updatedAt=clock()))}} }
    private fun toLocation(row:CustomLocationEntity)=StorageLocation.Direct(LocationId.of(row.id),row.displayName,RootPath.parse(row.absolutePath).getOrThrow(),StorageLocation.Source.CUSTOM)
}
