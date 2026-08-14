package com.isaver.filemanager.filetools

import com.isaver.filemanager.data.root.RootFileChunk
import com.isaver.filemanager.data.root.RootFileVersion
import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.fileops.ChecksumAlgorithm
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FileComparisonRepositoryTest {
    @Test fun `reports equal files after streaming all chunks`() = runTest {
        val data = mapOf("left.bin" to "0123456789".toByteArray(), "right.bin" to "0123456789".toByteArray())
        val repository = repository(data, chunkSize = 4)

        val result = repository.compareContent(entry("left.bin", 10), entry("right.bin", 10))

        assertEquals(ContentComparison.Identical(10), (result as OperationResult.Success).value)
    }

    @Test fun `reports current size mismatch using version probes`() = runTest {
        val data = mapOf("left.bin" to ByteArray(3), "right.bin" to ByteArray(5))

        val result = repository(data).compareContent(entry("left.bin", 3), entry("right.bin", 5))

        assertEquals(ContentComparison.DifferentSize(3, 5), (result as OperationResult.Success).value)
    }

    @Test fun `finds first difference across a chunk boundary with bounded context`() = runTest {
        val left = "abcdefghij".toByteArray()
        val right = "abcdXfghij".toByteArray()

        val result = repository(mapOf("left.bin" to left, "right.bin" to right), chunkSize = 4)
            .compareContent(entry("left.bin", 10), entry("right.bin", 10))

        val different = (result as OperationResult.Success).value as ContentComparison.DifferentContent
        assertEquals(4L, different.firstDifferenceOffset)
        assertEquals(0x65, different.leftByte)
        assertEquals(0x58, different.rightByte)
        assertEquals(4L, different.contextOffset)
        assertEquals(4, different.leftContext.size)
    }

    @Test fun `rejects a source version change during comparison`() = runTest {
        val bytes = ByteArray(8)
        var rightReads = 0
        val repository = FileComparisonRepository(
            readRange = { entry, offset, count ->
                if (entry.name == "right.bin") rightReads++
                val changed = if (entry.name == "right.bin" && rightReads > 2) 99L else 5L
                OperationResult.Success(
                    RootFileChunk(bytes.copyOfRange(offset.toInt(), (offset + count).toInt()), version(8, changed)),
                )
            },
            checksum = { _, _ -> OperationResult.Success("unused") },
            chunkSizeBytes = 4,
        )

        val result = repository.compareContent(entry("left.bin", 8), entry("right.bin", 8))

        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (result as OperationResult.Failure).code)
    }

    @Test fun `compares checksum values for selected algorithm`() = runTest {
        val values = mapOf("left.bin" to "aaa", "right.bin" to "bbb")
        val repository = FileComparisonRepository(
            readRange = { _, _, _ -> error("unused") },
            checksum = { entry, algorithm -> OperationResult.Success("${algorithm.label}:${values.getValue(entry.name)}") },
            chunkSizeBytes = 4,
        )

        val result = repository.compareChecksums(entry("left.bin", 1), entry("right.bin", 1), ChecksumAlgorithm.SHA256)

        assertEquals(
            ChecksumComparison(ChecksumAlgorithm.SHA256, "SHA-256:aaa", "SHA-256:bbb", false),
            (result as OperationResult.Success).value,
        )
    }

    private fun repository(data: Map<String, ByteArray>, chunkSize: Int = 4) = FileComparisonRepository(
        readRange = { entry, offset, count ->
            val bytes = data.getValue(entry.name)
            OperationResult.Success(
                RootFileChunk(bytes.copyOfRange(offset.toInt(), (offset + count).toInt()), version(bytes.size.toLong())),
            )
        },
        checksum = { entry, _ -> OperationResult.Success(data.getValue(entry.name).contentHashCode().toString()) },
        chunkSizeBytes = chunkSize,
    )

    private fun entry(name: String, size: Long) = DirectoryEntry(
        RootPath.parse("/data/local/tmp/$name").getOrThrow(), name, EntryType.FILE,
        size, 1, true, false, false,
    )

    private fun version(size: Long, changedSeconds: Long = 5) = RootFileVersion(size, 1, 2, 3, 4, changedSeconds, 6)
}
