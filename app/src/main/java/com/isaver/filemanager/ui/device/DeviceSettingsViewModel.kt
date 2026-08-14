package com.isaver.filemanager.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DeviceSettingsUiState(
    val loadingStorage: Boolean = true,
    val storageUsage: DeviceStorageUsage? = null,
    val storageError: String? = null,
)

class DeviceSettingsViewModel(
    private val repository: DeviceOverviewRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DeviceSettingsUiState())
    val state: StateFlow<DeviceSettingsUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutableState.value = DeviceSettingsUiState(loadingStorage = true)
        viewModelScope.launch {
            repository.load().fold(
                onSuccess = { mutableState.value = DeviceSettingsUiState(false, it) },
                onFailure = {
                    mutableState.value = DeviceSettingsUiState(
                        loadingStorage = false,
                        storageError = "无法读取存储信息",
                    )
                },
            )
        }
    }
}
