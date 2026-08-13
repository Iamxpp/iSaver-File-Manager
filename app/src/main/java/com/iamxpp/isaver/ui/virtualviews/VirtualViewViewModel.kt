package com.iamxpp.isaver.ui.virtualviews

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootEntryIdentity
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.virtualviews.VirtualViewNode
import com.iamxpp.isaver.virtualviews.VirtualViewRepository
import com.iamxpp.isaver.virtualviews.VirtualViewResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface VirtualViewStore {
    fun observeChildren(parentFolderId: String?): Flow<List<VirtualViewNode>>
    fun observeAll(): Flow<List<VirtualViewNode>> = observeChildren(null)
    suspend fun findNode(id: String): VirtualViewNode?
    suspend fun createFolder(parentFolderId: String?, name: String): VirtualViewResult
    suspend fun renameNode(nodeId: String, displayName: String): VirtualViewResult
    suspend fun moveNode(nodeId: String, targetFolderId: String?): VirtualViewResult
    suspend fun deleteFolder(nodeId: String, confirmed: Boolean): VirtualViewResult
    suspend fun removeReference(nodeId: String): VirtualViewResult
    suspend fun addReference(
        targetFolderId: String,
        path: RootPath,
        type: EntryType,
        identity: RootEntryIdentity?,
        displayName: String,
    ): VirtualViewResult
    suspend fun setReferenceAvailability(nodeId: String, available: Boolean): VirtualViewResult
    suspend fun rebindReference(
        nodeId: String,
        path: RootPath,
        type: EntryType,
        identity: RootEntryIdentity,
    ): VirtualViewResult
}

class VirtualViewRepositoryStore(private val repository: VirtualViewRepository) : VirtualViewStore {
    override fun observeChildren(parentFolderId: String?) = repository.observeChildren(parentFolderId)
    override fun observeAll() = repository.observeAll()
    override suspend fun findNode(id: String) = repository.findNode(id)
    override suspend fun createFolder(parentFolderId: String?, name: String) = repository.createFolder(parentFolderId, name)
    override suspend fun renameNode(nodeId: String, displayName: String) = repository.renameNode(nodeId, displayName)
    override suspend fun moveNode(nodeId: String, targetFolderId: String?) = repository.moveNode(nodeId, targetFolderId)
    override suspend fun deleteFolder(nodeId: String, confirmed: Boolean) = repository.deleteFolder(nodeId, confirmed)
    override suspend fun removeReference(nodeId: String) = repository.removeReference(nodeId)
    override suspend fun addReference(
        targetFolderId: String,
        path: RootPath,
        type: EntryType,
        identity: RootEntryIdentity?,
        displayName: String,
    ) = repository.addReference(targetFolderId, path, type, identity, displayName)
    override suspend fun setReferenceAvailability(nodeId: String, available: Boolean) =
        repository.setReferenceAvailability(nodeId, available)
    override suspend fun rebindReference(
        nodeId: String,
        path: RootPath,
        type: EntryType,
        identity: RootEntryIdentity,
    ) = repository.rebindReference(nodeId, path, type, identity)
}

data class PendingVirtualReference(val path: RootPath, val displayName: String, val entryType: EntryType)
data class PendingReferenceRebind(val nodeId: String, val entryType: EntryType, val previousPath: RootPath)
data class VerifiedVirtualReference(val nodeId: String, val displayName: String, val entry: com.iamxpp.isaver.domain.DirectoryEntry)

data class VirtualViewUiState(
    val currentFolderId: String? = null,
    val breadcrumbs: List<VirtualViewNode.VirtualFolder> = emptyList(),
    val children: List<VirtualViewNode> = emptyList(),
    val allFolders: List<VirtualViewNode.VirtualFolder> = emptyList(),
    val loading: Boolean = true,
    val operationInProgress: Boolean = false,
    val error: String? = null,
    val confirmDeleteFolderId: String? = null,
    val pendingReference: PendingVirtualReference? = null,
    val pickerFolderId: String? = null,
    val message: String? = null,
    val verifiedReference: VerifiedVirtualReference? = null,
    val pendingRebind: PendingReferenceRebind? = null,
)

class VirtualViewViewModel(
    private val store: VirtualViewStore,
    private val rootFileSystem: RootFileSystem? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(VirtualViewUiState())
    val state: StateFlow<VirtualViewUiState> = mutableState.asStateFlow()
    private var childrenJob: Job? = null

    init {
        observeAllFolders()
        navigateTo(null)
    }

    fun openFolder(folder: VirtualViewNode.VirtualFolder) = navigateTo(folder.id)

    fun navigateTo(folderId: String?) {
        childrenJob?.cancel()
        mutableState.value = mutableState.value.copy(currentFolderId = folderId, loading = true, error = null)
        childrenJob = viewModelScope.launch {
            try {
                val breadcrumbs = withContext(ioDispatcher) { buildBreadcrumbs(folderId) }
                store.observeChildren(folderId).collectLatest { children ->
                    mutableState.value = mutableState.value.copy(
                        currentFolderId = folderId,
                        breadcrumbs = breadcrumbs,
                        children = children,
                        loading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, error = "无法读取虚拟视图位置")
            }
        }
    }

    fun createFolder(name: String) = mutate { store.createFolder(state.value.currentFolderId, name) }

    fun createPickerFolder(name: String) {
        if (state.value.operationInProgress) return
        mutableState.value = state.value.copy(operationInProgress = true, error = null, message = null)
        viewModelScope.launch {
            val result = runOperation { store.createFolder(state.value.pickerFolderId, name) }
            mutableState.value = when (result) {
                is VirtualViewResult.Success -> state.value.copy(
                    operationInProgress = false,
                    pickerFolderId = result.nodeId,
                )
                else -> state.value.copy(operationInProgress = false, error = result.message())
            }
        }
    }

    fun renameNode(nodeId: String, displayName: String) = mutate { store.renameNode(nodeId, displayName) }

    fun moveNode(nodeId: String, targetFolderId: String?) = mutate { store.moveNode(nodeId, targetFolderId) }

    fun removeReference(nodeId: String) = mutate { store.removeReference(nodeId) }

    fun openReference(reference: VirtualViewNode.RealReference) {
        val fileSystem = rootFileSystem ?: return
        if (state.value.operationInProgress) return
        mutableState.value = state.value.copy(operationInProgress = true, error = null, verifiedReference = null)
        viewModelScope.launch {
            val verified = withContext(ioDispatcher) { verifyReference(reference, fileSystem) }
            mutableState.value = when (verified) {
                is ReferenceVerification.Valid -> {
                    val valid = verified
                    if (!reference.available) store.setReferenceAvailability(reference.id, true)
                    if (reference.identity == null || reference.targetPath != valid.path) {
                        store.rebindReference(reference.id, valid.path, reference.entryType, valid.identity)
                    }
                    state.value.copy(
                        operationInProgress = false,
                        verifiedReference = VerifiedVirtualReference(
                            reference.id,
                            reference.displayName,
                            valid.entry.copy(path = valid.path),
                        ),
                    )
                }
                ReferenceVerification.Invalid -> {
                    store.setReferenceAvailability(reference.id, false)
                    state.value.copy(
                        operationInProgress = false,
                        error = "原项目已移动、删除或被替换。",
                    )
                }
            }
        }
    }

    fun consumeVerifiedReference() {
        mutableState.value = state.value.copy(verifiedReference = null)
    }

    fun beginRebind(reference: VirtualViewNode.RealReference) {
        mutableState.value = state.value.copy(
            pendingRebind = PendingReferenceRebind(reference.id, reference.entryType, reference.targetPath),
            error = null,
            message = null,
        )
    }

    fun cancelRebind() {
        mutableState.value = state.value.copy(pendingRebind = null)
    }

    fun confirmRebind(entry: com.iamxpp.isaver.domain.DirectoryEntry) {
        val pending = state.value.pendingRebind ?: return
        val fileSystem = rootFileSystem ?: return
        if (entry.type != pending.entryType) {
            mutableState.value = state.value.copy(error = "重新定位项目类型必须与原引用一致。")
            return
        }
        if (state.value.operationInProgress) return
        mutableState.value = state.value.copy(operationInProgress = true, error = null)
        viewModelScope.launch {
            val candidate = VirtualViewNode.RealReference(
                id = pending.nodeId,
                parentId = "rebind",
                displayName = entry.name,
                targetPath = entry.path,
                entryType = pending.entryType,
                identity = null,
                available = true,
                sortOrder = 0,
                createdAt = 0,
                updatedAt = 0,
            )
            val verified = withContext(ioDispatcher) { verifyReference(candidate, fileSystem) }
            mutableState.value = when (verified) {
                is ReferenceVerification.Valid -> {
                    val result = store.rebindReference(
                        pending.nodeId,
                        verified.path,
                        pending.entryType,
                        verified.identity,
                    )
                    when (result) {
                    is VirtualViewResult.Success -> state.value.copy(
                        operationInProgress = false,
                        pendingRebind = null,
                        message = "已重新绑定真实项目",
                    )
                    else -> state.value.copy(operationInProgress = false, error = result.message())
                    }
                }
                ReferenceVerification.Invalid -> state.value.copy(
                    operationInProgress = false,
                    error = "无法校验真实项目，请重试。",
                )
            }
        }
    }

    fun deleteFolder(nodeId: String, confirmed: Boolean) = mutate(confirmationNodeId = nodeId) {
        store.deleteFolder(nodeId, confirmed)
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun dismissDeleteConfirmation() {
        mutableState.value = mutableState.value.copy(confirmDeleteFolderId = null)
    }

    fun beginAddReference(path: RootPath, displayName: String, entryType: EntryType) {
        if (entryType != EntryType.FILE && entryType != EntryType.DIRECTORY) {
            mutableState.value = state.value.copy(error = "此项目不能添加到虚拟视图位置")
            return
        }
        mutableState.value = state.value.copy(
            pendingReference = PendingVirtualReference(path, displayName, entryType),
            pickerFolderId = null,
            error = null,
            message = null,
        )
    }

    fun openPickerFolder(folderId: String?) {
        mutableState.value = state.value.copy(pickerFolderId = folderId, error = null)
    }

    fun dismissAddReference() {
        mutableState.value = state.value.copy(pendingReference = null, pickerFolderId = null, error = null)
    }

    fun confirmAddReference(displayName: String) {
        val pending = state.value.pendingReference ?: return
        val folderId = state.value.pickerFolderId ?: run {
            mutableState.value = state.value.copy(error = "请选择一个虚拟文件夹")
            return
        }
        val fileSystem = rootFileSystem ?: run {
            mutableState.value = state.value.copy(error = "无法校验真实项目，请重试。")
            return
        }
        if (state.value.operationInProgress) return
        mutableState.value = state.value.copy(operationInProgress = true, error = null, message = null)
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                val initial = fileSystem.stat(pending.path) as? OperationResult.Success
                    ?: return@withContext AddReferenceOutcome.Failure("无法校验真实项目，请重试。")
                if (initial.value.type != pending.entryType) {
                    return@withContext AddReferenceOutcome.Failure("真实项目类型已变化，请重新选择。")
                }
                val canonical = fileSystem.canonicalize(pending.path) as? OperationResult.Success
                    ?: return@withContext AddReferenceOutcome.Failure("无法校验真实项目，请重试。")
                val canonicalEntry = fileSystem.stat(canonical.value) as? OperationResult.Success
                    ?: return@withContext AddReferenceOutcome.Failure("无法校验真实项目，请重试。")
                if (canonicalEntry.value.type != pending.entryType) {
                    return@withContext AddReferenceOutcome.Failure("真实项目类型已变化，请重新选择。")
                }
                val identity = fileSystem.identity(canonical.value) as? OperationResult.Success
                    ?: return@withContext AddReferenceOutcome.Failure("无法校验真实项目，请重试。")
                AddReferenceOutcome.Result(
                    store.addReference(folderId, canonical.value, pending.entryType, identity.value, displayName),
                )
            }
            mutableState.value = when (result) {
                is AddReferenceOutcome.Failure -> state.value.copy(operationInProgress = false, error = result.message)
                is AddReferenceOutcome.Result -> when (val value = result.value) {
                    is VirtualViewResult.Success -> {
                        val folderName = state.value.allFolders.firstOrNull { it.id == folderId }?.displayName.orEmpty()
                        state.value.copy(
                            operationInProgress = false,
                            pendingReference = null,
                            pickerFolderId = null,
                            message = "已添加到“$folderName”",
                        )
                    }
                    else -> state.value.copy(operationInProgress = false, error = value.message())
                }
            }
        }
    }

    fun clearMessage() {
        mutableState.value = state.value.copy(message = null)
    }

    private fun observeAllFolders() {
        viewModelScope.launch {
            store.observeAll().collectLatest { nodes ->
                mutableState.value = mutableState.value.copy(
                    allFolders = nodes.filterIsInstance<VirtualViewNode.VirtualFolder>(),
                )
            }
        }
    }

    private fun mutate(confirmationNodeId: String? = null, operation: suspend () -> VirtualViewResult) {
        if (state.value.operationInProgress) return
        mutableState.value = mutableState.value.copy(operationInProgress = true, error = null)
        viewModelScope.launch {
            val result = runOperation(operation)
            mutableState.value = when (result) {
                is VirtualViewResult.ConfirmationRequired -> mutableState.value.copy(
                    operationInProgress = false,
                    confirmDeleteFolderId = confirmationNodeId,
                )
                is VirtualViewResult.Success -> mutableState.value.copy(
                    operationInProgress = false,
                    confirmDeleteFolderId = null,
                )
                else -> mutableState.value.copy(operationInProgress = false, error = result.message())
            }
        }
    }

    private suspend fun runOperation(operation: suspend () -> VirtualViewResult): VirtualViewResult? = try {
        withContext(ioDispatcher) { operation() }
    } catch (cancelled: CancellationException) {
        mutableState.value = mutableState.value.copy(operationInProgress = false)
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun buildBreadcrumbs(folderId: String?): List<VirtualViewNode.VirtualFolder> {
        var current = folderId
        val reverse = mutableListOf<VirtualViewNode.VirtualFolder>()
        val visited = hashSetOf<String>()
        while (current != null) {
            if (!visited.add(current)) throw IllegalStateException("Virtual view cycle")
            val folder = store.findNode(current) as? VirtualViewNode.VirtualFolder ?: break
            reverse += folder
            current = folder.parentId
        }
        return reverse.asReversed()
    }

    private suspend fun verifyReference(
        reference: VirtualViewNode.RealReference,
        fileSystem: RootFileSystem,
    ): ReferenceVerification {
        val original = fileSystem.stat(reference.targetPath) as? OperationResult.Success
            ?: return ReferenceVerification.Invalid
        if (original.value.type != reference.entryType) return ReferenceVerification.Invalid
        val canonical = fileSystem.canonicalize(reference.targetPath) as? OperationResult.Success
            ?: return ReferenceVerification.Invalid
        val canonicalEntry = fileSystem.stat(canonical.value) as? OperationResult.Success
            ?: return ReferenceVerification.Invalid
        if (canonicalEntry.value.type != reference.entryType || canonicalEntry.value.symbolicLink) {
            return ReferenceVerification.Invalid
        }
        val identity = fileSystem.identity(canonical.value) as? OperationResult.Success
            ?: return ReferenceVerification.Invalid
        if (reference.identity != null && reference.identity != identity.value) return ReferenceVerification.Invalid
        return ReferenceVerification.Valid(canonical.value, canonicalEntry.value, identity.value)
    }

    private fun VirtualViewResult?.message(): String = when (this) {
        VirtualViewResult.InvalidName -> "名称不能为空或超过 120 个字符"
        VirtualViewResult.InvalidParent -> "真实文件和文件夹只能作为虚拟视图的最后一层，不能在其下添加内容。"
        VirtualViewResult.NotFound -> "虚拟视图项目不存在"
        VirtualViewResult.DuplicateReference -> "此位置已添加。可选择其他虚拟文件夹。"
        VirtualViewResult.Cycle -> "不能移动到自身或其子文件夹。"
        VirtualViewResult.InvalidNode -> "此项目不支持该操作"
        else -> "虚拟视图操作失败"
    }

    private sealed interface AddReferenceOutcome {
        data class Result(val value: VirtualViewResult) : AddReferenceOutcome
        data class Failure(val message: String) : AddReferenceOutcome
    }

    private sealed interface ReferenceVerification {
        data class Valid(
            val path: RootPath,
            val entry: com.iamxpp.isaver.domain.DirectoryEntry,
            val identity: RootEntryIdentity,
        ) : ReferenceVerification
        data object Invalid : ReferenceVerification
    }
}
