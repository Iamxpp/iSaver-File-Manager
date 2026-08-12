package com.iamxpp.isaver.ui

import com.iamxpp.isaver.data.local.BrowserPreferences
import com.iamxpp.isaver.data.local.BrowserPreferencesStore
import com.iamxpp.isaver.data.root.DirectorySnapshot
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.export.ExternalFileGrant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Test fun `single file move keeps source identity while choosing and emits moved output`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val targetDirectory = RootPath.parse("/data/local/tmp/target").getOrThrow()
        val source = entry("report.txt", EntryType.FILE, path = "${sourceDirectory.value}/report.txt")
        val output = entry("report.txt", EntryType.FILE, path = "${targetDirectory.value}/report.txt")
        val requests = mutableListOf<Triple<DirectoryEntry, RootPath, RootPath>>()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            moveFile = { entry, sourceParent, targetParent ->
                requests += Triple(entry, sourceParent, targetParent)
                OperationResult.Success(output)
            },
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
    }

    @Test fun `move to source directory stays in picker and never dispatches`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val source = entry("report.txt", EntryType.FILE, path = "${sourceDirectory.value}/report.txt")
        var calls = 0
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            moveFile = { _, _, _ -> calls += 1; error("must not dispatch") },
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

    @Test fun `single file rename emits renamed output and refreshes the current directory`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val source = entry("report.txt", EntryType.FILE, path = "${sourceDirectory.value}/report.txt")
        val output = entry("renamed.txt", EntryType.FILE, path = "${sourceDirectory.value}/renamed.txt")
        val names = mutableListOf<String>()
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            renameFile = { selected, _, name ->
                assertEquals(source, selected)
                names += name
                OperationResult.Success(output)
            },
        )
        vm.openRoot(sourceDirectory, "来源")
        advanceUntilIdle()

        vm.renameEntry(source, "renamed.txt")
        advanceUntilIdle()

        assertEquals(listOf("renamed.txt"), names)
        assertFalse(vm.state.value.renamingFile)
        assertEquals(output, vm.state.value.renamedOutput)
        assertNull(vm.state.value.fileRenameError)
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
            copyFile = { selected, sourceParent, targetParent ->
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

    @Test fun `copy to source directory stays in picker and never dispatches`() = runTest {
        val sourceDirectory = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val source = entry("report.txt", EntryType.FILE, path = "${sourceDirectory.value}/report.txt")
        var calls = 0
        val vm = BrowserViewModel(
            FakeFileSystem { OperationResult.Success(emptyList()) },
            StandardTestDispatcher(testScheduler),
            defaultPreferences(),
            copyFile = { _, _, _ -> calls += 1; error("must not dispatch") },
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
        val snapshotBlock: (suspend (RootPath) -> OperationResult<DirectorySnapshot>)? = null,
        val canonicalBlock: suspend (RootPath) -> OperationResult<RootPath> = { OperationResult.Success(it) },
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
