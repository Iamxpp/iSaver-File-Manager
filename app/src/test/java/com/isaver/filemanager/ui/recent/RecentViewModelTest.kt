package com.isaver.filemanager.ui.recent

import com.isaver.filemanager.data.local.RecentItemDao
import com.isaver.filemanager.data.local.RecentItemEntity
import com.isaver.filemanager.data.root.DirectorySnapshot
import com.isaver.filemanager.data.root.RootFileSystem
import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.FolderName
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.recent.RecentRepository
import com.isaver.filemanager.recent.RecentItemType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecentViewModelTest {
    private lateinit var mainDispatcher: TestDispatcher

    @Before
    fun setUpMainDispatcher() {
        mainDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `unavailable recent item remains visible and cannot open`() = runTest {
        val dao = FakeRecentItemDao()
        dao.seed(RecentItemEntity("/gone", "失效文件", null, "FILE", "ACCESSED", 1, true))
        val fileSystem = FakeRootFileSystem()
        fileSystem.statResult = OperationResult.Failure(ErrorCode.NOT_FOUND, "路径不存在")
        val viewModel = RecentViewModel(
            RecentRepository(dao) { 2L },
            fileSystem,
            StandardTestDispatcher(testScheduler),
        )

        advanceUntilIdle()

        val item = viewModel.state.value.items.single()
        assertEquals("项目不可用", item.status)
        assertNull(viewModel.open(item))
    }

    @Test
    fun `available archive opens as archive target`() = runTest {
        val dao = FakeRecentItemDao()
        dao.seed(RecentItemEntity("/archive.zip", "archive.zip", null, "ARCHIVE", "COMPRESSED", 1, true))
        val fileSystem = FakeRootFileSystem()
        fileSystem.statResult = OperationResult.Success(entry("/archive.zip", EntryType.FILE))
        val viewModel = RecentViewModel(
            RecentRepository(dao) { 2L },
            fileSystem,
            StandardTestDispatcher(testScheduler),
        )

        advanceUntilIdle()

        assertEquals(
            RecentOpenTarget.Archive(root("/archive.zip"), "archive.zip"),
            viewModel.open(viewModel.state.value.items.single()),
        )
        assertTrue(viewModel.state.value.items.single().available)
    }

    @Test
    fun `available ordinary file keeps recent page and exposes information`() = runTest {
        val dao = FakeRecentItemDao()
        dao.seed(RecentItemEntity("/report.pdf", "report.pdf", null, "FILE", "ACCESSED", 1, true))
        val fileSystem = FakeRootFileSystem()
        val entry = entry("/report.pdf", EntryType.FILE)
        fileSystem.statResult = OperationResult.Success(entry)
        val viewModel = RecentViewModel(
            RecentRepository(dao) { 2L }, fileSystem, StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()

        assertEquals(RecentOpenTarget.File(entry), viewModel.open(viewModel.state.value.items.single()))
        assertNull(viewModel.state.value.fileInfo)
        viewModel.dismissFileInfo()
        assertNull(viewModel.state.value.fileInfo)
    }

    @Test
    fun `availability persistence does not trigger a second filesystem probe`() = runTest {
        val dao = FakeRecentItemDao()
        dao.seed(RecentItemEntity("/gone", "失效文件", null, "FILE", "ACCESSED", 1, true))
        val fileSystem = FakeRootFileSystem().apply {
            statResult = OperationResult.Failure(ErrorCode.NOT_FOUND, "路径不存在")
        }
        RecentViewModel(
            RecentRepository(dao) { 2L }, fileSystem, StandardTestDispatcher(testScheduler),
        )

        advanceUntilIdle()

        assertEquals(1, fileSystem.statCalls)
    }

    @Test
    fun `refresh keeps resolved item visible while revalidating`() = runTest {
        val dao = FakeRecentItemDao()
        dao.seed(RecentItemEntity("/report.pdf", "report.pdf", null, "FILE", "ACCESSED", 1, true))
        val fileSystem = FakeRootFileSystem().apply {
            statResult = OperationResult.Success(entry("/report.pdf", EntryType.FILE))
        }
        val viewModel = RecentViewModel(
            RecentRepository(dao) { 2L }, fileSystem, StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()

        viewModel.refresh()

        assertTrue(viewModel.state.value.refreshing)
        assertTrue(viewModel.state.value.items.single().available)
    }

    private class FakeRecentItemDao : RecentItemDao() {
        private val flow = MutableStateFlow<List<RecentItemEntity>>(emptyList())
        private val rows = linkedMapOf<String, RecentItemEntity>()

        fun seed(vararg entities: RecentItemEntity) {
            rows.clear()
            entities.forEach { rows[it.absolutePath] = it }
            flow.value = entities.toList()
        }

        override fun observeRecent(): Flow<List<RecentItemEntity>> = flow
        override suspend fun upsert(entity: RecentItemEntity) { rows[entity.absolutePath] = entity }
        override suspend fun findByPath(path: String): RecentItemEntity? = rows[path]
        override suspend fun deleteBeyondLimit(limit: Int) = Unit
        override suspend fun markAvailability(path: String, available: Boolean): Int {
            val row = rows[path] ?: return 0
            rows[path] = row.copy(available = available)
            flow.value = rows.values.toList()
            return 1
        }
    }

    private class FakeRootFileSystem : RootFileSystem {
        var statResult: OperationResult<DirectoryEntry> = OperationResult.Failure(ErrorCode.NOT_FOUND, "missing")
        var statCalls = 0
        override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> {
            statCalls += 1
            return statResult
        }
        override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> = OperationResult.Success(path)
        override suspend fun readDirectory(path: RootPath): OperationResult<DirectorySnapshot> =
            OperationResult.Success(DirectorySnapshot(1L, 1L, true, false, emptyList()))
        override suspend fun createDirectory(parent: RootPath, name: FolderName): OperationResult<DirectoryEntry> =
            error("unused")
    }

    private fun entry(path: String, type: EntryType) = DirectoryEntry(
        path = root(path), name = path.substringAfterLast('/'), type = type,
        sizeBytes = 12, modifiedAtEpochSeconds = 1, readable = true,
        writable = false, symbolicLink = false,
    )

    private fun root(value: String) = RootPath.parse(value).getOrThrow()
}
