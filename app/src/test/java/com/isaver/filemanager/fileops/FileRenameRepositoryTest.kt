package com.isaver.filemanager.fileops

import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRenameRepositoryTest {
    @Test
    fun `renames one readable regular file through typed operation`() = runTest {
        val parent = path("/data/local/tmp/source")
        val source = entry("report.txt", parent)
        val output = entry("renamed.txt", parent)
        var requestedName: String? = null
        val repository = FileRenameRepository { _, _, name ->
            requestedName = name.value
            OperationResult.Success(output)
        }

        val result = repository.rename(source, parent, "renamed.txt")

        assertEquals("renamed.txt", requestedName)
        assertEquals(output, (result as OperationResult.Success).value)
    }

    @Test
    fun `renames a readable directory through typed operation`() = runTest {
        val parent = path("/data/local/tmp/source")
        val source = entry("folder", parent, EntryType.DIRECTORY)
        var requestedName: String? = null
        val repository = FileRenameRepository { _, _, name ->
            requestedName = name.value
            OperationResult.Success(entry(name.value, parent, EntryType.DIRECTORY))
        }

        val result = repository.rename(source, parent, "renamed folder")

        assertTrue(result is OperationResult.Success)
        assertEquals("renamed folder", requestedName)
    }

    @Test
    fun `rejects invalid names unchanged names unsafe sources and protected parents`() = runTest {
        var calls = 0
        val repository = FileRenameRepository { _, _, _ ->
            calls += 1
            error("must not dispatch")
        }
        val parent = path("/data/local/tmp/source")
        val source = entry("report.txt", parent)
        val cases = listOf(
            repository.rename(source, parent, "bad/name") to ErrorCode.COMMAND_FAILED,
            repository.rename(source, parent, "report.txt") to ErrorCode.ALREADY_EXISTS,
            repository.rename(entry("other", parent, EntryType.OTHER), parent, "new") to ErrorCode.SOURCE_UNREADABLE,
            repository.rename(entry("report.txt", path("/system/source")), path("/system/source"), "new") to ErrorCode.NOT_WRITABLE,
            repository.rename(entry("report.txt", path("/data/adb")), path("/data/adb"), "new") to ErrorCode.NOT_WRITABLE,
        )

        cases.forEach { (result, code) ->
            assertTrue(result is OperationResult.Failure)
            assertEquals(code, (result as OperationResult.Failure).code)
        }
        assertEquals(0, calls)
    }

    private fun entry(name: String, parent: RootPath, type: EntryType = EntryType.FILE) = DirectoryEntry(
        path = path("${parent.value}/$name"), name = name, type = type, sizeBytes = 12L,
        modifiedAtEpochSeconds = 1L, readable = true, writable = false, symbolicLink = false,
    )

    private fun path(value: String) = RootPath.parse(value).getOrThrow()
}
