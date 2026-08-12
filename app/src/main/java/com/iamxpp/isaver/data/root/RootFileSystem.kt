package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.RootEntryIdentity
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
    suspend fun identity(path: RootPath): OperationResult<RootEntryIdentity> =
        OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法读取文件身份")
    /**
     * Creates one directory without retrying the write. If the mkdir dispatch is cancelled,
     * implementations perform at most one non-cancellable read-only post-check and then rethrow
     * the original cancellation; they do not clean up or claim a definite outcome.
     */
    suspend fun createDirectory(parent:RootPath,name:FolderName):OperationResult<DirectoryEntry>
    suspend fun createFileNoReplace(
        parent: RootPath,
        name: EntryName,
    ): OperationResult<DirectoryEntry> = unsupportedCreateFile()
    suspend fun copyToOutput(
        source: RootPath,
        output: OutputStream,
    ): OperationResult<Long> = unsupportedTransfer()
    suspend fun readRange(
        source: RootPath,
        offset: Long,
        count: Long,
    ): OperationResult<RootFileChunk> = unsupportedTransfer()

    suspend fun metadata(source: RootPath): OperationResult<RootFileMetadata> = unsupportedTransfer()

    suspend fun transferFromStream(
        source: RootTransferSource,
        targetDirectory: RootPath,
        finalName: EntryName,
    ): OperationResult<DirectoryEntry> = unsupportedTransfer()

    suspend fun moveFileNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
    ): OperationResult<DirectoryEntry> = unsupportedMove()

    suspend fun moveFileAsNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
        targetName: EntryName,
    ): OperationResult<DirectoryEntry> =
        if (targetName.value == source.name) {
            moveFileNoReplace(source, sourceDirectory, targetDirectory)
        } else {
            unsupportedMove()
        }

    suspend fun moveDirectoryAsNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
        targetName: EntryName,
    ): OperationResult<DirectoryEntry> = unsupportedMove()

    suspend fun moveEntryAsNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
        targetName: EntryName,
    ): OperationResult<DirectoryEntry> = when (source.type) {
        com.iamxpp.isaver.domain.EntryType.FILE ->
            moveFileAsNoReplace(source, sourceDirectory, targetDirectory, targetName)
        com.iamxpp.isaver.domain.EntryType.DIRECTORY ->
            moveDirectoryAsNoReplace(source, sourceDirectory, targetDirectory, targetName)
        com.iamxpp.isaver.domain.EntryType.OTHER -> unsupportedMove()
    }

    suspend fun renameFileNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetName: EntryName,
    ): OperationResult<DirectoryEntry> = unsupportedRename()

    suspend fun renameEntryNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetName: EntryName,
    ): OperationResult<DirectoryEntry> = renameFileNoReplace(source, sourceDirectory, targetName)

    suspend fun copyFileNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
    ): OperationResult<DirectoryEntry> = unsupportedCopy()

    suspend fun copyFileAsNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
        targetName: EntryName,
    ): OperationResult<DirectoryEntry> =
        if (targetName.value == source.name) {
            copyFileNoReplace(source, sourceDirectory, targetDirectory)
        } else {
            unsupportedCopy()
        }

    suspend fun copyDirectoryAsNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
        targetName: EntryName,
    ): OperationResult<DirectoryEntry> = unsupportedCopy()

    suspend fun copyEntryAsNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
        targetName: EntryName,
    ): OperationResult<DirectoryEntry> = when (source.type) {
        com.iamxpp.isaver.domain.EntryType.FILE ->
            copyFileAsNoReplace(source, sourceDirectory, targetDirectory, targetName)
        com.iamxpp.isaver.domain.EntryType.DIRECTORY ->
            copyDirectoryAsNoReplace(source, sourceDirectory, targetDirectory, targetName)
        com.iamxpp.isaver.domain.EntryType.OTHER -> unsupportedCopy()
    }

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

    suspend fun deleteEntryPermanently(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
    ): OperationResult<Unit> = OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法删除项目")
}

private fun unsupportedDirectorySnapshot(): OperationResult.Failure = OperationResult.Failure(
    ErrorCode.COMMAND_FAILED,
    "无法读取目录信息",
    "Directory snapshot primitive unsupported",
)

private fun <T> unsupportedTransfer():OperationResult<T> = OperationResult.Failure(ErrorCode.COMMAND_FAILED,"不支持文件传输","Transfer primitive unsupported")

private fun unsupportedMove(): OperationResult.Failure = OperationResult.Failure(
    ErrorCode.COMMAND_FAILED,
    "不支持安全移动",
    "Same-filesystem move primitive unsupported",
)

private fun unsupportedRename(): OperationResult.Failure = OperationResult.Failure(
    ErrorCode.COMMAND_FAILED,
    "不支持安全重命名",
    "Same-filesystem rename primitive unsupported",
)

private fun unsupportedCreateFile(): OperationResult.Failure = OperationResult.Failure(
    ErrorCode.COMMAND_FAILED,
    "不支持安全新建文件",
    "Empty file creation primitive unsupported",
)

private fun unsupportedCopy(): OperationResult.Failure = OperationResult.Failure(
    ErrorCode.COMMAND_FAILED,
    "不支持安全复制",
    "File copy primitive unsupported",
)

private fun <T> unsupportedExtraction(): OperationResult<T> = OperationResult.Failure(
    ErrorCode.COMMAND_FAILED,
    "不支持安全解压",
    "Extraction staging primitive unsupported",
)
