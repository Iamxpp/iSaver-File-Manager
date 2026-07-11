package com.iamxpp.isaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamxpp.isaver.data.root.RootSession
import com.iamxpp.isaver.domain.RootStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface RootGateUiState {
    data object Checking : RootGateUiState

    data object Granted : RootGateUiState

    data class Denied(val reason: String) : RootGateUiState
}

class RootGateViewModel(
    private val rootSession: RootSession,
    private val checkDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow<RootGateUiState>(RootGateUiState.Checking)
    private var checkJob: Job? = null
    private var checkGeneration = 0L

    val state: StateFlow<RootGateUiState> = mutableState.asStateFlow()

    init {
        checkRoot()
    }

    fun retry() {
        mutableState.value = RootGateUiState.Checking
        rootSession.invalidate()
        checkRoot()
    }

    private fun checkRoot() {
        val generation = ++checkGeneration
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            try {
                val status = withContext(checkDispatcher) { rootSession.check() }
                if (generation != checkGeneration) return@launch

                when (status) {
                    RootStatus.Available -> mutableState.value = RootGateUiState.Granted
                    is RootStatus.Unavailable -> {
                        mutableState.value = RootGateUiState.Denied(status.reason)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == checkGeneration) {
                    mutableState.value = RootGateUiState.Denied(ROOT_CHECK_FAILED_MESSAGE)
                }
            }
        }
    }

    private companion object {
        const val ROOT_CHECK_FAILED_MESSAGE = "无法确认 Root 权限，请重试"
    }
}
