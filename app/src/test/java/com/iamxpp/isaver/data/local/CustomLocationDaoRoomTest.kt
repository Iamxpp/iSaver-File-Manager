package com.iamxpp.isaver.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.iamxpp.isaver.locations.*
import com.iamxpp.isaver.domain.RootPath
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomLocationDaoRoomTest {
    private lateinit var db:ISaverDatabase; private lateinit var dao:CustomLocationDao
    @Before fun setup(){db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),ISaverDatabase::class.java).allowMainThreadQueries().build();dao=db.customLocationDao()}
    @After fun close()=db.close()
    @Test fun `concurrent inserts receive unique continuous sort order`()=runTest{
        (0 until 8).map{i->async{dao.insertAtEnd(entity("id$i","/$i"))}}.awaitAll()
        assertEquals((0 until 8).toList(),dao.observeAll().first().map{it.sortOrder}.sorted())
    }
    @Test fun `reorder rejects incomplete or duplicate ids without changing rows`()=runTest{
        listOf("a","b","c").forEach{dao.insertAtEnd(entity(it,"/$it"))}; val before=dao.observeAll().first().map{it.id}
        assertIllegalArgument { dao.reorderAtomically(listOf("a","a","c")) }
        assertEquals(before,dao.observeAll().first().map{it.id})
        assertIllegalArgument { dao.reorderAtomically(listOf("a","b")) }
        assertEquals(before,dao.observeAll().first().map{it.id})
    }
    @Test fun `reorder rolls back when a later update aborts`()=runTest{
        listOf("a","b","c").forEach{dao.insertAtEnd(entity(it,"/$it"))}; val before=dao.observeAll().first().map{it.id}
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_b BEFORE UPDATE OF sortOrder ON custom_locations WHEN NEW.id='b' BEGIN SELECT RAISE(ABORT, 'fail'); END")
        try{dao.reorderAtomically(listOf("c","b","a"));fail("expected failure")}catch(_:Exception){}
        assertEquals(before,dao.observeAll().first().map{it.id})
    }
    @Test fun `reorder flow observes only committed before and after states`()=runTest{
        listOf("a","b","c").forEach{dao.insertAtEnd(entity(it,"/$it"))}
        val firstSeen=CompletableDeferred<Unit>()
        val states=async{dao.observeAll().map{rows->rows.map{it.id}}.onEach{firstSeen.complete(Unit)}.take(2).toList()}
        firstSeen.await();dao.reorderAtomically(listOf("c","b","a"))
        assertEquals(listOf(listOf("a","b","c"),listOf("c","b","a")),states.await())
    }
    @Test fun `repository concurrent same path yields one success and one duplicate`()=runTest{
        val ids=AtomicInteger();val repo=CustomLocationRepository(dao,{LocationId.of("custom.${ids.incrementAndGet()}")},{1L})
        val path=RootPath.parse("/same path").getOrThrow()
        val results=listOf(async{repo.add("one",path)},async{repo.add("two",path)}).awaitAll()
        assertEquals(1,results.count{it is CustomLocationResult.Success})
        assertEquals(1,results.count{it is CustomLocationResult.DuplicatePath})
        assertEquals(1,dao.observeAll().first().size)
    }
    private fun entity(id:String,path:String)=CustomLocationEntity(id,id,path,999,1,1)
    private suspend fun assertIllegalArgument(block:suspend()->Unit){try{block();fail("Expected IllegalArgumentException")}catch(_:IllegalArgumentException){}}
}
