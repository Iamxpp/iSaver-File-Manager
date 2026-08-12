package com.iamxpp.isaver.fileops

import com.iamxpp.isaver.data.root.DirectorySnapshot
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryMergeRepositoryTest {
    @Test
    fun `merge recursively processes nested directories and removes empty move source`() = runTest {
        val sourceParent = path("/source-parent")
        val source = directory("/source-parent/folder", sourceParent)
        val targetParent = path("/target-parent")
        val target = directory("/target-parent/folder", targetParent)
        val file = file("/source-parent/folder/docs/readme.txt", path("/source-parent/folder/docs"))
        val targetDocs = directory("/target-parent/folder/docs", target.path)
        val targetFile = file("/target-parent/folder/docs/existing.txt", targetDocs.path)
        val directories = mutableMapOf(
            source.path.value to listOf(directory("/source-parent/folder/docs", source.path)),
            "/source-parent/folder/docs" to listOf(file),
            target.path.value to listOf(targetDocs),
            targetDocs.path.value to listOf(targetFile),
        )
        val moved = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val repository = DirectoryMergeRepository(
            fileSystem = fakeFileSystem(directories),
            copy = { _, _, _, _ -> error("copy should not be used") },
            move = { child, _, destination, _ ->
                moved += child.path.value
                directories[child.path.value.substringBeforeLast('/')] = emptyList()
                OperationResult.Success(child.copy(path = path("${destination.value}/${child.name}")))
            },
            deleteEmptyDirectory = { entry, _ ->
                deleted += entry.path.value
                val parent = entry.path.value.substringBeforeLast('/')
                directories[parent] = directories[parent].orEmpty().filterNot { it.path == entry.path }
                OperationResult.Success(Unit)
            },
        )

        val result = repository.merge(source, sourceParent, target, targetParent, moveSource = true, ConflictAction.MERGE)

        assertEquals(1, (result as OperationResult.Success).value.processed)
        assertEquals(listOf(file.path.value), moved)
        assertEquals(listOf("/source-parent/folder/docs", source.path.value), deleted)
    }

    @Test
    fun `merge keeps both same-name files without overwrite`() = runTest {
        val sourceParent = path("/source-parent")
        val targetParent = path("/target-parent")
        val source = directory("/source-parent/folder", sourceParent)
        val target = directory("/target-parent/folder", targetParent)
        val sourceFile = file("/source-parent/folder/readme.txt", source.path)
        val targetFile = file("/target-parent/folder/readme.txt", target.path)
        val copied = mutableListOf<Pair<String, ConflictAction>>()
        val repository = DirectoryMergeRepository(
            fileSystem = fakeFileSystem(
                mapOf(source.path.value to listOf(sourceFile), target.path.value to listOf(targetFile)),
            ),
            copy = { child, _, _, action ->
                copied += child.path.value to action
                OperationResult.Success(child)
            },
            move = { _, _, _, _ -> error("move should not be used") },
            deleteEmptyDirectory = { _, _ -> error("source must remain") },
        )

        val result = repository.merge(source, sourceParent, target, targetParent, moveSource = false, ConflictAction.MERGE)

        assertEquals(1, (result as OperationResult.Success).value.processed)
        assertEquals(listOf(sourceFile.path.value to ConflictAction.KEEP_BOTH), copied)
    }

    @Test
    fun `merge rejects symbolic links and special entries`() = runTest {
        val sourceParent = path("/source-parent")
        val targetParent = path("/target-parent")
        val source = directory("/source-parent/folder", sourceParent)
        val target = directory("/target-parent/folder", targetParent)
        val unsafe = file("/source-parent/folder/link", source.path).copy(symbolicLink = true)
        val repository = DirectoryMergeRepository(
            fileSystem = fakeFileSystem(mapOf(source.path.value to listOf(unsafe), target.path.value to emptyList())),
            copy = { _, _, _, _ -> error("copy should not be used") },
            move = { _, _, _, _ -> error("move should not be used") },
            deleteEmptyDirectory = { _, _ -> error("source must remain") },
        )

        val result = repository.merge(source, sourceParent, target, targetParent, moveSource = false, ConflictAction.MERGE)

        assertTrue(result is OperationResult.Failure)
        assertEquals(ErrorCode.SOURCE_UNREADABLE, (result as OperationResult.Failure).code)
    }

    private fun fakeFileSystem(directories: Map<String, List<DirectoryEntry>>): RootFileSystem = object : RootFileSystem {
        override suspend fun readDirectory(path: RootPath): OperationResult<DirectorySnapshot> =
            directories[path.value]?.let { entries -> OperationResult.Success(DirectorySnapshot(1, 1, true, true, entries)) }
                ?: OperationResult.Failure(ErrorCode.NOT_FOUND, "missing")

        override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> =
            OperationResult.Failure(ErrorCode.NOT_FOUND, "unused")

        override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> = OperationResult.Success(path)

        override suspend fun createDirectory(parent: RootPath, name: com.iamxpp.isaver.domain.FolderName): OperationResult<DirectoryEntry> =
            OperationResult.Failure(ErrorCode.COMMAND_FAILED, "unused")
    }

    private fun directory(value: String, parent: RootPath) = DirectoryEntry(path(value), value.substringAfterLast('/'), EntryType.DIRECTORY, null, 1, true, true, false)
    private fun file(value: String, parent: RootPath) = DirectoryEntry(path(value), value.substringAfterLast('/'), EntryType.FILE, 1, 1, true, true, false)
    private fun path(value: String) = RootPath.parse(value).getOrThrow()
}
