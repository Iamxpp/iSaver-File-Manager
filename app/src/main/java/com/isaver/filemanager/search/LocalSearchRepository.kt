package com.isaver.filemanager.search

import com.isaver.filemanager.data.root.RootFileSystem
import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

enum class SearchEntryType { ALL, FILE, DIRECTORY }

data class LocalSearchCriteria(
    val query: String,
    val regularExpression: Boolean = false,
    val extension: String = "",
    val entryType: SearchEntryType = SearchEntryType.ALL,
    val minimumSizeBytes: Long? = null,
    val maximumSizeBytes: Long? = null,
    val modifiedAfterEpochSeconds: Long? = null,
)

data class LocalSearchProgress(val scannedDirectories: Int, val scannedEntries: Int)

data class LocalSearchResult(
    val entries: List<DirectoryEntry>,
    val scannedDirectories: Int,
    val scannedEntries: Int,
    val skippedDirectories: Int,
    val truncated: Boolean,
)

class LocalSearchRepository(
    private val fileSystem: RootFileSystem,
    private val maxDepth: Int = 32,
    private val maxScannedEntries: Int = 10_000,
    private val maxResults: Int = 1_000,
) {
    suspend fun search(
        root: RootPath,
        criteria: LocalSearchCriteria,
        onProgress: (LocalSearchProgress) -> Unit = {},
    ): OperationResult<LocalSearchResult> {
        val matcher = createMatcher(criteria) ?: return OperationResult.Failure(
            ErrorCode.COMMAND_FAILED, "搜索条件无效",
        )
        val queue = ArrayDeque<SearchDirectory>().apply { addLast(SearchDirectory(root, 0)) }
        val matches = mutableListOf<DirectoryEntry>()
        var scannedDirectories = 0
        var scannedEntries = 0
        var skippedDirectories = 0
        var truncated = false

        while (queue.isNotEmpty() && !truncated) {
            currentCoroutineContext().ensureActive()
            val directory = queue.removeFirst()
            when (val snapshot = fileSystem.readDirectory(directory.path)) {
                is OperationResult.Failure -> {
                    if (scannedDirectories == 0) return snapshot
                    skippedDirectories += 1
                }
                is OperationResult.Success -> {
                    scannedDirectories += 1
                    for (entry in snapshot.value.entries) {
                        currentCoroutineContext().ensureActive()
                        if (scannedEntries >= maxScannedEntries) {
                            truncated = true
                            break
                        }
                        scannedEntries += 1
                        if (matcher(entry)) {
                            matches += entry
                            if (matches.size >= maxResults) {
                                truncated = true
                                break
                            }
                        }
                        if (
                            entry.type == EntryType.DIRECTORY && entry.readable && !entry.symbolicLink &&
                            directory.depth < maxDepth
                        ) {
                            queue.addLast(SearchDirectory(entry.path, directory.depth + 1))
                        }
                    }
                }
            }
            onProgress(LocalSearchProgress(scannedDirectories, scannedEntries))
            yield()
        }
        return OperationResult.Success(
            LocalSearchResult(matches, scannedDirectories, scannedEntries, skippedDirectories, truncated),
        )
    }

    private fun createMatcher(criteria: LocalSearchCriteria): ((DirectoryEntry) -> Boolean)? {
        if (
            criteria.minimumSizeBytes?.let { it < 0 } == true ||
            criteria.maximumSizeBytes?.let { it < 0 } == true ||
            criteria.minimumSizeBytes != null && criteria.maximumSizeBytes != null &&
            criteria.minimumSizeBytes > criteria.maximumSizeBytes
        ) return null
        val expression = if (criteria.regularExpression && criteria.query.isNotEmpty()) {
            try {
                Regex(criteria.query, RegexOption.IGNORE_CASE)
            } catch (_: IllegalArgumentException) {
                return null
            }
        } else null
        val extension = criteria.extension.trim().removePrefix(".").lowercase()
        return { entry ->
            val nameMatches = when {
                criteria.query.isEmpty() -> true
                expression != null -> expression.containsMatchIn(entry.name)
                else -> entry.name.contains(criteria.query, ignoreCase = true)
            }
            val typeMatches = when (criteria.entryType) {
                SearchEntryType.ALL -> true
                SearchEntryType.FILE -> entry.type == EntryType.FILE
                SearchEntryType.DIRECTORY -> entry.type == EntryType.DIRECTORY
            }
            val extensionMatches = extension.isEmpty() ||
                entry.type == EntryType.FILE && entry.name.substringAfterLast('.', "").lowercase() == extension
            val minimumMatches = criteria.minimumSizeBytes == null ||
                entry.sizeBytes?.let { it >= criteria.minimumSizeBytes } == true
            val maximumMatches = criteria.maximumSizeBytes == null ||
                entry.sizeBytes?.let { it <= criteria.maximumSizeBytes } == true
            val modifiedMatches = criteria.modifiedAfterEpochSeconds == null ||
                entry.modifiedAtEpochSeconds?.let { it >= criteria.modifiedAfterEpochSeconds } == true
            nameMatches && typeMatches && extensionMatches && minimumMatches && maximumMatches && modifiedMatches
        }
    }

    private data class SearchDirectory(val path: RootPath, val depth: Int)
}
