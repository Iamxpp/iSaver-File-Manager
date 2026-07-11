package com.iamxpp.isaver

import android.app.Application
import com.iamxpp.isaver.data.root.LibsuRootSession
import com.iamxpp.isaver.data.root.RootSession

class ISaverApplication : Application() {
    internal val rootSession: RootSession by lazy { LibsuRootSession() }

    override fun onCreate() {
        super.onCreate()
        // Detailed libsu logging stays disabled, including in debug builds, to avoid path disclosure.
    }
}
