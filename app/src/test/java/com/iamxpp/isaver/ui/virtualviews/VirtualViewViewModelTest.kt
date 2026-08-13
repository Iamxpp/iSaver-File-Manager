package com.iamxpp.isaver.ui.virtualviews

import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootEntryIdentity
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.virtualviews.VirtualViewNode
import com.iamxpp.isaver.virtualviews.VirtualViewResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualViewViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `navigates folders and creates children at current level`() = runTest {
        val store = FakeVirtualViewStore()
        val vm = VirtualViewViewModel(store, ioDispatcher = StandardTestDispatcher(testScheduler))
        testScheduler.runCurrent()

        vm.createFolder("工作")
        testScheduler.runCurrent()
        val folder = store.root.value.single()
        vm.openFolder(folder as VirtualViewNode.VirtualFolder)
        testScheduler.runCurrent()
        vm.createFolder("项目")
        testScheduler.runCurrent()

        assertEquals(folder.id, vm.state.value.currentFolderId)
        assertEquals(listOf("工作"), vm.state.value.breadcrumbs.map { it.displayName })
        assertEquals(listOf("项目"), vm.state.value.children.map { it.displayName })
        vm.navigateTo(null)
        testScheduler.runCurrent()
        assertNull(vm.state.value.currentFolderId)
    }

    @Test
    fun `nonempty deletion asks for confirmation before removing logical nodes`() = runTest {
        val store = FakeVirtualViewStore()
        val vm = VirtualViewViewModel(store, ioDispatcher = StandardTestDispatcher(testScheduler))
        testScheduler.runCurrent()
        vm.createFolder("资料")
        testScheduler.runCurrent()
        val folder = store.root.value.single() as VirtualViewNode.VirtualFolder
        store.deleteResult = VirtualViewResult.ConfirmationRequired

        vm.deleteFolder(folder.id, confirmed = false)
        testScheduler.runCurrent()
        assertEquals(folder.id, vm.state.value.confirmDeleteFolderId)

        store.deleteResult = VirtualViewResult.Success(folder.id)
        vm.deleteFolder(folder.id, confirmed = true)
        testScheduler.runCurrent()
        assertNull(vm.state.value.confirmDeleteFolderId)
    }

    @Test
    fun `adding a reference revalidates canonical type and identity before storing`() = runTest {
        val store = FakeVirtualViewStore()
        val source = RootPath.parse("/storage/emulated/0/Download/item.txt").getOrThrow()
        val canonical = RootPath.parse("/data/media/0/Download/item.txt").getOrThrow()
        val identity = RootEntryIdentity(12, 34)
        val fileSystem = FakeRootFileSystem(
            statEntry = entry(source, EntryType.FILE),
            canonicalPath = canonical,
            canonicalEntry = entry(canonical, EntryType.FILE),
            identity = identity,
        )
        val vm = VirtualViewViewModel(store, fileSystem, StandardTestDispatcher(testScheduler))
        testScheduler.runCurrent()
        vm.createFolder("资料")
        testScheduler.runCurrent()
        val folder = store.root.value.single() as VirtualViewNode.VirtualFolder

        vm.beginAddReference(source, "item.txt", EntryType.FILE)
        vm.openPickerFolder(folder.id)
        vm.confirmAddReference("工作文件")
        testScheduler.runCurrent()

        assertEquals(canonical, store.addedPath)
        assertEquals(EntryType.FILE, store.addedType)
        assertEquals(identity, store.addedIdentity)
        assertEquals("工作文件", store.addedName)
        assertNull(vm.state.value.pendingReference)
        assertEquals("已添加到“资料”", vm.state.value.message)
    }

    @Test
    fun `adding rejects a target whose type changed during revalidation`() = runTest {
        val store = FakeVirtualViewStore()
        val source = RootPath.parse("/storage/emulated/0/Download/item.txt").getOrThrow()
        val fileSystem = FakeRootFileSystem(
            statEntry = entry(source, EntryType.DIRECTORY),
            canonicalPath = source,
            canonicalEntry = entry(source, EntryType.DIRECTORY),
            identity = RootEntryIdentity(12, 34),
        )
        val vm = VirtualViewViewModel(store, fileSystem, StandardTestDispatcher(testScheduler))
        testScheduler.runCurrent()
        vm.createFolder("资料")
        testScheduler.runCurrent()
        val folder = store.root.value.single() as VirtualViewNode.VirtualFolder

        vm.beginAddReference(source, "item.txt", EntryType.FILE)
        vm.openPickerFolder(folder.id)
        vm.confirmAddReference("item.txt")
        testScheduler.runCurrent()

        assertNull(store.addedPath)
        assertTrue(vm.state.value.error!!.contains("类型已变化"))
        assertEquals(source, vm.state.value.pendingReference?.path)
    }

    private class FakeVirtualViewStore : VirtualViewStore {
        val root = MutableStateFlow<List<VirtualViewNode>>(emptyList())
        private val childFlows = mutableMapOf<String, MutableStateFlow<List<VirtualViewNode>>>()
        var deleteResult: VirtualViewResult? = null
        var addResult: VirtualViewResult? = null
        var addedPath: RootPath? = null
        var addedType: EntryType? = null
        var addedIdentity: RootEntryIdentity? = null
        var addedName: String? = null
        private var nextId = 0

        override fun observeChildren(parentFolderId: String?): Flow<List<VirtualViewNode>> =
            parentFolderId?.let { childFlows.getOrPut(it) { MutableStateFlow(emptyList()) } } ?: root

        override suspend fun findNode(id: String): VirtualViewNode? =
            (root.value + childFlows.values.flatMap { it.value }).firstOrNull { it.id == id }

        override suspend fun createFolder(parentFolderId: String?, name: String): VirtualViewResult {
            val id = "folder-${++nextId}"
            val folder = VirtualViewNode.VirtualFolder(id, parentFolderId, name, 0, 1, 1)
            val flow = parentFolderId?.let { childFlows.getOrPut(it) { MutableStateFlow(emptyList()) } } ?: root
            flow.value = flow.value + folder
            return VirtualViewResult.Success(id)
        }

        override suspend fun renameNode(nodeId: String, displayName: String) = VirtualViewResult.Success(nodeId)
        override suspend fun moveNode(nodeId: String, targetFolderId: String?) = VirtualViewResult.Success(nodeId)
        override suspend fun deleteFolder(nodeId: String, confirmed: Boolean) =
            deleteResult ?: VirtualViewResult.Success(nodeId)
        override suspend fun removeReference(nodeId: String) = VirtualViewResult.Success(nodeId)
        override suspend fun addReference(
            targetFolderId: String,
            path: RootPath,
            type: EntryType,
            identity: RootEntryIdentity?,
            displayName: String,
        ): VirtualViewResult {
            addedPath = path
            addedType = type
            addedIdentity = identity
            addedName = displayName
            return addResult ?: VirtualViewResult.Success("reference")
        }
    }

    private class FakeRootFileSystem(
        private val statEntry: DirectoryEntry,
        private val canonicalPath: RootPath,
        private val canonicalEntry: DirectoryEntry,
        private val identity: RootEntryIdentity,
    ) : RootFileSystem {
        private var statCalls = 0
        override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> =
            OperationResult.Success(if (statCalls++ == 0) statEntry else canonicalEntry)
        override suspend fun canonicalize(path: RootPath) = OperationResult.Success(canonicalPath)
        override suspend fun identity(path: RootPath) = OperationResult.Success(identity)
        override suspend fun createDirectory(parent: RootPath, name: com.iamxpp.isaver.domain.FolderName) =
            throw UnsupportedOperationException()
    }

    private companion object {
        fun entry(path: RootPath, type: EntryType) = DirectoryEntry(
            path = path,
            name = path.value.substringAfterLast('/'),
            type = type,
            sizeBytes = 1,
            modifiedAtEpochSeconds = 1,
            readable = true,
            writable = true,
            symbolicLink = false,
        )
    }
}
