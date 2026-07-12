package com.iamxpp.isaver.locations

import android.database.sqlite.SQLiteConstraintException
import com.iamxpp.isaver.data.local.CustomLocationDao
import com.iamxpp.isaver.data.local.CustomLocationEntity
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface CustomLocationResult { data object Success:CustomLocationResult; data object InvalidName:CustomLocationResult; data object DuplicatePath:CustomLocationResult; data object IdConflict:CustomLocationResult; data object InvalidOrder:CustomLocationResult; data object NotFound:CustomLocationResult }
class DataCorruptionException : IllegalStateException("Invalid custom location data")

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
        return try { dao.insertAtEnd(CustomLocationEntity(idFactory().value,name,path.value,0,now,now)); CustomLocationResult.Success }
        catch(_:SQLiteConstraintException){ if(dao.findByPath(path.value)!=null)CustomLocationResult.DuplicatePath else CustomLocationResult.IdConflict }
    }

    suspend fun update(id:LocationId,displayName:String,path:RootPath):CustomLocationResult {
        val name=displayName.trim(); if(name.isEmpty()) return CustomLocationResult.InvalidName
        val current=dao.findById(id.value)?:return CustomLocationResult.NotFound
        val duplicate=dao.findByPath(path.value); if(duplicate!=null&&duplicate.id!=id.value)return CustomLocationResult.DuplicatePath
        return try { if(dao.update(current.copy(displayName=name,absolutePath=path.value,updatedAt=clock()))==1)CustomLocationResult.Success else CustomLocationResult.NotFound }
        catch(_:SQLiteConstraintException){if(dao.findByPath(path.value)?.id!=id.value)CustomLocationResult.DuplicatePath else CustomLocationResult.IdConflict}
    }
    suspend fun remove(id:LocationId)=if(dao.deleteById(id.value)==1)CustomLocationResult.Success else CustomLocationResult.NotFound
    suspend fun reorder(ids:List<LocationId>):CustomLocationResult=try{dao.reorderAtomically(ids.map{it.value});CustomLocationResult.Success}catch(_:IllegalArgumentException){CustomLocationResult.InvalidOrder}
    private fun toLocation(row:CustomLocationEntity)=try{StorageLocation.Direct(LocationId.of(row.id),row.displayName,RootPath.parse(row.absolutePath).getOrThrow(),StorageLocation.Source.CUSTOM)}catch(_:Exception){throw DataCorruptionException()}
}
