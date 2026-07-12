package com.iamxpp.isaver

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.iamxpp.isaver.data.local.BrowserPreferencesRepository
import com.iamxpp.isaver.data.local.BrowserPreferencesStore
import com.iamxpp.isaver.data.root.LibsuRootSession
import com.iamxpp.isaver.data.root.RootSession
import com.iamxpp.isaver.data.root.LibsuRootFileSystem
import com.iamxpp.isaver.data.root.RootFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ISaverApplication : Application() {
    private val applicationScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    internal val rootSession: RootSession by lazy { LibsuRootSession() }
    internal val rootFileSystem: RootFileSystem by lazy { LibsuRootFileSystem() }
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
}
