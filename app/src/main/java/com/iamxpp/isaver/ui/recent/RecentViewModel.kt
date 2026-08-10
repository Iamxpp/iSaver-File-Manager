package com.iamxpp.isaver.ui.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.recent.RecentItem
import com.iamxpp.isaver.recent.RecentItemType
import com.iamxpp.isaver.recent.RecentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class RecentViewModel(
    private val repository: RecentRepository,
    private val fileSystem: RootFileSystem,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RecentUiState())
    private var currentItems: List<RecentItem> = emptyList()
    private var probeJob: Job? = null
    private var generation = 0L

    val state: StateFlow<RecentUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeRecent().collectLatest { items ->
                currentItems = items
                probe(items)
            }
        }
    }

    fun refresh() = probe(currentItems)

    fun open(item: RecentUiItem): RecentOpenTarget? {
        val entry = (item.availability as? RecentAvailability.Available)?.entry ?: return null
        return when (item.item.type) {
            RecentItemType.DIRECTORY -> RecentOpenTarget.Directory(entry.path, item.item.displayName)
            RecentItemType.ARCHIVE -> RecentOpenTarget.Archive(entry.path, item.item.displayName)
            RecentItemType.FILE -> RecentOpenTarget.File(entry)
        }
    }

    fun dismissFileInfo() {
        mutableState.value = mutableState.value.copy(fileInfo = null)
    }

    private fun probe(items: List<RecentItem>) {
        val request = ++generation
        probeJob?.cancel()
        mutableState.value = mutableState.value.copy(
            items = items.map { RecentUiItem(it) },
            refreshing = items.isNotEmpty(),
            errorMessage = null,
        )
        if (items.isEmpty()) return
        probeJob = viewModelScope.launch {
            try {
                val checked = withContext(ioDispatcher) {
                    val semaphore = Semaphore(4)
                    coroutineScope {
                        items.map { item ->
                            async { semaphore.withPermit { probeOne(item) } }
                        }.awaitAll()
                    }
                }
                if (request == generation) {
                    mutableState.value = mutableState.value.copy(items = checked, refreshing = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) {
                    mutableState.value = mutableState.value.copy(
                        items = items.map { RecentUiItem(it, RecentAvailability.Unavailable("无法校验项目")) },
                        refreshing = false,
                        errorMessage = "无法校验最近项目",
                    )
                }
            }
        }
    }

    private suspend fun probeOne(item: RecentItem): RecentUiItem {
        return when (val result = fileSystem.stat(item.path)) {
            is OperationResult.Success -> {
                repository.markAvailability(item.path, true)
                RecentUiItem(item.copy(available = true), RecentAvailability.Available(result.value))
            }
            is OperationResult.Failure -> {
                repository.markAvailability(item.path, false)
                RecentUiItem(item.copy(available = false), RecentAvailability.Unavailable(result.userMessage))
            }
        }
    }
}
