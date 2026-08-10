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
        val cached = when (val result = cache.cache(entry, mimeResolver.resolve(entry.name))) {
            is OperationResult.Failure -> return result
            is OperationResult.Success -> result.value
        }
        return registry.issue(cached).fold(
            onSuccess = { OperationResult.Success(it) },
            onFailure = {
                cache.discardNow(cached)
                OperationResult.Failure(
                    ErrorCode.COMMAND_FAILED,
                    "无法准备文件打开授权",
                )
            },
        )
    }

    fun revoke(grant: ExternalFileGrant) {
        registry.revoke(grant.token)
    }
}
