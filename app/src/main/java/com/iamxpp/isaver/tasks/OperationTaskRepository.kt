package com.iamxpp.isaver.tasks

import com.iamxpp.isaver.data.local.OperationTaskDao
import com.iamxpp.isaver.data.local.OperationTaskEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OperationTaskRepository internal constructor(
    private val dao: OperationTaskDao,
    private val clock: () -> Long,
    private val idFactory: () -> String,
) : OperationTaskStore {
    constructor(dao: OperationTaskDao) : this(dao, System::currentTimeMillis, { UUID.randomUUID().toString() })

    override val tasks: Flow<List<OperationTask>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun start(type: OperationTaskType, totalItems: Int, totalBytes: Long?): String {
        require(totalItems > 0)
        val now = clock()
        val id = idFactory()
        dao.upsert(
            OperationTaskEntity(
                id = id,
                type = type.name,
                state = OperationTaskState.QUEUED.name,
                totalItems = totalItems,
                completedItems = 0,
                failedItems = 0,
                totalBytes = totalBytes?.coerceAtLeast(0),
                completedBytes = 0,
                recoveryPolicy = OperationRecoveryPolicy.NEVER_REPLAY.name,
                message = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    override suspend fun update(
        id: String,
        state: OperationTaskState,
        completedItems: Int,
        failedItems: Int,
        message: String?,
        completedBytes: Long?,
    ) {
        val current = dao.find(id) ?: return
        dao.upsert(
            current.copy(
                state = state.name,
                completedItems = completedItems.coerceIn(0, current.totalItems),
                failedItems = failedItems.coerceAtLeast(0),
                completedBytes = (completedBytes ?: current.completedBytes).coerceIn(
                    0,
                    current.totalBytes ?: Long.MAX_VALUE,
                ),
                message = message,
                updatedAt = clock(),
            ),
        )
    }

    override suspend fun reconcileInterrupted() {
        dao.markInterruptedForReview("应用重启，请核对文件结果", clock())
    }

    override suspend fun clearFinished() {
        dao.deleteFinished()
    }

    private fun OperationTaskEntity.toModel() = OperationTask(
        id = id,
        type = OperationTaskType.valueOf(type),
        state = OperationTaskState.valueOf(state),
        totalItems = totalItems,
        completedItems = completedItems,
        failedItems = failedItems,
        totalBytes = totalBytes,
        completedBytes = completedBytes,
        recoveryPolicy = OperationRecoveryPolicy.valueOf(recoveryPolicy),
        message = message,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
