package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.ErrorCode

interface RootFileSystem {
    suspend fun list(path: RootPath): OperationResult<List<DirectoryEntry>>

    suspend fun stat(path: RootPath): OperationResult<DirectoryEntry>

    suspend fun canonicalize(path: RootPath): OperationResult<RootPath> = OperationResult.Failure(
        ErrorCode.COMMAND_FAILED, "无法解析真实路径", "Canonicalization is not implemented",
    )
}
