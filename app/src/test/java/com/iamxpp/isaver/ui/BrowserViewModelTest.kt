package com.iamxpp.isaver.ui

import com.iamxpp.isaver.archive.ArchiveFormat
import com.iamxpp.isaver.archive.ArchiveRepository
import com.iamxpp.isaver.archive.LocalArchiveEngine
import com.iamxpp.isaver.data.local.BrowserPreferences
import com.iamxpp.isaver.data.local.BrowserPreferencesStore
import com.iamxpp.isaver.data.local.BrowserSession
import com.iamxpp.isaver.data.local.BrowserSessionStore
import com.iamxpp.isaver.bookmarks.BookmarkRepository
import com.iamxpp.isaver.data.local.BookmarkDao
import com.iamxpp.isaver.data.local.BookmarkEntity
import com.iamxpp.isaver.data.root.DirectorySnapshot
import com.iamxpp.isaver.data.root.RootFileMetadata
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootEntryIdentity
import com.iamxpp.isaver.export.ExternalFileGrant
import com.iamxpp.isaver.fileops.BatchRenameMode
import com.iamxpp.isaver.fileops.BatchRenameRule
import com.iamxpp.isaver.fileops.ChecksumAlgorithm
import com.iamxpp.isaver.tasks.OperationTask
import com.iamxpp.isaver.tasks.OperationTaskState
import com.iamxpp.isaver.tasks.OperationTaskStore
import com.iamxpp.isaver.tasks.OperationTaskType
import com.iamxpp.isaver.search.LocalSearchCriteria
import com.iamxpp.isaver.search.LocalSearchRepository
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        vm.openInitial()
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
        vm.openInitial()
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
        vm.openInitial()
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
        vm.openInitial()
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
        vm.openInitial()
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
        vm.openInitial()
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
        vm.openInitial()
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

    @Test fun `init does not prefetch storage before an explicit open`() = runTest {
        val fs = FakeFileSystem { OperationResult.Success(emptyList()) }
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
        advanceUntilIdle()

        assertFalse(vm.state.value.loading)
        assertTrue(fs.readDirectories.isEmpty())
        assertTrue(fs.listed.isEmpty())
    }

    @Test fun `explicit open consumes entries and parent capabilities from one snapshot`() = runTest {
        val openedPath = RootPath.parse("/data/local/tmp").getOrThrow()
        val child = entry("child", EntryType.FILE, "/data/local/tmp/child")
        val fs = FakeFileSystem(
            snapshotBlock = {
                OperationResult.Success(snapshot(entries = listOf(child), writable = true))
            },
            statBlock = { error("snapshot parent metadata must replace stat") },
            listBlock = { error("snapshot primitive must replace list") },
        )
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())

        vm.openRoot(openedPath, "测试位置")
        advanceUntilIdle()

        assertEquals(listOf(openedPath.value), fs.readDirectories)
        assertTrue(fs.listed.isEmpty())
        assertEquals(listOf("child"), vm.state.value.entries.map { it.name })
        assertTrue(vm.state.value.canCreateDirectory)
    }

    @Test fun `cache hit shows old snapshot immediately while refreshing in background`() = runTest {
        val openedPath = RootPath.parse("/data/local/tmp").getOrThrow()
        val cachedEntry = entry("cached", EntryType.FILE, "/data/local/tmp/cached")
        val freshEntry = entry("fresh", EntryType.FILE, "/data/local/tmp/fresh")
        val freshSnapshot = snapshot(entries = listOf(freshEntry))
        val cache = DirectorySnapshotCache(monotonicNowMillis = { 0L }).apply {
            putForTest(openedPath, snapshot(entries = listOf(cachedEntry), writable = true))
        }
        val refresh = CompletableDeferred<OperationResult<DirectorySnapshot>>()
        val fs = FakeFileSystem(
            snapshotBlock = { refresh.await() },
            listBlock = { error("unused") },
        )
        val vm = BrowserViewModel(
            fs,
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            snapshotCache = cache,
        )

        vm.openRoot(openedPath, "测试位置")

        assertEquals(listOf("cached"), vm.state.value.allEntries.map { it.name })
        assertEquals(listOf("cached"), vm.state.value.entries.map { it.name })
        assertTrue(vm.state.value.refreshing)
        assertFalse(vm.state.value.loading)
        testScheduler.runCurrent()
        assertEquals(listOf("cached"), vm.state.value.entries.map { it.name })
        assertEquals(listOf(openedPath.value), fs.readDirectories)

        refresh.complete(OperationResult.Success(freshSnapshot))
        advanceUntilIdle()

        assertEquals(listOf("fresh"), vm.state.value.entries.map { it.name })
        assertFalse(vm.state.value.refreshing)
        assertSame(freshSnapshot, cache.get(openedPath)?.snapshot)
    }

    @Test fun `cache hit keeps raw snapshot entries while showing its prepared presentation`() = runTest {
        val openedPath = RootPath.parse("/data/local/tmp").getOrThrow()
        val alpha = entry("alpha", EntryType.FILE, "/data/local/tmp/alpha")
        val beta = entry("beta", EntryType.FILE, "/data/local/tmp/beta")
        val cachedSnapshot = snapshot(entries = listOf(beta, alpha))
        val cache = DirectorySnapshotCache(monotonicNowMillis = { 0L }).apply {
            putForTest(
                openedPath,
                cachedSnapshot,
                presentedEntries = listOf(alpha),
                presentationKey = DirectoryPresentationKey(
                    SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING),
                    "alpha",
                ),
            )
        }
        val refresh = CompletableDeferred<OperationResult<DirectorySnapshot>>()
        val vm = BrowserViewModel(
            FakeFileSystem(
                snapshotBlock = { refresh.await() },
                listBlock = { error("unused") },
            ),
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            snapshotCache = cache,
        )
        vm.setSearchQuery("alpha")
        advanceUntilIdle()

        vm.openRoot(openedPath, "测试位置")

        assertEquals(listOf("beta", "alpha"), vm.state.value.allEntries.map { it.name })
        assertEquals(listOf("alpha"), vm.state.value.entries.map { it.name })
        refresh.complete(OperationResult.Failure(ErrorCode.NOT_READABLE, "目录不可读", "test"))
        advanceUntilIdle()
        assertEquals(listOf("alpha"), vm.state.value.entries.map { it.name })
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun `query mismatch never synchronously shows rows from the cached presentation`() = runTest {
        val openedPath = RootPath.parse("/data/local/tmp").getOrThrow()
        val alpha = entry("alpha", EntryType.FILE, "/data/local/tmp/alpha")
        val beta = entry("beta", EntryType.FILE, "/data/local/tmp/beta")
        val cachedSnapshot = snapshot(entries = listOf(alpha, beta))
        val cache = DirectorySnapshotCache(monotonicNowMillis = { 0L }).apply {
            putForTest(openedPath, cachedSnapshot, presentedEntries = listOf(alpha, beta))
        }
        val refresh = CompletableDeferred<OperationResult<DirectorySnapshot>>()
        val vm = BrowserViewModel(
            FakeFileSystem(
                snapshotBlock = { refresh.await() },
                listBlock = { error("unused") },
            ),
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            snapshotCache = cache,
        )
        vm.setSearchQuery("alpha")

        vm.openRoot(openedPath, "测试位置")

        assertEquals("alpha", vm.state.value.searchQuery)
        assertTrue(vm.state.value.entries.isEmpty())
        assertTrue(vm.state.value.refreshing)
        assertFalse(vm.state.value.empty)
        refresh.complete(OperationResult.Failure(ErrorCode.NOT_READABLE, "目录不可读", "test"))
        advanceUntilIdle()
        assertEquals(listOf("alpha"), vm.state.value.entries.map { it.name })
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun `sort mismatch never synchronously shows rows from the cached presentation`() = runTest {
        val openedPath = RootPath.parse("/data/local/tmp").getOrThrow()
        val alpha = entry("alpha", EntryType.FILE, "/data/local/tmp/alpha")
        val beta = entry("beta", EntryType.FILE, "/data/local/tmp/beta")
        val cachedSnapshot = snapshot(entries = listOf(alpha, beta))
        val cache = DirectorySnapshotCache(monotonicNowMillis = { 0L }).apply {
            putForTest(openedPath, cachedSnapshot, presentedEntries = listOf(alpha, beta))
        }
        val refresh = CompletableDeferred<OperationResult<DirectorySnapshot>>()
        val descending = SortSpec(SortField.DISPLAY_NAME, SortDirection.DESCENDING)
        val vm = BrowserViewModel(
            FakeFileSystem(
                snapshotBlock = { refresh.await() },
                listBlock = { error("unused") },
            ),
            StandardTestDispatcher(testScheduler),
            FakeBrowserPreferencesStore(BrowserPreferences(sortSpec = descending)),
            snapshotCache = cache,
        )
        advanceUntilIdle()

        vm.openRoot(openedPath, "测试位置")

        assertEquals(descending, vm.state.value.sortSpec)
        assertTrue(vm.state.value.entries.isEmpty())
        assertTrue(vm.state.value.refreshing)
        assertFalse(vm.state.value.empty)
        refresh.complete(OperationResult.Failure(ErrorCode.NOT_READABLE, "目录不可读", "test"))
        advanceUntilIdle()
        assertEquals(listOf("beta", "alpha"), vm.state.value.entries.map { it.name })
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun `live failure waits for mismatched cached presentation before ending refresh`() = runTest {
        val openedPath = RootPath.parse("/data/local/tmp").getOrThrow()
        val alpha = entry("alpha", EntryType.FILE, "/data/local/tmp/alpha")
        val beta = entry("beta", EntryType.FILE, "/data/local/tmp/beta")
        val cachedSnapshot = snapshot(entries = listOf(alpha, beta))
        val cache = DirectorySnapshotCache(monotonicNowMillis = { 0L }).apply {
            putForTest(openedPath, cachedSnapshot, presentedEntries = listOf(alpha, beta))
        }
        val gatedDispatcher = GateNextDispatcher(StandardTestDispatcher(testScheduler))
        val vm = BrowserViewModel(
            FakeFileSystem(
                snapshotBlock = {
                    OperationResult.Failure(ErrorCode.NOT_READABLE, "目录不可读", "test")
                },
                listBlock = { error("unused") },
            ),
            gatedDispatcher,
            defaultPreferences(),
            snapshotCache = cache,
        )
        vm.setSearchQuery("alpha")
        advanceUntilIdle()
        gatedDispatcher.gateNext()

        vm.openRoot(openedPath, "测试位置")
        testScheduler.runCurrent()

        assertTrue(gatedDispatcher.hasGatedTask)
        assertTrue(vm.state.value.entries.isEmpty())
        assertTrue(vm.state.value.refreshing)
        assertFalse(vm.state.value.empty)

        gatedDispatcher.release()
        advanceUntilIdle()

        assertEquals(listOf("alpha"), vm.state.value.entries.map { it.name })
        assertFalse(vm.state.value.refreshing)
        assertFalse(vm.state.value.empty)
    }

    @Test fun `fresh refresh atomically replaces cached rows without an empty state`() = runTest {
        val openedPath = RootPath.parse("/data/local/tmp").getOrThrow()
        val cachedEntry = entry("cached", EntryType.FILE, "/data/local/tmp/cached")
        val freshEntry = entry("fresh", EntryType.FILE, "/data/local/tmp/fresh")
        val cache = DirectorySnapshotCache(monotonicNowMillis = { 0L }).apply {
            putForTest(openedPath, snapshot(entries = listOf(cachedEntry)))
        }
        val refresh = CompletableDeferred<OperationResult<DirectorySnapshot>>()
        val fs = FakeFileSystem(
            snapshotBlock = { refresh.await() },
            listBlock = { error("unused") },
        )
        val vm = BrowserViewModel(
            fs,
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            snapshotCache = cache,
        )
        val observedStates = mutableListOf<BrowserUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.state.collect(observedStates::add)
        }

        vm.openRoot(openedPath, "测试位置")
        testScheduler.runCurrent()
        val refreshStartIndex = observedStates.size

        refresh.complete(OperationResult.Success(snapshot(entries = listOf(freshEntry))))
        advanceUntilIdle()

        val refreshStates = observedStates.drop(refreshStartIndex)
        val firstFreshIndex = refreshStates.indexOfFirst { state ->
            state.entries.map { it.name } == listOf("fresh")
        }
        assertTrue(firstFreshIndex >= 0)
        assertTrue(
            refreshStates.take(firstFreshIndex).all { state ->
                state.entries.map { it.name } == listOf("cached")
            },
        )
        assertTrue(refreshStates.all { state -> !state.empty && state.errorMessage == null })
    }

    @Test fun `late cached refresh cannot overwrite a newer navigation generation`() = runTest {
        val oldPath = RootPath.parse("/data/local/tmp/old").getOrThrow()
        val newPath = RootPath.parse("/data/local/tmp/new").getOrThrow()
        val cache = DirectorySnapshotCache(monotonicNowMillis = { 0L }).apply {
            putForTest(
                oldPath,
                snapshot(listOf(entry("cached", EntryType.FILE, "/data/local/tmp/old/cached"))),
            )
        }
        val oldRefresh = CompletableDeferred<OperationResult<DirectorySnapshot>>()
        val newLoad = CompletableDeferred<OperationResult<DirectorySnapshot>>()
        val fs = FakeFileSystem(
            snapshotBlock = { path ->
                when (path) {
                    oldPath -> withContext(NonCancellable) { oldRefresh.await() }
                    newPath -> newLoad.await()
                    else -> error("unexpected path")
                }
            },
            listBlock = { error("unused") },
        )
        val vm = BrowserViewModel(
            fs,
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            snapshotCache = cache,
        )

        vm.openRoot(oldPath, "旧目录")
        testScheduler.runCurrent()
        assertEquals(listOf("cached"), vm.state.value.entries.map { it.name })

        vm.openRoot(newPath, "新目录")
        testScheduler.runCurrent()
        newLoad.complete(
            OperationResult.Success(
                snapshot(listOf(entry("fresh", EntryType.FILE, "/data/local/tmp/new/fresh"))),
            ),
        )
        advanceUntilIdle()
        oldRefresh.complete(
            OperationResult.Success(
                snapshot(listOf(entry("stale", EntryType.FILE, "/data/local/tmp/old/stale"))),
            ),
        )
        advanceUntilIdle()

        assertEquals(newPath, vm.state.value.currentPath)
        assertEquals(listOf("fresh"), vm.state.value.entries.map { it.name })
        assertEquals("新目录", vm.state.value.title)
    }

    @Test fun `refresh failure keeps cached rows without replacing them with an error`() = runTest {
        val openedPath = RootPath.parse("/data/local/tmp").getOrThrow()
        val cachedEntry = entry("cached", EntryType.FILE, "/data/local/tmp/cached")
        val cache = DirectorySnapshotCache(monotonicNowMillis = { 0L }).apply {
            putForTest(openedPath, snapshot(entries = listOf(cachedEntry), writable = true))
        }
        val fs = FakeFileSystem(
            snapshotBlock = {
                OperationResult.Failure(ErrorCode.NOT_READABLE, "目录不可读", "hidden")
            },
            listBlock = { error("unused") },
        )
        val vm = BrowserViewModel(
            fs,
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            snapshotCache = cache,
        )

        vm.openRoot(openedPath, "测试位置")
        advanceUntilIdle()

        assertEquals(listOf("cached"), vm.state.value.entries.map { it.name })
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.refreshing)
        assertTrue(vm.state.value.canCreateDirectory)
    }

    @Test fun `refresh exception also keeps cached rows without exposing details`() = runTest {
        val openedPath = RootPath.parse("/data/local/tmp").getOrThrow()
        val cachedEntry = entry("cached", EntryType.FILE, "/data/local/tmp/cached")
        val cache = DirectorySnapshotCache(monotonicNowMillis = { 0L }).apply {
            putForTest(openedPath, snapshot(entries = listOf(cachedEntry), writable = false))
        }
        val fs = FakeFileSystem(
            snapshotBlock = { error("sensitive refresh detail") },
            listBlock = { error("unused") },
        )
        val vm = BrowserViewModel(
            fs,
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            snapshotCache = cache,
        )

        vm.openRoot(openedPath, "测试位置")
        advanceUntilIdle()

        assertEquals(listOf("cached"), vm.state.value.entries.map { it.name })
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun `cached writable capability remains a hint and filesystem still decides writes`() = runTest {
        val openedPath = RootPath.parse("/data/local/tmp").getOrThrow()
        val cachedEntry = entry("cached", EntryType.FILE, "/data/local/tmp/cached")
        val cache = DirectorySnapshotCache(monotonicNowMillis = { 0L }).apply {
            putForTest(openedPath, snapshot(entries = listOf(cachedEntry), writable = true))
        }
        var createCalls = 0
        val fs = FakeFileSystem(
            snapshotBlock = {
                OperationResult.Failure(ErrorCode.NOT_READABLE, "目录不可读", "refresh failed")
            },
            createBlock = { _, _ ->
                createCalls += 1
                OperationResult.Failure(ErrorCode.NOT_WRITABLE, "目录不可写", "recheck denied")
            },
            listBlock = { error("unused") },
        )
        val vm = BrowserViewModel(
            fs,
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            snapshotCache = cache,
        )
        vm.openRoot(openedPath, "测试位置")
        advanceUntilIdle()
        assertTrue(vm.state.value.canCreateDirectory)

        vm.createDirectory("folder")
        advanceUntilIdle()

        assertEquals(1, createCalls)
        assertEquals(ErrorCode.NOT_WRITABLE, vm.state.value.createDirectoryError?.code)
        assertEquals(listOf("cached"), vm.state.value.entries.map { it.name })
    }

    @Test fun `uncached load shows blocking spinner only after one hundred twenty milliseconds`() = runTest {
        val openedPath = RootPath.parse("/data/local/tmp").getOrThrow()
        val result = CompletableDeferred<OperationResult<DirectorySnapshot>>()
        val fs = FakeFileSystem(
            snapshotBlock = { result.await() },
            listBlock = { error("unused") },
        )
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())

        vm.openRoot(openedPath, "测试位置")
        testScheduler.runCurrent()
        assertFalse(vm.state.value.loading)

        advanceTimeBy(119L)
        testScheduler.runCurrent()
        assertFalse(vm.state.value.loading)

        advanceTimeBy(1L)
        testScheduler.runCurrent()
        assertTrue(vm.state.value.loading)

        result.complete(OperationResult.Success(snapshot(emptyList())))
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
    }

    @Test fun `uncached loading grace period does not flash the empty state`() = runTest {
        val openedPath = RootPath.parse("/data/local/tmp").getOrThrow()
        val result = CompletableDeferred<OperationResult<DirectorySnapshot>>()
        val fs = FakeFileSystem(
            snapshotBlock = { result.await() },
            listBlock = { error("unused") },
        )
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())

        vm.openRoot(openedPath, "测试位置")
        testScheduler.runCurrent()

        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.refreshing)
        assertFalse(vm.state.value.empty)

        result.complete(OperationResult.Success(snapshot(emptyList())))
        advanceUntilIdle()
        assertTrue(vm.state.value.empty)
    }

    @Test fun `uncached load completing before one hundred twenty milliseconds never shows spinner`() = runTest {
        val openedPath = RootPath.parse("/data/local/tmp").getOrThrow()
        val loadedEntry = entry("fast", EntryType.FILE, "/data/local/tmp/fast")
        val fs = FakeFileSystem(
            snapshotBlock = {
                delay(119L)
                OperationResult.Success(snapshot(listOf(loadedEntry)))
            },
            listBlock = { error("unused") },
        )
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())

        vm.openRoot(openedPath, "测试位置")
        testScheduler.runCurrent()
        assertFalse(vm.state.value.loading)

        advanceTimeBy(119L)
        testScheduler.runCurrent()
        assertFalse(vm.state.value.loading)
        assertEquals(listOf("fast"), vm.state.value.entries.map { it.name })

        advanceTimeBy(1L)
        testScheduler.runCurrent()
        assertFalse(vm.state.value.loading)
    }

    @Test fun `empty and failure states retain current path and user message`() = runTest {
        var nowMillis = 0L
        val results = ArrayDeque<OperationResult<List<DirectoryEntry>>>().apply {
            add(OperationResult.Success(emptyList()))
            add(OperationResult.Failure(ErrorCode.NOT_READABLE, "目录不可读", "hidden"))
        }
        val vm = BrowserViewModel(
            FakeFileSystem { results.removeFirst() },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            snapshotCache = DirectorySnapshotCache(monotonicNowMillis = { nowMillis }),
        )
        vm.openInitial()
        advanceUntilIdle()
        assertTrue(vm.state.value.empty)
        nowMillis = 2_000L
        vm.retry(); advanceUntilIdle()
        assertEquals("目录不可读", vm.state.value.errorMessage)
        assertEquals("/storage/emulated/0", vm.state.value.currentPath.value)
    }

    @Test fun `sorts directories first with case insensitive natural numeric order`() = runTest {
        val input = listOf(entry("file10", EntryType.FILE), entry("Dir10", EntryType.DIRECTORY), entry("file2", EntryType.FILE), entry("dir2", EntryType.DIRECTORY), entry("Alpha", EntryType.FILE), entry("alpha", EntryType.FILE))
        val vm = BrowserViewModel(FakeFileSystem { OperationResult.Success(input) }, StandardTestDispatcher(testScheduler), defaultPreferences())
        vm.openInitial()
        advanceUntilIdle()
        assertEquals(listOf("dir2", "Dir10", "Alpha", "alpha", "file2", "file10"), vm.state.value.entries.map { it.name })
    }

    @Test fun `enter accepts only directories and back uses navigation stack`() = runTest {
        val vm = BrowserViewModel(FakeFileSystem { OperationResult.Success(emptyList()) }, StandardTestDispatcher(testScheduler), defaultPreferences())
        vm.openInitial()
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
        vm.openInitial()
        advanceUntilIdle()
        vm.enterDirectory(entry("child", EntryType.DIRECTORY, "/storage/emulated/0/child"))
        advanceUntilIdle()

        vm.openRoot(RootPath.parse("/data/local/tmp").getOrThrow(), "测试位置")
        advanceUntilIdle()

        assertEquals("/data/local/tmp", vm.state.value.currentPath.value)
        assertEquals("测试位置", vm.state.value.rootTitle)
        assertFalse(vm.state.value.canGoBack)
        assertEquals(BrowserBackResult.RETURN_HOME, vm.back())
        assertEquals("/data/local/tmp", fs.readDirectories.last())
    }

    @Test fun `back and forward preserve history while new navigation clears forward`() = runTest {
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
        )
        vm.openInitial()
        advanceUntilIdle()
        val first = entry("first", EntryType.DIRECTORY, "/storage/emulated/0/first")
        val second = entry("second", EntryType.DIRECTORY, "/storage/emulated/0/first/second")
        vm.enterDirectory(first)
        advanceUntilIdle()
        vm.enterDirectory(second)
        advanceUntilIdle()

        assertEquals(BrowserBackResult.NAVIGATED, vm.back())
        advanceUntilIdle()
        assertEquals(first.path, vm.state.value.currentPath)
        assertTrue(vm.state.value.canGoForward)

        assertTrue(vm.forward())
        advanceUntilIdle()
        assertEquals(second.path, vm.state.value.currentPath)
        assertFalse(vm.state.value.canGoForward)

        vm.back()
        advanceUntilIdle()
        vm.enterDirectory(entry("other", EntryType.DIRECTORY, "/storage/emulated/0/first/other"))
        advanceUntilIdle()
        assertFalse(vm.state.value.canGoForward)
        assertFalse(vm.forward())
    }

    @Test fun `restores persisted path and ordered navigation history`() = runTest {
        val root = RootPath.parse("/storage/emulated/0").getOrThrow()
        val documents = RootPath.parse("/storage/emulated/0/Documents").getOrThrow()
        val work = RootPath.parse("/storage/emulated/0/Documents/work").getOrThrow()
        val download = RootPath.parse("/storage/emulated/0/Download").getOrThrow()
        val store = FakeBrowserSessionStore(
            BrowserSession(root, "内部存储", work, listOf(root, documents), listOf(download)),
        )
        val vm = BrowserViewModel(
            FakeFileSystem(
                statBlock = { path -> OperationResult.Success(entry(path.value.substringAfterLast('/'), EntryType.DIRECTORY, path = path.value)) },
                listBlock = { OperationResult.Success(emptyList()) },
            ),
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            browserSessionStore = store,
        )

        vm.restoreSessionOrOpenRoot(RootPath.parse("/").getOrThrow(), "浏览")
        advanceUntilIdle()

        assertEquals(work, vm.state.value.currentPath)
        assertEquals("内部存储", vm.state.value.rootTitle)
        assertTrue(vm.state.value.canGoBack)
        assertTrue(vm.state.value.canGoForward)
        vm.back()
        advanceUntilIdle()
        assertEquals(documents, vm.state.value.currentPath)
    }

    @Test fun `invalid persisted directory is cleared and explicit root opens`() = runTest {
        val persisted = RootPath.parse("/missing").getOrThrow()
        val fallback = RootPath.parse("/").getOrThrow()
        val store = FakeBrowserSessionStore(
            BrowserSession(persisted, "失效", persisted, emptyList(), emptyList()),
        )
        val vm = BrowserViewModel(
            FakeFileSystem(
                statBlock = { OperationResult.Failure(ErrorCode.NOT_FOUND, "不存在") },
                listBlock = { OperationResult.Success(emptyList()) },
            ),
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            browserSessionStore = store,
        )

        vm.restoreSessionOrOpenRoot(fallback, "浏览")
        advanceUntilIdle()

        assertEquals(fallback, vm.state.value.currentPath)
        assertTrue(store.cleared)
    }

    @Test fun `toggles current path bookmark and reopens it as a new root`() = runTest {
        val repository = BookmarkRepository(FakeBookmarkDao()) { 123L }
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            bookmarkRepository = repository,
        )
        val path = RootPath.parse("/data/local/tmp/bookmarked").getOrThrow()
        vm.openRoot(path, "收藏目录")
        advanceUntilIdle()

        vm.toggleCurrentBookmark()
        advanceUntilIdle()
        assertTrue(vm.state.value.currentPathBookmarked)
        assertEquals(path, vm.state.value.bookmarks.single().path)

        vm.openInitial()
        advanceUntilIdle()
        vm.openBookmark(vm.state.value.bookmarks.single())
        advanceUntilIdle()
        assertEquals(path, vm.state.value.currentPath)
        assertEquals("收藏目录", vm.state.value.rootTitle)
        assertFalse(vm.state.value.canGoBack)

        vm.toggleCurrentBookmark()
        advanceUntilIdle()
        assertFalse(vm.state.value.currentPathBookmarked)
        assertTrue(vm.state.value.bookmarks.isEmpty())
    }

    @Test fun `file bookmark stores identity and opens the current file`() = runTest {
        val repository = BookmarkRepository(FakeBookmarkDao()) { 123L }
        val file = entry("report.bin", EntryType.FILE, path = "/data/local/tmp/report.bin")
        val opened = mutableListOf<DirectoryEntry>()
        val vm = BrowserViewModel(
            FakeFileSystem(
                statBlock = { OperationResult.Success(file) },
                identityBlock = { OperationResult.Success(RootEntryIdentity(8L, 99L)) },
                listBlock = { OperationResult.Success(emptyList()) },
            ),
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            bookmarkRepository = repository,
            exportFile = { opened += it; OperationResult.Failure(ErrorCode.COMMAND_FAILED, "opened") },
        )

        vm.toggleEntryBookmark(file)
        advanceUntilIdle()
        val bookmark = vm.state.value.bookmarks.single()
        assertEquals(EntryType.FILE, bookmark.type)
        assertEquals(RootEntryIdentity(8L, 99L), bookmark.identity)

        vm.openBookmark(bookmark)
        advanceUntilIdle()
        assertEquals(listOf(file), opened)
        assertTrue(vm.state.value.bookmarks.single().available)
    }

    @Test fun `changed bookmark identity is marked unavailable`() = runTest {
        val repository = BookmarkRepository(FakeBookmarkDao()) { 123L }
        val file = entry("report.txt", EntryType.FILE, path = "/data/local/tmp/report.txt")
        val vm = BrowserViewModel(
            FakeFileSystem(
                statBlock = { OperationResult.Success(file) },
                identityBlock = { OperationResult.Success(RootEntryIdentity(8L, 100L)) },
                listBlock = { OperationResult.Success(emptyList()) },
            ),
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            bookmarkRepository = repository,
        )
        repository.add(file.path, file.name, EntryType.FILE, RootEntryIdentity(8L, 99L))
        advanceUntilIdle()

        vm.openBookmark(vm.state.value.bookmarks.single())
        advanceUntilIdle()

        assertFalse(vm.state.value.bookmarks.single().available)
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
        vm.openInitial()
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
        vm.openInitial()
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
            snapshotBlock = { OperationResult.Success(snapshot(emptyList(), writable = false)) },
            createBlock = { _, _ -> createCalls += 1; error("must not create") },
            listBlock = { OperationResult.Success(emptyList()) },
        )
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
        vm.openInitial()
        advanceUntilIdle()

        assertFalse(vm.state.value.canCreateDirectory)
        vm.createDirectory("blocked")
        advanceUntilIdle()

        assertEquals(0, createCalls)
        assertEquals(ErrorCode.NOT_WRITABLE, vm.state.value.createDirectoryError?.code)
    }

    @Test fun `ordinary file emits external open grant while archive stays internal`() = runTest {
        val ordinary = entry("report.pdf", EntryType.FILE)
        val archive = entry("backup.tar.gz", EntryType.FILE)
        val grant = ExternalFileGrant(
            contentUri = "content://com.iamxpp.isaver.external-file/file/${"ab".repeat(32)}",
            token = "ab".repeat(32),
            displayName = ordinary.name,
            mimeType = "application/pdf",
        )
        val exported = mutableListOf<DirectoryEntry>()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            exportFile = { entry ->
                exported += entry
                OperationResult.Success(grant)
            },
        )

        vm.openEntry(ordinary)
        advanceUntilIdle()

        assertEquals(listOf(ordinary), exported)
        assertEquals(grant, vm.state.value.externalFileToOpen)
        assertNull(vm.state.value.fileInfo)
        vm.openEntry(archive)

        assertEquals(archive, vm.state.value.archiveToOpen)
    }

    @Test fun `explicit open with exports archives and requests chooser`() = runTest {
        val archive = entry("backup.zip", EntryType.FILE)
        val grant = ExternalFileGrant(
            contentUri = "content://com.iamxpp.isaver.external-file/file/${"ef".repeat(32)}",
            token = "ef".repeat(32),
            displayName = archive.name,
            mimeType = "application/zip",
        )
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            exportFile = { OperationResult.Success(grant) },
        )

        vm.openWith(archive)
        advanceUntilIdle()

        assertEquals(grant, vm.state.value.externalFileToOpen)
        assertTrue(vm.state.value.externalOpenChooser)
        assertNull(vm.state.value.archiveToOpen)
    }

    @Test fun `create file uses exact typed name then refreshes and exposes location target`() = runTest {
        val created = entry("中文 report.txt", EntryType.FILE, "/storage/emulated/0/中文 report.txt")
        var listCount = 0
        val fs = FakeFileSystem(
            listBlock = {
                listCount += 1
                OperationResult.Success(if (listCount == 1) emptyList() else listOf(created))
            },
            createFileBlock = { parent, name ->
                assertEquals("/storage/emulated/0", parent.value)
                assertEquals("中文 report.txt", name.value)
                OperationResult.Success(created)
            },
        )
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
        vm.openInitial()
        advanceUntilIdle()

        vm.createFile("中文 report.txt")
        advanceUntilIdle()

        assertEquals(2, listCount)
        assertEquals(created.path, vm.state.value.locationTarget)
        assertEquals(created, vm.state.value.createdFile)
        assertNull(vm.state.value.createFileError)
    }

    @Test fun `create file rejects invalid name before filesystem call`() = runTest {
        var createCalls = 0
        val fs = FakeFileSystem(
            listBlock = { OperationResult.Success(emptyList()) },
            createFileBlock = { _, _ -> createCalls += 1; error("must not create") },
        )
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
        vm.openInitial()
        advanceUntilIdle()

        vm.createFile("../invalid.txt")

        assertEquals(0, createCalls)
        assertEquals(ErrorCode.COMMAND_FAILED, vm.state.value.createFileError?.code)
        assertEquals("文件名称无效", vm.state.value.createFileError?.userMessage)
    }

    @Test fun `create file exposes no replace conflict without refreshing`() = runTest {
        var listCount = 0
        val fs = FakeFileSystem(
            listBlock = { listCount += 1; OperationResult.Success(emptyList()) },
            createFileBlock = { _, _ -> OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "文件已存在", "exists") },
        )
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
        vm.openInitial()
        advanceUntilIdle()

        vm.createFile("existing.txt")
        advanceUntilIdle()

        assertEquals(1, listCount)
        assertEquals(ErrorCode.ALREADY_EXISTS, vm.state.value.createFileError?.code)
        assertFalse(vm.state.value.creatingFile)
    }

    @Test fun `archive tap supersedes an external file export still in progress`() = runTest {
        val exportResult = CompletableDeferred<OperationResult<ExternalFileGrant>>()
        val ordinary = entry("report.pdf", EntryType.FILE)
        val archive = entry("backup.zip", EntryType.FILE)
        val grant = ExternalFileGrant(
            contentUri = "content://com.iamxpp.isaver.external-file/file/${"ef".repeat(32)}",
            token = "ef".repeat(32),
            displayName = ordinary.name,
            mimeType = "application/pdf",
        )
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            exportFile = { exportResult.await() },
        )

        vm.openEntry(ordinary)
        testScheduler.runCurrent()
        assertTrue(vm.state.value.openingFile)

        vm.openEntry(archive)
        exportResult.complete(OperationResult.Success(grant))
        advanceUntilIdle()

        assertEquals(archive, vm.state.value.archiveToOpen)
        assertNull(vm.state.value.externalFileToOpen)
        assertFalse(vm.state.value.openingFile)
    }

    @Test fun `failed Android launch revokes the grant and exposes a retryable error`() = runTest {
        val grant = ExternalFileGrant(
            contentUri = "content://com.iamxpp.isaver.external-file/file/${"cd".repeat(32)}",
            token = "cd".repeat(32),
            displayName = "report.pdf",
            mimeType = "application/pdf",
        )
        val revoked = mutableListOf<ExternalFileGrant>()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            exportFile = { OperationResult.Success(grant) },
            revokeExport = revoked::add,
        )

        vm.openEntry(entry("report.pdf", EntryType.FILE))
        advanceUntilIdle()
        vm.completeExternalOpen(grant, launched = false)

        assertEquals(listOf(grant), revoked)
        assertNull(vm.state.value.externalFileToOpen)
        assertEquals("没有可打开此文件的应用", vm.state.value.fileOpenError?.userMessage)
        vm.dismissFileOpenError()
        assertNull(vm.state.value.fileOpenError)
    }

    @Test fun `unsafe file is rejected before an export capability is requested`() = runTest {
        var exportCalls = 0
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            exportFile = {
                exportCalls += 1
                error("must not export")
            },
        )

        vm.openEntry(entry("link.pdf", EntryType.FILE, symbolicLink = true))
        advanceUntilIdle()

        assertEquals(0, exportCalls)
        assertEquals(ErrorCode.SOURCE_UNREADABLE, vm.state.value.fileOpenError?.code)
    }

    @Test fun `single file share emits a dedicated grant and clears selection after chooser launch`() = runTest {
        val file = entry("report.pdf", EntryType.FILE)
        val grant = ExternalFileGrant(
            contentUri = "content://com.iamxpp.isaver.external-file/file/${"12".repeat(32)}",
            token = "12".repeat(32),
            displayName = file.name,
            mimeType = "application/pdf",
        )
        val shared = mutableListOf<DirectoryEntry>()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            shareFile = { entry ->
                shared += entry
                OperationResult.Success(grant)
            },
        )
        vm.selectEntry(file)

        vm.shareEntry(file)
        advanceUntilIdle()

        assertEquals(listOf(file), shared)
        assertEquals(grant, vm.state.value.externalFileToShare)
        assertFalse(vm.state.value.sharingFile)

        vm.completeExternalShare(grant, launched = true)

        assertNull(vm.state.value.externalFileToShare)
        assertTrue(vm.state.value.selectedEntries.isEmpty())
        assertNull(vm.state.value.fileShareError)
    }

    @Test fun `failed share chooser launch revokes grant and reports an error`() = runTest {
        val file = entry("report.pdf", EntryType.FILE)
        val grant = ExternalFileGrant(
            contentUri = "content://com.iamxpp.isaver.external-file/file/${"34".repeat(32)}",
            token = "34".repeat(32),
            displayName = file.name,
            mimeType = "application/pdf",
        )
        val revoked = mutableListOf<ExternalFileGrant>()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            shareFile = { OperationResult.Success(grant) },
            revokeExport = revoked::add,
        )

        vm.shareEntry(file)
        advanceUntilIdle()
        vm.completeExternalShare(grant, launched = false)

        assertEquals(listOf(grant), revoked)
        assertEquals("没有可接收此文件的应用", vm.state.value.fileShareError?.userMessage)
        vm.dismissFileShareError()
        assertNull(vm.state.value.fileShareError)
    }

    @Test fun `multiple file share prepares every grant and clears selection after launch`() = runTest {
        val first = entry("first.txt", EntryType.FILE)
        val second = entry("second.pdf", EntryType.FILE)
        val grants = listOf(
            ExternalFileGrant("content://test/first", "ab".repeat(32), first.name, "text/plain"),
            ExternalFileGrant("content://test/second", "cd".repeat(32), second.name, "application/pdf"),
        )
        val shared = mutableListOf<DirectoryEntry>()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            shareFile = { selected ->
                shared += selected
                OperationResult.Success(grants[shared.lastIndex])
            },
        )
        vm.selectEntry(first)
        vm.selectEntry(second)

        vm.shareSelection()
        advanceUntilIdle()

        assertEquals(listOf(first, second), shared)
        assertEquals(grants, vm.state.value.externalFilesToShare)
        vm.completeExternalShare(grants, launched = true)
        assertTrue(vm.state.value.selectedEntries.isEmpty())
        assertTrue(vm.state.value.externalFilesToShare.isEmpty())
    }

    @Test fun `multiple share routes directories through archive share`() = runTest {
        val file = entry("first.txt", EntryType.FILE)
        val directory = entry("folder", EntryType.DIRECTORY)
        var exportCalls = 0
        val grant = ExternalFileGrant("content://test/archive", "aa".repeat(32), "iSaver-share.zip", "application/zip")
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            shareFile = {
                exportCalls += 1
                error("must not export")
            },
            shareDirectory = { entries ->
                assertEquals(listOf(file, directory), entries)
                OperationResult.Success(grant)
            },
        )
        vm.selectEntry(file)
        vm.selectEntry(directory)

        vm.shareSelection()
        advanceUntilIdle()

        assertEquals(0, exportCalls)
        assertEquals(grant, vm.state.value.externalFileToShare)
    }

    @Test fun `multiple share failure revokes grants prepared before the failure`() = runTest {
        val first = entry("first.txt", EntryType.FILE)
        val second = entry("second.pdf", EntryType.FILE)
        val grant = ExternalFileGrant("content://test/first", "ef".repeat(32), first.name, "text/plain")
        val revoked = mutableListOf<ExternalFileGrant>()
        var calls = 0
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            shareFile = {
                calls += 1
                if (calls == 1) OperationResult.Success(grant)
                else OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, "无法分享文件")
            },
            revokeExport = revoked::add,
        )
        vm.selectEntry(first)
        vm.selectEntry(second)

        vm.shareSelection()
        advanceUntilIdle()

        assertEquals(listOf(grant), revoked)
        assertTrue(vm.state.value.externalFilesToShare.isEmpty())
        assertEquals(ErrorCode.SOURCE_UNREADABLE, vm.state.value.fileShareError?.code)
    }

    @Test fun `single file move keeps source identity while choosing and emits moved output`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val targetDirectory = RootPath.parse("/data/local/tmp/target").getOrThrow()
        val source = entry("report.txt", EntryType.FILE, path = "${sourceDirectory.value}/report.txt")
        val output = entry("report.txt", EntryType.FILE, path = "${targetDirectory.value}/report.txt")
        val requests = mutableListOf<Triple<DirectoryEntry, RootPath, RootPath>>()
        val oldIdentity = RootEntryIdentity(8, 9)
        val relocated = mutableListOf<Pair<RootEntryIdentity, DirectoryEntry>>()
        val vm = BrowserViewModel(
            FakeFileSystem(identityBlock = { OperationResult.Success(oldIdentity) }) { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            moveFile = { entry, sourceParent, targetParent, _ ->
                requests += Triple(entry, sourceParent, targetParent)
                OperationResult.Success(output)
            },
            relocateVirtualReferences = { identity, entry -> relocated += identity to entry },
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()
        vm.selectEntry(source)

        vm.beginMove(source)
        assertEquals(BrowserMoveSelection(source, sourceDirectory), vm.state.value.moveSelection)

        vm.moveTo(targetDirectory)
        advanceUntilIdle()

        assertEquals(listOf(Triple(source, sourceDirectory, targetDirectory)), requests)
        assertFalse(vm.state.value.movingFile)
        assertNull(vm.state.value.moveSelection)
        assertEquals(output, vm.state.value.movedOutput)
        assertTrue(vm.state.value.selectedEntries.isEmpty())
        assertEquals(listOf(oldIdentity to output), relocated)
    }

    @Test fun `move to source directory stays in picker and never dispatches`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val source = entry("report.txt", EntryType.FILE, path = "${sourceDirectory.value}/report.txt")
        var calls = 0
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            moveFile = { _, _, _, _ -> calls += 1; error("must not dispatch") },
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()
        vm.beginMove(source)

        vm.moveTo(sourceDirectory)
        advanceUntilIdle()

        assertEquals(0, calls)
        assertEquals(ErrorCode.ALREADY_EXISTS, vm.state.value.fileMoveError?.code)
        assertEquals(BrowserMoveSelection(source, sourceDirectory), vm.state.value.moveSelection)
    }

    @Test fun `multiple selected files move in directory order and report completed count`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val targetDirectory = RootPath.parse("/data/local/tmp/target").getOrThrow()
        val first = entry("first.txt", EntryType.FILE, path = "${sourceDirectory.value}/first.txt")
        val second = entry("second.txt", EntryType.FILE, path = "${sourceDirectory.value}/second.txt")
        val outputs = listOf(
            entry("first.txt", EntryType.FILE, path = "${targetDirectory.value}/first.txt"),
            entry("second.txt", EntryType.FILE, path = "${targetDirectory.value}/second.txt"),
        )
        val requests = mutableListOf<DirectoryEntry>()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(first, second)) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            moveFile = { selected, _, _, _ ->
                requests += selected
                OperationResult.Success(outputs[requests.lastIndex])
            },
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()
        vm.selectEntry(second)
        vm.selectEntry(first)

        assertTrue(vm.beginMoveSelection())
        assertEquals(listOf(first, second), vm.state.value.moveSelection?.entries)
        vm.moveTo(targetDirectory)
        advanceUntilIdle()

        assertEquals(listOf(first, second), requests)
        assertEquals(2, vm.state.value.moveCompletedCount)
        assertEquals(2, vm.state.value.moveTotalCount)
        assertEquals(outputs.last(), vm.state.value.movedOutput)
        assertNull(vm.state.value.fileMoveError)
    }

    @Test fun `multiple move conflict pauses and keep both resumes without replay`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val targetDirectory = RootPath.parse("/data/local/tmp/target").getOrThrow()
        val first = entry("first.txt", EntryType.FILE, path = "${sourceDirectory.value}/first.txt")
        val second = entry("second.txt", EntryType.FILE, path = "${sourceDirectory.value}/second.txt")
        val output = entry("first.txt", EntryType.FILE, path = "${targetDirectory.value}/first.txt")
        var calls = 0
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(first, second)) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            moveFile = { selected, _, _, action ->
                calls += 1
                when {
                    selected == first -> OperationResult.Success(output)
                    action == com.iamxpp.isaver.fileops.ConflictAction.KEEP_BOTH -> OperationResult.Success(
                        entry("second (1).txt", EntryType.FILE, path = "${targetDirectory.value}/second (1).txt"),
                    )
                    else -> OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "目标位置已存在同名文件")
                }
            },
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()
        vm.selectEntry(first)
        vm.selectEntry(second)

        vm.beginMoveSelection()
        vm.moveTo(targetDirectory)
        advanceUntilIdle()

        assertEquals(2, calls)
        assertEquals("second.txt", vm.state.value.conflictPrompt?.entryName)
        assertEquals(1, vm.state.value.moveCompletedCount)
        assertEquals(output, vm.state.value.movedOutput)

        vm.resolveConflict(com.iamxpp.isaver.fileops.ConflictAction.KEEP_BOTH, applyToAll = true)
        advanceUntilIdle()

        assertEquals(3, calls)
        assertNull(vm.state.value.conflictPrompt)
        assertNull(vm.state.value.moveSelection)
        assertEquals(2, vm.state.value.moveCompletedCount)
        assertNull(vm.state.value.fileMoveError)
    }

    @Test fun `skip all skips only later conflicts and still processes non conflicting files`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val targetDirectory = RootPath.parse("/data/local/tmp/target").getOrThrow()
        val first = entry("first.txt", EntryType.FILE, path = "${sourceDirectory.value}/first.txt")
        val second = entry("second.txt", EntryType.FILE, path = "${sourceDirectory.value}/second.txt")
        val third = entry("third.txt", EntryType.FILE, path = "${sourceDirectory.value}/third.txt")
        val calls = mutableListOf<String>()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(first, second, third)) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            copyFile = { selected, _, _, _ ->
                calls += selected.name
                when (selected) {
                    first -> OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "目标位置已存在同名文件")
                    second -> OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "目标位置已存在同名文件")
                    else -> OperationResult.Success(
                        entry(selected.name, EntryType.FILE, path = "${targetDirectory.value}/${selected.name}"),
                    )
                }
            },
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()
        vm.selectEntry(first)
        vm.selectEntry(second)
        vm.selectEntry(third)

        vm.beginCopySelection()
        vm.copyTo(targetDirectory)
        advanceUntilIdle()
        vm.resolveConflict(com.iamxpp.isaver.fileops.ConflictAction.SKIP, applyToAll = true)
        advanceUntilIdle()

        assertEquals(listOf("first.txt", "second.txt", "third.txt"), calls)
        assertEquals(1, vm.state.value.copyCompletedCount)
        assertEquals("third.txt", vm.state.value.copiedOutput?.name)
        assertNull(vm.state.value.conflictPrompt)
        assertNull(vm.state.value.fileCopyError)
    }

    @Test fun `single file rename emits renamed output and refreshes the current directory`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val source = entry("report.txt", EntryType.FILE, path = "${sourceDirectory.value}/report.txt")
        val output = entry("renamed.txt", EntryType.FILE, path = "${sourceDirectory.value}/renamed.txt")
        val names = mutableListOf<String>()
        val oldIdentity = RootEntryIdentity(8, 9)
        val relocated = mutableListOf<Pair<RootEntryIdentity, DirectoryEntry>>()
        val vm = BrowserViewModel(
            FakeFileSystem(identityBlock = { OperationResult.Success(oldIdentity) }) { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            renameFile = { selected, _, name ->
                assertEquals(source, selected)
                names += name
                OperationResult.Success(output)
            },
            relocateVirtualReferences = { identity, entry -> relocated += identity to entry },
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()

        vm.renameEntry(source, "renamed.txt")
        advanceUntilIdle()

        assertEquals(listOf("renamed.txt"), names)
        assertFalse(vm.state.value.renamingFile)
        assertEquals(output, vm.state.value.renamedOutput)
        assertNull(vm.state.value.fileRenameError)
        assertEquals(listOf(oldIdentity to output), relocated)
    }

    @Test fun `rename failure remains visible and does not claim output`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val source = entry("report.txt", EntryType.FILE, path = "${sourceDirectory.value}/report.txt")
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            renameFile = { _, _, _ -> OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "目标位置已存在同名文件") },
        )

        vm.renameEntry(source, "renamed.txt")
        advanceUntilIdle()

        assertFalse(vm.state.value.renamingFile)
        assertNull(vm.state.value.renamedOutput)
        assertEquals(ErrorCode.ALREADY_EXISTS, vm.state.value.fileRenameError?.code)
    }

    @Test fun `batch rename preview reports conflicts before execution`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val first = entry("a.txt", EntryType.FILE, path = "${sourceDirectory.value}/a.txt")
        val second = entry("b.txt", EntryType.FILE, path = "${sourceDirectory.value}/b.txt")
        val existing = entry("c.txt", EntryType.FILE, path = "${sourceDirectory.value}/c.txt")
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(first, second, existing)) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()
        vm.selectEntry(first)
        vm.selectEntry(second)

        vm.previewBatchRename(BatchRenameRule(BatchRenameMode.PREFIX_SUFFIX, prefix = "new-"))
        assertEquals(listOf("new-a.txt", "new-b.txt"), vm.state.value.batchRenamePlan?.items?.map { it.targetName.value })
        assertNull(vm.state.value.batchRenameError)

        vm.previewBatchRename(BatchRenameRule(BatchRenameMode.FIND_REPLACE, find = "a", replacement = "c"))
        assertNull(vm.state.value.batchRenamePlan)
        assertEquals(ErrorCode.ALREADY_EXISTS, vm.state.value.batchRenameError?.code)
    }

    @Test fun `batch rename rejects changed selection after preview`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val first = entry("a.txt", EntryType.FILE, path = "${sourceDirectory.value}/a.txt")
        val second = entry("b.txt", EntryType.FILE, path = "${sourceDirectory.value}/b.txt")
        var renameCalls = 0
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(first, second)) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            renameFile = { _, _, _ -> renameCalls += 1; error("must not rename") },
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()
        vm.selectEntry(first)
        vm.selectEntry(second)
        vm.previewBatchRename(BatchRenameRule(BatchRenameMode.PREFIX_SUFFIX, prefix = "new-"))

        vm.selectEntry(second)
        vm.executeBatchRename()

        assertEquals(0, renameCalls)
        assertNull(vm.state.value.batchRenamePlan)
        assertEquals("选择已变化，请重新预览", vm.state.value.batchRenameError?.userMessage)
    }

    @Test fun `successful batch rename clears selection and refreshes directory`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val first = entry("a.txt", EntryType.FILE, path = "${sourceDirectory.value}/a.txt")
        val second = entry("b.txt", EntryType.FILE, path = "${sourceDirectory.value}/b.txt")
        val current = linkedMapOf(first.name to first, second.name to second)
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(current.values.toList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            renameFile = { source, parent, target ->
                current.remove(source.name)
                val output = source.copy(path = RootPath.parse("${parent.value}/$target").getOrThrow(), name = target)
                current[target] = output
                OperationResult.Success(output)
            },
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()
        vm.selectEntry(first)
        vm.selectEntry(second)
        vm.previewBatchRename(BatchRenameRule(BatchRenameMode.PREFIX_SUFFIX, prefix = "new-"))

        vm.executeBatchRename()
        advanceUntilIdle()

        assertTrue(vm.state.value.selectedEntries.isEmpty())
        assertNull(vm.state.value.batchRenamePlan)
        assertNull(vm.state.value.batchRenameError)
        assertEquals(listOf("new-a.txt", "new-b.txt"), vm.state.value.allEntries.map { it.name })
    }

    @Test fun `batch rename execution failure remains visible`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val first = entry("a.txt", EntryType.FILE, path = "${sourceDirectory.value}/a.txt")
        val second = entry("b.txt", EntryType.FILE, path = "${sourceDirectory.value}/b.txt")
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(first, second)) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            renameFile = { _, _, _ -> OperationResult.Failure(ErrorCode.NOT_WRITABLE, "目录不可写") },
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()
        vm.selectEntry(first)
        vm.selectEntry(second)
        vm.previewBatchRename(BatchRenameRule(BatchRenameMode.PREFIX_SUFFIX, prefix = "new-"))

        vm.executeBatchRename()
        advanceUntilIdle()

        assertFalse(vm.state.value.renamingFile)
        assertNull(vm.state.value.batchRenamePlan)
        assertEquals(ErrorCode.NOT_WRITABLE, vm.state.value.batchRenameError?.code)
        assertEquals(setOf(first, second), vm.state.value.selectedEntries)
    }

    @Test fun `single file copy keeps source identity while choosing and emits copied output`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val targetDirectory = RootPath.parse("/data/local/tmp/target").getOrThrow()
        val source = entry("report.txt", EntryType.FILE, path = "${sourceDirectory.value}/report.txt")
        val output = entry("report.txt", EntryType.FILE, path = "${targetDirectory.value}/report.txt")
        val requests = mutableListOf<Triple<DirectoryEntry, RootPath, RootPath>>()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            copyFile = { selected, sourceParent, targetParent, _ ->
                requests += Triple(selected, sourceParent, targetParent)
                OperationResult.Success(output)
            },
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()
        vm.selectEntry(source)

        vm.beginCopy(source)
        assertEquals(BrowserCopySelection(source, sourceDirectory), vm.state.value.copySelection)

        vm.copyTo(targetDirectory)
        advanceUntilIdle()

        assertEquals(listOf(Triple(source, sourceDirectory, targetDirectory)), requests)
        assertFalse(vm.state.value.copyingFile)
        assertNull(vm.state.value.copySelection)
        assertEquals(output, vm.state.value.copiedOutput)
        assertTrue(vm.state.value.selectedEntries.isEmpty())
    }

    @Test fun `multiple selected files copy and restore the full selection when picker is cancelled`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val first = entry("first.txt", EntryType.FILE, path = "${sourceDirectory.value}/first.txt")
        val second = entry("second.txt", EntryType.FILE, path = "${sourceDirectory.value}/second.txt")
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(first, second)) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()
        vm.selectEntry(first)
        vm.selectEntry(second)

        assertTrue(vm.beginCopySelection())
        assertEquals(listOf(first, second), vm.state.value.copySelection?.entries)
        assertTrue(vm.cancelCopy(restoreSelection = true))
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()

        assertEquals(setOf(first, second), vm.state.value.selectedEntries)
    }

    @Test fun `copy task records progress and success without persisting paths`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val targetDirectory = RootPath.parse("/data/local/tmp/target").getOrThrow()
        val first = entry("first.txt", EntryType.FILE, path = "${sourceDirectory.value}/first.txt")
        val second = entry("second.txt", EntryType.FILE, path = "${sourceDirectory.value}/second.txt")
        val taskStore = RecordingOperationTaskStore()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(first, second)) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            copyFile = { source, _, target, _ ->
                OperationResult.Success(source.copy(path = RootPath.parse("${target.value}/${source.name}").getOrThrow()))
            },
            operationTaskStore = taskStore,
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()
        vm.selectEntry(first)
        vm.selectEntry(second)
        vm.beginCopySelection()

        vm.copyTo(targetDirectory)
        advanceUntilIdle()

        assertEquals(listOf(OperationTaskType.COPY to 2), taskStore.starts)
        assertEquals(
            listOf(
                OperationTaskState.RUNNING to 0,
                OperationTaskState.RUNNING to 1,
                OperationTaskState.RUNNING to 2,
                OperationTaskState.SUCCESS to 2,
            ),
            taskStore.updates.map { it.state to it.completed },
        )
    }

    @Test fun `sha256 updates current info and persistent task`() = runTest {
        val file = entry("value.txt", EntryType.FILE, path = "/data/local/tmp/value.txt")
        val taskStore = RecordingOperationTaskStore()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(file)) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            operationTaskStore = taskStore,
            checksumFile = { OperationResult.Success("a".repeat(64)) },
        )

        vm.showFileInfo(file)
        vm.calculateSha256()
        advanceUntilIdle()

        assertEquals("a".repeat(64), vm.state.value.checksumValue)
        assertEquals(listOf(OperationTaskType.CHECKSUM to 1), taskStore.starts)
        assertEquals(OperationTaskState.SUCCESS, taskStore.updates.last().state)
    }

    @Test fun `selected checksum algorithm is passed to the operation and task`() = runTest {
        val file = entry("value.txt", EntryType.FILE, path = "/data/local/tmp/value.txt")
        val taskStore = RecordingOperationTaskStore()
        val algorithms = mutableListOf<ChecksumAlgorithm>()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(file)) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            operationTaskStore = taskStore,
            checksumFileByAlgorithm = { _, algorithm ->
                algorithms += algorithm
                OperationResult.Success("digest")
            },
        )

        vm.showFileInfo(file)
        advanceUntilIdle()
        vm.setChecksumAlgorithm(ChecksumAlgorithm.SHA512)
        vm.calculateChecksum()
        advanceUntilIdle()

        assertEquals(listOf(ChecksumAlgorithm.SHA512), algorithms)
        assertEquals("digest", vm.state.value.checksumValue)
        assertEquals(ChecksumAlgorithm.SHA512, vm.state.value.checksumAlgorithm)
        assertEquals(OperationTaskState.SUCCESS, taskStore.updates.last().state)
    }

    @Test fun `archive failure records persistent task without paths`() = runTest {
        val cacheDir = Files.createTempDirectory("isaver-browser-archive").toFile()
        val file = entry("value.txt", EntryType.FILE, path = "/storage/emulated/0/value.txt")
        val taskStore = RecordingOperationTaskStore()
        val fileSystem = FakeFileSystem { OperationResult.Success(listOf(file)) }
        val repository = ArchiveRepository(
            rootFileSystem = fileSystem,
            localEngine = LocalArchiveEngine(),
            cacheDir = cacheDir,
            publish = { _, _, _ -> error("unsupported format must not publish") },
        )
        try {
            val vm = BrowserViewModel(
                fileSystem,
                StandardTestDispatcher(testScheduler),
                defaultPreferences(),
                archiveRepository = repository,
                operationTaskStore = taskStore,
            )
            vm.openInitial()
            advanceUntilIdle()
            vm.selectEntry(file)

            vm.compress("archive.rar", ArchiveFormat.RAR)
            advanceUntilIdle()

            assertEquals("压缩文件名称与所选格式不一致", vm.state.value.compressionMessage)
            assertEquals(listOf(OperationTaskType.ARCHIVE to 1), taskStore.starts)
            assertEquals(OperationTaskState.RUNNING, taskStore.updates.first().state)
            assertEquals(OperationTaskState.FAILED, taskStore.updates.last().state)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test fun `file info only exposes metadata while path identity still matches`() = runTest {
        val file = entry("value.txt", EntryType.FILE, path = "/data/local/tmp/value.txt")
        val metadata = RootFileMetadata(0x1A0, 1000, 1001, 12, 34)
        val matching = FakeFileSystem(
            metadataBlock = { OperationResult.Success(metadata) },
            identityBlock = { OperationResult.Success(RootEntryIdentity(12, 34)) },
        ) { OperationResult.Success(listOf(file)) }
        val matchingVm = BrowserViewModel(
            matching, StandardTestDispatcher(testScheduler), defaultPreferences(),
        )

        matchingVm.showFileInfo(file)
        advanceUntilIdle()

        assertEquals(metadata, matchingVm.state.value.fileMetadata)
        assertNull(matchingVm.state.value.fileMetadataError)

        val changed = FakeFileSystem(
            metadataBlock = { OperationResult.Success(metadata) },
            identityBlock = { OperationResult.Success(RootEntryIdentity(12, 35)) },
        ) { OperationResult.Success(listOf(file)) }
        val changedVm = BrowserViewModel(
            changed, StandardTestDispatcher(testScheduler), defaultPreferences(),
        )

        changedVm.showFileInfo(file)
        advanceUntilIdle()

        assertNull(changedVm.state.value.fileMetadata)
        assertEquals("文件已变化，请刷新核对", changedVm.state.value.fileMetadataError)
    }

    @Test fun `deep search reports results progress and persistent task`() = runTest {
        val root = RootPath.parse(BrowserViewModel.INITIAL_PATH).getOrThrow()
        val result = entry("report.txt", EntryType.FILE, path = "${root.value}/report.txt")
        val fileSystem = FakeFileSystem(
            snapshotBlock = {
                OperationResult.Success(DirectorySnapshot(1, 2, true, true, listOf(result)))
            },
        ) { OperationResult.Success(emptyList()) }
        val taskStore = RecordingOperationTaskStore()
        val vm = BrowserViewModel(
            fileSystem,
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            operationTaskStore = taskStore,
            localSearchRepository = LocalSearchRepository(fileSystem),
        )

        vm.startDeepSearch(LocalSearchCriteria("report"))
        advanceUntilIdle()

        assertEquals(listOf(result), vm.state.value.deepSearchResults)
        assertEquals(1, vm.state.value.deepSearchScannedDirectories)
        assertEquals(1, vm.state.value.deepSearchScannedEntries)
        assertFalse(vm.state.value.deepSearchRunning)
        assertEquals(listOf(OperationTaskType.SEARCH to 1), taskStore.starts)
        assertEquals(OperationTaskState.SUCCESS, taskStore.updates.last().state)
    }

    @Test fun `deep search cancellation clears running state and cancels task`() = runTest {
        val release = CompletableDeferred<Unit>()
        val fileSystem = FakeFileSystem(
            snapshotBlock = {
                release.await()
                OperationResult.Success(DirectorySnapshot(1, 2, true, true, emptyList()))
            },
        ) { OperationResult.Success(emptyList()) }
        val taskStore = RecordingOperationTaskStore()
        val vm = BrowserViewModel(
            fileSystem,
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            operationTaskStore = taskStore,
            localSearchRepository = LocalSearchRepository(fileSystem),
        )
        vm.startDeepSearch(LocalSearchCriteria("report"))
        runCurrent()

        vm.cancelDeepSearch()
        advanceUntilIdle()

        assertFalse(vm.state.value.deepSearchRunning)
        assertEquals(OperationTaskState.CANCELLED, taskStore.updates.last().state)
    }

    @Test fun `closing info cancels checksum and clears late result`() = runTest {
        val file = entry("value.txt", EntryType.FILE, path = "/data/local/tmp/value.txt")
        val release = CompletableDeferred<Unit>()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(file)) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            checksumFile = {
                release.await()
                OperationResult.Success("b".repeat(64))
            },
        )
        vm.showFileInfo(file)
        vm.calculateSha256()
        runCurrent()

        vm.dismissFileInfo()
        release.complete(Unit)
        advanceUntilIdle()

        assertNull(vm.state.value.fileInfo)
        assertNull(vm.state.value.checksumValue)
        assertFalse(vm.state.value.checksumRunning)
    }

    @Test fun `copy conflict keeps one task while waiting and resuming`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val targetDirectory = RootPath.parse("/data/local/tmp/target").getOrThrow()
        val source = entry("report.txt", EntryType.FILE, path = "${sourceDirectory.value}/report.txt")
        val taskStore = RecordingOperationTaskStore()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(source)) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            copyFile = { selected, _, target, action ->
                if (action == com.iamxpp.isaver.fileops.ConflictAction.KEEP_BOTH) {
                    OperationResult.Success(selected.copy(path = RootPath.parse("${target.value}/report (1).txt").getOrThrow()))
                } else {
                    OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "目标位置已存在同名文件")
                }
            },
            operationTaskStore = taskStore,
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()
        vm.selectEntry(source)
        vm.beginCopySelection()

        vm.copyTo(targetDirectory)
        advanceUntilIdle()
        vm.resolveConflict(com.iamxpp.isaver.fileops.ConflictAction.KEEP_BOTH)
        advanceUntilIdle()

        assertEquals(1, taskStore.starts.size)
        assertTrue(taskStore.updates.any { it.state == OperationTaskState.NEEDS_ACTION })
        assertEquals(OperationTaskState.SUCCESS, taskStore.updates.last().state)
        assertTrue(taskStore.updates.all { it.id == "task-1" })
    }

    @Test fun `copy task pauses between entries and resumes without replay`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val targetDirectory = RootPath.parse("/data/local/tmp/target").getOrThrow()
        val first = entry("first.txt", EntryType.FILE, path = "${sourceDirectory.value}/first.txt")
        val second = entry("second.txt", EntryType.FILE, path = "${sourceDirectory.value}/second.txt")
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val taskStore = RecordingOperationTaskStore()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(first, second)) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            copyFile = { source, _, target, _ ->
                calls += source.name
                if (source == first) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                OperationResult.Success(source.copy(path = RootPath.parse("${target.value}/${source.name}").getOrThrow()))
            },
            operationTaskStore = taskStore,
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()
        vm.selectEntry(first)
        vm.selectEntry(second)
        vm.beginCopySelection()
        vm.copyTo(targetDirectory)
        firstStarted.await()

        vm.pauseTask("task-1")
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("first.txt"), calls)
        assertTrue(taskStore.updates.any { it.state == OperationTaskState.PAUSED && it.completed == 1 })
        vm.resumeTask("task-1")
        advanceUntilIdle()
        assertEquals(listOf("first.txt", "second.txt"), calls)
        assertEquals(OperationTaskState.SUCCESS, taskStore.updates.last().state)
    }

    @Test fun `copy task cancellation stops later entries and records terminal state`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val targetDirectory = RootPath.parse("/data/local/tmp/target").getOrThrow()
        val first = entry("first.txt", EntryType.FILE, path = "${sourceDirectory.value}/first.txt")
        val second = entry("second.txt", EntryType.FILE, path = "${sourceDirectory.value}/second.txt")
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val taskStore = RecordingOperationTaskStore()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(first, second)) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            copyFile = { source, _, target, _ ->
                calls += source.name
                firstStarted.complete(Unit)
                withContext(NonCancellable) { releaseFirst.await() }
                OperationResult.Success(source.copy(path = RootPath.parse("${target.value}/${source.name}").getOrThrow()))
            },
            operationTaskStore = taskStore,
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()
        vm.selectEntry(first)
        vm.selectEntry(second)
        vm.beginCopySelection()
        vm.copyTo(targetDirectory)
        firstStarted.await()

        vm.cancelTask("task-1")
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("first.txt"), calls)
        assertTrue(taskStore.updates.any { it.state == OperationTaskState.CANCELLING })
        assertEquals(OperationTaskState.CANCELLED, taskStore.updates.last().state)
    }

    @Test fun `copy to source directory stays in picker and never dispatches`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val source = entry("report.txt", EntryType.FILE, path = "${sourceDirectory.value}/report.txt")
        var calls = 0
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            copyFile = { _, _, _, _ -> calls += 1; error("must not dispatch") },
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()
        vm.beginCopy(source)

        vm.copyTo(sourceDirectory)
        advanceUntilIdle()

        assertEquals(0, calls)
        assertEquals(ErrorCode.ALREADY_EXISTS, vm.state.value.fileCopyError?.code)
        assertEquals(BrowserCopySelection(source, sourceDirectory), vm.state.value.copySelection)
    }

    @Test fun `long press selection accepts readable files and directories and rejects unsafe entries`() = runTest {
        val vm = BrowserViewModel(FakeFileSystem { OperationResult.Success(emptyList()) }, StandardTestDispatcher(testScheduler), defaultPreferences())
        val file = entry("file.txt", EntryType.FILE)
        val directory = entry("folder", EntryType.DIRECTORY)
        val symlink = entry("link", EntryType.FILE, symbolicLink = true)
        val unreadable = DirectoryEntry(RootPath.parse("/x/blocked").getOrThrow(), "blocked", EntryType.FILE, 1, 2, false, false, false)

        vm.selectEntry(file)
        vm.selectEntry(directory)
        vm.selectEntry(symlink)
        vm.selectEntry(unreadable)

        assertEquals(setOf(file, directory), vm.state.value.selectedEntries)
        assertTrue(vm.state.value.selectionMode)
        vm.clearSelection()
        assertFalse(vm.state.value.selectionMode)
    }

    @Test fun `selection actions operate on the full filtered directory`() = runTest {
        val directory = RootPath.parse("/data/local/tmp/selection").getOrThrow()
        val firstFile = entry("first.txt", EntryType.FILE, path = "${directory.value}/first.txt")
        val secondFile = entry("second.txt", EntryType.FILE, path = "${directory.value}/second.txt")
        val folder = entry("folder", EntryType.DIRECTORY, path = "${directory.value}/folder")
        val other = entry("device", EntryType.OTHER, path = "${directory.value}/device")
        val unreadable = DirectoryEntry(
            RootPath.parse("${directory.value}/blocked").getOrThrow(),
            "blocked",
            EntryType.FILE,
            1,
            2,
            false,
            false,
            false,
        )
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(firstFile, secondFile, folder, other, unreadable)) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
        )

        vm.openRoot(directory, "选择")
        advanceUntilIdle()
        vm.selectAllVisible()

        assertEquals(setOf(firstFile, secondFile, folder), vm.state.value.selectedEntries)
        vm.setSearchQuery(".txt")
        advanceUntilIdle()
        vm.clearSelection()
        vm.selectEntry(firstFile)
        vm.selectSameType()
        assertEquals(setOf(firstFile, secondFile), vm.state.value.selectedEntries)
        vm.invertVisibleSelection()
        assertTrue(vm.state.value.selectedEntries.isEmpty())
    }

    @Test fun `same type selection requires a single selected type`() = runTest {
        val directory = RootPath.parse("/data/local/tmp/selection").getOrThrow()
        val file = entry("file.txt", EntryType.FILE, path = "${directory.value}/file.txt")
        val folder = entry("folder", EntryType.DIRECTORY, path = "${directory.value}/folder")
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(listOf(file, folder)) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
        )

        vm.openRoot(directory, "选择")
        advanceUntilIdle()
        vm.selectEntry(file)
        vm.selectEntry(folder)
        vm.selectSameType()

        assertEquals(setOf(file, folder), vm.state.value.selectedEntries)
    }

    @Test fun `symlink directory listing failure does not allow folder creation`() = runTest {
        val fs = FakeFileSystem(
            snapshotBlock = {
                OperationResult.Failure(ErrorCode.NOT_DIRECTORY, "路径不是目录", "symlink refused")
            },
            listBlock = { OperationResult.Success(emptyList()) },
        )
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
        vm.openInitial()
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
        vm.openInitial()
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
        vm.openInitial()
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
        val vm = BrowserViewModel(fs, StandardTestDispatcher(testScheduler), defaultPreferences())
        vm.openInitial(); advanceUntilIdle()
        vm.enterDirectory(entry("old", EntryType.DIRECTORY, "/storage/emulated/0/old")); testScheduler.runCurrent()
        vm.enterDirectory(entry("new", EntryType.DIRECTORY, "/storage/emulated/0/new")); testScheduler.runCurrent()
        fresh.complete(OperationResult.Success(listOf(entry("fresh", EntryType.FILE)))); advanceUntilIdle()
        old.complete(OperationResult.Success(listOf(entry("stale", EntryType.FILE)))); advanceUntilIdle()
        assertEquals("/storage/emulated/0/new", vm.state.value.currentPath.value)
        assertEquals(listOf("fresh"), vm.state.value.entries.map { it.name })
    }

    @Test fun `cancellation does not become an error`() = runTest {
        val vm = BrowserViewModel(FakeFileSystem { throw CancellationException("cancel") }, StandardTestDispatcher(testScheduler), defaultPreferences())
        vm.openInitial()
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
        vm.openInitial()
        advanceUntilIdle()
        assertTrue(sortedWithMarker)
        assertEquals(listOf("a", "b"), vm.state.value.entries.map { it.name })
    }

    @Test fun `large results remain complete and reveal 200 entries per page`() = runTest {
        val all = (1..450).map { entry("file$it", EntryType.FILE) }
        val vm = BrowserViewModel(FakeFileSystem { OperationResult.Success(all) }, StandardTestDispatcher(testScheduler), defaultPreferences())
        vm.openInitial(); advanceUntilIdle()
        assertEquals(450, vm.state.value.totalCount); assertEquals(200, vm.state.value.entries.size); assertTrue(vm.state.value.hasMore)
        vm.loadMore(); assertEquals(400, vm.state.value.entries.size)
        vm.loadMore(); assertEquals(450, vm.state.value.entries.size); assertFalse(vm.state.value.hasMore)
        assertEquals(450, vm.state.value.allEntries.size)
    }

    @Test fun `successful directory load records canonical access and failure does not`() = runTest {
        val recorded = mutableListOf<Pair<RootPath, String>>()
        val canonical = RootPath.parse("/canonical/location").getOrThrow()
        val fileSystem = FakeFileSystem(
            canonicalBlock = { OperationResult.Success(canonical) },
            listBlock = { OperationResult.Success(emptyList()) },
        )
        val vm = BrowserViewModel(
            fileSystem,
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            recordDirectoryAccess = { path, title -> recorded += path to title },
        )

        vm.openRoot(RootPath.parse("/alias/location").getOrThrow(), "我的位置")
        advanceUntilIdle()

        assertEquals(listOf(canonical to "我的位置"), recorded)
    }

    @Test fun `automatic post extraction root load does not overwrite extracted activity`() = runTest {
        val recorded = mutableListOf<Pair<RootPath, String>>()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            recordDirectoryAccess = { path, title -> recorded += path to title },
        )

        vm.openRoot(
            RootPath.parse("/data/local/tmp/isaver-test/ui-flow/sample").getOrThrow(),
            "sample",
            recordAccess = false,
        )
        advanceUntilIdle()

        assertTrue(recorded.isEmpty())
    }

    private class FakeFileSystem(
        val statBlock: suspend (RootPath) -> OperationResult<DirectoryEntry> = { path ->
            OperationResult.Success(DirectoryEntry(path, "current", EntryType.DIRECTORY, 0, 0, true, true, false))
        },
        val createBlock: suspend (RootPath, FolderName) -> OperationResult<DirectoryEntry> = { _, _ -> error("unused") },
        val createFileBlock: suspend (RootPath, EntryName) -> OperationResult<DirectoryEntry> = { _, _ -> error("unused") },
        val snapshotBlock: (suspend (RootPath) -> OperationResult<DirectorySnapshot>)? = null,
        val canonicalBlock: suspend (RootPath) -> OperationResult<RootPath> = { OperationResult.Success(it) },
        val metadataBlock: suspend (RootPath) -> OperationResult<RootFileMetadata> = {
            OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法读取文件属性")
        },
        val identityBlock: suspend (RootPath) -> OperationResult<RootEntryIdentity> = {
            OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法读取文件身份")
        },
        val copyToOutputBlock: suspend (RootPath, OutputStream) -> OperationResult<Long> = { _, _ ->
            OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法读取文件")
        },
        val listBlock: suspend (RootPath) -> OperationResult<List<DirectoryEntry>>,
    ) : RootFileSystem {
        val listed = mutableListOf<String>()
        val readDirectories = mutableListOf<String>()
        override suspend fun readDirectory(path: RootPath): OperationResult<DirectorySnapshot> {
            readDirectories += path.value
            snapshotBlock?.let { return it(path) }
            return when (val result = listBlock(path)) {
                is OperationResult.Failure -> result
                is OperationResult.Success -> OperationResult.Success(
                    DirectorySnapshot(
                        parentDevice = 1L,
                        parentInode = 2L,
                        parentReadable = true,
                        parentWritable = true,
                        entries = result.value,
                    ),
                )
            }
        }
        override suspend fun list(path: RootPath): OperationResult<List<DirectoryEntry>> { listed += path.value; return listBlock(path) }
        override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> = statBlock(path)
        override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> = canonicalBlock(path)
        override suspend fun metadata(source: RootPath): OperationResult<RootFileMetadata> = metadataBlock(source)
        override suspend fun identity(path: RootPath): OperationResult<RootEntryIdentity> = identityBlock(path)
        override suspend fun copyToOutput(source: RootPath, output: OutputStream): OperationResult<Long> =
            copyToOutputBlock(source, output)
        override suspend fun createDirectory(parent: RootPath, name: FolderName): OperationResult<DirectoryEntry> = createBlock(parent, name)
        override suspend fun createFileNoReplace(parent: RootPath, name: EntryName): OperationResult<DirectoryEntry> =
            createFileBlock(parent, name)
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

    private class FakeBrowserSessionStore(initial: BrowserSession?) : BrowserSessionStore {
        override val session = MutableStateFlow(initial)
        val writes = mutableListOf<BrowserSession>()
        var cleared = false
        override suspend fun save(session: BrowserSession) { writes += session; this.session.value = session }
        override suspend fun clear() { cleared = true; session.value = null }
    }

    private class FakeBookmarkDao : BookmarkDao {
        private val rows = linkedMapOf<String, BookmarkEntity>()
        private val flow = MutableStateFlow<List<BookmarkEntity>>(emptyList())

        override fun observeAll(): Flow<List<BookmarkEntity>> = flow

        override suspend fun upsert(entity: BookmarkEntity) {
            rows[entity.absolutePath] = entity
            emit()
        }

        override suspend fun delete(entity: BookmarkEntity) {
            rows.remove(entity.absolutePath)
            emit()
        }

        override suspend fun setAvailability(absolutePath: String, available: Boolean) {
            rows[absolutePath]?.let { rows[absolutePath] = it.copy(available = available) }
            emit()
        }

        override suspend fun relocate(
            oldPath: String,
            newPath: String,
            displayName: String,
            entryType: String,
            device: Long?,
            inode: Long?,
        ) {
            rows.remove(oldPath)?.let {
                rows[newPath] = it.copy(
                    absolutePath = newPath,
                    displayName = displayName,
                    entryType = entryType,
                    device = device,
                    inode = inode,
                    available = true,
                )
            }
            emit()
        }

        private fun emit() {
            flow.value = rows.values.sortedWith(
                compareByDescending<BookmarkEntity> { it.createdAt }.thenBy { it.absolutePath },
            )
        }
    }

    private class RecordingOperationTaskStore : OperationTaskStore {
        override val tasks = MutableStateFlow(emptyList<OperationTask>())
        val starts = mutableListOf<Pair<OperationTaskType, Int>>()
        val updates = mutableListOf<TaskUpdate>()

        override suspend fun start(type: OperationTaskType, totalItems: Int, totalBytes: Long?): String {
            starts += type to totalItems
            return "task-${starts.size}"
        }

        override suspend fun update(
            id: String,
            state: OperationTaskState,
            completedItems: Int,
            failedItems: Int,
            message: String?,
            completedBytes: Long?,
        ) {
            updates += TaskUpdate(id, state, completedItems)
        }

        override suspend fun reconcileInterrupted() = Unit
        override suspend fun clearFinished() = Unit
    }

    private data class TaskUpdate(val id: String, val state: OperationTaskState, val completed: Int)

    private fun defaultPreferences() = FakeBrowserPreferencesStore(BrowserPreferences())

    private fun DirectorySnapshotCache.putForTest(
        path: RootPath,
        snapshot: DirectorySnapshot,
        presentedEntries: List<DirectoryEntry> = snapshot.entries,
        presentationKey: DirectoryPresentationKey = DirectoryPresentationKey(
            SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING),
            "",
        ),
    ) {
        put(path, snapshot, presentedEntries, presentationKey)
    }

    private fun BrowserViewModel.openInitial() {
        openRoot(RootPath.parse(BrowserViewModel.INITIAL_PATH).getOrThrow(), "内部存储")
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

    private class GateNextDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        private var armed = false
        private var gatedTask: Pair<CoroutineContext, Runnable>? = null

        val hasGatedTask: Boolean get() = gatedTask != null

        fun gateNext() {
            check(!armed && gatedTask == null)
            armed = true
        }

        fun release() {
            val (context, block) = checkNotNull(gatedTask)
            gatedTask = null
            delegate.dispatch(context, block)
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (armed) {
                armed = false
                gatedTask = context to block
            } else {
                delegate.dispatch(context, block)
            }
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

    private fun snapshot(
        entries: List<DirectoryEntry>,
        writable: Boolean = false,
    ) = DirectorySnapshot(
        parentDevice = 1L,
        parentInode = 2L,
        parentReadable = true,
        parentWritable = writable,
        entries = entries,
    )
}
