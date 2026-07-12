package com.iamxpp.isaver.ui

import com.iamxpp.isaver.data.local.BrowserPreferences
import com.iamxpp.isaver.data.local.BrowserPreferencesStore
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
import kotlinx.coroutines.flow.MutableStateFlow
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
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.SortDirection
import com.iamxpp.isaver.ui.files.SortField
import com.iamxpp.isaver.ui.files.SortSpec

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(mainDispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `collects browser preferences into presentation state`() = runTest {
        val preferences = FakeBrowserPreferencesStore(
            BrowserPreferences(
                displayMode = DisplayMode.GRID,
                sortSpec = SortSpec(SortField.SIZE, SortDirection.DESCENDING),
            ),
        )
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            preferences,
        )

        advanceUntilIdle()

        assertEquals(DisplayMode.GRID, vm.state.value.displayMode)
        assertEquals(SortSpec(SortField.SIZE, SortDirection.DESCENDING), vm.state.value.sortSpec)
        assertEquals("", vm.state.value.searchQuery)
    }

    @Test fun `preference changes reorder loaded entries without listing again`() = runTest {
        var listCalls = 0
        val preferences = FakeBrowserPreferencesStore(BrowserPreferences())
        val vm = BrowserViewModel(
            FakeFileSystem {
                listCalls += 1
                OperationResult.Success(
                    listOf(
                        entry("small.txt", EntryType.FILE, size = 1),
                        entry("large.txt", EntryType.FILE, size = 20),
                    ),
                )
            },
            StandardTestDispatcher(testScheduler),
            preferences,
        )
        advanceUntilIdle()

        preferences.emit(
            BrowserPreferences(
                displayMode = DisplayMode.GRID,
                sortSpec = SortSpec(SortField.SIZE, SortDirection.DESCENDING),
            ),
        )
        advanceUntilIdle()

        assertEquals(1, listCalls)
        assertEquals(listOf("large.txt", "small.txt"), vm.state.value.entries.map { it.name })
        assertEquals(listOf("small.txt", "large.txt"), vm.state.value.allEntries.map { it.name })
    }

    @Test fun `display mode keeps paging while sort changes reset and reorder without relisting`() = runTest {
        var listCalls = 0
        val preferences = FakeBrowserPreferencesStore(BrowserPreferences())
        val all = (1..450).map { index -> entry("file$index", EntryType.FILE, size = index.toLong()) }
        val vm = BrowserViewModel(
            FakeFileSystem {
                listCalls += 1
                OperationResult.Success(all)
            },
            StandardTestDispatcher(testScheduler),
            preferences,
        )
        advanceUntilIdle()
        vm.loadMore()
        assertEquals(400, vm.state.value.entries.size)

        preferences.emit(BrowserPreferences(displayMode = DisplayMode.GRID))
        advanceUntilIdle()

        assertEquals(DisplayMode.GRID, vm.state.value.displayMode)
        assertEquals(400, vm.state.value.entries.size)
        assertEquals(1, listCalls)

        preferences.emit(
            BrowserPreferences(
                displayMode = DisplayMode.GRID,
                sortSpec = SortSpec(SortField.SIZE, SortDirection.DESCENDING),
            ),
        )
        advanceUntilIdle()

        assertEquals(200, vm.state.value.entries.size)
        assertEquals("file450", vm.state.value.entries.first().name)
        assertTrue(vm.state.value.hasMore)
        assertEquals(1, listCalls)
    }

    @Test fun `display and sort events delegate to preferences store`() = runTest {
        val preferences = FakeBrowserPreferencesStore(BrowserPreferences())
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            preferences,
        )
        advanceUntilIdle()

        vm.setDisplayMode(DisplayMode.GRID)
        vm.setSort(SortSpec(SortField.TYPE, SortDirection.DESCENDING))
        advanceUntilIdle()

        assertEquals(listOf(DisplayMode.GRID), preferences.displayModeWrites)
        assertEquals(
            listOf(SortSpec(SortField.TYPE, SortDirection.DESCENDING)),
            preferences.sortWrites,
        )
    }

    @Test fun `display preference write failure becomes a non sensitive state error`() = runTest {
        val preferences = FakeBrowserPreferencesStore(BrowserPreferences()).apply {
            displayModeFailure = IllegalStateException("secret datastore detail")
        }
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            preferences,
        )
        advanceUntilIdle()

        vm.setDisplayMode(DisplayMode.GRID)
        advanceUntilIdle()

        assertEquals("无法保存显示设置", vm.state.value.presentationError)
    }

    @Test fun `sort preference write failure becomes a non sensitive state error`() = runTest {
        val preferences = FakeBrowserPreferencesStore(BrowserPreferences()).apply {
            sortFailure = IllegalStateException("secret datastore detail")
        }
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            preferences,
        )
        advanceUntilIdle()

        vm.setSort(SortSpec(SortField.TYPE, SortDirection.DESCENDING))
        advanceUntilIdle()

        assertEquals("无法保存显示设置", vm.state.value.presentationError)
    }

    @Test fun `search is case insensitive and paginates filtered sorted results without relisting`() = runTest {
        var listCalls = 0
        val all = (1..250).map { entry("Match$it", EntryType.FILE) } +
            (1..25).map { entry("other$it", EntryType.FILE) }
        val vm = BrowserViewModel(
            FakeFileSystem {
                listCalls += 1
                OperationResult.Success(all.reversed())
            },
            StandardTestDispatcher(testScheduler),
            FakeBrowserPreferencesStore(BrowserPreferences()),
        )
        advanceUntilIdle()

        vm.setSearchQuery("mAtCh")
        advanceUntilIdle()

        assertEquals(1, listCalls)
        assertEquals(275, vm.state.value.allEntries.size)
        assertEquals(250, vm.state.value.totalCount)
        assertEquals(200, vm.state.value.entries.size)
        assertTrue(vm.state.value.hasMore)
        assertEquals("Match1", vm.state.value.entries.first().name)

        vm.loadMore()

        assertEquals(250, vm.state.value.entries.size)
        assertFalse(vm.state.value.hasMore)
    }

    @Test fun `search reset prevents stale load more from expanding the new result`() = runTest {
        val all = (1..250).map { entry("match$it", EntryType.FILE) } +
            (1..250).map { entry("other$it", EntryType.FILE) }
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(all) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
        )
        advanceUntilIdle()
        vm.loadMore()
        assertEquals(400, vm.state.value.entries.size)

        vm.setSearchQuery("match")
        vm.loadMore()
        advanceUntilIdle()

        assertEquals(200, vm.state.value.entries.size)
        assertEquals(250, vm.state.value.totalCount)
        assertTrue(vm.state.value.hasMore)
    }

    @Test fun `directory result uses the latest preference selected while loading`() = runTest {
        val result = CompletableDeferred<OperationResult<List<DirectoryEntry>>>()
        val preferences = FakeBrowserPreferencesStore(BrowserPreferences())
        val vm = BrowserViewModel(
            FakeFileSystem { result.await() },
            StandardTestDispatcher(testScheduler),
            preferences,
        )
        testScheduler.runCurrent()

        preferences.emit(
            BrowserPreferences(
                sortSpec = SortSpec(SortField.SIZE, SortDirection.DESCENDING),
            ),
        )
        result.complete(
            OperationResult.Success(
                listOf(
                    entry("small", EntryType.FILE, size = 1),
                    entry("large", EntryType.FILE, size = 10),
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(SortSpec(SortField.SIZE, SortDirection.DESCENDING), vm.state.value.sortSpec)
        assertEquals(listOf("large", "small"), vm.state.value.entries.map { it.name })
    }

    @Test fun `opening browse root clears navigation and returns home from slash`() = runTest {
        val fs = FakeFileSystem { OperationResult.Success(emptyList()) }
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
        advanceUntilIdle()
        vm.enterDirectory(entry("child", EntryType.DIRECTORY, "/storage/emulated/0/child"))
        advanceUntilIdle()

        vm.openRoot(RootPath.parse("/").getOrThrow(), "浏览")
        advanceUntilIdle()

        assertEquals("/", vm.state.value.currentPath.value)
        assertEquals("浏览", vm.state.value.rootTitle)
        assertFalse(vm.state.value.canGoBack)
        assertEquals(BrowserBackResult.RETURN_HOME, vm.back())
    }

    @Test fun `title follows browse root custom root child and back navigation`() = runTest {
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
        )
        advanceUntilIdle()

        vm.openRoot(RootPath.parse("/").getOrThrow(), "浏览")
        advanceUntilIdle()
        assertEquals("/", vm.state.value.title)

        vm.openRoot(RootPath.parse("/data/local/tmp").getOrThrow(), "测试备注")
        advanceUntilIdle()
        assertEquals("测试备注", vm.state.value.title)

        vm.enterDirectory(entry("child", EntryType.DIRECTORY, "/data/local/tmp/child"))
        advanceUntilIdle()
        assertEquals("child", vm.state.value.title)

        assertEquals(BrowserBackResult.NAVIGATED, vm.back())
        advanceUntilIdle()
        assertEquals("测试备注", vm.state.value.title)
    }

    @Test fun `late old navigation result cannot overwrite latest title`() = runTest {
        val old = CompletableDeferred<OperationResult<List<DirectoryEntry>>>()
        val fresh = CompletableDeferred<OperationResult<List<DirectoryEntry>>>()
        val fs = FakeFileSystem { path ->
            when {
                path.value.endsWith("old") -> withContext(NonCancellable) { old.await() }
                path.value.endsWith("new") -> fresh.await()
                else -> OperationResult.Success(emptyList())
            }
        }
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
        advanceUntilIdle()

        vm.enterDirectory(entry("old", EntryType.DIRECTORY, "/storage/emulated/0/old"))
        testScheduler.runCurrent()
        vm.enterDirectory(entry("new", EntryType.DIRECTORY, "/storage/emulated/0/new"))
        testScheduler.runCurrent()
        fresh.complete(OperationResult.Success(emptyList()))
        advanceUntilIdle()
        old.complete(OperationResult.Success(emptyList()))
        advanceUntilIdle()

        assertEquals("new", vm.state.value.title)
    }

    @Test fun `init asynchronously loads storage root and exposes loading then success`() = runTest {
        val gate = CompletableDeferred<OperationResult<List<DirectoryEntry>>>()
        val fs = FakeFileSystem { gate.await() }
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
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
        val vm = BrowserViewModel(FakeFileSystem { results.removeFirst() }, StandardTestDispatcher(testScheduler), defaultPreferences())
        advanceUntilIdle()
        assertTrue(vm.state.value.empty)
        vm.retry(); advanceUntilIdle()
        assertEquals("目录不可读", vm.state.value.errorMessage)
        assertEquals("/storage/emulated/0", vm.state.value.currentPath.value)
    }

    @Test fun `sorts directories first with case insensitive natural numeric order`() = runTest {
        val input = listOf(entry("file10", EntryType.FILE), entry("Dir10", EntryType.DIRECTORY), entry("file2", EntryType.FILE), entry("dir2", EntryType.DIRECTORY), entry("Alpha", EntryType.FILE), entry("alpha", EntryType.FILE))
        val vm = BrowserViewModel(FakeFileSystem { OperationResult.Success(input) }, StandardTestDispatcher(testScheduler), defaultPreferences())
        advanceUntilIdle()
        assertEquals(listOf("dir2", "Dir10", "Alpha", "alpha", "file2", "file10"), vm.state.value.entries.map { it.name })
    }

    @Test fun `enter accepts only directories and back uses navigation stack`() = runTest {
        val vm = BrowserViewModel(FakeFileSystem { OperationResult.Success(emptyList()) }, StandardTestDispatcher(testScheduler), defaultPreferences())
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
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
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
        val vm = BrowserViewModel(FakeFileSystem { OperationResult.Success(emptyList()) }, StandardTestDispatcher(testScheduler), defaultPreferences())
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
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
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
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
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
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
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
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
        advanceUntilIdle()

        assertFalse(vm.state.value.canCreateDirectory)
    }

    @Test fun `invalid folder name is rejected before the root filesystem call`() = runTest {
        var createCalls = 0
        val fs = FakeFileSystem(
            createBlock = { _, _ -> createCalls += 1; error("must not create") },
            listBlock = { OperationResult.Success(emptyList()) },
        )
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
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
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
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
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences()); advanceUntilIdle()
        vm.enterDirectory(entry("old", EntryType.DIRECTORY, "/storage/emulated/0/old")); testScheduler.runCurrent()
        vm.enterDirectory(entry("new", EntryType.DIRECTORY, "/storage/emulated/0/new")); testScheduler.runCurrent()
        fresh.complete(OperationResult.Success(listOf(entry("fresh", EntryType.FILE)))); advanceUntilIdle()
        old.complete(OperationResult.Success(listOf(entry("stale", EntryType.FILE)))); advanceUntilIdle()
        assertEquals("/storage/emulated/0/new", vm.state.value.currentPath.value)
        assertEquals(listOf("fresh"), vm.state.value.entries.map { it.name })
    }

    @Test fun `cancellation does not become an error`() = runTest {
        val vm = BrowserViewModel(FakeFileSystem { throw CancellationException("cancel") }, StandardTestDispatcher(testScheduler), defaultPreferences())
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
            defaultPreferences(),
            sorter = { entries, spec ->
                sortedWithMarker = marker.get() == true
                com.iamxpp.isaver.ui.files.FileEntrySorter.sort(entries, spec)
            },
        )
        advanceUntilIdle()
        assertTrue(sortedWithMarker)
        assertEquals(listOf("a", "b"), vm.state.value.entries.map { it.name })
    }

    @Test fun `large results remain complete and reveal 200 entries per page`() = runTest {
        val all = (1..450).map { entry("file$it", EntryType.FILE) }
        val vm = BrowserViewModel(FakeFileSystem { OperationResult.Success(all) }, StandardTestDispatcher(testScheduler), defaultPreferences()); advanceUntilIdle()
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

    private class FakeBrowserPreferencesStore(initial: BrowserPreferences) : BrowserPreferencesStore {
        override val preferences = MutableStateFlow(initial)
        val displayModeWrites = mutableListOf<DisplayMode>()
        val sortWrites = mutableListOf<SortSpec>()
        var displayModeFailure: Throwable? = null
        var sortFailure: Throwable? = null
        override suspend fun setDisplayMode(displayMode: DisplayMode) {
            displayModeFailure?.let { throw it }
            displayModeWrites += displayMode
        }
        override suspend fun setSort(sortSpec: SortSpec) {
            sortFailure?.let { throw it }
            sortWrites += sortSpec
        }
        fun emit(value: BrowserPreferences) { preferences.value = value }
    }

    private fun defaultPreferences() = FakeBrowserPreferencesStore(BrowserPreferences())

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
        size: Long? = 1,
        writable: Boolean = false,
        symbolicLink: Boolean = false,
    ) = DirectoryEntry(RootPath.parse(path).getOrThrow(), name, type, size, 2, true, writable, symbolicLink)
}
