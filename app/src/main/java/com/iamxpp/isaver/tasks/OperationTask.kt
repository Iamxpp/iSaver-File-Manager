package com.iamxpp.isaver.tasks

import kotlinx.coroutines.flow.Flow

enum class OperationTaskType { COPY, MOVE, DELETE, ARCHIVE, EXTRACT, CHECKSUM, SEARCH }

enum class OperationTaskState {
    QUEUED,
    RUNNING,
    PAUSED,
    CANCELLING,
    NEEDS_ACTION,
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED,
    OUTCOME_UNCERTAIN,
    NEEDS_REVIEW,
}

enum class OperationRecoveryPolicy { NEVER_REPLAY, READ_ONLY_RESTARTABLE }

data class OperationTask(
    val id: String,
    val type: OperationTaskType,
    val state: OperationTaskState,
    val totalItems: Int,
    val completedItems: Int,
    val failedItems: Int,
    val totalBytes: Long?,
    val completedBytes: Long,
    val recoveryPolicy: OperationRecoveryPolicy,
    val message: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

interface OperationTaskStore {
    val tasks: Flow<List<OperationTask>>

    suspend fun start(type: OperationTaskType, totalItems: Int, totalBytes: Long? = null): String

    suspend fun update(
        id: String,
        state: OperationTaskState,
        completedItems: Int,
        failedItems: Int = 0,
        message: String? = null,
        completedBytes: Long? = null,
    )

    suspend fun reconcileInterrupted()

    suspend fun clearFinished()
}
