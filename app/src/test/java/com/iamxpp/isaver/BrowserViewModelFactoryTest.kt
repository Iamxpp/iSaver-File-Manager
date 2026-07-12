package com.iamxpp.isaver

import com.iamxpp.isaver.data.local.BrowserPreferences
import com.iamxpp.isaver.data.local.BrowserPreferencesStore
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.ui.BrowserViewModel
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.SortSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelFactoryTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `factory injects the application preferences store`() = runTest {
        val store = FakeStore()
        val factory = BrowserViewModelFactory(
            fileSystem = FakeFileSystem,
            preferencesStore = store,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val viewModel = factory.create(BrowserViewModel::class.java)
        advanceUntilIdle()
        viewModel.setDisplayMode(DisplayMode.GRID)
        advanceUntilIdle()

        assertSame(store, store.lastWriter)
    }

    private class FakeStore : BrowserPreferencesStore {
        override val preferences = MutableStateFlow(BrowserPreferences())
        var lastWriter: FakeStore? = null
        override suspend fun setDisplayMode(displayMode: DisplayMode) { lastWriter = this }
        override suspend fun setSort(sortSpec: SortSpec) = Unit
    }

    private data object FakeFileSystem : RootFileSystem {
        override suspend fun list(path: RootPath) = OperationResult.Success(emptyList<DirectoryEntry>())
        override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> = error("unused")
        override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> = error("unused")
        override suspend fun createDirectory(parent: RootPath, name: FolderName): OperationResult<DirectoryEntry> = error("unused")
    }
}
