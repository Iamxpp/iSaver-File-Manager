package com.isaver.filemanager.search

import com.isaver.filemanager.data.root.DirectorySnapshot
import com.isaver.filemanager.data.root.RootFileSystem
import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.FolderName
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSearchRepositoryTest {
    @Test fun `breadth first search filters names extensions types sizes and dates`() = runTest {
        val fileSystem = FakeSearchFileSystem(
            mapOf(
                "/root" to listOf(
                    entry("/root/a", "a", EntryType.DIRECTORY),
                    entry("/root/report.TXT", "report.TXT", EntryType.FILE, 30, 200),
                    entry("/root/old.txt", "old.txt", EntryType.FILE, 30, 10),
                ),
                "/root/a" to listOf(entry("/root/a/report-2.txt", "report-2.txt", EntryType.FILE, 40, 300)),
            ),
        )
        val repository = LocalSearchRepository(fileSystem)

        val result = repository.search(
            path("/root"),
            LocalSearchCriteria(
                query = "report-?\\d*",
                regularExpression = true,
                extension = ".txt",
                entryType = SearchEntryType.FILE,
                minimumSizeBytes = 35,
                modifiedAfterEpochSeconds = 100,
            ),
        ) as OperationResult.Success

        assertEquals(listOf("/root/a/report-2.txt"), result.value.entries.map { it.path.value })
        assertEquals(listOf("/root", "/root/a"), fileSystem.readPaths)
        assertEquals(4, result.value.scannedEntries)
        assertFalse(result.value.truncated)
    }

    @Test fun `does not follow symbolic or unreadable directories and skips child failures`() = runTest {
        val fileSystem = FakeSearchFileSystem(
            mapOf(
                "/root" to listOf(
                    entry("/root/link", "link", EntryType.DIRECTORY, symbolicLink = true),
                    entry("/root/closed", "closed", EntryType.DIRECTORY, readable = false),
                    entry("/root/gone", "gone", EntryType.DIRECTORY),
                ),
            ),
        )
        val result = LocalSearchRepository(fileSystem).search(
            path("/root"), LocalSearchCriteria("missing"),
        ) as OperationResult.Success

        assertEquals(listOf("/root", "/root/gone"), fileSystem.readPaths)
        assertEquals(1, result.value.skippedDirectories)
    }

    @Test fun `rejects malformed regular expressions before root io`() = runTest {
        val fileSystem = FakeSearchFileSystem(emptyMap())
        val result = LocalSearchRepository(fileSystem).search(
            path("/root"), LocalSearchCriteria("[", regularExpression = true),
        )

        assertEquals(ErrorCode.COMMAND_FAILED, (result as OperationResult.Failure).code)
        assertEquals("搜索条件无效", result.userMessage)
        assertTrue(fileSystem.readPaths.isEmpty())
    }

    @Test fun `enforces result and scan limits`() = runTest {
        val entries = (1..5).map { entry("/root/$it.txt", "$it.txt", EntryType.FILE) }
        val resultLimit = LocalSearchRepository(
            FakeSearchFileSystem(mapOf("/root" to entries)), maxResults = 2,
        ).search(path("/root"), LocalSearchCriteria("")) as OperationResult.Success
        val scanLimit = LocalSearchRepository(
            FakeSearchFileSystem(mapOf("/root" to entries)), maxScannedEntries = 3,
        ).search(path("/root"), LocalSearchCriteria("none")) as OperationResult.Success

        assertEquals(2, resultLimit.value.entries.size)
        assertTrue(resultLimit.value.truncated)
        assertEquals(3, scanLimit.value.scannedEntries)
        assertTrue(scanLimit.value.truncated)
    }

    @Test fun `cancellation propagates while a directory is loading`() = runTest {
        val release = CompletableDeferred<Unit>()
        val fileSystem = object : RootFileSystem by FakeSearchFileSystem(emptyMap()) {
            override suspend fun readDirectory(path: RootPath): OperationResult<DirectorySnapshot> {
                release.await()
                return snapshot(emptyList())
            }
        }
        val search = async { LocalSearchRepository(fileSystem).search(path("/root"), LocalSearchCriteria("")) }

        search.cancel()

        try {
            search.await()
            throw AssertionError("cancellation expected")
        } catch (_: CancellationException) {
            assertTrue(search.isCancelled)
        }
    }

    private class FakeSearchFileSystem(
        private val directories: Map<String, List<DirectoryEntry>>,
    ) : RootFileSystem {
        val readPaths = mutableListOf<String>()
        override suspend fun readDirectory(path: RootPath): OperationResult<DirectorySnapshot> {
            readPaths += path.value
            return directories[path.value]?.let(::snapshot)
                ?: OperationResult.Failure(ErrorCode.NOT_FOUND, "路径不存在")
        }
        override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> = error("unused")
        override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> = error("unused")
        override suspend fun createDirectory(
            parent: RootPath,
            name: FolderName,
        ): OperationResult<DirectoryEntry> = error("search must remain read only")
    }

    private companion object {
        fun path(value: String) = RootPath.parse(value).getOrThrow()
        fun snapshot(entries: List<DirectoryEntry>) = OperationResult.Success(
            DirectorySnapshot(1, 2, true, true, entries),
        )
        fun entry(
            path: String,
            name: String,
            type: EntryType,
            size: Long? = null,
            modified: Long? = null,
            readable: Boolean = true,
            symbolicLink: Boolean = false,
        ) = DirectoryEntry(
            RootPath.parse(path).getOrThrow(), name, type, size, modified,
            readable, false, symbolicLink,
        )
    }
}
