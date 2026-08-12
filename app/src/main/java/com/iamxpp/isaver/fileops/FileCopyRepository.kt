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

class FileCopyRepository internal constructor(
    private val copyFileAs: suspend (DirectoryEntry, RootPath, RootPath, EntryName) -> OperationResult<DirectoryEntry>,
    private val nameResolver: TargetNameResolver = TargetNameResolver(),
) {
    internal constructor(
        copyFile: suspend (DirectoryEntry, RootPath, RootPath) -> OperationResult<DirectoryEntry>,
    ) : this(
        copyFileAs = { source, sourceDirectory, targetDirectory, _ ->
            copyFile(source, sourceDirectory, targetDirectory)
        },
    )

    constructor(fileSystem: RootFileSystem) : this(fileSystem::copyFileAsNoReplace)

    suspend fun copy(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
        conflictAction: ConflictAction = ConflictAction.CANCEL,
    ): OperationResult<DirectoryEntry> {
        val name = EntryName.parse(source.name).getOrElse { return sourceUnreadable() }
        if (
            source.type != EntryType.FILE ||
            !source.readable ||
            source.symbolicLink ||
            source.path != EntryName.join(sourceDirectory, name)
        ) {
            return sourceUnreadable()
        }
        if (sourceDirectory == targetDirectory) {
            return OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "文件已在当前目录")
        }
        if (RootPathRiskPolicy.isProtected(targetDirectory)) {
            return OperationResult.Failure(ErrorCode.NOT_WRITABLE, "系统保护区域仅允许浏览")
        }
        val draft = OutputNameDraft.fromDisplayName(source.name)
        var attempt = 0
        while (true) {
            val targetName = nameResolver.resolve(draft, attempt).getOrElse {
                return OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "同名文件过多")
            }
            when (val result = copyFileAs(source, sourceDirectory, targetDirectory, targetName)) {
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
        "当前仅支持复制单个普通文件",
    )
}
