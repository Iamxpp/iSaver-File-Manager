package com.iamxpp.isaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
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

class BrowserViewModel(
    private val rootFileSystem: RootFileSystem,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val initialPath = RootPath.parse(INITIAL_PATH).getOrThrow()
    private val stack = ArrayDeque<RootPath>()
    private val mutableState = MutableStateFlow(BrowserUiState(currentPath = initialPath))
    private var loadJob: Job? = null
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

    fun back(): Boolean {
        val previous = stack.removeLastOrNull() ?: return false
        load(previous)
        return true
    }

    fun retry() = load(mutableState.value.currentPath)

    fun loadMore() {
        val current = mutableState.value
        if (!current.hasMore) return
        visibleCount = (visibleCount + PAGE_SIZE).coerceAtMost(current.totalCount)
        mutableState.value = current.copy(
            entries = current.allEntries.take(visibleCount),
            hasMore = visibleCount < current.totalCount,
        )
    }

    private fun load(path: RootPath) {
        val request = ++generation
        loadJob?.cancel()
        visibleCount = PAGE_SIZE
        mutableState.value = BrowserUiState(currentPath = path, canGoBack = stack.isNotEmpty())
        loadJob = viewModelScope.launch {
            try {
                when (val result = withContext(ioDispatcher) { rootFileSystem.list(path) }) {
                    is OperationResult.Failure -> if (request == generation) {
                        mutableState.value = mutableState.value.copy(loading = false, errorMessage = result.userMessage)
                    }
                    is OperationResult.Success -> if (request == generation) {
                        val sorted = result.value.sortedWith(ENTRY_COMPARATOR)
                        mutableState.value = mutableState.value.copy(
                            allEntries = sorted,
                            entries = sorted.take(PAGE_SIZE),
                            totalCount = sorted.size,
                            loading = false,
                            hasMore = sorted.size > PAGE_SIZE,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) {
                    mutableState.value = mutableState.value.copy(loading = false, errorMessage = "无法读取目录")
                }
            }
        }
    }

    private companion object {
        const val INITIAL_PATH = "/storage/emulated/0"
        const val PAGE_SIZE = 200
        val ENTRY_COMPARATOR = Comparator<DirectoryEntry> { left, right ->
            val typeOrder = typeRank(left.type).compareTo(typeRank(right.type))
            if (typeOrder != 0) typeOrder else naturalCompare(left.name, right.name)
        }

        fun typeRank(type: EntryType) = if (type == EntryType.DIRECTORY) 0 else 1

        fun naturalCompare(left: String, right: String): Int {
            val a = CHUNKS.findAll(left.lowercase()).map { it.value }.toList()
            val b = CHUNKS.findAll(right.lowercase()).map { it.value }.toList()
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

        val CHUNKS = Regex("\\d+|\\D+")
    }
}
