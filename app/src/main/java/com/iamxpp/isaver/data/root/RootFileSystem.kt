package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.ErrorCode
import java.io.OutputStream

interface RootFileSystem {
    suspend fun readDirectory(path: RootPath): OperationResult<DirectorySnapshot> =
        unsupportedDirectorySnapshot()

    suspend fun list(path: RootPath): OperationResult<List<DirectoryEntry>> =
        when (val result = readDirectory(path)) {
            is OperationResult.Failure -> result
            is OperationResult.Success -> OperationResult.Success(result.value.entries)
        }

    suspend fun stat(path: RootPath): OperationResult<DirectoryEntry>

    suspend fun canonicalize(path: RootPath): OperationResult<RootPath>
    /**
     * Creates one directory without retrying the write. If the mkdir dispatch is cancelled,
     * implementations perform at most one non-cancellable read-only post-check and then rethrow
     * the original cancellation; they do not clean up or claim a definite outcome.
     */
    suspend fun createDirectory(parent:RootPath,name:FolderName):OperationResult<DirectoryEntry>
    suspend fun copyToOutput(
        source: RootPath,
        output: OutputStream,
    ): OperationResult<Long> = unsupportedTransfer()

    suspend fun transferFromStream(
        source: RootTransferSource,
        targetDirectory: RootPath,
        finalName: EntryName,
    ): OperationResult<DirectoryEntry> = unsupportedTransfer()

    suspend fun prepareExtractionStage(parent: RootPath): OperationResult<ExtractionStage> =
        unsupportedExtraction()

    suspend fun createExtractionDirectory(
        stage: ExtractionStage,
        relativePath: String,
    ): OperationResult<Unit> = unsupportedExtraction()

    suspend fun transferIntoExtractionStage(
        stage: ExtractionStage,
        relativeParent: String,
        source: RootTransferSource,
        finalName: EntryName,
    ): OperationResult<Unit> = unsupportedExtraction()

    suspend fun commitExtractionStage(
        stage: ExtractionStage,
        finalName: FolderName,
    ): OperationResult<DirectoryEntry> = unsupportedExtraction()

    suspend fun cleanupExtractionStage(stage: ExtractionStage): OperationResult<Unit> =
        unsupportedExtraction()
}

private fun unsupportedDirectorySnapshot(): OperationResult.Failure = OperationResult.Failure(
    ErrorCode.COMMAND_FAILED,
    "无法读取目录信息",
    "Directory snapshot primitive unsupported",
)

private fun <T> unsupportedTransfer():OperationResult<T> = OperationResult.Failure(ErrorCode.COMMAND_FAILED,"不支持文件传输","Transfer primitive unsupported")

private fun <T> unsupportedExtraction(): OperationResult<T> = OperationResult.Failure(
    ErrorCode.COMMAND_FAILED,
    "不支持安全解压",
    "Extraction staging primitive unsupported",
)
