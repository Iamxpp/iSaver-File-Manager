package com.iamxpp.isaver.texteditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TextEditorUiState(
    val loaded: LoadedTextFile? = null,
    val document: TextDocument? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val exitConfirmation: Boolean = false,
    val draftRestored: Boolean = false,
) {
    val visible get() = loading || loaded != null || error != null
    val dirty get() = loaded != null && document != loaded.document
}

class TextEditorViewModel(
    private val repository: TextEditorRepository,
    private val drafts: TextDraftStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TextEditorUiState())
    val state: StateFlow<TextEditorUiState> = mutableState.asStateFlow()
    private var draftJob: Job? = null
    private var pendingOpen: Pair<DirectoryEntry, RootPath>? = null

    fun open(entry: DirectoryEntry, parent: RootPath) {
        pendingOpen = entry to parent
        mutableState.value = TextEditorUiState(loading = true)
        viewModelScope.launch {
            when (val result = withContext(ioDispatcher) { repository.load(entry, parent) }) {
                is OperationResult.Failure -> mutableState.value = TextEditorUiState(error = result.userMessage)
                is OperationResult.Success -> {
                    val draft = drafts.read(entry.path, result.value.version)
                    mutableState.value = TextEditorUiState(
                        loaded = result.value,
                        document = draft ?: result.value.document,
                        draftRestored = draft != null && draft != result.value.document,
                    )
                }
            }
        }
    }

    fun updateText(text: String) = updateDocument { copy(text = text) }
    fun setEncoding(value: TextEncoding) = updateDocument { copy(encoding = value, hasBom = hasBom && value != TextEncoding.GB18030) }
    fun setLineEnding(value: LineEnding) = updateDocument { copy(lineEnding = value) }
    fun setBom(value: Boolean) = updateDocument { copy(hasBom = value && encoding != TextEncoding.GB18030) }
    fun replaceAll(query: String, replacement: String, matchCase: Boolean): Int {
        val current = mutableState.value.document ?: return 0
        val result = TextSearch.replaceAll(current.text, query, replacement, matchCase)
        if (result.changed) updateText(result.text)
        return result.count
    }

    fun save() {
        val snapshot = mutableState.value
        val loaded = snapshot.loaded ?: return
        val document = snapshot.document ?: return
        if (snapshot.saving) return
        mutableState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val result = withContext(ioDispatcher) { repository.save(loaded, document) }) {
                is OperationResult.Failure -> mutableState.update { it.copy(saving = false, error = result.userMessage) }
                is OperationResult.Success -> {
                    drafts.delete(loaded.entry.path)
                    mutableState.value = TextEditorUiState(loaded = result.value, document = result.value.document)
                }
            }
        }
    }

    fun reload() {
        val loaded = mutableState.value.loaded
        if (loaded != null) drafts.deleteSoon(loaded.entry.path)
        val request = loaded?.let { it.entry to it.parent } ?: pendingOpen ?: return
        open(request.first, request.second)
    }

    fun requestClose() {
        if (mutableState.value.dirty) mutableState.update { it.copy(exitConfirmation = true) }
        else closeNow()
    }
    fun cancelClose() = mutableState.update { it.copy(exitConfirmation = false) }
    fun discardAndClose() {
        mutableState.value.loaded?.let { drafts.deleteSoon(it.entry.path) }
        closeNow()
    }
    fun dismissError() = mutableState.update { it.copy(error = null) }

    private fun updateDocument(transform: TextDocument.() -> TextDocument) {
        mutableState.update { state -> state.document?.let { state.copy(document = transform(it), error = null) } ?: state }
        scheduleDraft()
    }
    private fun scheduleDraft() {
        draftJob?.cancel()
        val snapshot = mutableState.value
        val loaded = snapshot.loaded ?: return
        val document = snapshot.document ?: return
        draftJob = viewModelScope.launch {
            delay(500)
            if (document != loaded.document) drafts.write(loaded.entry.path, loaded.version, document)
            else drafts.delete(loaded.entry.path)
        }
    }
    private fun closeNow() {
        draftJob?.cancel()
        pendingOpen = null
        mutableState.value = TextEditorUiState()
    }
    private fun TextDraftStore.deleteSoon(path: RootPath) { viewModelScope.launch { delete(path) } }
}
