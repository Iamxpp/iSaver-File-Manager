package com.iamxpp.isaver

import android.app.Application
import com.iamxpp.isaver.data.root.LibsuRootSession
import com.iamxpp.isaver.data.root.RootSession
import com.iamxpp.isaver.data.root.LibsuRootFileSystem
import com.iamxpp.isaver.data.root.RootFileSystem

class ISaverApplication : Application() {
    internal val rootSession: RootSession by lazy { LibsuRootSession() }
    internal val rootFileSystem: RootFileSystem by lazy { LibsuRootFileSystem() }

    override fun onCreate() {
        super.onCreate()
        // Detailed libsu logging stays disabled, including in debug builds, to avoid path disclosure.
    }
}
