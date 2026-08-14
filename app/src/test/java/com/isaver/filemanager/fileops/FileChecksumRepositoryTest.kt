package com.isaver.filemanager.fileops

import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FileChecksumRepositoryTest {
    @Test fun `computes all supported digest algorithms`() = runTest {
        val bytes = "iSaver".toByteArray()
        val repository = FileChecksumRepository { _, output ->
            output.write(bytes)
            OperationResult.Success(bytes.size.toLong())
        }

        assertEquals(
            "e1a46133232f05b052e30309f599d794",
            (repository.checksum(entry(bytes.size.toLong()), ChecksumAlgorithm.MD5) as OperationResult.Success).value,
        )
        assertEquals(
            "dc04eb5f798d99373ec95f485308bd5ed850f4bb",
            (repository.checksum(entry(bytes.size.toLong()), ChecksumAlgorithm.SHA1) as OperationResult.Success).value,
        )
        assertEquals(
            "ab6022dbe2380e400b471fdfb28dd44c1435a2725d00bf99eb31d026f264096b",
            (repository.checksum(entry(bytes.size.toLong()), ChecksumAlgorithm.SHA256) as OperationResult.Success).value,
        )
        assertEquals(
            "c6c9f237551be84da0a5be07d888693708186a219ed00c87b43d91f1264d5313200882abfc4c102cb5808fe413583354f23fb3644a01d975d05e21a290eda451",
            (repository.checksum(entry(bytes.size.toLong()), ChecksumAlgorithm.SHA512) as OperationResult.Success).value,
        )
    }

    @Test fun `streams multiple immutable ranges beyond the legacy limit`() = runTest {
        val chunkSize = 4L
        val bytes = "0123456789".toByteArray()
        val offsets = mutableListOf<Long>()
        val repository = FileChecksumRepository(
            readRange = { _, offset, count ->
                offsets += offset
                val end = (offset + count).coerceAtMost(bytes.size.toLong()).toInt()
                OperationResult.Success(
                    com.isaver.filemanager.data.root.RootFileChunk(
                        bytes.copyOfRange(offset.toInt(), end),
                        com.isaver.filemanager.data.root.RootFileVersion(bytes.size.toLong(), 1, 2, 3, 4, 5, 6),
                    ),
                )
            },
            chunkSizeBytes = chunkSize,
        )

        val result = repository.sha256(entry(bytes.size.toLong()))

        assertEquals(listOf(0L, 4L, 8L), offsets)
        assertEquals(
            "84d89877f0d4041efb6bf91a16f0248f2fd573e6af05c19f96bedb9f882f7882",
            (result as OperationResult.Success).value,
        )
    }

    @Test fun `rejects file version changes between ranges`() = runTest {
        var calls = 0
        val repository = FileChecksumRepository(
            readRange = { _, _, _ ->
                calls += 1
                OperationResult.Success(
                    com.isaver.filemanager.data.root.RootFileChunk(
                        ByteArray(4),
                        com.isaver.filemanager.data.root.RootFileVersion(8, 1, 2, 3, calls.toLong(), 5, 6),
                    ),
                )
            },
            chunkSizeBytes = 4,
        )

        val result = repository.sha256(entry(8))

        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (result as OperationResult.Failure).code)
    }

    @Test fun `computes lower case sha256 without caching the file`() = runTest {
        val bytes = "iSaver".toByteArray()
        val repository = FileChecksumRepository { _, output ->
            output.write(bytes)
            OperationResult.Success(bytes.size.toLong())
        }
        assertEquals(
            "ab6022dbe2380e400b471fdfb28dd44c1435a2725d00bf99eb31d026f264096b",
            (repository.sha256(entry(bytes.size.toLong())) as OperationResult.Success).value,
        )
    }

    @Test fun `computes standard empty file digest`() = runTest {
        var calls = 0
        val repository = FileChecksumRepository(
            readRange = { _, offset, count ->
                calls += 1
                assertEquals(0L, offset)
                assertEquals(0L, count)
                OperationResult.Success(
                    com.isaver.filemanager.data.root.RootFileChunk(
                        ByteArray(0),
                        com.isaver.filemanager.data.root.RootFileVersion(0, 1, 2, 3, 4, 5, 6),
                    ),
                )
            },
            chunkSizeBytes = 4,
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            (repository.sha256(entry(0)) as OperationResult.Success).value,
        )
        assertEquals(1, calls)
    }

    @Test fun `rejects directories and mismatched read sizes`() = runTest {
        val repository = FileChecksumRepository { _, _ -> OperationResult.Success(1) }
        assertEquals(
            ErrorCode.SOURCE_UNREADABLE,
            (repository.sha256(entry(null, EntryType.DIRECTORY)) as OperationResult.Failure).code,
        )
        assertEquals(
            ErrorCode.OUTCOME_UNCERTAIN,
            (repository.sha256(entry(2)) as OperationResult.Failure).code,
        )
    }

    private fun entry(size: Long?, type: EntryType = EntryType.FILE) = DirectoryEntry(
        RootPath.parse("/data/local/tmp/value.txt").getOrThrow(), "value.txt", type,
        size, 1, true, false, false,
    )
}
