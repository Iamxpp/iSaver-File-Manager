package com.iamxpp.isaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamxpp.isaver.data.local.BrowserPreferencesStore
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.ui.files.FileEntrySorter
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.SortSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowserViewModel(
    private val rootFileSystem: RootFileSystem,
    private val ioDispatcher: CoroutineDispatcher,
    private val preferencesStore: BrowserPreferencesStore,
    private val sorter: (List<DirectoryEntry>, SortSpec) -> List<DirectoryEntry> = FileEntrySorter::sort,
) : ViewModel() {
    private val initialPath = RootPath.parse(INITIAL_PATH).getOrThrow()
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
        load(initialPath)
    }

    fun enterDirectory(entry: DirectoryEntry): Boolean {
        if (entry.type != EntryType.DIRECTORY) return false
        stack.addLast(mutableState.value.currentPath)
        load(entry.path)
        return true
    }

    fun openRoot(path: RootPath, title: String) {
        stack.clear()
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
        mutableState.value = BrowserUiState(
            currentPath = path,
            rootTitle = mutableState.value.rootTitle,
            canGoBack = stack.isNotEmpty(),
            locationTarget = locationTarget,
            displayMode = mutableState.value.displayMode,
            sortSpec = mutableState.value.sortSpec,
            searchQuery = mutableState.value.searchQuery,
        )
        loadJob = viewModelScope.launch {
            try {
                val (result, canCreateDirectory) = withContext(ioDispatcher) {
                    val listed = rootFileSystem.list(path)
                    val writable = try {
                        when (val stat = rootFileSystem.stat(path)) {
                            is OperationResult.Failure -> false
                            is OperationResult.Success -> stat.value.type == EntryType.DIRECTORY &&
                                stat.value.writable &&
                                !stat.value.symbolicLink
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    listed to writable
                }
                when (result) {
                    is OperationResult.Failure -> if (request == generation) {
                        mutableState.value = mutableState.value.copy(
                            loading = false,
                            errorMessage = result.userMessage,
                            canCreateDirectory = false,
                        )
                    }
                    is OperationResult.Success -> if (request == generation) {
                        val allEntries = result.value
                        resetPresentationWindow()
                        mutableState.value = mutableState.value.copy(
                            allEntries = allEntries,
                            loading = false,
                            canCreateDirectory = canCreateDirectory,
                        )
                        refreshPresentation()
                    }
                }
            } catch (cancelled: CancellationException) {
                if (request == generation) {
                    mutableState.value = mutableState.value.copy(loading = false)
                }
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) {
                    mutableState.value = mutableState.value.copy(loading = false, errorMessage = "无法读取目录")
                }
            }
        }
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

    private fun refreshPresentation() {
        val current = mutableState.value
        presentationJob?.cancel()
        presentationJob = viewModelScope.launch {
            val derived = withContext(ioDispatcher) {
                sorter(
                    current.allEntries.filter { entry ->
                        current.searchQuery.isEmpty() || entry.name.contains(current.searchQuery, ignoreCase = true)
                    },
                    current.sortSpec,
                )
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
        }
    }

    internal companion object {
        const val INITIAL_PATH = "/storage/emulated/0"
        const val PAGE_SIZE = 200
    }
}

enum class BrowserBackResult {
    NAVIGATED,
    RETURN_HOME,
}
