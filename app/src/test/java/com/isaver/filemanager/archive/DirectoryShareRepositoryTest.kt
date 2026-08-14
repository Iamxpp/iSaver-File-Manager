package com.isaver.filemanager.archive

import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.export.ExternalFileGrant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DirectoryShareRepositoryTest {
    @Test
    fun `rejects unsafe directory before archive creation`() = runTest {
        var called = false
        val repository = DirectoryShareRepository(
            createArchive = {
                called = true
                OperationResult.Failure(ErrorCode.COMMAND_FAILED, "unexpected")
            },
            discardArchive = {},
            shareLocalFile = { _, _, _ -> error("export should not be used") },
        )

        val result = repository.share(listOf(entry("link", EntryType.DIRECTORY, symbolicLink = true)))

        assertTrue(result is OperationResult.Failure)
        assertEquals(ErrorCode.SOURCE_UNREADABLE, (result as OperationResult.Failure).code)
        assertTrue(!called)
    }

    @Test
    fun `shares generated zip and discards archive cache after export`() = runTest {
        val archive = Files.createTempFile("isaver-share", ".tmp").toFile()
        archive.writeText("zip")
        var discarded: File? = null
        val grant = ExternalFileGrant("content://test/file", "aa".repeat(32), "iSaver-share.zip", "application/zip")
        val repository = DirectoryShareRepository(
            createArchive = { OperationResult.Success(archive) },
            discardArchive = { discarded = it },
            shareLocalFile = { file, name, mime ->
                assertEquals(archive, file)
                assertEquals("iSaver-share.zip", name)
                assertEquals("application/zip", mime)
                OperationResult.Success(grant)
            },
        )

        val result = repository.share(listOf(entry("folder", EntryType.DIRECTORY)))

        assertEquals(OperationResult.Success(grant), result)
        assertEquals(archive, discarded)
        archive.delete()
    }

    private fun entry(name: String, type: EntryType, symbolicLink: Boolean = false) = DirectoryEntry(
        path = RootPath.parse("/data/$name").getOrThrow(), name = name, type = type,
        sizeBytes = null, modifiedAtEpochSeconds = 1, readable = true, writable = true, symbolicLink = symbolicLink,
    )
}
