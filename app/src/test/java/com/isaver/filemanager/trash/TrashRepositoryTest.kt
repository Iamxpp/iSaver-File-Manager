package com.isaver.filemanager.trash

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.isaver.filemanager.data.local.ISaverDatabase
import com.isaver.filemanager.data.root.DirectorySnapshot
import com.isaver.filemanager.data.root.RootFileSystem
import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryName
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.FolderName
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootEntryIdentity
import com.isaver.filemanager.domain.RootPath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrashRepositoryTest {
    private lateinit var database: ISaverDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), ISaverDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun `recycle journals identity then restore removes active record`() = runTest {
        val fileSystem = FakeTrashFileSystem()
        val repository = TrashRepository(fileSystem, database.trashItemDao(), { 10 }, { "trash-id" })
        val sourceParent = path("/storage/emulated/0/Documents")
        val source = entry("report.txt", sourceParent, EntryType.FILE)
        fileSystem.entries[source.path] = source

        val recycled = repository.recycle(source, sourceParent) as OperationResult.Success

        assertEquals(TrashItemState.ACTIVE, recycled.value.state)
        assertEquals("trash-id", recycled.value.trashedName)
        assertEquals(1, repository.items.first().size)

        val restored = repository.restore(recycled.value)

        assertTrue(restored is OperationResult.Success)
        assertTrue(repository.items.first().isEmpty())
        assertTrue(fileSystem.entries.containsKey(source.path))
    }

    @Test fun `restore conflict preserves trash record`() = runTest {
        val fileSystem = FakeTrashFileSystem()
        val repository = TrashRepository(fileSystem, database.trashItemDao(), { 10 }, { "trash-id" })
        val sourceParent = path("/storage/emulated/0/Documents")
        val source = entry("report.txt", sourceParent, EntryType.FILE)
        fileSystem.entries[source.path] = source
        val item = (repository.recycle(source, sourceParent) as OperationResult.Success).value
        fileSystem.entries[source.path] = source

        val result = repository.restore(item)

        assertEquals(ErrorCode.ALREADY_EXISTS, (result as OperationResult.Failure).code)
        assertEquals(1, repository.items.first().size)
    }

    @Test fun `restore conflict can keep both with a generated name`() = runTest {
        val fileSystem = FakeTrashFileSystem()
        val repository = TrashRepository(fileSystem, database.trashItemDao(), { 10 }, { "trash-id" })
        val parent = path("/storage/emulated/0/Documents")
        val source = entry("report.txt", parent, EntryType.FILE)
        fileSystem.entries[source.path] = source
        val item = (repository.recycle(source, parent) as OperationResult.Success).value
        fileSystem.entries[source.path] = source

        val result = repository.restore(item, RestoreConflictAction.KEEP_BOTH)

        assertTrue(result is OperationResult.Success)
        assertTrue(fileSystem.entries.keys.any { it.value.endsWith("report (1).txt") })
        assertTrue(repository.items.first().isEmpty())
    }

    @Test fun `restore conflict accepts a validated replacement name`() = runTest {
        val fileSystem = FakeTrashFileSystem()
        val repository = TrashRepository(fileSystem, database.trashItemDao(), { 10 }, { "trash-id" })
        val parent = path("/storage/emulated/0/Documents")
        val source = entry("report.txt", parent, EntryType.FILE)
        fileSystem.entries[source.path] = source
        val item = (repository.recycle(source, parent) as OperationResult.Success).value
        fileSystem.entries[source.path] = source

        val result = repository.restore(item, RestoreConflictAction.RENAME, "restored.txt")

        assertTrue(result is OperationResult.Success)
        assertTrue(fileSystem.entries.containsKey(path("/storage/emulated/0/Documents/restored.txt")))
    }

    @Test fun `private data does not silently enter shared trash`() = runTest {
        val repository = TrashRepository(FakeTrashFileSystem(), database.trashItemDao(), { 10 }, { "trash-id" })
        val parent = path("/data/local/tmp")

        val result = repository.recycle(entry("secret.txt", parent, EntryType.FILE), parent)

        assertEquals(ErrorCode.NOT_WRITABLE, (result as OperationResult.Failure).code)
        assertTrue(repository.items.first().isEmpty())
    }

    @Test fun `permanent delete removes record only after filesystem success`() = runTest {
        val fileSystem = FakeTrashFileSystem()
        val repository = TrashRepository(fileSystem, database.trashItemDao(), { 10 }, { "trash-id" })
        val sourceParent = path("/storage/emulated/0/Documents")
        val source = entry("report.txt", sourceParent, EntryType.FILE)
        fileSystem.entries[source.path] = source
        val item = (repository.recycle(source, sourceParent) as OperationResult.Success).value

        val result = repository.deletePermanently(item)

        assertTrue(result is OperationResult.Success)
        assertTrue(repository.items.first().isEmpty())
        assertTrue(item.trashedPath !in fileSystem.entries)
    }

    @Test fun `batch restore stops at first conflict and preserves remaining records`() = runTest {
        val fileSystem = FakeTrashFileSystem()
        var nextId = 0
        val repository = TrashRepository(fileSystem, database.trashItemDao(), { 10 }, { "trash-${++nextId}" })
        val parent = path("/storage/emulated/0/Documents")
        val firstSource = entry("first.txt", parent, EntryType.FILE)
        val secondSource = entry("second.txt", parent, EntryType.FILE)
        fileSystem.entries[firstSource.path] = firstSource
        fileSystem.entries[secondSource.path] = secondSource
        val first = (repository.recycle(firstSource, parent) as OperationResult.Success).value
        val second = (repository.recycle(secondSource, parent) as OperationResult.Success).value
        fileSystem.entries[second.originalPath] = secondSource

        val result = repository.restoreAll(listOf(first, second))

        assertEquals(1, result.completed)
        assertEquals(ErrorCode.ALREADY_EXISTS, result.failure?.code)
        assertEquals(listOf(second.id), repository.items.first().map { it.id })
    }

    @Test fun `batch permanent delete removes every verified item`() = runTest {
        val fileSystem = FakeTrashFileSystem()
        var nextId = 0
        val repository = TrashRepository(fileSystem, database.trashItemDao(), { 10 }, { "trash-${++nextId}" })
        val parent = path("/storage/emulated/0/Documents")
        val sources = listOf(
            entry("first.txt", parent, EntryType.FILE),
            entry("second.txt", parent, EntryType.FILE),
        )
        sources.forEach { fileSystem.entries[it.path] = it }
        val items = sources.map { (repository.recycle(it, parent) as OperationResult.Success).value }

        val progress = mutableListOf<Int>()
        val result = repository.deletePermanentlyAll(items) { progress += it }

        assertEquals(2, result.completed)
        assertEquals(null, result.failure)
        assertEquals(listOf(1, 2), progress)
        assertTrue(repository.items.first().isEmpty())
    }

    private class FakeTrashFileSystem : RootFileSystem {
        val entries = linkedMapOf<RootPath, DirectoryEntry>().apply {
            listOf(
                path("/storage/emulated/0"), path("/storage/emulated/0/Documents"),
            ).forEach { put(it, directory(it)) }
        }
        private var nextInode = 100L
        private val identities = mutableMapOf<RootPath, RootEntryIdentity>()

        override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> =
            entries[path]?.let { OperationResult.Success(it) }
                ?: OperationResult.Failure(ErrorCode.NOT_FOUND, "路径不存在")

        override suspend fun canonicalize(path: RootPath) = OperationResult.Success(path)

        override suspend fun identity(path: RootPath): OperationResult<RootEntryIdentity> {
            if (path !in entries) return OperationResult.Failure(ErrorCode.NOT_FOUND, "路径不存在")
            return OperationResult.Success(identities.getOrPut(path) { RootEntryIdentity(1, nextInode++) })
        }

        override suspend fun createDirectory(parent: RootPath, name: FolderName): OperationResult<DirectoryEntry> {
            val target = FolderName.join(parent, name)
            if (target in entries) return OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "已存在")
            val created = directory(target)
            entries[target] = created
            return OperationResult.Success(created)
        }

        override suspend fun moveEntryAsNoReplace(
            source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath, targetName: EntryName,
        ): OperationResult<DirectoryEntry> {
            val target = EntryName.join(targetDirectory, targetName)
            if (target in entries) return OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "已存在")
            if (entries[source.path] != source) return OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, "来源变化")
            entries.remove(source.path)
            val identity = identities.remove(source.path)
            val moved = source.copy(path = target, name = targetName.value)
            entries[target] = moved
            if (identity != null) identities[target] = identity
            return OperationResult.Success(moved)
        }

        override suspend fun deleteEntryPermanently(source: DirectoryEntry, sourceDirectory: RootPath): OperationResult<Unit> {
            if (entries.remove(source.path) == null) return OperationResult.Failure(ErrorCode.NOT_FOUND, "不存在")
            identities.remove(source.path)
            return OperationResult.Success(Unit)
        }

        override suspend fun readDirectory(path: RootPath): OperationResult<DirectorySnapshot> = error("unused")
    }

    companion object {
        private fun path(value: String) = RootPath.parse(value).getOrThrow()
        private fun directory(path: RootPath) = DirectoryEntry(
            path, path.value.substringAfterLast('/'), EntryType.DIRECTORY, 0, 1, true, true, false,
        )
        private fun entry(name: String, parent: RootPath, type: EntryType) = DirectoryEntry(
            EntryName.join(parent, EntryName.parse(name).getOrThrow()), name, type,
            if (type == EntryType.FILE) 10 else 0, 1, true, true, false,
        )
    }
}
