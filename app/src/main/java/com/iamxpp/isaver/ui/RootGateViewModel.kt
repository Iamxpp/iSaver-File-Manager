package com.iamxpp.isaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamxpp.isaver.data.root.RootSession
import com.iamxpp.isaver.data.access.FileAccessController
import com.iamxpp.isaver.data.access.FileAccessMode
import com.iamxpp.isaver.data.access.FileAccessModeStore
import com.iamxpp.isaver.domain.RootStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface RootGateUiState {
    data object Checking : RootGateUiState

    data object Granted : RootGateUiState

    data class ReadOnly(val reason: String? = null) : RootGateUiState

    data class Denied(val reason: String) : RootGateUiState
}

class RootGateViewModel(
    private val rootSession: RootSession,
    private val checkDispatcher: CoroutineDispatcher,
    private val modeStore: FileAccessModeStore = DefaultRootModeStore,
    private val accessController: FileAccessController = FileAccessController(FileAccessMode.LOCAL_READ_ONLY),
) : ViewModel() {
    private val mutableState = MutableStateFlow<RootGateUiState>(RootGateUiState.Checking)
    private var checkJob: Job? = null
    private var checkGeneration = 0L
    private val orchestrationMutex = Mutex()

    val state: StateFlow<RootGateUiState> = mutableState.asStateFlow()

    init {
        orchestrateStartup()
    }

    fun retry() {
        setRootEnabled(true)
    }

    fun setRootEnabled(enabled: Boolean) {
        if (!enabled) {
            val generation = ++checkGeneration
            accessController.activate(FileAccessMode.LOCAL_READ_ONLY)
            mutableState.value = RootGateUiState.ReadOnly()
            viewModelScope.launch {
                orchestrationMutex.withLock {
                    checkJob?.cancelAndJoin()
                    checkJob = null
                    if (generation == checkGeneration) {
                        persistMode(FileAccessMode.LOCAL_READ_ONLY)
                    }
                }
            }
            return
        }
        mutableState.value = RootGateUiState.Checking
        orchestrateCheck(invalidateSession = true)
    }

    private fun orchestrateStartup() {
        val generation = ++checkGeneration
        viewModelScope.launch {
            val preferredMode = try {
                withContext(checkDispatcher) { modeStore.load() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                FileAccessMode.ROOT
            }
            if (generation != checkGeneration) return@launch
            if (preferredMode == FileAccessMode.LOCAL_READ_ONLY) {
                accessController.activate(FileAccessMode.LOCAL_READ_ONLY)
                mutableState.value = RootGateUiState.ReadOnly()
                return@launch
            }
            orchestrationMutex.withLock {
                if (generation == checkGeneration) checkJob = launchCheck(generation)
            }
        }
    }

    private fun orchestrateCheck(invalidateSession: Boolean) {
        val generation = ++checkGeneration
        viewModelScope.launch {
            orchestrationMutex.withLock {
                checkJob?.cancelAndJoin()
                checkJob = null

                if (generation != checkGeneration) return@withLock
                if (invalidateSession) {
                    try {
                        rootSession.invalidate()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (generation == checkGeneration) {
                            fallBackToReadOnly(ROOT_CHECK_FAILED_MESSAGE)
                        }
                        return@withLock
                    }
                }
                if (generation != checkGeneration) return@withLock

                checkJob = launchCheck(generation)
            }
        }
    }

    private fun launchCheck(generation: Long): Job = viewModelScope.launch {
        try {
            val status = withContext(checkDispatcher) { rootSession.check() }
            if (generation != checkGeneration) return@launch

            when (status) {
                RootStatus.Available -> {
                    accessController.activate(FileAccessMode.ROOT)
                    persistMode(FileAccessMode.ROOT)
                    mutableState.value = RootGateUiState.Granted
                }
                is RootStatus.Unavailable -> {
                    fallBackToReadOnly(status.reason)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (generation == checkGeneration) {
                fallBackToReadOnly(ROOT_CHECK_FAILED_MESSAGE)
            }
        }
    }

    private suspend fun fallBackToReadOnly(reason: String) {
        accessController.activate(FileAccessMode.LOCAL_READ_ONLY)
        persistMode(FileAccessMode.LOCAL_READ_ONLY)
        mutableState.value = RootGateUiState.ReadOnly(reason)
    }

    private suspend fun persistMode(mode: FileAccessMode) {
        try {
            withContext(checkDispatcher) { modeStore.save(mode) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The active in-memory mode remains authoritative for this process.
        }
    }

    private companion object {
        const val ROOT_CHECK_FAILED_MESSAGE = "无法确认 Root 权限，请重试"

        object DefaultRootModeStore : FileAccessModeStore {
            override suspend fun load() = FileAccessMode.ROOT
            override suspend fun save(mode: FileAccessMode) = Unit
        }
    }
}
