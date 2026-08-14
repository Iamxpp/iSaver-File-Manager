package com.isaver.filemanager.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface RemoteConnectionUiState {
    data object Idle : RemoteConnectionUiState
    data object Connecting : RemoteConnectionUiState
    data class Connected(
        val host: String,
        val path: RemotePath = RemotePath.parse("/").getOrThrow(),
        val entries: List<RemoteEntry> = emptyList(),
    ) : RemoteConnectionUiState
    data class Error(val message: String) : RemoteConnectionUiState
}

class RemoteConnectionViewModel(
    private val credentialStore: CredentialStore,
    private val connector: RemoteConnector,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow<RemoteConnectionUiState>(RemoteConnectionUiState.Idle)
    private var activeSession: RemoteSession? = null
    val state: StateFlow<RemoteConnectionUiState> = mutableState.asStateFlow()

    fun connect(draft: RemoteConnectionDraft) {
        if (mutableState.value == RemoteConnectionUiState.Connecting) return
        mutableState.value = RemoteConnectionUiState.Connecting
        viewModelScope.launch {
            val secretRef = "remote-${UUID.randomUUID()}"
            try {
                val profile = draft.toProfile(UUID.randomUUID().toString(), secretRef).getOrThrow()
                withContext(ioDispatcher) { credentialStore.put(secretRef, draft.password) }
                val session = withContext(ioDispatcher) { connector.connect(profile).getOrThrow() }
                activeSession?.close()
                activeSession = session
                val entries = withContext(ioDispatcher) { session.list(profile.remoteRoot).getOrThrow() }
                mutableState.value = RemoteConnectionUiState.Connected(profile.host, profile.remoteRoot, entries)
            } catch (cancelled: CancellationException) {
                withContext(Dispatchers.IO) { credentialStore.remove(secretRef) }
                mutableState.value = RemoteConnectionUiState.Idle
                throw cancelled
            } catch (error: Exception) {
                withContext(ioDispatcher) { credentialStore.remove(secretRef) }
                mutableState.value = RemoteConnectionUiState.Error(error.message ?: "无法连接服务器")
            }
        }
    }

    fun clearMessage() {
        if (mutableState.value !is RemoteConnectionUiState.Connecting) {
            mutableState.value = RemoteConnectionUiState.Idle
        }
    }

    fun refreshRemote() {
        val connected = mutableState.value as? RemoteConnectionUiState.Connected ?: return
        val session = activeSession ?: return
        viewModelScope.launch {
            runCatching { withContext(ioDispatcher) { session.list(connected.path).getOrThrow() } }
                .onSuccess { entries -> mutableState.value = connected.copy(entries = entries) }
                .onFailure { error -> mutableState.value = RemoteConnectionUiState.Error(error.message ?: "远程目录读取失败") }
        }
    }

    fun createRemoteDirectory(name: String) {
        val connected = mutableState.value as? RemoteConnectionUiState.Connected ?: return
        val session = activeSession ?: return
        val entry = RemoteEntryName.parse(name).getOrElse {
            mutableState.value = RemoteConnectionUiState.Error("远程文件夹名称无效")
            return
        }
        val child = RemotePath.parse(
            if (connected.path.value == "/") "/${entry.value}" else "${connected.path.value.trimEnd('/')}/${entry.value}",
        ).getOrElse {
            mutableState.value = RemoteConnectionUiState.Error("远程文件夹名称无效")
            return
        }
        viewModelScope.launch {
            runCatching { withContext(ioDispatcher) { session.createDirectory(child).getOrThrow() } }
                .onSuccess { refreshRemote() }
                .onFailure { error -> mutableState.value = RemoteConnectionUiState.Error(error.message ?: "远程新建文件夹失败") }
        }
    }

    override fun onCleared() {
        activeSession?.close()
    }
}
