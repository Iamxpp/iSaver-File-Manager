package com.iamxpp.isaver.ui.files

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import java.util.Locale

enum class HomeTab { RECENT, VIEWS, BROWSE }

enum class DisplayMode { LIST, GRID }

enum class SortField { DISPLAY_NAME, TYPE, MODIFIED_AT, SIZE }

enum class SortDirection { ASCENDING, DESCENDING }

data class SortSpec(
    val field: SortField,
    val direction: SortDirection,
)

object FileEntrySorter {
    fun sort(entries: List<DirectoryEntry>, spec: SortSpec): List<DirectoryEntry> =
        entries.sortedWith { left, right ->
            val directoryComparison = directoryRank(left).compareTo(directoryRank(right))
            if (directoryComparison != 0) {
                directoryComparison
            } else if (spec.direction == SortDirection.ASCENDING) {
                compareField(left, right, spec.field)
            } else {
                compareField(right, left, spec.field)
            }
        }

    private fun directoryRank(entry: DirectoryEntry): Int =
        if (entry.type == EntryType.DIRECTORY) 0 else 1

    private fun compareField(left: DirectoryEntry, right: DirectoryEntry, field: SortField): Int = when (field) {
        SortField.DISPLAY_NAME -> compareNatural(left.name, right.name)
        SortField.TYPE -> typeKey(left).compareTo(typeKey(right))
        SortField.MODIFIED_AT -> compareNullable(left.modifiedAtEpochSeconds, right.modifiedAtEpochSeconds)
        SortField.SIZE -> compareNullable(left.sizeBytes, right.sizeBytes)
    }

    private fun typeKey(entry: DirectoryEntry): String = when (entry.type) {
        EntryType.DIRECTORY -> "directory"
        EntryType.OTHER -> "other"
        EntryType.FILE -> "file:${entry.name.extension().lowercase(Locale.ROOT)}"
    }

    private fun String.extension(): String {
        val separator = lastIndexOf('.')
        return if (separator <= 0 || separator == lastIndex) "" else substring(separator + 1)
    }

    private fun compareNullable(left: Long?, right: Long?): Int = when {
        left == null && right == null -> 0
        left == null -> -1
        right == null -> 1
        else -> left.compareTo(right)
    }

    private fun compareNatural(left: String, right: String): Int {
        val leftChunks = chunks(left)
        val rightChunks = chunks(right)
        for (index in 0 until minOf(leftChunks.size, rightChunks.size)) {
            val comparison = compareChunk(leftChunks[index], rightChunks[index])
            if (comparison != 0) return comparison
        }
        return leftChunks.size.compareTo(rightChunks.size)
    }

    private fun chunks(value: String): List<String> =
        CHUNK_PATTERN.findAll(value.lowercase(Locale.ROOT)).map { it.value }.toList()

    private fun compareChunk(left: String, right: String): Int {
        if (!left.first().isDigit() || !right.first().isDigit()) return left.compareTo(right)
        val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
        val normalizedRight = right.trimStart('0').ifEmpty { "0" }
        return normalizedLeft.length.compareTo(normalizedRight.length)
            .takeIf { it != 0 }
            ?: normalizedLeft.compareTo(normalizedRight)
    }

    private val CHUNK_PATTERN = Regex("\\d+|\\D+")
}
