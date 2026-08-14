package com.isaver.filemanager.fileops

import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.transfer.TargetNameResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileMoveRepositoryTest {
    @Test
    fun `keep both passes a generated target name to the typed move`() = runTest {
        val sourceDirectory = path("/data/local/tmp/source")
        val targetDirectory = path("/data/local/tmp/target")
        val source = entry("archive.tar.gz", sourceDirectory)
        val targetNames = mutableListOf<String>()
        val repository = FileMoveRepository(
            moveFileAs = { _, _, _, targetName ->
                targetNames += targetName.value
                if (targetNames.size == 1) {
                    OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "目标位置已存在同名文件")
                } else {
                    OperationResult.Success(entry(targetName.value, targetDirectory))
                }
            },
            nameResolver = TargetNameResolver(3),
        )

        val result = repository.move(
            source,
            sourceDirectory,
            targetDirectory,
            ConflictAction.KEEP_BOTH,
        )

        assertEquals(listOf("archive.tar.gz", "archive.tar (1).gz"), targetNames)
        assertEquals("archive.tar (1).gz", (result as OperationResult.Success).value.name)
    }

    @Test
    fun `moves one readable regular file through the typed root operation`() = runTest {
        val sourceDirectory = path("/data/local/tmp/source")
        val targetDirectory = path("/data/local/tmp/target")
        val source = entry("report.txt", sourceDirectory)
        val output = entry("report.txt", targetDirectory)
        val requests = mutableListOf<Triple<DirectoryEntry, RootPath, RootPath>>()
        val repository = FileMoveRepository { entry, sourceParent, targetParent ->
            requests += Triple(entry, sourceParent, targetParent)
            OperationResult.Success(output)
        }

        val result = repository.move(source, sourceDirectory, targetDirectory)

        assertEquals(listOf(Triple(source, sourceDirectory, targetDirectory)), requests)
        assertEquals(output, (result as OperationResult.Success).value)
    }

    @Test
    fun `moves a readable directory through the generic typed operation`() = runTest {
        val sourceParent = path("/data/local/tmp/source")
        val targetParent = path("/data/local/tmp/target")
        val source = entry("folder", sourceParent, EntryType.DIRECTORY)
        val targets = mutableListOf<String>()
        val repository = FileMoveRepository(
            moveFileAs = { _, _, target, targetName ->
                targets += targetName.value
                OperationResult.Success(entry(targetName.value, target, EntryType.DIRECTORY))
            },
            nameResolver = TargetNameResolver(),
        )

        val result = repository.move(source, sourceParent, targetParent)

        assertTrue(result is OperationResult.Success)
        assertEquals(listOf("folder"), targets)
    }

    @Test
    fun `rejects unsafe sources same parent and protected paths before root dispatch`() = runTest {
        var calls = 0
        val repository = FileMoveRepository { _, _, _ ->
            calls += 1
            error("must not dispatch")
        }
        val sourceDirectory = path("/data/local/tmp/source")
        val cases = listOf(
            Triple(entry("link", sourceDirectory, symbolicLink = true), sourceDirectory, path("/data/local/tmp/target")) to ErrorCode.SOURCE_UNREADABLE,
            Triple(entry("report.txt", sourceDirectory), sourceDirectory, sourceDirectory) to ErrorCode.ALREADY_EXISTS,
            Triple(entry("report.txt", path("/system/source")), path("/system/source"), path("/data/local/tmp/target")) to ErrorCode.NOT_WRITABLE,
            Triple(entry("report.txt", sourceDirectory), sourceDirectory, path("/data/adb")) to ErrorCode.NOT_WRITABLE,
        )

        cases.forEach { (request, expectedCode) ->
            val result = repository.move(request.first, request.second, request.third)
            assertEquals(expectedCode, (result as OperationResult.Failure).code)
        }
        assertEquals(0, calls)
    }

    @Test
    fun `rejects a source path that is not a direct child of the selected directory`() = runTest {
        val repository = FileMoveRepository { _, _, _ -> error("must not dispatch") }
        val result = repository.move(
            entry("report.txt", path("/data/local/tmp/other")),
            path("/data/local/tmp/source"),
            path("/data/local/tmp/target"),
        )

        assertTrue(result is OperationResult.Failure)
        assertEquals(ErrorCode.SOURCE_UNREADABLE, (result as OperationResult.Failure).code)
    }

    private fun entry(
        name: String,
        parent: RootPath,
        type: EntryType = EntryType.FILE,
        symbolicLink: Boolean = false,
    ) = DirectoryEntry(
        path = path("${parent.value}/$name"),
        name = name,
        type = type,
        sizeBytes = 12L,
        modifiedAtEpochSeconds = 1L,
        readable = true,
        writable = false,
        symbolicLink = symbolicLink,
    )

    private fun path(value: String) = RootPath.parse(value).getOrThrow()
}
