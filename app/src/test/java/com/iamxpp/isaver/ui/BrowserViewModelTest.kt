package com.iamxpp.isaver.ui

import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.Before

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(mainDispatcher)
    @After fun tearDown() = Dispatchers.resetMain()
    @Test fun `init asynchronously loads storage root and exposes loading then success`() = runTest {
        val gate = CompletableDeferred<OperationResult<List<DirectoryEntry>>>()
        val fs = FakeFileSystem { gate.await() }
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler))
        assertTrue(vm.state.value.loading)
        assertEquals("/storage/emulated/0", vm.state.value.currentPath.value)
        testScheduler.runCurrent()
        assertEquals(listOf("/storage/emulated/0"), fs.listed)
        gate.complete(OperationResult.Success(listOf(entry("a", EntryType.FILE))))
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals(listOf("a"), vm.state.value.entries.map { it.name })
    }

    @Test fun `empty and failure states retain current path and user message`() = runTest {
        val results = ArrayDeque<OperationResult<List<DirectoryEntry>>>().apply {
            add(OperationResult.Success(emptyList()))
            add(OperationResult.Failure(ErrorCode.NOT_READABLE, "目录不可读", "hidden"))
        }
        val vm = BrowserViewModel(FakeFileSystem { results.removeFirst() }, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertTrue(vm.state.value.empty)
        vm.retry(); advanceUntilIdle()
        assertEquals("目录不可读", vm.state.value.errorMessage)
        assertEquals("/storage/emulated/0", vm.state.value.currentPath.value)
    }

    @Test fun `sorts directories first with case insensitive natural numeric order`() = runTest {
        val input = listOf(entry("file10", EntryType.FILE), entry("Dir10", EntryType.DIRECTORY), entry("file2", EntryType.FILE), entry("dir2", EntryType.DIRECTORY), entry("Alpha", EntryType.FILE), entry("alpha", EntryType.FILE))
        val vm = BrowserViewModel(FakeFileSystem { OperationResult.Success(input) }, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(listOf("dir2", "Dir10", "Alpha", "alpha", "file2", "file10"), vm.state.value.entries.map { it.name })
    }

    @Test fun `enter accepts only directories and back uses navigation stack`() = runTest {
        val vm = BrowserViewModel(FakeFileSystem { OperationResult.Success(emptyList()) }, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertFalse(vm.enterDirectory(entry("file", EntryType.FILE)))
        assertEquals(BrowserBackResult.RETURN_HOME, vm.back())
        val directory = entry("child", EntryType.DIRECTORY, "/storage/emulated/0/child")
        assertTrue(vm.enterDirectory(directory)); advanceUntilIdle()
        assertEquals(directory.path, vm.state.value.currentPath)
        assertTrue(vm.state.value.canGoBack)
        assertEquals(BrowserBackResult.NAVIGATED, vm.back()); advanceUntilIdle()
        assertEquals("/storage/emulated/0", vm.state.value.currentPath.value)
        assertEquals(BrowserBackResult.RETURN_HOME, vm.back())
    }

    @Test fun `openRoot clears old navigation and loads requested location`() = runTest {
        val fs = FakeFileSystem { OperationResult.Success(emptyList()) }
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        vm.enterDirectory(entry("child", EntryType.DIRECTORY, "/storage/emulated/0/child"))
        advanceUntilIdle()

        vm.openRoot(RootPath.parse("/data/local/tmp").getOrThrow(), "测试位置")
        advanceUntilIdle()

        assertEquals("/data/local/tmp", vm.state.value.currentPath.value)
        assertEquals("测试位置", vm.state.value.rootTitle)
        assertFalse(vm.state.value.canGoBack)
        assertEquals(BrowserBackResult.RETURN_HOME, vm.back())
        assertEquals("/data/local/tmp", fs.listed.last())
    }

    @Test fun `back at an opened location root requests the locations home`() = runTest {
        val vm = BrowserViewModel(FakeFileSystem { OperationResult.Success(emptyList()) }, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        vm.openRoot(RootPath.parse("/data/local/tmp").getOrThrow(), "测试位置")
        advanceUntilIdle()

        assertEquals(BrowserBackResult.RETURN_HOME, vm.back())
    }

    @Test fun `create directory uses typed name then refreshes and exposes location target`() = runTest {
        val created = entry("中文 folder", EntryType.DIRECTORY, "/storage/emulated/0/中文 folder")
        var listCount = 0
        val fs = FakeFileSystem(
            listBlock = {
                listCount += 1
                OperationResult.Success(if (listCount == 1) emptyList() else listOf(created))
            },
            createBlock = { parent, name ->
                assertEquals("/storage/emulated/0", parent.value)
                assertEquals("中文 folder", name.value)
                OperationResult.Success(created)
            },
        )
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        vm.createDirectory("中文 folder")
        advanceUntilIdle()

        assertEquals(2, listCount)
        assertEquals(created.path, vm.state.value.locationTarget)
        assertNull(vm.state.value.createDirectoryError)
    }

    @Test fun `create directory exposes the structured filesystem failure without refreshing`() = runTest {
        var listCount = 0
        val fs = FakeFileSystem(
            createBlock = { _, _ -> OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "文件夹已存在", "exists") },
            listBlock = { listCount += 1; OperationResult.Success(emptyList()) },
        )
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        vm.createDirectory("existing")
        advanceUntilIdle()

        assertEquals(1, listCount)
        assertEquals(ErrorCode.ALREADY_EXISTS, vm.state.value.createDirectoryError?.code)
        assertEquals("文件夹已存在", vm.state.value.createDirectoryError?.userMessage)
        assertFalse(vm.state.value.creatingDirectory)
    }

    @Test fun `read only current directory disables and rejects folder creation`() = runTest {
        var createCalls = 0
        val fs = FakeFileSystem(
            statBlock = { path -> OperationResult.Success(entry("readonly", EntryType.DIRECTORY, path.value, writable = false)) },
            createBlock = { _, _ -> createCalls += 1; error("must not create") },
            listBlock = { OperationResult.Success(emptyList()) },
        )
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        assertFalse(vm.state.value.canCreateDirectory)
        vm.createDirectory("blocked")
        advanceUntilIdle()

        assertEquals(0, createCalls)
        assertEquals(ErrorCode.NOT_WRITABLE, vm.state.value.createDirectoryError?.code)
    }

    @Test fun `writable directory symlink does not allow folder creation`() = runTest {
        val fs = FakeFileSystem(
            statBlock = { path ->
                OperationResult.Success(
                    entry("linked", EntryType.DIRECTORY, path.value, writable = true, symbolicLink = true),
                )
            },
            listBlock = { OperationResult.Success(emptyList()) },
        )
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        assertFalse(vm.state.value.canCreateDirectory)
    }

    @Test fun `invalid folder name is rejected before the root filesystem call`() = runTest {
        var createCalls = 0
        val fs = FakeFileSystem(
            createBlock = { _, _ -> createCalls += 1; error("must not create") },
            listBlock = { OperationResult.Success(emptyList()) },
        )
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        vm.createDirectory("..")

        assertEquals(0, createCalls)
        assertEquals(ErrorCode.COMMAND_FAILED, vm.state.value.createDirectoryError?.code)
        assertEquals("文件夹名称无效", vm.state.value.createDirectoryError?.userMessage)
    }

    @Test fun `unexpected create exception becomes a structured failure`() = runTest {
        val fs = FakeFileSystem(
            createBlock = { _, _ -> error("boom") },
            listBlock = { OperationResult.Success(emptyList()) },
        )
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        vm.createDirectory("folder")
        advanceUntilIdle()

        assertEquals(ErrorCode.COMMAND_FAILED, vm.state.value.createDirectoryError?.code)
        assertEquals("新建文件夹失败", vm.state.value.createDirectoryError?.userMessage)
        assertFalse(vm.state.value.creatingDirectory)
    }

    @Test fun `late old navigation result cannot overwrite newer directory`() = runTest {
        val old = CompletableDeferred<OperationResult<List<DirectoryEntry>>>()
        val fresh = CompletableDeferred<OperationResult<List<DirectoryEntry>>>()
        val fs = FakeFileSystem { path -> if (path.value.endsWith("old")) withContext(NonCancellable) { old.await() } else if (path.value.endsWith("new")) fresh.await() else OperationResult.Success(emptyList()) }
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler)); advanceUntilIdle()
        vm.enterDirectory(entry("old", EntryType.DIRECTORY, "/storage/emulated/0/old")); testScheduler.runCurrent()
        vm.enterDirectory(entry("new", EntryType.DIRECTORY, "/storage/emulated/0/new")); testScheduler.runCurrent()
        fresh.complete(OperationResult.Success(listOf(entry("fresh", EntryType.FILE)))); advanceUntilIdle()
        old.complete(OperationResult.Success(listOf(entry("stale", EntryType.FILE)))); advanceUntilIdle()
        assertEquals("/storage/emulated/0/new", vm.state.value.currentPath.value)
        assertEquals(listOf("fresh"), vm.state.value.entries.map { it.name })
    }

    @Test fun `cancellation does not become an error`() = runTest {
        val vm = BrowserViewModel(FakeFileSystem { throw CancellationException("cancel") }, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `sorting executes inside injected background dispatcher`() = runTest {
        val marker = ThreadLocal<Boolean>()
        val dispatcher = MarkerDispatcher(StandardTestDispatcher(testScheduler), marker)
        var sortedWithMarker = false
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(entry("b", EntryType.FILE), entry("a", EntryType.FILE))) },
            dispatcher,
            sorter = { entries -> sortedWithMarker = marker.get() == true; BrowserViewModel.sortEntries(entries) },
        )
        advanceUntilIdle()
        assertTrue(sortedWithMarker)
        assertEquals(listOf("a", "b"), vm.state.value.entries.map { it.name })
    }

    @Test fun `large results remain complete and reveal 200 entries per page`() = runTest {
        val all = (1..450).map { entry("file$it", EntryType.FILE) }
        val vm = BrowserViewModel(FakeFileSystem { OperationResult.Success(all) }, StandardTestDispatcher(testScheduler)); advanceUntilIdle()
        assertEquals(450, vm.state.value.totalCount); assertEquals(200, vm.state.value.entries.size); assertTrue(vm.state.value.hasMore)
        vm.loadMore(); assertEquals(400, vm.state.value.entries.size)
        vm.loadMore(); assertEquals(450, vm.state.value.entries.size); assertFalse(vm.state.value.hasMore)
        assertEquals(450, vm.state.value.allEntries.size)
    }

    private class FakeFileSystem(
        val statBlock: suspend (RootPath) -> OperationResult<DirectoryEntry> = { path ->
            OperationResult.Success(DirectoryEntry(path, "current", EntryType.DIRECTORY, 0, 0, true, true, false))
        },
        val createBlock: suspend (RootPath, FolderName) -> OperationResult<DirectoryEntry> = { _, _ -> error("unused") },
        val listBlock: suspend (RootPath) -> OperationResult<List<DirectoryEntry>>,
    ) : RootFileSystem {
        val listed = mutableListOf<String>()
        override suspend fun list(path: RootPath): OperationResult<List<DirectoryEntry>> { listed += path.value; return listBlock(path) }
        override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> = statBlock(path)
        override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> = error("unused")
        override suspend fun createDirectory(parent: RootPath, name: FolderName): OperationResult<DirectoryEntry> = createBlock(parent, name)
    }

    private class MarkerDispatcher(
        private val delegate: CoroutineDispatcher,
        private val marker: ThreadLocal<Boolean>,
    ) : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = delegate.dispatch(context) {
            marker.set(true)
            try { block.run() } finally { marker.remove() }
        }
    }

    private fun entry(
        name: String,
        type: EntryType,
        path: String = "/x/$name",
        writable: Boolean = false,
        symbolicLink: Boolean = false,
    ) = DirectoryEntry(RootPath.parse(path).getOrThrow(), name, type, 1, 2, true, writable, symbolicLink)
}
