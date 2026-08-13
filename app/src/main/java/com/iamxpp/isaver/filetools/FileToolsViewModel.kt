package com.iamxpp.isaver.filetools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamxpp.isaver.data.root.RootFileVersion
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.fileops.ChecksumAlgorithm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FileToolMode { HEX, COMPARE }

data class FileToolsUiState(
    val mode: FileToolMode? = null,
    val entries: List<DirectoryEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val hexPage: HexPage? = null,
    val contentComparison: ContentComparison? = null,
    val checksumAlgorithm: ChecksumAlgorithm = ChecksumAlgorithm.SHA256,
    val checksumComparison: ChecksumComparison? = null,
) {
    val visible get() = mode != null
}

class FileToolsViewModel(
    private val hexRepository: HexViewerRepository,
    private val comparisonRepository: FileComparisonRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FileToolsUiState())
    val state: StateFlow<FileToolsUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var hexVersion: RootFileVersion? = null

    fun openHex(entry: DirectoryEntry) {
        hexVersion = null
        mutableState.value = FileToolsUiState(mode = FileToolMode.HEX, entries = listOf(entry), loading = true)
        loadHexPage(0)
    }

    fun previousHexPage() {
        val page = mutableState.value.hexPage ?: return
        loadHexPage((page.offset - hexRepository.pageSizeBytes).coerceAtLeast(0))
    }

    fun nextHexPage() {
        val page = mutableState.value.hexPage ?: return
        loadHexPage(page.offset + hexRepository.pageSizeBytes)
    }

    fun jumpToOffset(text: String): Boolean {
        val value = text.trim()
        val offset = when {
            value.startsWith("0x", ignoreCase = true) -> value.drop(2).toLongOrNull(16)
            else -> value.toLongOrNull()
        } ?: return false
        if (offset < 0) return false
        loadHexPage(offset)
        return true
    }

    fun reloadHex() {
        val entry = mutableState.value.entries.singleOrNull() ?: return
        openHex(entry)
    }

    fun openComparison(entries: List<DirectoryEntry>) {
        if (entries.size != 2) return
        mutableState.value = FileToolsUiState(mode = FileToolMode.COMPARE, entries = entries, loading = true)
        runComparison()
    }

    fun setChecksumAlgorithm(algorithm: ChecksumAlgorithm) {
        if (mutableState.value.mode != FileToolMode.COMPARE) return
        mutableState.update { it.copy(checksumAlgorithm = algorithm, checksumComparison = null, loading = true, error = null) }
        runChecksumOnly()
    }

    fun retry() = when (mutableState.value.mode) {
        FileToolMode.HEX -> reloadHex()
        FileToolMode.COMPARE -> {
            mutableState.update { it.copy(loading = true, error = null, contentComparison = null, checksumComparison = null) }
            runComparison()
        }
        null -> Unit
    }

    fun close() {
        loadJob?.cancel()
        hexVersion = null
        mutableState.value = FileToolsUiState()
    }

    private fun loadHexPage(offset: Long) {
        val entry = mutableState.value.entries.singleOrNull() ?: return
        loadJob?.cancel()
        mutableState.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            when (val result = withContext(ioDispatcher) { hexRepository.loadPage(entry, offset, hexVersion) }) {
                is OperationResult.Failure -> mutableState.update { it.copy(loading = false, error = result.userMessage) }
                is OperationResult.Success -> {
                    if (hexVersion == null) hexVersion = result.value.version
                    mutableState.update { it.copy(loading = false, hexPage = result.value, error = null) }
                }
            }
        }
    }

    private fun runComparison() {
        loadJob?.cancel()
        val entries = mutableState.value.entries.takeIf { it.size == 2 } ?: return
        val algorithm = mutableState.value.checksumAlgorithm
        loadJob = viewModelScope.launch {
            when (val content = withContext(ioDispatcher) { comparisonRepository.compareContent(entries[0], entries[1]) }) {
                is OperationResult.Failure -> mutableState.update { it.copy(loading = false, error = content.userMessage) }
                is OperationResult.Success -> {
                    mutableState.update { it.copy(contentComparison = content.value) }
                    loadChecksum(entries, algorithm)
                }
            }
        }
    }

    private fun runChecksumOnly() {
        loadJob?.cancel()
        val entries = mutableState.value.entries.takeIf { it.size == 2 } ?: return
        val algorithm = mutableState.value.checksumAlgorithm
        loadJob = viewModelScope.launch { loadChecksum(entries, algorithm) }
    }

    private suspend fun loadChecksum(entries: List<DirectoryEntry>, algorithm: ChecksumAlgorithm) {
        when (val checksum = withContext(ioDispatcher) {
            comparisonRepository.compareChecksums(entries[0], entries[1], algorithm)
        }) {
            is OperationResult.Failure -> mutableState.update { it.copy(loading = false, error = checksum.userMessage) }
            is OperationResult.Success -> mutableState.update {
                it.copy(loading = false, checksumComparison = checksum.value, error = null)
            }
        }
    }
}
