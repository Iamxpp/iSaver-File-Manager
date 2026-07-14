package com.iamxpp.isaver.transfer

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.share.IncomingShare
import com.iamxpp.isaver.share.ShareIntentFailureReason
import com.iamxpp.isaver.share.ShareIntentParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TransferViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val parseShare: suspend (Intent) -> ShareIntentParseResult,
    private val validateTarget: suspend (RootPath) -> OperationResult<RootPath>,
    private val cacheIncoming: suspend (IncomingShare, (Long) -> Unit) -> IncomingFileCacheResult,
    private val validateCache: suspend (CachedIncomingFile) -> Boolean,
    private val cleanupIncoming: suspend (CachedIncomingFile) -> Boolean,
    private val transferCached: (
        CachedIncomingFile,
        OutputNameDraft,
        RootPath,
        () -> Boolean,
    ) -> Flow<TransferState>,
    private val recordSaved: suspend (DirectoryEntry) -> Unit,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    @Inject
    internal constructor(
        savedStateHandle: SavedStateHandle,
        dependencies: TransferDependencies,
    ) : this(
        savedStateHandle = savedStateHandle,
        parseShare = dependencies.parseShare,
        validateTarget = dependencies.validateTarget,
        cacheIncoming = dependencies.cacheIncoming,
        validateCache = dependencies.validateCache,
        cleanupIncoming = dependencies.cleanupIncoming,
        transferCached = dependencies.transferCached,
        recordSaved = dependencies.recordSaved,
        workDispatcher = dependencies.workDispatcher,
    )

    private val mutableState = MutableStateFlow<TransferUiState>(restoreState())
    private var active: TransferRequest? = null
    private var queued: TransferRequest? = null
    private var nextGeneration = 0L
    private var targetValidationJob: Job? = null
    private var operationJob: Job? = null
    private var intentJob: Job? = null
    private var publishInFlight = false
    private var cancelRequested = false
    private var lastRetryableIntent: Intent? = null

    val state: StateFlow<TransferUiState> = mutableState.asStateFlow()

    fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_VIEW) return
        val retainedIntent = Intent(intent)
        intentJob?.cancel()
        if (!isProtectedActive()) mutableState.value = TransferUiState.Parsing
        intentJob = viewModelScope.launch {
            val result = try {
                withContext(workDispatcher) { parseShare(retainedIntent) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ShareIntentParseResult.Failure(
                    ShareIntentFailureReason.INVALID_SHARE,
                    "分享文件信息无效",
                )
            }
            when (result) {
                is ShareIntentParseResult.Success -> {
                    lastRetryableIntent = null
                    acceptShare(result.share)
                }
                is ShareIntentParseResult.Failure -> acceptParseFailure(result.reason, retainedIntent)
            }
        }
    }

    fun acceptShare(share: IncomingShare) {
        val request = TransferRequest(
            generation = ++nextGeneration,
            share = share,
            summary = share.toSummary(),
            outputName = OutputNameDraft.fromDisplayName(share.displayName),
        )
        persistSummary(request.summary)

        if (publishInFlight || isProtectedTerminal()) {
            replaceQueued(request)
            showProtectedStateWithQueue()
            startCaching(request)
            return
        }

        replaceActive(request)
        startCaching(request)
    }

    fun selectTarget(path: RootPath) {
        val request = active ?: return
        if (publishInFlight || mutableState.value is TransferUiState.Uncertain) return
        request.targetDirectory = path
        request.validatedCanonical = null
        request.targetMessage = null
        val validationGeneration = ++request.targetValidationGeneration
        render(request, validating = request.cached != null)
        targetValidationJob?.cancel()
        targetValidationJob = viewModelScope.launch {
            val result = safeValidateTarget(path)
            if (active !== request || validationGeneration != request.targetValidationGeneration) return@launch
            when (result) {
                is OperationResult.Success -> {
                    request.validatedCanonical = result.value
                    request.targetMessage = null
                }
                is OperationResult.Failure -> {
                    request.validatedCanonical = null
                    request.targetMessage = safeTargetMessage(result.code)
                }
            }
            render(request)
        }
    }

    fun setStem(stem: String) = updateOutputName { it.copy(stem = stem) }

    fun setExtension(extension: String) = updateOutputName { it.copy(extension = extension) }

    fun save() {
        val request = active ?: return
        val cached = request.cached ?: return
        val target = request.targetDirectory ?: return
        val selectedCanonical = request.validatedCanonical ?: return
        if (request.outputName.toEntryName().isFailure || publishInFlight) return

        targetValidationJob?.cancel()
        operationJob?.cancel()
        mutableState.value = TransferUiState.ValidatingTarget(
            share = request.summary,
            outputName = request.outputName,
            cachedBytes = cached.sizeBytes,
            targetDirectory = target,
        )
        operationJob = viewModelScope.launch {
            val cacheValid = try {
                withContext(workDispatcher) { validateCache(cached) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (active !== request) return@launch
            if (!cacheValid) {
                cleanupOwnedCache(request)
                mutableState.value = TransferUiState.RequiresReshare(
                    share = request.summary,
                    outputName = request.outputName,
                    message = "分享文件缓存已变化，请重新分享",
                )
                return@launch
            }

            val currentTarget = safeValidateTarget(target)
            if (active !== request) return@launch
            if (currentTarget !is OperationResult.Success || currentTarget.value != selectedCanonical) {
                request.validatedCanonical = null
                request.targetMessage = "目标文件夹已变化，请重新选择"
                render(request)
                return@launch
            }

            mutableState.value = TransferUiState.Saving(
                share = request.summary,
                outputName = request.outputName,
                cachedBytes = cached.sizeBytes,
                targetDirectory = target,
                phase = TransferPhase.ResolvingName,
            )
            cancelRequested = false
            try {
                transferCached(cached, request.outputName, target) {
                    !cancelRequested && queued == null
                }.collect { transfer ->
                    when (transfer) {
                        TransferState.Resolving -> Unit
                        is TransferState.Publishing -> {
                            publishInFlight = true
                            if (queued == null && !cancelRequested) {
                                mutableState.value = TransferUiState.Saving(
                                    share = request.summary,
                                    outputName = request.outputName,
                                    cachedBytes = cached.sizeBytes,
                                    targetDirectory = target,
                                    phase = TransferPhase.Publishing(
                                        candidateName = transfer.candidate.value,
                                        attempt = transfer.attempt,
                                    ),
                                )
                            }
                        }
                        is TransferState.Success -> {
                            publishInFlight = false
                            finishSuccess(request, transfer)
                        }
                        is TransferState.Failure -> {
                            publishInFlight = false
                            finishFailure(request, transfer)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (active === request && !publishInFlight) render(request)
                throw cancelled
            } catch (_: Exception) {
                if (active === request) {
                    finishFailure(
                        request,
                        TransferState.Failure(ErrorCode.COMMAND_FAILED, "保存失败，请稍后重试"),
                    )
                }
            } finally {
                publishInFlight = false
                cancelRequested = false
            }
        }
    }

    fun retry() {
        val failure = mutableState.value as? TransferUiState.Failure ?: return
        val request = active ?: return
        if (!failure.retryable || failure.requiresReshare || request.cached == null) return
        render(request)
        save()
    }

    fun continueWithQueued() {
        val failure = mutableState.value as? TransferUiState.Failure ?: return
        if (!failure.queuedPending || queued == null) return
        viewModelScope.launch {
            active?.let { cleanupOwnedCache(it) }
            activateQueuedOrIdle()
        }
    }

    fun acknowledgeUncertain() {
        if (mutableState.value !is TransferUiState.Uncertain) return
        viewModelScope.launch {
            active?.let { cleanupOwnedCache(it) }
            activateQueuedOrIdle()
        }
    }

    fun cancel() {
        val request = active ?: return
        if (publishInFlight) {
            cancelRequested = true
            val target = request.targetDirectory ?: return
            mutableState.value = TransferUiState.Cancelling(
                share = request.summary,
                outputName = request.outputName,
                targetDirectory = target,
            )
            return
        }
        operationJob?.cancel()
        targetValidationJob?.cancel()
        viewModelScope.launch {
            discard(request)
            if (active === request) active = null
            activateQueuedOrIdle()
        }
    }

    fun exitRootGate() {
        if (publishInFlight) {
            cancel()
            return
        }
        operationJob?.cancel()
        targetValidationJob?.cancel()
        intentJob?.cancel()
        val oldActive = active
        val oldQueued = queued
        active = null
        queued = null
        viewModelScope.launch {
            oldActive?.let { discard(it) }
            oldQueued?.let { discard(it) }
            clearSavedSummary()
            mutableState.value = TransferUiState.Idle
        }
    }

    private fun startCaching(request: TransferRequest) {
        if (active === request) render(request)
        request.cacheJob = viewModelScope.launch {
            val result = try {
                withContext(workDispatcher) {
                    cacheIncoming(request.share) { copied ->
                        request.bytesCopied = copied
                        if (active === request && !isProtectedTerminal()) render(request)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                IncomingFileCacheResult.Failure(IncomingFileCacheFailure.CACHE_WRITE_FAILED)
            }
            when (result) {
                is IncomingFileCacheResult.Success -> {
                    request.cached = result.file
                    request.bytesCopied = result.file.sizeBytes
                    if (active === request && !isProtectedTerminal()) render(request)
                }
                is IncomingFileCacheResult.Failure -> handleCacheFailure(request, result.reason)
            }
        }
    }

    private fun handleCacheFailure(request: TransferRequest, reason: IncomingFileCacheFailure) {
        if (queued === request) {
            queued = null
            return
        }
        if (active !== request) return
        val requiresReshare = reason == IncomingFileCacheFailure.SOURCE_UNREADABLE ||
            reason == IncomingFileCacheFailure.SIZE_MISMATCH
        mutableState.value = if (requiresReshare) {
            TransferUiState.RequiresReshare(
                share = request.summary,
                outputName = request.outputName,
                message = if (reason == IncomingFileCacheFailure.SIZE_MISMATCH) {
                    "分享文件内容不完整，请重新分享"
                } else {
                    "无法读取分享文件，请重新分享"
                },
            )
        } else {
            TransferUiState.Failure(
                share = request.summary,
                outputName = request.outputName,
                targetDirectory = request.targetDirectory,
                code = if (reason == IncomingFileCacheFailure.NO_SPACE) ErrorCode.NO_SPACE else ErrorCode.COMMAND_FAILED,
                message = if (reason == IncomingFileCacheFailure.NO_SPACE) {
                    "缓存空间不足，无法保存文件"
                } else {
                    "无法准备分享文件，请重试"
                },
                retryable = true,
            )
        }
    }

    private suspend fun finishSuccess(request: TransferRequest, transfer: TransferState.Success) {
        if (active !== request) return
        val recentWarning = try {
            withContext(workDispatcher) { recordSaved(transfer.entry) }
            null
        } catch (_: Exception) {
            "文件已保存，但无法更新最近项目"
        }
        val cleanupWarning = cleanupOwnedCache(request)
        if (queued != null) {
            activateQueuedOrIdle()
        } else {
            mutableState.value = TransferUiState.Success(
                share = request.summary,
                outputName = request.outputName,
                savedEntry = transfer.entry,
                cleanupWarning = transfer.cleanupWarning ?: cleanupWarning ?: recentWarning,
            )
        }
    }

    private suspend fun finishFailure(request: TransferRequest, transfer: TransferState.Failure) {
        if (active !== request) return
        if (transfer.code == ErrorCode.OUTCOME_UNCERTAIN) {
            mutableState.value = TransferUiState.Uncertain(
                share = request.summary,
                outputName = request.outputName,
                targetDirectory = requireNotNull(request.targetDirectory),
                message = "无法确认文件是否已保存，请检查目标文件夹",
                queuedPending = queued != null,
            )
            return
        }
        if (transfer.code == ErrorCode.CANCELLED && (cancelRequested || queued != null)) {
            cleanupOwnedCache(request)
            activateQueuedOrIdle()
            return
        }
        val retryable = transfer.code in RETRYABLE_PUBLISH_ERRORS
        if (!retryable) {
            cleanupOwnedCache(request)
            if (queued != null) {
                activateQueuedOrIdle()
                return
            }
        }
        mutableState.value = TransferUiState.Failure(
            share = request.summary,
            outputName = request.outputName,
            targetDirectory = request.targetDirectory,
            code = transfer.code,
            message = safeFailureMessage(transfer.code),
            retryable = retryable,
            requiresReshare = transfer.code == ErrorCode.SOURCE_UNREADABLE,
            queuedPending = retryable && queued != null,
        )
    }

    private fun updateOutputName(transform: (OutputNameDraft) -> OutputNameDraft) {
        val request = active ?: return
        if (publishInFlight || mutableState.value is TransferUiState.Uncertain) return
        request.outputName = transform(request.outputName)
        when (val current = mutableState.value) {
            is TransferUiState.Failure -> mutableState.value = current.copy(outputName = request.outputName)
            else -> render(request)
        }
    }

    private fun render(request: TransferRequest, validating: Boolean = false) {
        if (active !== request) return
        val cached = request.cached
        if (cached == null) {
            mutableState.value = TransferUiState.Caching(
                share = request.summary,
                outputName = request.outputName,
                bytesCopied = request.bytesCopied,
                targetDirectory = request.targetDirectory,
                targetMessage = request.targetMessage,
            )
            return
        }
        val target = request.targetDirectory
        if (validating && target != null) {
            mutableState.value = TransferUiState.ValidatingTarget(
                share = request.summary,
                outputName = request.outputName,
                cachedBytes = cached.sizeBytes,
                targetDirectory = target,
            )
            return
        }
        mutableState.value = TransferUiState.Choosing(
            share = request.summary,
            outputName = request.outputName,
            cachedBytes = cached.sizeBytes,
            targetDirectory = target,
            canSave = target != null &&
                request.validatedCanonical != null &&
                request.outputName.toEntryName().isSuccess,
            targetMessage = request.targetMessage,
        )
    }

    private fun replaceActive(request: TransferRequest) {
        operationJob?.cancel()
        targetValidationJob?.cancel()
        val previous = active
        active = request
        previous?.let { viewModelScope.launch { discard(it) } }
    }

    private fun replaceQueued(request: TransferRequest) {
        val previous = queued
        queued = request
        previous?.let { viewModelScope.launch { discard(it) } }
    }

    private fun showProtectedStateWithQueue() {
        val request = active ?: return
        when (val current = mutableState.value) {
            is TransferUiState.Uncertain -> mutableState.value = current.copy(queuedPending = true)
            is TransferUiState.Failure -> mutableState.value = current.copy(queuedPending = true)
            else -> request.targetDirectory?.let { target ->
                mutableState.value = TransferUiState.Reconciliation(
                    share = request.summary,
                    outputName = request.outputName,
                    targetDirectory = target,
                    queuedPending = true,
                )
            }
        }
    }

    private suspend fun activateQueuedOrIdle() {
        val next = queued
        queued = null
        active = next
        if (next == null) {
            clearSavedSummary()
            mutableState.value = TransferUiState.Idle
        } else {
            persistSummary(next.summary)
            render(next)
        }
    }

    private suspend fun discard(request: TransferRequest) {
        request.cacheJob?.cancel()
        cleanupOwnedCache(request)
    }

    private suspend fun cleanupOwnedCache(request: TransferRequest): String? {
        val cached = request.cached ?: return null
        request.cached = null
        return try {
            if (withContext(workDispatcher) { cleanupIncoming(cached) }) null else CLEANUP_WARNING
        } catch (_: Exception) {
            CLEANUP_WARNING
        }
    }

    private suspend fun safeValidateTarget(path: RootPath): OperationResult<RootPath> = try {
        withContext(workDispatcher) { validateTarget(path) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法验证目标文件夹")
    }

    private fun acceptParseFailure(reason: ShareIntentFailureReason, intent: Intent) {
        val retryable = reason == ShareIntentFailureReason.PROVIDER_TIMEOUT
        lastRetryableIntent = if (retryable) Intent(intent) else null
        if (publishInFlight || isProtectedTerminal()) return
        val current = active
        current?.let { viewModelScope.launch { discard(it) } }
        active = null
        mutableState.value = TransferUiState.Failure(
            share = current?.summary,
            outputName = current?.outputName,
            targetDirectory = current?.targetDirectory,
            code = if (reason == ShareIntentFailureReason.SOURCE_UNREADABLE) {
                ErrorCode.SOURCE_UNREADABLE
            } else {
                ErrorCode.COMMAND_FAILED
            },
            message = when (reason) {
                ShareIntentFailureReason.UNSUPPORTED_INTENT -> "仅支持分享或打开单个文件"
                ShareIntentFailureReason.MISSING_STREAM -> "未接收到文件"
                ShareIntentFailureReason.INVALID_SHARE -> "分享文件信息无效"
                ShareIntentFailureReason.UNSUPPORTED_URI -> "仅支持安全的内容 Uri"
                ShareIntentFailureReason.PROVIDER_TIMEOUT -> "来源应用响应超时，请重试"
                ShareIntentFailureReason.SOURCE_UNREADABLE -> "无法读取来源文件，请重新分享"
            },
            retryable = retryable,
            requiresReshare = !retryable,
        )
    }

    private fun isProtectedActive(): Boolean = publishInFlight || isProtectedTerminal()

    private fun isProtectedTerminal(): Boolean = when (val current = mutableState.value) {
        is TransferUiState.Uncertain -> true
        is TransferUiState.Failure -> current.retryable && active?.cached != null
        else -> false
    }

    private fun safeTargetMessage(code: ErrorCode): String = when (code) {
        ErrorCode.ROOT_DENIED, ErrorCode.ROOT_UNAVAILABLE -> "Root 权限不可用，请重新授权"
        ErrorCode.NOT_WRITABLE -> "目标文件夹不可写"
        else -> "请选择可写的真实文件夹"
    }

    private fun safeFailureMessage(code: ErrorCode): String = when (code) {
        ErrorCode.ROOT_DENIED, ErrorCode.ROOT_UNAVAILABLE -> "Root 权限不可用，请重新授权后再试"
        ErrorCode.NO_SPACE -> "存储空间不足，无法保存文件"
        ErrorCode.SOURCE_UNREADABLE -> "无法读取分享文件，请重新分享"
        ErrorCode.NOT_WRITABLE -> "目标文件夹不可写"
        ErrorCode.CANCELLED -> "保存已取消"
        else -> "保存失败，请稍后重试"
    }

    private fun persistSummary(summary: ShareSummary) {
        savedStateHandle[KEY_PENDING] = true
        savedStateHandle[KEY_DISPLAY_NAME] = summary.displayName
        if (summary.sizeBytes == null) savedStateHandle.remove<Long>(KEY_SIZE_BYTES)
        else savedStateHandle[KEY_SIZE_BYTES] = summary.sizeBytes
        if (summary.mimeType == null) savedStateHandle.remove<String>(KEY_MIME_TYPE)
        else savedStateHandle[KEY_MIME_TYPE] = summary.mimeType
    }

    private fun clearSavedSummary() {
        SAVED_KEYS.forEach { savedStateHandle.remove<Any>(it) }
    }

    private fun restoreState(): TransferUiState {
        if (savedStateHandle.get<Boolean>(KEY_PENDING) != true) return TransferUiState.Idle
        return try {
            val displayName = savedStateHandle.get<String>(KEY_DISPLAY_NAME)
                ?.takeIf(String::isNotBlank)
                ?: error("missing display name")
            val summary = ShareSummary(
                displayName = displayName,
                sizeBytes = savedStateHandle.get<Long>(KEY_SIZE_BYTES),
                mimeType = savedStateHandle.get<String>(KEY_MIME_TYPE),
            )
            TransferUiState.RequiresReshare(
                share = summary,
                outputName = OutputNameDraft.fromDisplayName(displayName),
                message = "请重新分享文件后再保存",
                uncertainPrevious = savedStateHandle.get<Boolean>(KEY_UNCERTAIN) == true,
            )
        } catch (_: Exception) {
            clearSavedSummary()
            TransferUiState.Idle
        }
    }

    private fun IncomingShare.toSummary() = ShareSummary(displayName, sizeBytes, mimeType)

    private data class TransferRequest(
        val generation: Long,
        val share: IncomingShare,
        val summary: ShareSummary,
        var outputName: OutputNameDraft,
        var bytesCopied: Long = 0L,
        var cached: CachedIncomingFile? = null,
        var targetDirectory: RootPath? = null,
        var validatedCanonical: RootPath? = null,
        var targetMessage: String? = null,
        var targetValidationGeneration: Long = 0L,
        var cacheJob: Job? = null,
    )

    private companion object {
        const val KEY_PENDING = "transfer.pending"
        const val KEY_DISPLAY_NAME = "transfer.displayName"
        const val KEY_SIZE_BYTES = "transfer.sizeBytes"
        const val KEY_MIME_TYPE = "transfer.mimeType"
        const val KEY_UNCERTAIN = "transfer.uncertain"
        const val CLEANUP_WARNING = "临时缓存清理失败，请稍后重试"
        val SAVED_KEYS = listOf(
            KEY_PENDING,
            KEY_DISPLAY_NAME,
            KEY_SIZE_BYTES,
            KEY_MIME_TYPE,
            KEY_UNCERTAIN,
        )
        val RETRYABLE_PUBLISH_ERRORS = setOf(
            ErrorCode.NO_SPACE,
            ErrorCode.NOT_WRITABLE,
            ErrorCode.COMMAND_FAILED,
            ErrorCode.ROOT_DENIED,
            ErrorCode.ROOT_UNAVAILABLE,
        )
    }
}
