package com.iamxpp.isaver.fileops

import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.trash.TrashItem
import com.iamxpp.isaver.trash.TrashRepository

internal class RecoverableReplaceRepository(
    private val stat: suspend (com.iamxpp.isaver.domain.RootPath) -> OperationResult<DirectoryEntry>,
    private val recycle: suspend (DirectoryEntry, RootPath) -> OperationResult<TrashItem>,
    private val restore: suspend (TrashItem) -> OperationResult<DirectoryEntry>,
) {
    constructor(fileSystem: RootFileSystem, trashRepository: TrashRepository) : this(
        fileSystem::stat,
        trashRepository::recycle,
        trashRepository::restore,
    )

    suspend fun replace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
        operation: suspend (DirectoryEntry, RootPath, RootPath, EntryName) -> OperationResult<DirectoryEntry>,
    ): OperationResult<DirectoryEntry> {
        val targetName = EntryName.parse(source.name).getOrElse {
            return OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, "无法替换此项目")
        }
        val existing = stat(EntryName.join(targetDirectory, targetName))
        if (existing !is OperationResult.Success) {
            return if (existing is OperationResult.Failure && existing.code == ErrorCode.NOT_FOUND) {
                operation(source, sourceDirectory, targetDirectory, targetName)
            } else existing as OperationResult.Failure
        }
        val backup = recycle(existing.value, targetDirectory)
        if (backup !is OperationResult.Success) return backup as OperationResult.Failure
        return when (val published = operation(source, sourceDirectory, targetDirectory, targetName)) {
            is OperationResult.Success -> published
            is OperationResult.Failure -> when (restore(backup.value)) {
                is OperationResult.Success -> published
                is OperationResult.Failure -> OperationResult.Failure(
                    ErrorCode.OUTCOME_UNCERTAIN,
                    "替换失败，原项目恢复结果需要核对",
                )
            }
        }
    }
}
