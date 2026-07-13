package com.iamxpp.isaver.transfer

import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

sealed interface TransferState {
    data object Resolving : TransferState
    data class Publishing(val candidate: EntryName, val attempt: Int) : TransferState
    data class Success(val entry: DirectoryEntry, val name: EntryName, val cleanupWarning: String? = null) : TransferState
    data class Failure(val code: ErrorCode, val message: String, val cleanupWarning: String? = null) : TransferState
}

class RootFileTransferRepository(
    private val fileSystem: RootFileSystem,
    private val nameResolver: TargetNameResolver,
    private val cleanupCache: suspend (CachedIncomingFile) -> Boolean,
) {
    fun transfer(cached: CachedIncomingFile, originalDisplayName: String, targetDirectory: RootPath): Flow<TransferState> = flow {
        emit(TransferState.Resolving)
        var attempt = 0
        var rootWriteStarted = false
        try {
            while (true) {
                val candidate = nameResolver.resolve(originalDisplayName, attempt).getOrElse { error ->
                    val warning = cleanupWarning(cached)
                    emit(TransferState.Failure(ErrorCode.COMMAND_FAILED, safeNameFailure(error), warning))
                    return@flow
                }
                emit(TransferState.Publishing(candidate, attempt))
                rootWriteStarted = true
                when (val result = fileSystem.transferFromAppCache(cached.appCachePath, targetDirectory, candidate, cached.sizeBytes)) {
                    is OperationResult.Success -> {
                        emit(TransferState.Success(result.value, candidate, cleanupWarning(cached)))
                        return@flow
                    }
                    is OperationResult.Failure -> {
                        if (result.code == ErrorCode.ALREADY_EXISTS) { attempt++; continue }
                        val warning = if (result.code == ErrorCode.OUTCOME_UNCERTAIN) null else cleanupWarning(cached)
                        emit(TransferState.Failure(result.code, safeFailureMessage(result.code), warning))
                        return@flow
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (!rootWriteStarted) cleanupWarning(cached)
            throw cancelled
        }
    }

    private suspend fun cleanupWarning(cached: CachedIncomingFile): String? =
        withContext(NonCancellable) {
            try {
                if (cleanupCache(cached)) null else CLEANUP_WARNING
            } catch (_: Exception) {
                CLEANUP_WARNING
            }
        }

    private companion object { const val CLEANUP_WARNING = "临时缓存清理失败，请稍后重试" }

    private fun safeNameFailure(error: Throwable): String = when (error) {
        is TargetNameException.AttemptsExhausted -> "同名文件过多，无法生成可用名称"
        is TargetNameException.NameTooLong -> "文件名过长，无法保存"
        else -> "文件名无效，无法保存"
    }

    private fun safeFailureMessage(code: ErrorCode): String = when (code) {
        ErrorCode.ROOT_DENIED, ErrorCode.ROOT_UNAVAILABLE -> "Root 权限不可用，请重新授权后再试"
        ErrorCode.NO_SPACE -> "存储空间不足，无法保存文件"
        ErrorCode.SOURCE_UNREADABLE -> "无法读取分享文件"
        ErrorCode.NOT_WRITABLE -> "目标文件夹不可写"
        ErrorCode.OUTCOME_UNCERTAIN -> "无法确认文件是否已保存；已保留临时缓存，请检查目标文件夹"
        ErrorCode.CANCELLED -> "保存已取消"
        else -> "保存失败，请稍后重试"
    }
}
