package com.iamxpp.isaver.fileops

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FileChecksumRepositoryTest {
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
        val repository = FileChecksumRepository { _, _ -> OperationResult.Success(0) }
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            (repository.sha256(entry(0)) as OperationResult.Success).value,
        )
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
