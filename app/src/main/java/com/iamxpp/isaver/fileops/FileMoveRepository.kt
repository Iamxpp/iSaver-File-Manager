package com.iamxpp.isaver.fileops

import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootPathRiskPolicy
import com.iamxpp.isaver.transfer.OutputNameDraft
import com.iamxpp.isaver.transfer.TargetNameResolver
import com.iamxpp.isaver.trash.TrashRepository

class FileMoveRepository internal constructor(
    private val moveFileAs: suspend (DirectoryEntry, RootPath, RootPath, EntryName) -> OperationResult<DirectoryEntry>,
    private val nameResolver: TargetNameResolver = TargetNameResolver(),
    private val replaceExisting: (suspend (DirectoryEntry, RootPath, RootPath) -> OperationResult<DirectoryEntry>)? = null,
) {
    internal constructor(
        moveFile: suspend (DirectoryEntry, RootPath, RootPath) -> OperationResult<DirectoryEntry>,
    ) : this(
        moveFileAs = { source, sourceDirectory, targetDirectory, _ ->
            moveFile(source, sourceDirectory, targetDirectory)
        },
    )

    constructor(fileSystem: RootFileSystem, trashRepository: TrashRepository? = null) : this(
        fileSystem::moveEntryAsNoReplace,
        replaceExisting = trashRepository?.let { trash ->
            { source, sourceDirectory, targetDirectory ->
                RecoverableReplaceRepository(fileSystem, trash).replace(
                    source, sourceDirectory, targetDirectory, fileSystem::moveEntryAsNoReplace,
                )
            }
        },
    )

    suspend fun move(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
        conflictAction: ConflictAction = ConflictAction.CANCEL,
    ): OperationResult<DirectoryEntry> {
        val name = EntryName.parse(source.name).getOrElse { return sourceUnreadable() }
        if (
            source.type == EntryType.OTHER ||
            !source.readable ||
            source.symbolicLink ||
            source.path != EntryName.join(sourceDirectory, name)
        ) {
            return sourceUnreadable()
        }
        if (conflictAction == ConflictAction.REPLACE) {
            return replaceExisting?.invoke(source, sourceDirectory, targetDirectory)
                ?: OperationResult.Failure(ErrorCode.NOT_WRITABLE, "此位置不支持可恢复替换")
        }
        if (sourceDirectory == targetDirectory) {
            return OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "文件已在当前目录")
        }
        if (source.type == EntryType.DIRECTORY && targetIsSourceOrDescendant(source.path, targetDirectory)) {
            return OperationResult.Failure(ErrorCode.COMMAND_FAILED, "不能移动到目录自身或其子目录")
        }
        if (
            RootPathRiskPolicy.isProtected(sourceDirectory) ||
            RootPathRiskPolicy.isProtected(targetDirectory)
        ) {
            return OperationResult.Failure(ErrorCode.NOT_WRITABLE, "系统保护区域仅允许浏览")
        }
        val draft = OutputNameDraft.fromDisplayName(source.name)
        var attempt = 0
        while (true) {
            val targetName = nameResolver.resolve(draft, attempt).getOrElse {
                return OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "同名文件过多")
            }
            when (val result = moveFileAs(source, sourceDirectory, targetDirectory, targetName)) {
                is OperationResult.Success -> return result
                is OperationResult.Failure -> {
                    if (result.code != ErrorCode.ALREADY_EXISTS || conflictAction != ConflictAction.KEEP_BOTH) {
                        return result
                    }
                    attempt += 1
                }
            }
        }
    }

    private fun sourceUnreadable() = OperationResult.Failure(
        ErrorCode.SOURCE_UNREADABLE,
        "无法移动此项目",
    )

    private fun targetIsSourceOrDescendant(source: RootPath, target: RootPath): Boolean {
        val prefix = source.value.trimEnd('/') + "/"
        return target == source || target.value.startsWith(prefix)
    }
}
