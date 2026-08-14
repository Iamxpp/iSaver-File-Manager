package com.isaver.filemanager.locations

import com.isaver.filemanager.data.local.CustomLocationDao
import com.isaver.filemanager.data.local.CustomLocationEntity
import com.isaver.filemanager.domain.RootPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CustomLocationRepositoryTest {
    @Test fun `add trims name preserves path and injects id clock`() = runTest {
        val dao=FakeDao(); val repo=CustomLocationRepository(dao,{LocationId.of("custom.one")},{123L})
        val path=root("/a b/line\nname")
        assertTrue(repo.add("  名称  ",path) is CustomLocationResult.Success)
        val row=dao.rows.single(); assertEquals("custom.one",row.id); assertEquals("名称",row.displayName); assertEquals(path.value,row.absolutePath); assertEquals(123L,row.createdAt); assertEquals(123L,row.updatedAt)
    }
    @Test fun `blank name and duplicate path are structured failures`() = runTest {
        val dao=FakeDao(); val repo=CustomLocationRepository(dao,{LocationId.of("custom.id")},{1L})
        assertTrue(repo.add("  ",root("/a")) is CustomLocationResult.InvalidName)
        repo.add("a",root("/a")); assertTrue(repo.add("b",root("/a")) is CustomLocationResult.DuplicatePath)
    }
    @Test fun `update preserves created time and rejects another rows path`() = runTest {
        val dao=FakeDao(); var now=1L; val repo=CustomLocationRepository(dao,{LocationId.of("custom.${dao.rows.size}")},{now})
        repo.add("a",root("/a")); repo.add("b",root("/b")); now=9
        assertTrue(repo.update(LocationId.of("custom.0")," A ",root("/a")) is CustomLocationResult.Success)
        assertEquals(1L,dao.rows.first().createdAt); assertEquals(9L,dao.rows.first().updatedAt)
        assertTrue(repo.update(LocationId.of("custom.0"),"x",root("/b")) is CustomLocationResult.DuplicatePath)
    }
    @Test fun `observe maps sorted custom locations and remove only deletes dao row`() = runTest {
        val dao=FakeDao(); dao.rows += CustomLocationEntity("z","Z","/z",2,1,1); dao.rows += CustomLocationEntity("a","A","/a",1,2,2); dao.emit()
        val repo=CustomLocationRepository(dao,{LocationId.of("unused")},{0})
        val observed=repo.observeAll().first(); assertEquals(listOf("A","Z"),observed.map{it.displayName}); assertTrue(observed.all{it.source==StorageLocation.Source.CUSTOM})
        repo.remove(LocationId.of("a")); assertEquals(listOf("a"),dao.deleted)
    }
    @Test fun `reorder assigns stable consecutive sort order`() = runTest {
        val dao=FakeDao(); listOf("a","b","c").forEachIndexed{i,id->dao.rows+=CustomLocationEntity(id,id,"/$id",i,1,1)}
        CustomLocationRepository(dao,{LocationId.of("x")},{5}).reorder(listOf(LocationId.of("c"),LocationId.of("a"),LocationId.of("b")))
        assertEquals(listOf("c","a","b"),dao.rows.sortedBy{it.sortOrder}.map{it.id})
    }
    @Test fun `invalid stored row terminates flow with path free corruption error`() = runTest {
        val dao=FakeDao();dao.rows+=CustomLocationEntity("valid.id","bad","relative/secret",0,1,1);dao.emit()
        val error=try{CustomLocationRepository(dao,{LocationId.of("x")},{0}).observeAll().first();null}catch(e:DataCorruptionException){e}
        assertNotNull(error);assertFalse(error!!.message.orEmpty().contains("relative/secret"))
    }
    private class FakeDao:CustomLocationDao(){
        val rows= mutableListOf<CustomLocationEntity>(); val flow=MutableStateFlow<List<CustomLocationEntity>>(emptyList()); val deleted= mutableListOf<String>()
        fun emit(){flow.value=rows.sortedWith(compareBy<CustomLocationEntity>{it.sortOrder}.thenBy{it.createdAt}.thenBy{it.id})}
        override fun observeAll():Flow<List<CustomLocationEntity>> = flow
        override suspend fun insert(entity:CustomLocationEntity){rows+=entity;emit()}
        override suspend fun update(entity:CustomLocationEntity):Int{val found=rows.any{it.id==entity.id};rows.replaceAll{if(it.id==entity.id)entity else it};emit();return if(found)1 else 0}
        override suspend fun updateSortOrder(id:String,order:Int):Int{val e=findById(id)?:return 0;return update(e.copy(sortOrder=order))}
        override suspend fun deleteById(id:String):Int{deleted+=id;val n=rows.size;rows.removeAll{it.id==id};emit();return if(rows.size<n)1 else 0}
        override suspend fun findByPath(path:String)=rows.firstOrNull{it.absolutePath==path}
        override suspend fun findById(id:String)=rows.firstOrNull{it.id==id}
        override suspend fun nextSortOrder()=(rows.maxOfOrNull{it.sortOrder}?:-1)+1
        override suspend fun allIds()=rows.map{it.id}
    }
    private fun root(v:String)=RootPath.parse(v).getOrThrow()
}
