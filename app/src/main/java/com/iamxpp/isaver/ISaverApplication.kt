package com.iamxpp.isaver

import android.app.Application

class ISaverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Detailed libsu logging stays disabled, including in debug builds, to avoid path disclosure.
    }
}
