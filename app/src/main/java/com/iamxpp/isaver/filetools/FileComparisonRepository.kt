package com.iamxpp.isaver.filetools

import com.iamxpp.isaver.data.root.RootFileChunk
import com.iamxpp.isaver.data.root.RootFileRangeProtocol
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.data.root.RootFileVersion
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.fileops.ChecksumAlgorithm
import com.iamxpp.isaver.fileops.FileChecksumRepository

sealed interface ContentComparison {
    data class Identical(val sizeBytes: Long) : ContentComparison
    data class DifferentSize(val leftSizeBytes: Long, val rightSizeBytes: Long) : ContentComparison
    data class DifferentContent(
        val firstDifferenceOffset: Long,
        val leftByte: Int,
        val rightByte: Int,
        val contextOffset: Long,
        val leftContext: ByteArray,
        val rightContext: ByteArray,
    ) : ContentComparison {
        override fun equals(other: Any?): Boolean = other is DifferentContent &&
            firstDifferenceOffset == other.firstDifferenceOffset && leftByte == other.leftByte &&
            rightByte == other.rightByte && contextOffset == other.contextOffset &&
            leftContext.contentEquals(other.leftContext) && rightContext.contentEquals(other.rightContext)

        override fun hashCode(): Int = 31 * leftContext.contentHashCode() + rightContext.contentHashCode()
    }
}

data class ChecksumComparison(
    val algorithm: ChecksumAlgorithm,
    val leftDigest: String,
    val rightDigest: String,
    val identical: Boolean,
)

class FileComparisonRepository internal constructor(
    private val readRange: suspend (DirectoryEntry, Long, Long) -> OperationResult<RootFileChunk>,
    private val checksum: suspend (DirectoryEntry, ChecksumAlgorithm) -> OperationResult<String>,
    chunkSizeBytes: Int,
) {
    private val chunkSizeBytes = checkedChunkSize(chunkSizeBytes)

    constructor(fileSystem: RootFileSystem, checksums: FileChecksumRepository) : this(
        readRange = { entry: DirectoryEntry, offset: Long, count: Long ->
            fileSystem.readRange(entry.path, offset, count)
        },
        checksum = checksums::checksum,
        chunkSizeBytes = DEFAULT_CHUNK_BYTES,
    )

    suspend fun compareContent(
        left: DirectoryEntry,
        right: DirectoryEntry,
    ): OperationResult<ContentComparison> {
        if (!left.isReadableFile() || !right.isReadableFile()) return unreadable()
        val leftVersion = probe(left).valueOrReturn { return it }
        val rightVersion = probe(right).valueOrReturn { return it }
        if (leftVersion.sizeBytes != left.sizeBytes || rightVersion.sizeBytes != right.sizeBytes) return changed()
        if (leftVersion.sizeBytes != rightVersion.sizeBytes) {
            return OperationResult.Success(ContentComparison.DifferentSize(leftVersion.sizeBytes, rightVersion.sizeBytes))
        }
        var offset = 0L
        while (offset < leftVersion.sizeBytes) {
            val count = minOf(chunkSizeBytes.toLong(), leftVersion.sizeBytes - offset)
            val leftChunk = readStable(left, offset, count, leftVersion).valueOrReturn { return it }
            val rightChunk = readStable(right, offset, count, rightVersion).valueOrReturn { return it }
            val difference = leftChunk.bytes.indices.firstOrNull { leftChunk.bytes[it] != rightChunk.bytes[it] }
            if (difference != null) {
                return OperationResult.Success(
                    ContentComparison.DifferentContent(
                        firstDifferenceOffset = offset + difference,
                        leftByte = leftChunk.bytes[difference].toInt() and 0xff,
                        rightByte = rightChunk.bytes[difference].toInt() and 0xff,
                        contextOffset = offset,
                        leftContext = leftChunk.bytes.copyOfRange(difference, minOf(difference + CONTEXT_BYTES, leftChunk.bytes.size)),
                        rightContext = rightChunk.bytes.copyOfRange(difference, minOf(difference + CONTEXT_BYTES, rightChunk.bytes.size)),
                    ),
                )
            }
            offset += count
        }
        return OperationResult.Success(ContentComparison.Identical(leftVersion.sizeBytes))
    }

    suspend fun compareChecksums(
        left: DirectoryEntry,
        right: DirectoryEntry,
        algorithm: ChecksumAlgorithm,
    ): OperationResult<ChecksumComparison> {
        if (!left.isReadableFile() || !right.isReadableFile()) return unreadable()
        val leftDigest = checksum(left, algorithm).valueOrReturn { return it }
        val rightDigest = checksum(right, algorithm).valueOrReturn { return it }
        return OperationResult.Success(
            ChecksumComparison(algorithm, leftDigest, rightDigest, leftDigest == rightDigest),
        )
    }

    private suspend fun probe(entry: DirectoryEntry) = when (val read = readRange(entry, 0, 0)) {
        is OperationResult.Failure -> read
        is OperationResult.Success -> if (read.value.bytes.isEmpty()) OperationResult.Success(read.value.version) else changed()
    }

    private suspend fun readStable(
        entry: DirectoryEntry,
        offset: Long,
        count: Long,
        version: RootFileVersion,
    ) = when (val read = readRange(entry, offset, count)) {
        is OperationResult.Failure -> read
        is OperationResult.Success -> if (read.value.version == version && read.value.bytes.size.toLong() == count) read else changed()
    }

    private inline fun <T> OperationResult<T>.valueOrReturn(onFailure: (OperationResult.Failure) -> Nothing): T = when (this) {
        is OperationResult.Success -> value
        is OperationResult.Failure -> onFailure(this)
    }

    private fun DirectoryEntry.isReadableFile() =
        type == EntryType.FILE && readable && !symbolicLink && sizeBytes != null

    private fun unreadable() = OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, "只能比较两个可读普通文件")
    private fun changed() = OperationResult.Failure(ErrorCode.OUTCOME_UNCERTAIN, "比较期间文件发生变化，请重新比较")

    companion object {
        const val DEFAULT_CHUNK_BYTES = 1024 * 1024
        private const val CONTEXT_BYTES = 16
        private fun checkedChunkSize(value: Int): Int = value.also {
            require(it > 0 && it <= RootFileRangeProtocol.MAX_RANGE_BYTES)
        }
    }
}
