package com.iamxpp.isaver.fileops

import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootPathRiskPolicy

class FileRenameRepository internal constructor(
    private val renameFile: suspend (DirectoryEntry, RootPath, EntryName) -> OperationResult<DirectoryEntry>,
) {
    constructor(fileSystem: RootFileSystem) : this(fileSystem::renameEntryNoReplace)

    suspend fun rename(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        newName: String,
    ): OperationResult<DirectoryEntry> {
        val name = EntryName.parse(source.name).getOrElse { return invalidSource() }
        val targetName = EntryName.parse(newName).getOrElse {
            return OperationResult.Failure(ErrorCode.COMMAND_FAILED, "文件名无效")
        }
        if (
            source.type == EntryType.OTHER ||
            !source.readable ||
            source.symbolicLink ||
            (source.type == EntryType.FILE && source.sizeBytes == null) ||
            source.path != EntryName.join(sourceDirectory, name)
        ) {
            return invalidSource()
        }
        if (name == targetName) {
            return OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "文件名没有变化")
        }
        if (RootPathRiskPolicy.isProtected(sourceDirectory)) {
            return OperationResult.Failure(ErrorCode.NOT_WRITABLE, "系统保护区域仅允许浏览")
        }
        return renameFile(source, sourceDirectory, targetName)
    }

    private fun invalidSource() = OperationResult.Failure(
        ErrorCode.SOURCE_UNREADABLE,
        "无法重命名此项目",
    )
}
