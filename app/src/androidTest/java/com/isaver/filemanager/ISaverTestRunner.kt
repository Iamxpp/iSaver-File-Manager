package com.isaver.filemanager

import androidx.test.runner.AndroidJUnitRunner
import com.isaver.filemanager.data.access.FileAccessMode

class ISaverTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        (targetContext.applicationContext as? ISaverApplication)
            ?.fileAccessController
            ?.activate(FileAccessMode.ROOT)
        super.onStart()
    }
}
