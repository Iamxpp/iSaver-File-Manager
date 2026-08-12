package com.iamxpp.isaver.fileops

import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath

data class DirectoryMergeSummary(val processed: Int, val skipped: Int)

class DirectoryMergeRepository(
    private val fileSystem: RootFileSystem,
    private val copy: suspend (DirectoryEntry, RootPath, RootPath, ConflictAction) -> OperationResult<DirectoryEntry>,
    private val move: suspend (DirectoryEntry, RootPath, RootPath, ConflictAction) -> OperationResult<DirectoryEntry>,
    private val deleteEmptyDirectory: suspend (DirectoryEntry, RootPath) -> OperationResult<Unit>,
) {
    suspend fun merge(
        source: DirectoryEntry,
        sourceParent: RootPath,
        target: DirectoryEntry,
        targetParent: RootPath,
        moveSource: Boolean,
        conflictAction: ConflictAction = ConflictAction.CANCEL,
    ): OperationResult<DirectoryMergeSummary> {
        if (source.type != EntryType.DIRECTORY || target.type != EntryType.DIRECTORY ||
            source.symbolicLink || target.symbolicLink || source.path != EntryName.join(sourceParent, EntryName.parse(source.name).getOrThrow())
        ) return failure("无法合并此目录")
        return mergeChildren(source, sourceParent, target.path, moveSource, conflictAction)
    }

    private suspend fun mergeChildren(
        source: DirectoryEntry,
        sourceParent: RootPath,
        targetDirectory: RootPath,
        moveSource: Boolean,
        conflictAction: ConflictAction,
    ): OperationResult<DirectoryMergeSummary> {
        val listing = fileSystem.readDirectory(source.path)
        if (listing !is OperationResult.Success) return listing as OperationResult.Failure
        val targetListing = fileSystem.readDirectory(targetDirectory)
        if (targetListing !is OperationResult.Success) return targetListing as OperationResult.Failure
        val existing = targetListing.value.entries.associateBy { it.name }
        var processed = 0
        var skipped = 0
        for (child in listing.value.entries) {
            if (child.symbolicLink || child.type == EntryType.OTHER) return failure("目录包含不支持的项目")
            val targetChild = existing[child.name]
            if (targetChild == null) {
                val result = if (moveSource) move(child, source.path, targetDirectory, ConflictAction.CANCEL)
                else copy(child, source.path, targetDirectory, ConflictAction.CANCEL)
                if (result is OperationResult.Failure) return result
                processed += 1
                continue
            }
            if (child.type == EntryType.DIRECTORY && targetChild.type == EntryType.DIRECTORY &&
                conflictAction == ConflictAction.MERGE
            ) {
                when (val nested = mergeChildren(child, source.path, targetChild.path, moveSource, conflictAction)) {
                    is OperationResult.Failure -> return nested
                    is OperationResult.Success -> {
                        processed += nested.value.processed
                        skipped += nested.value.skipped
                    }
                }
                continue
            }
            when (conflictAction) {
                ConflictAction.SKIP -> skipped += 1
                ConflictAction.KEEP_BOTH -> {
                    val result = if (moveSource) move(child, source.path, targetDirectory, ConflictAction.KEEP_BOTH)
                    else copy(child, source.path, targetDirectory, ConflictAction.KEEP_BOTH)
                    if (result is OperationResult.Failure) return result
                    processed += 1
                }
                else -> return OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "合并目录时发现同名项目")
            }
        }
        if (moveSource) {
            val refreshed = fileSystem.readDirectory(source.path)
            if (refreshed !is OperationResult.Success) return refreshed as OperationResult.Failure
            if (refreshed.value.entries.isEmpty()) {
                val deleted = deleteEmptyDirectory(source, sourceParent)
                if (deleted is OperationResult.Failure) return deleted
            }
        }
        return OperationResult.Success(DirectoryMergeSummary(processed, skipped))
    }

    private fun failure(message: String): OperationResult.Failure =
        OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, message)
}
