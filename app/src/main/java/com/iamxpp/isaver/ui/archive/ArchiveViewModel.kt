package com.iamxpp.isaver.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamxpp.isaver.archive.ArchiveListing
import com.iamxpp.isaver.archive.ArchiveNode
import com.iamxpp.isaver.archive.ArchiveProgress
import com.iamxpp.isaver.archive.ArchiveState
import com.iamxpp.isaver.archive.children
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.tasks.OperationTaskState
import com.iamxpp.isaver.tasks.OperationTaskStore
import com.iamxpp.isaver.tasks.OperationTaskType
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.HomeTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ArchiveViewModel(
    private val inspectArchive: suspend (RootPath) -> OperationResult<ArchiveListing>,
    private val extractArchive: (RootPath, RootPath) -> Flow<ArchiveState>,
    private val recordAccess: suspend (RootPath, String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher,
    private val operationTaskStore: OperationTaskStore? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ArchiveUiState())
    private var inspectJob: Job? = null
    private var extractionJob: Job? = null
    private var requestGeneration = 0L

    val state: StateFlow<ArchiveUiState> = mutableState.asStateFlow()

    fun open(source: RootPath, sourceName: String, sourceTab: HomeTab) {
        val generation = ++requestGeneration
        inspectJob?.cancel()
        extractionJob?.cancel()
        mutableState.value = ArchiveUiState(
            source = source,
            sourceName = sourceName,
            sourceTab = sourceTab,
            loading = true,
            displayMode = mutableState.value.displayMode,
        )
        inspectJob = viewModelScope.launch {
            val result = try {
                withContext(ioDispatcher) { inspectArchive(source) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OperationResult.Failure(
                    com.iamxpp.isaver.domain.ErrorCode.COMMAND_FAILED,
                    "无法读取压缩包",
                )
            }
            if (generation != requestGeneration) return@launch
            when (result) {
                is OperationResult.Success -> {
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        listing = result.value,
                        nodes = result.value.children(""),
                        errorMessage = null,
                    )
                    recordAccessWithoutBlocking(source, sourceName)
                }
                is OperationResult.Failure -> mutableState.value = mutableState.value.copy(
                    loading = false,
                    listing = null,
                    nodes = emptyList(),
                    errorMessage = result.userMessage,
                )
            }
        }
    }

    fun retry() {
        val current = mutableState.value
        val source = current.source ?: return
        open(source, current.sourceName, current.sourceTab)
    }

    fun enter(node: ArchiveNode) {
        if (!node.directory) return
        val listing = mutableState.value.listing ?: return
        mutableState.value = mutableState.value.copy(
            prefix = node.path,
            nodes = listing.children(node.path),
            searchQuery = "",
        )
    }

    fun back(): ArchiveBackResult {
        val current = mutableState.value
        if (current.prefix.isEmpty()) return ArchiveBackResult.CLOSE_ARCHIVE
        val parent = current.prefix.substringBeforeLast('/', "")
        val listing = current.listing ?: return ArchiveBackResult.CLOSE_ARCHIVE
        mutableState.value = current.copy(
            prefix = parent,
            nodes = listing.children(parent),
            searchQuery = "",
        )
        return ArchiveBackResult.NAVIGATED
    }

    fun setSearchQuery(query: String) {
        mutableState.value = mutableState.value.copy(searchQuery = query)
    }

    fun setDisplayMode(mode: DisplayMode) {
        mutableState.value = mutableState.value.copy(displayMode = mode)
    }

    fun chooseExtractionTarget() {
        if (mutableState.value.listing == null || mutableState.value.operation != null) return
        mutableState.value = mutableState.value.copy(extractionTargetRequested = true)
    }

    fun consumeExtractionTargetRequest() {
        mutableState.value = mutableState.value.copy(extractionTargetRequested = false)
    }

    fun extractTo(targetDirectory: RootPath) {
        val source = mutableState.value.source ?: return
        val totalItems = mutableState.value.listing?.entries?.size?.coerceAtLeast(1) ?: 1
        extractionJob?.cancel()
        mutableState.value = mutableState.value.copy(
            extractionTargetRequested = false,
            operation = ArchiveState.Preparing,
        )
        extractionJob = viewModelScope.launch {
            val taskId = operationTaskStore?.start(OperationTaskType.EXTRACT, totalItems)
            try {
                extractArchive(source, targetDirectory).collect { operation ->
                    mutableState.value = mutableState.value.copy(operation = operation)
                    taskId?.let { id -> updateTask(id, operation, totalItems) }
                }
            } catch (cancelled: CancellationException) {
                val completedItems = mutableState.value.operation
                    ?.progressItems(totalItems)
                    ?: 0
                taskId?.let { id ->
                    withContext(NonCancellable) {
                        updateTaskTerminal(id, OperationTaskState.CANCELLED, completedItems, "解压已取消")
                    }
                }
                mutableState.value = mutableState.value.copy(operation = null)
                throw cancelled
            } catch (_: Exception) {
                taskId?.let { id ->
                    withContext(NonCancellable) {
                        updateTaskTerminal(id, OperationTaskState.FAILED, totalItems, "无法解压文件")
                    }
                }
                mutableState.value = mutableState.value.copy(
                    operation = ArchiveState.Failure(
                        com.iamxpp.isaver.domain.ErrorCode.COMMAND_FAILED,
                        "无法解压文件",
                    ),
                )
            }
        }
    }

    private suspend fun updateTask(id: String, operation: ArchiveState?, totalItems: Int) {
        val update = when (operation) {
            null -> TaskUpdate(OperationTaskState.FAILED, 0, "无法解压文件")
            is ArchiveState.Success -> TaskUpdate(OperationTaskState.SUCCESS, totalItems)
            is ArchiveState.Failure -> TaskUpdate(
                if (operation.code == com.iamxpp.isaver.domain.ErrorCode.OUTCOME_UNCERTAIN) {
                    OperationTaskState.OUTCOME_UNCERTAIN
                } else {
                    OperationTaskState.FAILED
                },
                0,
                operation.message,
            )
            else -> TaskUpdate(OperationTaskState.RUNNING, operation.progressItems(totalItems))
        }
        updateTaskTerminal(id, update.state, update.completedItems, update.message)
    }

    private suspend fun updateTaskTerminal(
        id: String,
        state: OperationTaskState,
        completedItems: Int,
        message: String?,
    ) {
        try {
            operationTaskStore?.update(id, state, completedItems, message = message)
        } catch (_: Exception) {
            Unit
        }
    }

    private fun ArchiveState.progressItems(totalItems: Int): Int = when (this) {
        ArchiveState.Preparing,
        ArchiveState.Cleaning,
        ArchiveState.Finalizing,
        -> 0
        is ArchiveState.Running -> when (val progress = progress) {
            ArchiveProgress.Preparing -> 0
            is ArchiveProgress.Entry -> 0
            is ArchiveProgress.Publishing -> progress.completedEntries.toInt().coerceIn(0, totalItems)
        }
        is ArchiveState.Publishing -> 0
        is ArchiveState.Success -> totalItems
        is ArchiveState.Failure -> 0
    }

    private data class TaskUpdate(
        val state: OperationTaskState,
        val completedItems: Int,
        val message: String? = null,
    )


    fun cancelExtraction() {
        val operation = mutableState.value.operation
        if (operation !is ArchiveState.Preparing && operation !is ArchiveState.Running) return
        mutableState.value = mutableState.value.copy(operation = ArchiveState.Cleaning)
        extractionJob?.cancel()
    }

    fun dismissOperation() {
        if (mutableState.value.operation is ArchiveState.Success ||
            mutableState.value.operation is ArchiveState.Failure
        ) {
            mutableState.value = mutableState.value.copy(operation = null)
        }
    }

    private suspend fun recordAccessWithoutBlocking(source: RootPath, sourceName: String) {
        try {
            withContext(ioDispatcher) { recordAccess(source, sourceName) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Unit
        }
    }
}
