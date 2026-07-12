package com.iamxpp.isaver.ui
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.*
import com.iamxpp.isaver.locations.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
fun interface LocationHomeAppResolver{suspend fun resolve(template:AppPathTemplate):ResolvedAppLocation}
interface LocationHomeCustomStore{fun observeAll():Flow<List<StorageLocation.Direct>>;suspend fun add(name:String,path:RootPath):CustomLocationResult;suspend fun update(id:LocationId,name:String,path:RootPath):CustomLocationResult;suspend fun remove(id:LocationId):CustomLocationResult}
class LocationHomeViewModel(private val resolver:LocationHomeAppResolver,private val store:LocationHomeCustomStore,private val fs:RootFileSystem,private val dispatcher:CoroutineDispatcher):ViewModel(){
 private val mutable=MutableStateFlow(LocationHomeUiState(commonLocations=LocationCatalog.commonLocations));val state:StateFlow<LocationHomeUiState> = mutable.asStateFlow()
 private var appJob:Job?=null;private var appGeneration=0L
 init{refresh();viewModelScope.launch{store.observeAll().collectLatest{items->mutable.value=mutable.value.copy(customLocations=items.map{CustomLocationState(it,LocationAvailability.Checking)});val checked=withContext(dispatcher){items.map{loc->probe(loc)}};mutable.value=mutable.value.copy(customLocations=checked)}}}
 fun refresh(){val g=++appGeneration;appJob?.cancel();mutable.value=mutable.value.copy(loading=true,error=null);appJob=viewModelScope.launch{try{val groups=withContext(dispatcher){LocationCatalog.appTemplates.map{resolver.resolve(it)}};if(g==appGeneration)mutable.value=mutable.value.copy(loading=false,appGroups=groups)}catch(c:CancellationException){throw c}catch(_:Exception){if(g==appGeneration)mutable.value=mutable.value.copy(loading=false,error="无法加载位置")}}}
 private suspend fun probe(loc:StorageLocation.Direct)=try{when(val r=fs.stat(loc.path)){is OperationResult.Success->if(r.value.type==EntryType.DIRECTORY&&r.value.readable)CustomLocationState(loc,LocationAvailability.Available(true,r.value.writable))else CustomLocationState(loc,LocationAvailability.Unavailable(if(r.value.type!=EntryType.DIRECTORY)"路径不是目录" else "目录不可读"));is OperationResult.Failure->CustomLocationState(loc,LocationAvailability.Unavailable(r.userMessage))}}catch(c:CancellationException){throw c}catch(_:Exception){CustomLocationState(loc,LocationAvailability.Unavailable("位置不可用"))}
 fun addCustomLocation(name:String,rawPath:String)=mutate(name,rawPath,null)
 fun editCustomLocation(id:LocationId,name:String,rawPath:String)=mutate(name,rawPath,id)
 fun removeCustomLocation(id:LocationId){viewModelScope.launch{store.remove(id)}}
 private fun mutate(name:String,rawPath:String,id:LocationId?){viewModelScope.launch{mutable.value=mutable.value.copy(operationInProgress=true,addError=null);val n=name.trim();if(n.isEmpty()){finish("名称不能为空");return@launch};val p=RootPath.parse(rawPath).getOrElse{finish("路径格式无效");return@launch};try{when(val s=withContext(dispatcher){fs.stat(p)}){is OperationResult.Failure->{finish(mapCode(s.code));return@launch};is OperationResult.Success->{if(s.value.type!=EntryType.DIRECTORY){finish("路径不是目录");return@launch};if(!s.value.readable){finish("目录不可读");return@launch};if(!s.value.writable){finish("目录不可写");return@launch}}};finish(mapResult(if(id==null)store.add(n,p)else store.update(id,n,p)))}catch(c:CancellationException){throw c}catch(_:Exception){finish("保存位置失败")}}}
 private fun finish(e:String?){mutable.value=mutable.value.copy(operationInProgress=false,addError=e)}
 private fun mapCode(c:ErrorCode)=when(c){ErrorCode.NOT_FOUND->"路径不存在";ErrorCode.NOT_DIRECTORY->"路径不是目录";ErrorCode.NOT_READABLE->"目录不可读";ErrorCode.NOT_WRITABLE->"目录不可写";else->"无法校验路径"}
 private fun mapResult(r:CustomLocationResult)=when(r){CustomLocationResult.Success->null;CustomLocationResult.InvalidName->"名称不能为空";CustomLocationResult.DuplicatePath->"该路径已存在";CustomLocationResult.IdConflict->"位置标识冲突";CustomLocationResult.NotFound->"位置不存在";CustomLocationResult.InvalidOrder->"位置顺序无效"}
}
