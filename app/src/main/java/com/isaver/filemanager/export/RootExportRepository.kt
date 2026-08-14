package com.isaver.filemanager.export

import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult
import java.io.File

class RootExportRepository(
    private val cache: RootExportCache,
    private val registry: ExternalFileRegistry,
    private val mimeResolver: MimeResolver,
) {
    suspend fun export(entry: DirectoryEntry): OperationResult<ExternalFileGrant> {
        return prepareGrant(
            entry = entry,
            ttlMillis = ExternalFileRegistry.OPEN_TTL_MILLIS,
            failureMessage = "无法准备文件打开授权",
        )
    }

    suspend fun share(entry: DirectoryEntry): OperationResult<ExternalFileGrant> {
        return prepareGrant(
            entry = entry,
            ttlMillis = ExternalFileRegistry.SHARE_TTL_MILLIS,
            failureMessage = "无法准备文件分享授权",
        )
    }

    suspend fun shareLocalFile(
        file: File,
        displayName: String,
        mimeType: String,
    ): OperationResult<ExternalFileGrant> {
        val cached = when (val result = cache.cacheLocalFile(file, displayName, mimeType)) {
            is OperationResult.Failure -> return result
            is OperationResult.Success -> result.value
        }
        return registry.issue(cached, ExternalFileRegistry.SHARE_TTL_MILLIS).fold(
            onSuccess = { OperationResult.Success(it) },
            onFailure = {
                cache.discardNow(cached)
                OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法准备文件分享授权")
            },
        )
    }

    private suspend fun prepareGrant(
        entry: DirectoryEntry,
        ttlMillis: Long,
        failureMessage: String,
    ): OperationResult<ExternalFileGrant> {
        val extensionMime = mimeResolver.resolve(entry.name)
        val cached = when (val result = cache.cache(entry, extensionMime)) {
            is OperationResult.Failure -> return result
            is OperationResult.Success -> result.value
        }
        val header = cache.readPrefix(cached) ?: byteArrayOf()
        val inspected = cached.copy(mimeType = mimeResolver.resolve(entry.name, header))
        return registry.issue(inspected, ttlMillis).fold(
            onSuccess = { OperationResult.Success(it) },
            onFailure = {
                cache.discardNow(cached)
                OperationResult.Failure(
                    ErrorCode.COMMAND_FAILED,
                    failureMessage,
                )
            },
        )
    }

    fun revoke(grant: ExternalFileGrant) {
        registry.revoke(grant.token)
    }
}
