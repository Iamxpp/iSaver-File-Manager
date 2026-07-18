package com.iamxpp.isaver.ui
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.*
import com.iamxpp.isaver.locations.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
fun interface LocationHomeAppResolver{suspend fun resolve(template:AppPathTemplate):ResolvedAppLocation}
interface LocationHomeCustomStore{fun observeAll():Flow<List<StorageLocation.Direct>>;suspend fun add(name:String,path:RootPath):CustomLocationResult;suspend fun update(id:LocationId,name:String,path:RootPath):CustomLocationResult;suspend fun remove(id:LocationId):CustomLocationResult}
class LocationHomeViewModel(private val resolver:LocationHomeAppResolver,private val store:LocationHomeCustomStore,private val fs:RootFileSystem,private val dispatcher:CoroutineDispatcher):ViewModel(){
 private val mutable=MutableStateFlow(LocationHomeUiState(commonLocations=LocationCatalog.commonLocations));val state:StateFlow<LocationHomeUiState> = mutable.asStateFlow()
 private var appJob:Job?=null;private var appGeneration=0L
 private var customJob:Job?=null;private var customGeneration=0L
 init{refresh();refreshCustomLocations()}
 fun refreshCustomLocations(){val g=++customGeneration;customJob?.cancel();customJob=viewModelScope.launch{try{store.observeAll().collectLatest{items->mutable.value=mutable.value.copy(customLocations=items.map{CustomLocationState(it,LocationAvailability.Checking)});val checked=withContext(dispatcher){coroutineScope{val sem=Semaphore(4);items.map{loc->async{sem.withPermit{probe(loc)}}}.awaitAll()}};if(g==customGeneration)mutable.value=mutable.value.copy(customLocations=checked)}}catch(c:CancellationException){throw c}catch(_:Exception){if(g==customGeneration)mutable.value=mutable.value.copy(error="自定义位置数据无法读取")}}}
 fun refresh(){val g=++appGeneration;appJob?.cancel();mutable.value=mutable.value.copy(loading=true,error=null);appJob=viewModelScope.launch{try{val groups=withContext(dispatcher){LocationCatalog.appTemplates.map{resolver.resolve(it)}};if(g==appGeneration)mutable.value=mutable.value.copy(loading=false,appGroups=groups)}catch(c:CancellationException){throw c}catch(_:Exception){if(g==appGeneration)mutable.value=mutable.value.copy(loading=false,error="无法加载位置")}}}
 private suspend fun probe(loc:StorageLocation.Direct)=try{when(val r=fs.stat(loc.path)){is OperationResult.Success->if(r.value.type==EntryType.DIRECTORY&&r.value.readable)CustomLocationState(loc,LocationAvailability.Available(true,r.value.writable))else CustomLocationState(loc,LocationAvailability.Unavailable(if(r.value.type!=EntryType.DIRECTORY)"路径不是目录" else "目录不可读"));is OperationResult.Failure->CustomLocationState(loc,LocationAvailability.Unavailable(r.userMessage))}}catch(c:CancellationException){throw c}catch(_:Exception){CustomLocationState(loc,LocationAvailability.Unavailable("位置不可用"))}
 fun addCustomLocation(name:String,rawPath:String)=mutate(name,rawPath,null)
 fun editCustomLocation(id:LocationId,name:String,rawPath:String)=mutate(name,rawPath,id)
 fun clearAddError(){mutable.value=mutable.value.copy(addError=null)}
 private val revalidationGenerations=mutableMapOf<LocationId,Long>()
 fun revalidateCustomLocation(id:LocationId){
  val current=mutable.value.customLocations.firstOrNull{it.location.id==id}?:return
  val generation=(revalidationGenerations[id]?:0L)+1L
  revalidationGenerations[id]=generation
  mutable.value=mutable.value.copy(customLocations=mutable.value.customLocations.map{if(it.location.id==id)it.copy(availability=LocationAvailability.Checking)else it})
  viewModelScope.launch{
   val checked=withContext(dispatcher){probe(current.location)}
   if(revalidationGenerations[id]==generation){
    mutable.value=mutable.value.copy(customLocations=mutable.value.customLocations.map{
     if(it.location.id==id&&it.location.path==current.location.path)checked else it
    })
   }
  }
 }
 private var operationGeneration=0L
 private val mutationMutex=Mutex()
 fun removeCustomLocation(id:LocationId){val g=++operationGeneration;mutable.value=mutable.value.copy(operationInProgress=true,addError=null);viewModelScope.launch{if(g!=operationGeneration)return@launch;try{mutationMutex.withLock{if(g!=operationGeneration)return@withLock;finish(mapResult(store.remove(id)),g)}}catch(c:CancellationException){finish(null,g);throw c}catch(_:Exception){finish("删除位置失败",g)}}}
 private fun mutate(name:String,rawPath:String,id:LocationId?){val g=++operationGeneration;mutable.value=mutable.value.copy(operationInProgress=true,addError=null);viewModelScope.launch{if(g!=operationGeneration)return@launch;val n=name.trim();if(n.isEmpty()){finish("名称不能为空",g);return@launch};val p=RootPath.parse(rawPath).getOrElse{finish("路径格式无效",g);return@launch};try{when(val s=withContext(dispatcher){fs.stat(p)}){is OperationResult.Failure->{finish(mapCode(s.code),g);return@launch};is OperationResult.Success->{if(s.value.type!=EntryType.DIRECTORY){finish("路径不是目录",g);return@launch};if(!s.value.readable){finish("目录不可读",g);return@launch}}};mutationMutex.withLock{if(g!=operationGeneration)return@withLock;val result=if(id==null)store.add(n,p)else store.update(id,n,p);finish(mapResult(result),g,result==CustomLocationResult.Success)}}catch(c:CancellationException){finish(null,g);throw c}catch(_:Exception){finish("保存位置失败",g)}}}
 private fun finish(e:String?,g:Long,saveSucceeded:Boolean=false){if(g==operationGeneration)mutable.value=mutable.value.copy(operationInProgress=false,addError=e,saveSuccessVersion=mutable.value.saveSuccessVersion+if(saveSucceeded)1 else 0)}
 private fun mapCode(c:ErrorCode)=when(c){ErrorCode.NOT_FOUND->"路径不存在";ErrorCode.NOT_DIRECTORY->"路径不是目录";ErrorCode.NOT_READABLE->"目录不可读";ErrorCode.NOT_WRITABLE->"目录不可写";else->"无法校验路径"}
 private fun mapResult(r:CustomLocationResult)=when(r){CustomLocationResult.Success->null;CustomLocationResult.InvalidName->"名称不能为空";CustomLocationResult.DuplicatePath->"该路径已存在";CustomLocationResult.IdConflict->"位置标识冲突";CustomLocationResult.NotFound->"位置不存在";CustomLocationResult.InvalidOrder->"位置顺序无效"}
}
