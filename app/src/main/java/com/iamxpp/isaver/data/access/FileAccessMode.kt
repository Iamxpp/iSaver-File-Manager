package com.iamxpp.isaver.data.access

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FileAccessMode {
    ROOT,
    LOCAL_READ_ONLY,
}

class FileAccessController(initialMode: FileAccessMode) {
    private val mutableMode = MutableStateFlow(initialMode)

    val mode: StateFlow<FileAccessMode> = mutableMode.asStateFlow()

    fun activate(mode: FileAccessMode) {
        mutableMode.value = mode
    }
}
