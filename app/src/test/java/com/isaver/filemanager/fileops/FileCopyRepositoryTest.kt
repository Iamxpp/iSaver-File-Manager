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

class FileCopyRepositoryTest {
    @Test
    fun `keep both retries only explicit conflicts with generated target names`() = runTest {
        val sourceDirectory = path("/data/local/tmp/source")
        val targetDirectory = path("/data/local/tmp/target")
        val source = entry("report.txt", sourceDirectory)
        val targetNames = mutableListOf<String>()
        val repository = FileCopyRepository(
            copyFileAs = { _, _, _, targetName ->
                targetNames += targetName.value
                if (targetNames.size < 3) {
                    OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "目标位置已存在同名文件")
                } else {
                    OperationResult.Success(entry(targetName.value, targetDirectory))
                }
            },
            nameResolver = TargetNameResolver(4),
        )

        val result = repository.copy(
            source,
            sourceDirectory,
            targetDirectory,
            ConflictAction.KEEP_BOTH,
        )

        assertEquals(listOf("report.txt", "report (1).txt", "report (2).txt"), targetNames)
        assertEquals("report (2).txt", (result as OperationResult.Success).value.name)
    }

    @Test
    fun `keep both never retries an uncertain copy result`() = runTest {
        var calls = 0
        val sourceDirectory = path("/data/local/tmp/source")
        val repository = FileCopyRepository(
            copyFileAs = { _, _, _, _ ->
                calls += 1
                OperationResult.Failure(ErrorCode.OUTCOME_UNCERTAIN, "复制结果不确定")
            },
            nameResolver = TargetNameResolver(4),
        )

        val result = repository.copy(
            entry("report.txt", sourceDirectory),
            sourceDirectory,
            path("/data/local/tmp/target"),
            ConflictAction.KEEP_BOTH,
        )

        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (result as OperationResult.Failure).code)
        assertEquals(1, calls)
    }

    @Test
    fun `copies one readable regular file through the typed root operation`() = runTest {
        val sourceDirectory = path("/data/local/tmp/source")
        val targetDirectory = path("/data/local/tmp/target")
        val source = entry("report.txt", sourceDirectory)
        val output = entry("report.txt", targetDirectory)
        val requests = mutableListOf<Triple<DirectoryEntry, RootPath, RootPath>>()
        val repository = FileCopyRepository { selected, sourceParent, targetParent ->
            requests += Triple(selected, sourceParent, targetParent)
            OperationResult.Success(output)
        }

        val result = repository.copy(source, sourceDirectory, targetDirectory)

        assertEquals(listOf(Triple(source, sourceDirectory, targetDirectory)), requests)
        assertEquals(output, (result as OperationResult.Success).value)
    }

    @Test
    fun `copies a readable directory but rejects itself and descendant targets`() = runTest {
        val sourceParent = path("/data/local/tmp/source")
        val source = entry("folder", sourceParent, EntryType.DIRECTORY)
        val target = path("/data/local/tmp/target")
        val requests = mutableListOf<DirectoryEntry>()
        val repository = FileCopyRepository(
            copyFileAs = { selected, _, targetDirectory, targetName ->
                requests += selected
                OperationResult.Success(entry(targetName.value, targetDirectory, EntryType.DIRECTORY))
            },
            nameResolver = TargetNameResolver(),
        )

        assertTrue(repository.copy(source, sourceParent, target) is OperationResult.Success)
        assertEquals(ErrorCode.COMMAND_FAILED, (repository.copy(source, sourceParent, source.path) as OperationResult.Failure).code)
        assertEquals(
            ErrorCode.COMMAND_FAILED,
            (repository.copy(source, sourceParent, path("${source.path.value}/child")) as OperationResult.Failure).code,
        )
        assertEquals(listOf(source), requests)
    }

    @Test
    fun `rejects unsafe sources same parent and protected targets before root dispatch`() = runTest {
        var calls = 0
        val repository = FileCopyRepository { _, _, _ ->
            calls += 1
            error("must not dispatch")
        }
        val sourceDirectory = path("/data/local/tmp/source")
        val cases = listOf(
            Triple(entry("link", sourceDirectory, symbolicLink = true), sourceDirectory, path("/data/local/tmp/target")) to ErrorCode.SOURCE_UNREADABLE,
            Triple(entry("report.txt", sourceDirectory), sourceDirectory, sourceDirectory) to ErrorCode.ALREADY_EXISTS,
            Triple(entry("report.txt", sourceDirectory), sourceDirectory, path("/system")) to ErrorCode.NOT_WRITABLE,
        )

        cases.forEach { (request, expectedCode) ->
            val result = repository.copy(request.first, request.second, request.third)
            assertEquals(expectedCode, (result as OperationResult.Failure).code)
        }
        assertEquals(0, calls)
    }

    @Test
    fun `rejects a source path that is not a direct child of the selected directory`() = runTest {
        val repository = FileCopyRepository { _, _, _ -> error("must not dispatch") }
        val result = repository.copy(
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
