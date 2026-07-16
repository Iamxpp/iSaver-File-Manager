package com.iamxpp.isaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamxpp.isaver.data.local.BrowserPreferencesStore
import com.iamxpp.isaver.data.root.DirectorySnapshot
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.archive.ArchiveRepository
import com.iamxpp.isaver.archive.ArchiveState
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.ui.files.FileEntrySorter
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.SortSpec
import com.iamxpp.isaver.transfer.OutputNameDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowserViewModel(
    private val rootFileSystem: RootFileSystem,
    private val ioDispatcher: CoroutineDispatcher,
    private val preferencesStore: BrowserPreferencesStore,
    private val archiveRepository: ArchiveRepository? = null,
    private val snapshotCache: DirectorySnapshotCache = DirectorySnapshotCache(),
    private val sorter: (List<DirectoryEntry>, SortSpec) -> List<DirectoryEntry> = FileEntrySorter::sort,
) : ViewModel() {
    private val initialPath = RootPath.parse(INITIAL_PATH).getOrThrow()
    private var selectedRootPath = initialPath
    private val stack = ArrayDeque<RootPath>()
    private val mutableState = MutableStateFlow(BrowserUiState(currentPath = initialPath))
    private var loadJob: Job? = null
    private var presentationJob: Job? = null
    private var createDirectoryJob: Job? = null
    private var generation = 0L
    private var visibleCount = PAGE_SIZE
    private var presentedEntries: List<DirectoryEntry> = emptyList()

    val state: StateFlow<BrowserUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesStore.preferences.collect { preferences ->
                val sortChanged = preferences.sortSpec != mutableState.value.sortSpec
                if (sortChanged) resetPresentationWindow()
                mutableState.value = mutableState.value.copy(
                    displayMode = preferences.displayMode,
                    sortSpec = preferences.sortSpec,
                )
                if (sortChanged) refreshPresentation()
            }
        }
    }

    fun enterDirectory(entry: DirectoryEntry): Boolean {
        if (entry.type != EntryType.DIRECTORY) return false
        stack.addLast(mutableState.value.currentPath)
        load(entry.path)
        return true
    }

    fun toggleSelection(entry: DirectoryEntry) {
        if (entry.type != EntryType.FILE) return
        val selected = mutableState.value.selectedEntries.toMutableSet()
        if (!selected.add(entry)) selected.remove(entry)
        mutableState.value = mutableState.value.copy(selectedEntries = selected)
    }

    fun compress(outputName: String) {
        val repository = archiveRepository ?: return
        val current = mutableState.value
        if (current.selectedEntries.isEmpty() || current.compressing) return
        val draft = OutputNameDraft.fromDisplayName(outputName)
        mutableState.value = current.copy(compressing = true, compressionMessage = null)
        viewModelScope.launch {
            try {
                repository.createZip(
                    sources = current.selectedEntries.toList(),
                    targetDirectory = current.currentPath,
                    outputName = draft,
                ).collect { state ->
                    when (state) {
                        is ArchiveState.Success -> mutableState.value = mutableState.value.copy(
                            compressing = false,
                            compressionMessage = "压缩完成",
                            selectedEntries = emptySet(),
                        )
                        is ArchiveState.Failure -> mutableState.value = mutableState.value.copy(
                            compressing = false,
                            compressionMessage = state.message,
                        )
                        else -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(compressing = false)
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    compressing = false,
                    compressionMessage = "压缩失败",
                )
            }
        }
    }

    fun clearCompressionMessage() {
        mutableState.value = mutableState.value.copy(compressionMessage = null)
    }

    fun openRoot(path: RootPath, title: String) {
        stack.clear()
        selectedRootPath = path
        mutableState.value = mutableState.value.copy(rootTitle = title)
        load(path)
    }

    fun back(): BrowserBackResult {
        val previous = stack.removeLastOrNull() ?: return BrowserBackResult.RETURN_HOME
        load(previous)
        return BrowserBackResult.NAVIGATED
    }

    fun retry() = load(mutableState.value.currentPath)

    fun setDisplayMode(displayMode: DisplayMode) {
        savePresentationPreference { preferencesStore.setDisplayMode(displayMode) }
    }

    fun setSort(sortSpec: SortSpec) {
        savePresentationPreference { preferencesStore.setSort(sortSpec) }
    }

    fun setSearchQuery(searchQuery: String) {
        resetPresentationWindow()
        mutableState.value = mutableState.value.copy(searchQuery = searchQuery)
        refreshPresentation()
    }

    fun createDirectory(rawName: String) {
        if (createDirectoryJob?.isActive == true) return
        if (!mutableState.value.canCreateDirectory) {
            mutableState.value = mutableState.value.copy(
                createDirectoryError = BrowserOperationError(ErrorCode.NOT_WRITABLE, "目录不可写"),
            )
            return
        }
        val name = FolderName.parse(rawName).getOrElse {
            mutableState.value = mutableState.value.copy(
                createDirectoryError = BrowserOperationError(ErrorCode.COMMAND_FAILED, "文件夹名称无效"),
            )
            return
        }
        val parent = mutableState.value.currentPath
        mutableState.value = mutableState.value.copy(
            creatingDirectory = true,
            createDirectoryError = null,
            locationTarget = null,
        )
        createDirectoryJob = viewModelScope.launch {
            try {
                when (val result = withContext(ioDispatcher) { rootFileSystem.createDirectory(parent, name) }) {
                    is OperationResult.Failure -> if (mutableState.value.currentPath == parent) {
                        mutableState.value = mutableState.value.copy(
                            creatingDirectory = false,
                            createDirectoryError = BrowserOperationError(result.code, result.userMessage),
                        )
                    }
                    is OperationResult.Success -> if (mutableState.value.currentPath == parent) {
                        load(parent, result.value.path)
                    }
                }
            } catch (cancelled: CancellationException) {
                if (mutableState.value.currentPath == parent) {
                    mutableState.value = mutableState.value.copy(creatingDirectory = false)
                }
                throw cancelled
            } catch (_: Exception) {
                if (mutableState.value.currentPath == parent) {
                    mutableState.value = mutableState.value.copy(
                        creatingDirectory = false,
                        createDirectoryError = BrowserOperationError(ErrorCode.COMMAND_FAILED, "新建文件夹失败"),
                    )
                }
            }
        }
    }

    fun loadMore() {
        val current = mutableState.value
        if (!current.hasMore) return
        visibleCount = (visibleCount + PAGE_SIZE).coerceAtMost(current.totalCount)
        mutableState.value = current.copy(
            entries = presentedEntries.take(visibleCount),
            hasMore = visibleCount < current.totalCount,
        )
    }

    private fun load(path: RootPath, locationTarget: RootPath? = null) {
        val request = ++generation
        loadJob?.cancel()
        resetPresentationWindow()
        val cached = snapshotCache.get(path)
        val cachedSnapshot = cached?.snapshot
        val requestedPresentationKey = DirectoryPresentationKey(
            mutableState.value.sortSpec,
            mutableState.value.searchQuery,
        )
        val cachedEntries = cached
            ?.takeIf { it.presentationKey == requestedPresentationKey }
            ?.presentedEntries
            .orEmpty()
        if (cached != null) presentedEntries = cachedEntries
        mutableState.value = BrowserUiState(
            currentPath = path,
            rootTitle = mutableState.value.rootTitle,
            title = displayTitle(path),
            allEntries = cachedSnapshot?.entries.orEmpty(),
            entries = cachedEntries.take(PAGE_SIZE),
            totalCount = cachedEntries.size,
            refreshing = true,
            canGoBack = stack.isNotEmpty(),
            hasMore = cachedEntries.size > PAGE_SIZE,
            canCreateDirectory = cachedSnapshot?.parentWritable == true,
            locationTarget = locationTarget,
            displayMode = mutableState.value.displayMode,
            sortSpec = mutableState.value.sortSpec,
            searchQuery = mutableState.value.searchQuery,
            selectedEntries = emptySet(),
            compressionMessage = null,
        )
        val cachedPresentationJob = if (cachedSnapshot != null && cachedEntries.isEmpty()) {
            refreshPresentation()
        } else {
            null
        }
        val loadingIndicatorJob = if (cachedSnapshot == null) {
            viewModelScope.launch {
                delay(LOADING_INDICATOR_DELAY_MILLIS)
                if (request == generation) {
                    mutableState.value = mutableState.value.copy(loading = true)
                }
            }
        } else {
            null
        }
        loadJob = viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { rootFileSystem.readDirectory(path) }
                when (result) {
                    is OperationResult.Failure -> if (request == generation) {
                        cachedPresentationJob?.join()
                        if (request == generation) {
                            mutableState.value = if (cachedSnapshot == null) {
                                mutableState.value.copy(
                                    loading = false,
                                    refreshing = false,
                                    errorMessage = result.userMessage,
                                    canCreateDirectory = false,
                                )
                            } else {
                                mutableState.value.copy(loading = false, refreshing = false)
                            }
                        }
                    }
                    is OperationResult.Success -> publishSnapshot(path, result.value, request)
                }
            } catch (cancelled: CancellationException) {
                if (request == generation) {
                    mutableState.value = mutableState.value.copy(loading = false, refreshing = false)
                }
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) {
                    cachedPresentationJob?.join()
                    if (request == generation) {
                        mutableState.value = if (cachedSnapshot == null) {
                            mutableState.value.copy(
                                loading = false,
                                refreshing = false,
                                errorMessage = "无法读取目录",
                            )
                        } else {
                            mutableState.value.copy(loading = false, refreshing = false)
                        }
                    }
                }
            } finally {
                loadingIndicatorJob?.cancel()
            }
        }
    }

    private suspend fun publishSnapshot(
        path: RootPath,
        snapshot: DirectorySnapshot,
        request: Long,
    ) {
        while (request == generation) {
            val presentationState = mutableState.value
            val sortSpec = presentationState.sortSpec
            val searchQuery = presentationState.searchQuery
            val derived = withContext(ioDispatcher) {
                derivePresentation(snapshot.entries, searchQuery, sortSpec)
            }
            if (request != generation) return
            val current = mutableState.value
            if (current.sortSpec != sortSpec || current.searchQuery != searchQuery) continue

            visibleCount = PAGE_SIZE
            presentedEntries = derived
            snapshotCache.put(
                path,
                snapshot,
                derived,
                DirectoryPresentationKey(sortSpec, searchQuery),
            )
            mutableState.value = current.copy(
                allEntries = snapshot.entries,
                entries = derived.take(visibleCount),
                totalCount = derived.size,
                loading = false,
                refreshing = false,
                canCreateDirectory = snapshot.parentWritable,
                hasMore = visibleCount < derived.size,
            )
            return
        }
    }

    private fun displayTitle(path: RootPath): String = when {
        path == selectedRootPath && path.value == "/" -> "/"
        path == selectedRootPath -> mutableState.value.rootTitle
        else -> path.value.substringAfterLast('/').ifEmpty { "/" }
    }

    private fun resetPresentationWindow() {
        visibleCount = PAGE_SIZE
        presentedEntries = emptyList()
        mutableState.value = mutableState.value.copy(
            entries = emptyList(),
            totalCount = 0,
            hasMore = false,
        )
    }

    private fun savePresentationPreference(write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                write()
                mutableState.value = mutableState.value.copy(presentationError = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(presentationError = "无法保存显示设置")
            }
        }
    }

    private fun refreshPresentation(): Job {
        val current = mutableState.value
        presentationJob?.cancel()
        return viewModelScope.launch {
            val derived = withContext(ioDispatcher) {
                derivePresentation(current.allEntries, current.searchQuery, current.sortSpec)
            }
            if (
                mutableState.value.allEntries == current.allEntries &&
                mutableState.value.sortSpec == current.sortSpec &&
                mutableState.value.searchQuery == current.searchQuery
            ) {
                presentedEntries = derived
                mutableState.value = mutableState.value.copy(
                    entries = derived.take(visibleCount),
                    totalCount = derived.size,
                    hasMore = visibleCount < derived.size,
                )
            }
        }.also { presentationJob = it }
    }

    private fun derivePresentation(
        entries: List<DirectoryEntry>,
        searchQuery: String,
        sortSpec: SortSpec,
    ): List<DirectoryEntry> = sorter(
        entries.filter { entry ->
            searchQuery.isEmpty() || entry.name.contains(searchQuery, ignoreCase = true)
        },
        sortSpec,
    )

    internal companion object {
        const val INITIAL_PATH = "/storage/emulated/0"
        const val PAGE_SIZE = 200
        const val LOADING_INDICATOR_DELAY_MILLIS = 120L
    }
}

enum class BrowserBackResult {
    NAVIGATED,
    RETURN_HOME,
}
