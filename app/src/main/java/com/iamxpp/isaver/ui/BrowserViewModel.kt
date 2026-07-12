package com.iamxpp.isaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class BrowserViewModel(
    private val rootFileSystem: RootFileSystem,
    private val ioDispatcher: CoroutineDispatcher,
    private val sorter: (List<DirectoryEntry>) -> List<DirectoryEntry> = ::sortEntries,
) : ViewModel() {
    private val initialPath = RootPath.parse(INITIAL_PATH).getOrThrow()
    private val stack = ArrayDeque<RootPath>()
    private val mutableState = MutableStateFlow(BrowserUiState(currentPath = initialPath))
    private var loadJob: Job? = null
    private var createDirectoryJob: Job? = null
    private var generation = 0L
    private var visibleCount = PAGE_SIZE

    val state: StateFlow<BrowserUiState> = mutableState.asStateFlow()

    init { load(initialPath) }

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
            entries = current.allEntries.take(visibleCount),
            hasMore = visibleCount < current.totalCount,
        )
    }

    private fun load(path: RootPath, locationTarget: RootPath? = null) {
        val request = ++generation
        loadJob?.cancel()
        visibleCount = PAGE_SIZE
        mutableState.value = BrowserUiState(
            currentPath = path,
            rootTitle = mutableState.value.rootTitle,
            canGoBack = stack.isNotEmpty(),
            locationTarget = locationTarget,
        )
        loadJob = viewModelScope.launch {
            try {
                val (result, canCreateDirectory) = withContext(ioDispatcher) {
                    val listed = when (val value = rootFileSystem.list(path)) {
                        is OperationResult.Failure -> value
                        is OperationResult.Success -> OperationResult.Success(sorter(value.value))
                    }
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
                        val sorted = result.value
                        mutableState.value = mutableState.value.copy(
                            allEntries = sorted,
                            entries = sorted.take(PAGE_SIZE),
                            totalCount = sorted.size,
                            loading = false,
                            hasMore = sorted.size > PAGE_SIZE,
                            canCreateDirectory = canCreateDirectory,
                        )
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

    internal companion object {
        const val INITIAL_PATH = "/storage/emulated/0"
        const val PAGE_SIZE = 200
        internal fun sortEntries(entries: List<DirectoryEntry>): List<DirectoryEntry> = entries
            .map { entry -> SortableEntry(entry, typeRank(entry.type), naturalKey(entry.name)) }
            .sortedWith(compareBy<SortableEntry> { it.typeRank }.thenComparator { left, right -> compareKeys(left.key, right.key) })
            .map { it.entry }

        private fun typeRank(type: EntryType) = if (type == EntryType.DIRECTORY) 0 else 1

        private fun naturalKey(name: String) = CHUNKS.findAll(name.lowercase(Locale.ROOT)).map { it.value }.toList()

        private fun compareKeys(a: List<String>, b: List<String>): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val x = a[i]; val y = b[i]
                val comparison = if (x.first().isDigit() && y.first().isDigit()) {
                    val nx = x.trimStart('0').ifEmpty { "0" }; val ny = y.trimStart('0').ifEmpty { "0" }
                    nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
                } else x.compareTo(y)
                if (comparison != 0) return comparison
            }
            return a.size.compareTo(b.size)
        }

        private val CHUNKS = Regex("\\d+|\\D+")
        private data class SortableEntry(val entry: DirectoryEntry, val typeRank: Int, val key: List<String>)
    }
}

enum class BrowserBackResult {
    NAVIGATED,
    RETURN_HOME,
}
