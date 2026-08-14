package com.isaver.filemanager

import com.isaver.filemanager.data.root.RootFileSystem
import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.FolderName
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.locations.CustomLocationResult
import com.isaver.filemanager.locations.LocationId
import com.isaver.filemanager.locations.ResolvedAppLocation
import com.isaver.filemanager.locations.StorageLocation
import com.isaver.filemanager.ui.LocationHomeAppResolver
import com.isaver.filemanager.ui.LocationHomeCustomStore
import com.isaver.filemanager.ui.LocationHomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationHomeViewModelFactoryTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun factoryCreatesLocationHomeViewModelFromApplicationAdapters() = runTest {
        val resolver = LocationHomeAppResolver { template ->
            ResolvedAppLocation(template.id, template.displayName, emptyList(), template.candidates.size)
        }
        val factory = LocationHomeViewModelFactory(
            resolver = resolver,
            store = EmptyStore,
            fileSystem = EmptyFileSystem,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertEquals(LocationHomeViewModel::class.java, factory.create(LocationHomeViewModel::class.java).javaClass)
    }

    private data object EmptyStore : LocationHomeCustomStore {
        override fun observeAll(): Flow<List<StorageLocation.Direct>> = flowOf(emptyList())
        override suspend fun add(name: String, path: RootPath) = CustomLocationResult.Success
        override suspend fun update(id: LocationId, name: String, path: RootPath) = CustomLocationResult.Success
        override suspend fun remove(id: LocationId) = CustomLocationResult.Success
    }

    private data object EmptyFileSystem : RootFileSystem {
        override suspend fun list(path: RootPath) = OperationResult.Success(emptyList<DirectoryEntry>())
        override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> = error("unused")
        override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> = error("unused")
        override suspend fun createDirectory(parent: RootPath, name: FolderName): OperationResult<DirectoryEntry> = error("unused")
    }
}
