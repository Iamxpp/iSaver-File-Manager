package com.iamxpp.isaver.ui.virtualviews

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
import org.junit.Test

class VirtualViewViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `navigates folders and creates children at current level`() = runTest {
        val store = FakeVirtualViewStore()
        val vm = VirtualViewViewModel(store, StandardTestDispatcher(testScheduler))
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
        val vm = VirtualViewViewModel(store, StandardTestDispatcher(testScheduler))
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

    private class FakeVirtualViewStore : VirtualViewStore {
        val root = MutableStateFlow<List<VirtualViewNode>>(emptyList())
        private val childFlows = mutableMapOf<String, MutableStateFlow<List<VirtualViewNode>>>()
        var deleteResult: VirtualViewResult? = null
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
    }
}
