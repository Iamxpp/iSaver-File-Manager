package com.iamxpp.isaver.ui.virtualviews

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
}

data class VirtualViewUiState(
    val currentFolderId: String? = null,
    val breadcrumbs: List<VirtualViewNode.VirtualFolder> = emptyList(),
    val children: List<VirtualViewNode> = emptyList(),
    val allFolders: List<VirtualViewNode.VirtualFolder> = emptyList(),
    val loading: Boolean = true,
    val operationInProgress: Boolean = false,
    val error: String? = null,
    val confirmDeleteFolderId: String? = null,
)

class VirtualViewViewModel(
    private val store: VirtualViewStore,
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

    fun renameNode(nodeId: String, displayName: String) = mutate { store.renameNode(nodeId, displayName) }

    fun moveNode(nodeId: String, targetFolderId: String?) = mutate { store.moveNode(nodeId, targetFolderId) }

    fun removeReference(nodeId: String) = mutate { store.removeReference(nodeId) }

    fun deleteFolder(nodeId: String, confirmed: Boolean) = mutate(confirmationNodeId = nodeId) {
        store.deleteFolder(nodeId, confirmed)
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun dismissDeleteConfirmation() {
        mutableState.value = mutableState.value.copy(confirmDeleteFolderId = null)
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
            val result = try {
                withContext(ioDispatcher) { operation() }
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(operationInProgress = false)
                throw cancelled
            } catch (_: Exception) {
                null
            }
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

    private fun VirtualViewResult?.message(): String = when (this) {
        VirtualViewResult.InvalidName -> "名称不能为空或超过 120 个字符"
        VirtualViewResult.InvalidParent -> "真实文件和文件夹只能作为虚拟视图的最后一层，不能在其下添加内容。"
        VirtualViewResult.NotFound -> "虚拟视图项目不存在"
        VirtualViewResult.DuplicateReference -> "此位置已添加。可选择其他虚拟文件夹。"
        VirtualViewResult.Cycle -> "不能移动到自身或其子文件夹。"
        VirtualViewResult.InvalidNode -> "此项目不支持该操作"
        else -> "虚拟视图操作失败"
    }
}
