package com.iamxpp.isaver.export

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult

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

    private suspend fun prepareGrant(
        entry: DirectoryEntry,
        ttlMillis: Long,
        failureMessage: String,
    ): OperationResult<ExternalFileGrant> {
        val cached = when (val result = cache.cache(entry, mimeResolver.resolve(entry.name))) {
            is OperationResult.Failure -> return result
            is OperationResult.Success -> result.value
        }
        return registry.issue(cached, ttlMillis).fold(
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
