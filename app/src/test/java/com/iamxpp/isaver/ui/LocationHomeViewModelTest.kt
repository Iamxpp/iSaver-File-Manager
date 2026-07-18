package com.iamxpp.isaver.ui

import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.*
import com.iamxpp.isaver.locations.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocationHomeViewModelTest {
    private val main=UnconfinedTestDispatcher()
    @Before fun setup()=Dispatchers.setMain(main)
    @After fun tearDown()=Dispatchers.resetMain()

    @Test fun `initial state exposes common locations then resolves apps and custom availability`()=runTest{
        val custom=StorageLocation.Direct(LocationId.of("custom.one"),"自定义",root("/custom"),StorageLocation.Source.CUSTOM)
        val store=FakeStore().apply{flow.value=listOf(custom)}
        val fs=FakeFs().apply{stats["/custom"]=entry("/custom",EntryType.DIRECTORY,true,true)}
        val resolver=LocationHomeAppResolver{template->ResolvedAppLocation(template.id,template.displayName,emptyList(),template.candidates.size)}
        val vm=LocationHomeViewModel(resolver,store,fs,StandardTestDispatcher(testScheduler))
        assertEquals(LocationCatalog.commonLocations.map{it.path},vm.state.value.commonLocations.map{it.path})
        advanceUntilIdle()
        assertFalse(vm.state.value.loading);assertTrue(vm.state.value.appGroups.single().empty)
        assertTrue(vm.state.value.customLocations.single().availability is LocationAvailability.Available)
    }

    @Test fun `add trims name preserves raw path and remove never stats`()=runTest{
        val store=FakeStore();val fs=FakeFs();val raw="/a b/line\nname";fs.stats[raw]=entry(raw,EntryType.DIRECTORY,true,true)
        val vm=LocationHomeViewModel(LocationHomeAppResolver{ResolvedAppLocation(it.id,it.displayName,emptyList(),0)},store,fs,StandardTestDispatcher(testScheduler));advanceUntilIdle()
        vm.addCustomLocation("  名称  ",raw);advanceUntilIdle()
        assertEquals("名称",store.addedName);assertEquals(raw,store.addedPath!!.value);assertNull(vm.state.value.addError)
        val calls=fs.statCalls;vm.removeCustomLocation(LocationId.of("custom.one"));advanceUntilIdle()
        assertEquals("custom.one",store.removed!!.value);assertEquals(calls,fs.statCalls)
    }

    @Test fun `successful add increments save success version`()=runTest{
        val store=FakeStore();val fs=FakeFs().apply{stats["/saved"]=entry("/saved",EntryType.DIRECTORY,true,true)}
        val vm=LocationHomeViewModel(LocationHomeAppResolver{ResolvedAppLocation(it.id,it.displayName,emptyList(),0)},store,fs,StandardTestDispatcher(testScheduler));advanceUntilIdle()
        val initialVersion=vm.state.value.saveSuccessVersion

        vm.addCustomLocation("saved","/saved");advanceUntilIdle()

        assertEquals(initialVersion+1,vm.state.value.saveSuccessVersion)
    }

    @Test fun `clear add error removes stale dialog error without changing save version`()=runTest{
        val vm=LocationHomeViewModel(LocationHomeAppResolver{ResolvedAppLocation(it.id,it.displayName,emptyList(),0)},FakeStore(),FakeFs(),StandardTestDispatcher(testScheduler));advanceUntilIdle()
        vm.addCustomLocation("x","relative");advanceUntilIdle()
        val version=vm.state.value.saveSuccessVersion

        vm.clearAddError()

        assertNull(vm.state.value.addError)
        assertEquals(version,vm.state.value.saveSuccessVersion)
    }

    @Test fun `successful edit increments save success version`()=runTest{
        val store=FakeStore();val fs=FakeFs().apply{stats["/edited"]=entry("/edited",EntryType.DIRECTORY,true,true)}
        val vm=LocationHomeViewModel(LocationHomeAppResolver{ResolvedAppLocation(it.id,it.displayName,emptyList(),0)},store,fs,StandardTestDispatcher(testScheduler));advanceUntilIdle()

        vm.editCustomLocation(LocationId.of("custom.edit"),"edited","/edited");advanceUntilIdle()

        assertEquals(1,vm.state.value.saveSuccessVersion)
    }

    @Test fun `failed save and remove do not increment save success version`()=runTest{
        val store=FakeStore().apply{addResult=CustomLocationResult.DuplicatePath};val fs=FakeFs().apply{stats["/duplicate"]=entry("/duplicate",EntryType.DIRECTORY,true,true)}
        val vm=LocationHomeViewModel(LocationHomeAppResolver{ResolvedAppLocation(it.id,it.displayName,emptyList(),0)},store,fs,StandardTestDispatcher(testScheduler));advanceUntilIdle()

        vm.addCustomLocation("duplicate","/duplicate");advanceUntilIdle()
        vm.removeCustomLocation(LocationId.of("custom.remove"));advanceUntilIdle()

        assertEquals(0,vm.state.value.saveSuccessVersion)
    }

    @Test fun `add maps invalid path and duplicate while allowing readonly directory`()=runTest{
        val store=FakeStore();val fs=FakeFs();val vm=LocationHomeViewModel(LocationHomeAppResolver{ResolvedAppLocation(it.id,it.displayName,emptyList(),0)},store,fs,StandardTestDispatcher(testScheduler));advanceUntilIdle()
        vm.addCustomLocation("x"," relative");advanceUntilIdle();assertEquals("路径格式无效",vm.state.value.addError)
        fs.stats["/x"]=entry("/x",EntryType.DIRECTORY,true,false);vm.addCustomLocation("x","/x");advanceUntilIdle();assertNull(vm.state.value.addError);assertEquals("/x",store.addedPath?.value)
        fs.stats["/x"]=entry("/x",EntryType.DIRECTORY,true,true);store.addResult=CustomLocationResult.DuplicatePath;vm.addCustomLocation("x","/x");advanceUntilIdle();assertEquals("该路径已存在",vm.state.value.addError)
    }

    @Test fun `single custom location revalidation probes only requested row`()=runTest{
        val first=StorageLocation.Direct(LocationId.of("custom.first"),"first",root("/first"),StorageLocation.Source.CUSTOM)
        val second=StorageLocation.Direct(LocationId.of("custom.second"),"second",root("/second"),StorageLocation.Source.CUSTOM)
        val store=FakeStore().apply{flow.value=listOf(first,second)}
        val fs=FakeFs().apply{stats["/first"]=entry("/first",EntryType.DIRECTORY,true,true);stats["/second"]=entry("/second",EntryType.DIRECTORY,true,false)}
        val vm=LocationHomeViewModel(LocationHomeAppResolver{ResolvedAppLocation(it.id,it.displayName,emptyList(),0)},store,fs,StandardTestDispatcher(testScheduler));advanceUntilIdle()
        val initialCalls=fs.statCalls
        fs.stats.remove("/first")

        vm.revalidateCustomLocation(first.id)
        advanceUntilIdle()

        assertEquals(initialCalls+1,fs.statCalls)
        assertTrue(vm.state.value.customLocations.first().availability is LocationAvailability.Unavailable)
        assertTrue(vm.state.value.customLocations.last().availability is LocationAvailability.Available)
    }
    @Test fun `edit validates and updates exact id name and raw path`()=runTest{
        val store=FakeStore();val fs=FakeFs();val raw="/edited \n";fs.stats[raw]=entry(raw,EntryType.DIRECTORY,true,true)
        val vm=LocationHomeViewModel(LocationHomeAppResolver{ResolvedAppLocation(it.id,it.displayName,emptyList(),0)},store,fs,StandardTestDispatcher(testScheduler));advanceUntilIdle()
        vm.editCustomLocation(LocationId.of("custom.edit")," Edit ",raw);advanceUntilIdle()
        assertEquals("custom.edit",store.updatedId!!.value);assertEquals("Edit",store.updatedName);assertEquals(raw,store.updatedPath!!.value)
    }
    @Test fun `late refresh cannot overwrite newer app result`()=runTest{
        val old=CompletableDeferred<ResolvedAppLocation>();val fresh=CompletableDeferred<ResolvedAppLocation>();var calls=0
        val resolver=LocationHomeAppResolver{t->calls++;if(calls==1)withContext(NonCancellable){old.await()}else fresh.await()}
        val vm=LocationHomeViewModel(resolver,FakeStore(),FakeFs(),StandardTestDispatcher(testScheduler));testScheduler.runCurrent();vm.refresh();testScheduler.runCurrent()
        fresh.complete(ResolvedAppLocation(LocationCatalog.weChat.id,"fresh",emptyList(),0));testScheduler.runCurrent();old.complete(ResolvedAppLocation(LocationCatalog.weChat.id,"old",emptyList(),0));advanceUntilIdle()
        assertEquals("fresh",vm.state.value.appGroups.single().displayName)
    }
    @Test fun `custom probes run concurrently at max four and preserve order`()=runTest{
        val store=FakeStore();store.flow.value=(1..6).map{StorageLocation.Direct(LocationId.of("custom.$it"),"$it",root("/$it"),StorageLocation.Source.CUSTOM)}
        val fs=FakeFs().apply{blockStats=true;(1..6).forEach{stats["/$it"]=entry("/$it",EntryType.DIRECTORY,true,true)}}
        val vm=LocationHomeViewModel(LocationHomeAppResolver{ResolvedAppLocation(it.id,it.displayName,emptyList(),0)},store,fs,StandardTestDispatcher(testScheduler));testScheduler.runCurrent()
        assertEquals(4,fs.maxActive);assertTrue(fs.maxActive>1);fs.release.complete(Unit);advanceUntilIdle()
        assertEquals((1..6).map{it.toString()},vm.state.value.customLocations.map{it.location.displayName})
    }
    @Test fun `remove exposes progress maps not found and never stats`()=runTest{
        val store=FakeStore();store.removeGate=CompletableDeferred();val fs=FakeFs();val vm=LocationHomeViewModel(LocationHomeAppResolver{ResolvedAppLocation(it.id,it.displayName,emptyList(),0)},store,fs,StandardTestDispatcher(testScheduler));advanceUntilIdle();val calls=fs.statCalls
        vm.removeCustomLocation(LocationId.of("missing"));testScheduler.runCurrent();assertTrue(vm.state.value.operationInProgress)
        store.removeGate!!.complete(CustomLocationResult.NotFound);advanceUntilIdle();assertFalse(vm.state.value.operationInProgress);assertEquals("位置不存在",vm.state.value.addError);assertEquals(calls,fs.statCalls)
    }
    @Test fun `queued stale mutation cannot execute or overwrite newer operation`()=runTest{
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store=FakeStore().apply{removeGate=CompletableDeferred()};val fs=FakeFs().apply{stats["/old"]=entry("/old",EntryType.DIRECTORY,true,true)}
        val vm=LocationHomeViewModel(LocationHomeAppResolver{ResolvedAppLocation(it.id,it.displayName,emptyList(),0)},store,fs,StandardTestDispatcher(testScheduler))
        vm.addCustomLocation("old","/old")
        vm.removeCustomLocation(LocationId.of("new"))
        assertTrue(vm.state.value.operationInProgress)
        testScheduler.runCurrent();store.removeGate!!.complete(CustomLocationResult.NotFound);advanceUntilIdle()
        assertEquals(0,store.addCalls);assertEquals(0,fs.statCalls);assertFalse(vm.state.value.operationInProgress);assertEquals("位置不存在",vm.state.value.addError)
    }
    @Test fun `in flight store side effects are serialized in call order`()=runTest{
        val store=FakeStore().apply{blockMutations=true};val fs=FakeFs().apply{stats["/a"]=entry("/a",EntryType.DIRECTORY,true,true);stats["/b"]=entry("/b",EntryType.DIRECTORY,true,true)}
        val vm=LocationHomeViewModel(LocationHomeAppResolver{ResolvedAppLocation(it.id,it.displayName,emptyList(),0)},store,fs,StandardTestDispatcher(testScheduler));advanceUntilIdle()
        vm.addCustomLocation("a","/a");testScheduler.runCurrent();vm.editCustomLocation(LocationId.of("b"),"b","/b");testScheduler.runCurrent()
        assertEquals(1,store.maxActive);assertEquals(listOf("add"),store.started)
        store.releaseOne.complete(Unit);testScheduler.runCurrent();assertEquals(listOf("add","update"),store.started);assertEquals(1,store.maxActive)
        store.releaseTwo.complete(Unit);advanceUntilIdle()
    }

    private class FakeStore:LocationHomeCustomStore{
        val flow=MutableStateFlow<List<StorageLocation.Direct>>(emptyList())
        var addedName:String?=null;var addedPath:RootPath?=null;var removed:LocationId?=null;var addResult:CustomLocationResult=CustomLocationResult.Success
        var addCalls=0
        var updatedId:LocationId?=null;var updatedName:String?=null;var updatedPath:RootPath?=null
        var removeGate:CompletableDeferred<CustomLocationResult>?=null
        var blockMutations=false;val releaseOne=CompletableDeferred<Unit>();val releaseTwo=CompletableDeferred<Unit>();var active=0;var maxActive=0;val started=mutableListOf<String>()
        override fun observeAll()=flow
        override suspend fun add(name:String,path:RootPath):CustomLocationResult{started+="add";active++;maxActive=maxOf(maxActive,active);if(blockMutations)releaseOne.await();active--;addCalls++;addedName=name;addedPath=path;return addResult}
        override suspend fun update(id:LocationId,name:String,path:RootPath):CustomLocationResult{started+="update";active++;maxActive=maxOf(maxActive,active);if(blockMutations)releaseTwo.await();active--;updatedId=id;updatedName=name;updatedPath=path;return CustomLocationResult.Success}
        override suspend fun remove(id:LocationId):CustomLocationResult{removed=id;return removeGate?.await()?:CustomLocationResult.Success}
    }
    private class FakeFs:RootFileSystem{
        val stats=mutableMapOf<String,DirectoryEntry>()
        var blockStats=false;val release=CompletableDeferred<Unit>();var active=0;var maxActive=0
        var statCalls=0;override suspend fun stat(path:RootPath):OperationResult<DirectoryEntry>{statCalls++;active++;maxActive=maxOf(maxActive,active);if(blockStats)release.await();active--;return stats[path.value]?.let{OperationResult.Success(it)}?:OperationResult.Failure(ErrorCode.NOT_FOUND,"missing")}
        override suspend fun list(path:RootPath):OperationResult<List<DirectoryEntry>> = error("unused")
        override suspend fun canonicalize(path:RootPath):OperationResult<RootPath> = error("unused")
        override suspend fun createDirectory(parent:RootPath,name:FolderName):OperationResult<DirectoryEntry> = error("unused")
    }
    private fun root(v:String)=RootPath.parse(v).getOrThrow()
    private fun entry(p:String,t:EntryType,r:Boolean,w:Boolean)=DirectoryEntry(root(p),p.substringAfterLast('/'),t,null,null,r,w,false)
}
