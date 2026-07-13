package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.ErrorCode

interface RootFileSystem {
    suspend fun list(path: RootPath): OperationResult<List<DirectoryEntry>>

    suspend fun stat(path: RootPath): OperationResult<DirectoryEntry>

    suspend fun canonicalize(path: RootPath): OperationResult<RootPath>
    /**
     * Creates one directory without retrying the write. If the mkdir dispatch is cancelled,
     * implementations perform at most one non-cancellable read-only post-check and then rethrow
     * the original cancellation; they do not clean up or claim a definite outcome.
     */
    suspend fun createDirectory(parent:RootPath,name:FolderName):OperationResult<DirectoryEntry>
    suspend fun transferFromAppCache(
        source:AppCachePath,
        targetDirectory:RootPath,
        finalName:FolderName,
        expectedSizeBytes:Long,
    ):OperationResult<DirectoryEntry> = unsupportedTransfer()
}

private fun <T> unsupportedTransfer():OperationResult<T> = OperationResult.Failure(ErrorCode.COMMAND_FAILED,"不支持文件传输","Transfer primitive unsupported")
