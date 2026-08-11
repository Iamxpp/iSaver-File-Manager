package com.iamxpp.isaver.fileops

import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootPathRiskPolicy

class FileMoveRepository internal constructor(
    private val moveFile: suspend (DirectoryEntry, RootPath, RootPath) -> OperationResult<DirectoryEntry>,
) {
    constructor(fileSystem: RootFileSystem) : this(fileSystem::moveFileNoReplace)

    suspend fun move(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
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
        if (
            RootPathRiskPolicy.isProtected(sourceDirectory) ||
            RootPathRiskPolicy.isProtected(targetDirectory)
        ) {
            return OperationResult.Failure(ErrorCode.NOT_WRITABLE, "系统保护区域仅允许浏览")
        }
        return moveFile(source, sourceDirectory, targetDirectory)
    }

    private fun sourceUnreadable() = OperationResult.Failure(
        ErrorCode.SOURCE_UNREADABLE,
        "当前仅支持移动单个普通文件",
    )
}
