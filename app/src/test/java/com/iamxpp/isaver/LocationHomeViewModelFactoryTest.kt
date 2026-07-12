package com.iamxpp.isaver

import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.locations.CustomLocationResult
import com.iamxpp.isaver.locations.LocationId
import com.iamxpp.isaver.locations.ResolvedAppLocation
import com.iamxpp.isaver.locations.StorageLocation
import com.iamxpp.isaver.ui.LocationHomeAppResolver
import com.iamxpp.isaver.ui.LocationHomeCustomStore
import com.iamxpp.isaver.ui.LocationHomeViewModel
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
