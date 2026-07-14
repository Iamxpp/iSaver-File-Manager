package com.iamxpp.isaver.transfer

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.RootPath

data class ShareSummary(
    val displayName: String,
    val sizeBytes: Long?,
    val mimeType: String?,
)

sealed interface TransferUiState {
    data object Idle : TransferUiState
    data object Parsing : TransferUiState

    data class Caching(
        override val share: ShareSummary,
        override val outputName: OutputNameDraft,
        val bytesCopied: Long,
        val targetDirectory: RootPath? = null,
        val targetMessage: String? = null,
    ) : TransferUiState, ActiveTransferUiState

    data class Choosing(
        override val share: ShareSummary,
        override val outputName: OutputNameDraft,
        val cachedBytes: Long,
        val targetDirectory: RootPath? = null,
        val canSave: Boolean = false,
        val targetMessage: String? = null,
    ) : TransferUiState, ActiveTransferUiState

    data class ValidatingTarget(
        override val share: ShareSummary,
        override val outputName: OutputNameDraft,
        val cachedBytes: Long,
        val targetDirectory: RootPath,
    ) : TransferUiState, ActiveTransferUiState

    data class Saving(
        override val share: ShareSummary,
        override val outputName: OutputNameDraft,
        val cachedBytes: Long,
        val targetDirectory: RootPath,
        val phase: TransferPhase,
    ) : TransferUiState, ActiveTransferUiState

    data class Cancelling(
        override val share: ShareSummary,
        override val outputName: OutputNameDraft,
        val targetDirectory: RootPath,
    ) : TransferUiState, ActiveTransferUiState

    data class Reconciliation(
        override val share: ShareSummary,
        override val outputName: OutputNameDraft,
        val targetDirectory: RootPath,
        val queuedPending: Boolean,
    ) : TransferUiState, ActiveTransferUiState

    data class Success(
        override val share: ShareSummary,
        override val outputName: OutputNameDraft,
        val savedEntry: DirectoryEntry,
        val cleanupWarning: String? = null,
    ) : TransferUiState, ActiveTransferUiState

    data class Failure(
        override val share: ShareSummary?,
        override val outputName: OutputNameDraft?,
        val targetDirectory: RootPath?,
        val code: ErrorCode?,
        val message: String,
        val retryable: Boolean,
        val requiresReshare: Boolean = false,
        val queuedPending: Boolean = false,
    ) : TransferUiState, NullableTransferUiState

    data class Uncertain(
        override val share: ShareSummary,
        override val outputName: OutputNameDraft,
        val targetDirectory: RootPath,
        val message: String,
        val queuedPending: Boolean = false,
    ) : TransferUiState, ActiveTransferUiState

    data class RequiresReshare(
        override val share: ShareSummary?,
        override val outputName: OutputNameDraft?,
        val message: String,
        val uncertainPrevious: Boolean = false,
    ) : TransferUiState, NullableTransferUiState
}

sealed interface ActiveTransferUiState {
    val share: ShareSummary
    val outputName: OutputNameDraft
}

sealed interface NullableTransferUiState {
    val share: ShareSummary?
    val outputName: OutputNameDraft?
}

sealed interface TransferPhase {
    data object ResolvingName : TransferPhase
    data class Publishing(val candidateName: String, val attempt: Int) : TransferPhase
}
