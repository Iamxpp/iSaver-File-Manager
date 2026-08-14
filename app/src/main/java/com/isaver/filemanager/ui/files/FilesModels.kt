package com.isaver.filemanager.ui.files

import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
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
    fun sort(entries: List<DirectoryEntry>, spec: SortSpec): List<DirectoryEntry> {
        val sortableEntries = entries.map { entry ->
            SortableEntry(
                entry = entry,
                fieldKey = when (spec.field) {
                    SortField.DISPLAY_NAME -> NaturalNameKey(entry.name)
                    SortField.TYPE -> StringKey(typeKey(entry))
                    SortField.MODIFIED_AT -> LongKey(entry.modifiedAtEpochSeconds)
                    SortField.SIZE -> LongKey(entry.sizeBytes)
                },
            )
        }
        return sortableEntries.sortedWith { left, right ->
            val directoryComparison = directoryRank(left.entry).compareTo(directoryRank(right.entry))
            if (directoryComparison != 0) {
                directoryComparison
            } else if (spec.direction == SortDirection.ASCENDING) {
                left.fieldKey.compareTo(right.fieldKey)
            } else {
                right.fieldKey.compareTo(left.fieldKey)
            }
        }.map(SortableEntry::entry)
    }

    private fun directoryRank(entry: DirectoryEntry): Int =
        if (entry.type == EntryType.DIRECTORY) 0 else 1

    private fun typeKey(entry: DirectoryEntry): String = when (entry.type) {
        EntryType.DIRECTORY -> "directory"
        EntryType.OTHER -> "other"
        EntryType.FILE -> "file:${entry.name.extension().lowercase(Locale.ROOT)}"
    }

    private fun String.extension(): String {
        val separator = lastIndexOf('.')
        return if (separator <= 0 || separator == lastIndex) "" else substring(separator + 1)
    }

    private data class SortableEntry(
        val entry: DirectoryEntry,
        val fieldKey: FieldKey,
    )

    private sealed interface FieldKey : Comparable<FieldKey>

    private data class StringKey(val value: String) : FieldKey {
        override fun compareTo(other: FieldKey): Int = value.compareTo((other as StringKey).value)
    }

    private data class LongKey(val value: Long?) : FieldKey {
        override fun compareTo(other: FieldKey): Int {
            val right = (other as LongKey).value
            return when {
                value == null && right == null -> 0
                value == null -> -1
                right == null -> 1
                else -> value.compareTo(right)
            }
        }
    }

    private data class NaturalNameKey(val chunks: List<String>) : FieldKey {
        constructor(value: String) : this(
            CHUNK_PATTERN.findAll(value.lowercase(Locale.ROOT)).map { it.value }.toList(),
        )

        override fun compareTo(other: FieldKey): Int {
            val rightChunks = (other as NaturalNameKey).chunks
            for (index in 0 until minOf(chunks.size, rightChunks.size)) {
                val comparison = compareChunk(chunks[index], rightChunks[index])
                if (comparison != 0) return comparison
            }
            return chunks.size.compareTo(rightChunks.size)
        }

        private fun compareChunk(left: String, right: String): Int {
            if (!left.first().isDigit() || !right.first().isDigit()) return left.compareTo(right)
            val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
            val normalizedRight = right.trimStart('0').ifEmpty { "0" }
            return normalizedLeft.length.compareTo(normalizedRight.length)
                .takeIf { it != 0 }
                ?: normalizedLeft.compareTo(normalizedRight)
        }
    }

    private val CHUNK_PATTERN = Regex("\\d+|\\D+")
}
