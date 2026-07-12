package com.iamxpp.isaver

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.iamxpp.isaver.data.local.BrowserPreferencesRepository
import com.iamxpp.isaver.data.local.BrowserPreferencesStore
import com.iamxpp.isaver.data.local.ISaverDatabase
import com.iamxpp.isaver.data.root.LibsuRootSession
import com.iamxpp.isaver.data.root.RootSession
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.data.root.LibsuRootFileSystem
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.locations.CustomLocationRepository
import com.iamxpp.isaver.locations.CustomLocationResult
import com.iamxpp.isaver.locations.LocationId
import com.iamxpp.isaver.locations.LocationResolver
import com.iamxpp.isaver.locations.StorageLocation
import com.iamxpp.isaver.ui.LocationHomeAppResolver
import com.iamxpp.isaver.ui.LocationHomeCustomStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ISaverApplication : Application() {
    private val applicationScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    internal val rootSession: RootSession by lazy { LibsuRootSession() }
    internal val rootFileSystem: RootFileSystem by lazy { LibsuRootFileSystem() }
    internal val database: ISaverDatabase by lazy {
        Room.databaseBuilder(this, ISaverDatabase::class.java, DATABASE_NAME).build()
    }
    internal val customLocationRepository: CustomLocationRepository by lazy {
        CustomLocationRepository(
            dao = database.customLocationDao(),
            idFactory = { LocationId.of(UUID.randomUUID().toString()) },
            clock = System::currentTimeMillis,
        )
    }
    internal val locationResolver: LocationResolver by lazy {
        LocationResolver(rootFileSystem, Dispatchers.IO)
    }
    internal val locationHomeAppResolver: LocationHomeAppResolver by lazy {
        LocationHomeAppResolver(locationResolver::resolve)
    }
    internal val locationHomeCustomStore: LocationHomeCustomStore by lazy {
        CustomLocationStoreAdapter(customLocationRepository)
    }
    internal val browserPreferencesStore: BrowserPreferencesStore by lazy {
        BrowserPreferencesRepository(
            PreferenceDataStoreFactory.create(
                scope = applicationScope,
                produceFile = { preferencesDataStoreFile("browser.preferences_pb") },
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Detailed libsu logging stays disabled, including in debug builds, to avoid path disclosure.
    }

    private companion object {
        const val DATABASE_NAME = "isaver.db"
    }
}

internal class CustomLocationStoreAdapter(
    private val repository: CustomLocationRepository,
) : LocationHomeCustomStore {
    override fun observeAll(): Flow<List<StorageLocation.Direct>> = repository.observeAll()

    override suspend fun add(name: String, path: RootPath): CustomLocationResult = repository.add(name, path)

    override suspend fun update(
        id: LocationId,
        name: String,
        path: RootPath,
    ): CustomLocationResult = repository.update(id, name, path)

    override suspend fun remove(id: LocationId): CustomLocationResult = repository.remove(id)
}
