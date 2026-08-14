package com.iamxpp.isaver

import androidx.test.runner.AndroidJUnitRunner
import com.iamxpp.isaver.data.access.FileAccessMode

class ISaverTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        (targetContext.applicationContext as? ISaverApplication)
            ?.fileAccessController
            ?.activate(FileAccessMode.ROOT)
        super.onStart()
    }
}
