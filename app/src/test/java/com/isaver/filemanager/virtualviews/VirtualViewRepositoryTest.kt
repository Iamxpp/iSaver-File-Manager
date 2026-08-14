package com.isaver.filemanager.virtualviews

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.isaver.filemanager.data.local.ISaverDatabase
import com.isaver.filemanager.domain.EntryType
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
import java.util.ArrayDeque

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VirtualViewRepositoryTest {
    private lateinit var database: ISaverDatabase
    private lateinit var repository: VirtualViewRepository
    private val ids = ArrayDeque(listOf("folder-a", "folder-b", "folder-c", "ref-a", "ref-b"))

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ISaverDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = VirtualViewRepository(database, { ids.removeFirst() }, { 100L })
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `real references require a virtual folder parent and are leaves`() = runTest {
        val path = root("/storage/emulated/0/Documents/report.txt")

        assertIs<VirtualViewResult.InvalidParent>(
            repository.addReference("missing", path, EntryType.FILE, null, "报告"),
        )
        val folderId = repository.createFolder(null, "工作").idOrThrow()
        val referenceId = repository.addReference(
            folderId,
            path,
            EntryType.FILE,
            RootEntryIdentity(8L, 9L),
            "报告",
        ).idOrThrow()

        assertIs<VirtualViewResult.InvalidParent>(repository.createFolder(referenceId, "非法子级"))
        assertEquals(listOf(referenceId), repository.observeChildren(folderId).first().map { it.id })
    }

    @Test
    fun `same target is unique within one folder but allowed across folders`() = runTest {
        val firstFolder = repository.createFolder(null, "工作").idOrThrow()
        val secondFolder = repository.createFolder(null, "归档").idOrThrow()
        val path = root("/storage/emulated/0/Documents")

        assertIs<VirtualViewResult.Success>(
            repository.addReference(firstFolder, path, EntryType.DIRECTORY, null, "文档"),
        )
        assertIs<VirtualViewResult.DuplicateReference>(
            repository.addReference(firstFolder, path, EntryType.DIRECTORY, null, "另一备注"),
        )
        assertIs<VirtualViewResult.Success>(
            repository.addReference(secondFolder, path, EntryType.DIRECTORY, null, "文档"),
        )
    }

    @Test
    fun `moving a folder into its descendant is rejected without partial update`() = runTest {
        val parent = repository.createFolder(null, "父级").idOrThrow()
        val child = repository.createFolder(parent, "子级").idOrThrow()

        assertIs<VirtualViewResult.Cycle>(repository.moveNode(parent, child))

        assertEquals(null, repository.findNode(parent)?.parentId)
        assertEquals(parent, repository.findNode(child)?.parentId)
    }

    @Test
    fun `deleting a nonempty virtual folder only removes virtual rows`() = runTest {
        val folder = repository.createFolder(null, "临时分组").idOrThrow()
        repository.addReference(folder, root("/data/local/tmp/keep.txt"), EntryType.FILE, null, "保留")

        assertIs<VirtualViewResult.ConfirmationRequired>(repository.deleteFolder(folder, confirmed = false))
        assertIs<VirtualViewResult.Success>(repository.deleteFolder(folder, confirmed = true))
        assertEquals(emptyList<VirtualViewNode>(), repository.observeChildren(null).first())
    }

    @Test
    fun `cleanup removes only the fixed empty migration folder`() = runTest {
        val userFolder = repository.createFolder(null, "未分组").idOrThrow()
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO virtual_view_nodes
                (id, parentId, nodeType, displayName, targetPath, entryType, device, inode,
                 available, sortOrder, createdAt, updatedAt)
            VALUES
                ('migration.virtual.ungrouped', NULL, 'VIRTUAL_FOLDER', '未分组', NULL, NULL, NULL, NULL,
                 1, 99, 0, 0)
            """.trimIndent(),
        )

        repository.cleanupEmptyLegacyMigrationFolder()

        assertEquals(listOf(userFolder), repository.observeChildren(null).first().map { it.id })
    }

    @Test
    fun `relocating an identity updates every reference and preserves organization`() = runTest {
        val firstFolder = repository.createFolder(null, "工作").idOrThrow()
        val secondFolder = repository.createFolder(null, "常用").idOrThrow()
        val oldPath = root("/data/local/tmp/old.txt")
        val newPath = root("/data/local/tmp/new.txt")
        val oldIdentity = RootEntryIdentity(8, 9)
        val newIdentity = RootEntryIdentity(8, 9)
        repository.addReference(firstFolder, oldPath, EntryType.FILE, oldIdentity, "报告").idOrThrow()
        repository.addReference(secondFolder, oldPath, EntryType.FILE, oldIdentity, "重点报告").idOrThrow()

        assertIs<VirtualViewResult.Success>(
            repository.relocateReferences(oldIdentity, newPath, EntryType.FILE, newIdentity),
        )

        val references = listOf(firstFolder, secondFolder).map {
            repository.observeChildren(it).first().single() as VirtualViewNode.RealReference
        }
        assertEquals(listOf("报告", "重点报告"), references.map { it.displayName })
        assertEquals(listOf(firstFolder, secondFolder), references.map { it.parentId })
        assertTrue(references.all { it.targetPath == newPath && it.identity == newIdentity && it.available })
    }

    @Test
    fun `availability changes only matching identity references`() = runTest {
        val folder = repository.createFolder(null, "工作").idOrThrow()
        val path = root("/data/local/tmp/report.txt")
        val expected = RootEntryIdentity(8, 9)
        repository.addReference(folder, path, EntryType.FILE, expected, "报告").idOrThrow()

        repository.setReferencesAvailability(expected, false)

        val reference = repository.observeChildren(folder).first().single() as VirtualViewNode.RealReference
        assertEquals(false, reference.available)
    }

    @Test
    fun `rebind rejects a duplicate target in the same virtual folder`() = runTest {
        val folder = repository.createFolder(null, "工作").idOrThrow()
        val oldPath = root("/data/local/tmp/old.txt")
        val existingPath = root("/data/local/tmp/existing.txt")
        val reference = repository.addReference(
            folder,
            oldPath,
            EntryType.FILE,
            RootEntryIdentity(8, 9),
            "旧报告",
        ).idOrThrow()
        repository.addReference(
            folder,
            existingPath,
            EntryType.FILE,
            RootEntryIdentity(8, 10),
            "已有报告",
        ).idOrThrow()

        assertIs<VirtualViewResult.DuplicateReference>(
            repository.rebindReference(reference, existingPath, EntryType.FILE, RootEntryIdentity(8, 10)),
        )

        val unchanged = repository.findNode(reference) as VirtualViewNode.RealReference
        assertEquals(oldPath, unchanged.targetPath)
        assertEquals("旧报告", unchanged.displayName)
    }

    private fun VirtualViewResult.idOrThrow(): String = (this as VirtualViewResult.Success).nodeId

    private inline fun <reified T> assertIs(value: Any?) {
        assertTrue("Expected ${T::class.java.simpleName}, got ${value?.javaClass?.simpleName}", value is T)
    }

    private fun root(value: String): RootPath = RootPath.parse(value).getOrThrow()
}
