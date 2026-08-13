package com.iamxpp.isaver.fileops

import com.iamxpp.isaver.data.root.RootFileMetadata
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootPathRiskPolicy

enum class PermissionBit(val mask: Int) {
    OWNER_READ(0x100), OWNER_WRITE(0x80), OWNER_EXECUTE(0x40),
    GROUP_READ(0x20), GROUP_WRITE(0x10), GROUP_EXECUTE(0x8),
    OTHER_READ(0x4), OTHER_WRITE(0x2), OTHER_EXECUTE(0x1),
}

@JvmInline
value class FilePermissions private constructor(val mode: Int) {
    val label get() = "0${mode.toString(8).padStart(3, '0')}"
    val ownerRead get() = has(PermissionBit.OWNER_READ)
    val groupWrite get() = has(PermissionBit.GROUP_WRITE)
    val otherWrite get() = has(PermissionBit.OTHER_WRITE)

    fun has(bit: PermissionBit) = mode and bit.mask != 0
    fun withBit(bit: PermissionBit, enabled: Boolean) = fromMode(
        if (enabled) mode or bit.mask else mode and bit.mask.inv(),
    )

    companion object {
        fun fromMode(mode: Int): FilePermissions {
            require(mode in 0..0x1FF) { "Only rwx permission bits are supported" }
            return FilePermissions(mode)
        }
    }
}

enum class PermissionPreset(val label: String, val permissions: FilePermissions) {
    PRIVATE_FILE("私有文件 600", FilePermissions.fromMode(0x180)),
    STANDARD_FILE("常用文件 644", FilePermissions.fromMode(0x1A4)),
    PRIVATE_DIRECTORY("私有目录 700", FilePermissions.fromMode(0x1C0)),
    DIRECTORY_STANDARD("常用目录 755", FilePermissions.fromMode(0x1ED)),
}

object PermissionRiskPolicy {
    fun requiresConfirmation(path: RootPath, permissions: FilePermissions): Boolean =
        permissions.groupWrite || permissions.otherWrite || isPrivateAppData(path)

    fun isPrivateAppData(path: RootPath): Boolean = listOf("/data/user", "/data/data").any { root ->
        path.value == root || path.value.startsWith("$root/")
    }
}

class FilePermissionRepository(private val fileSystem: RootFileSystem) {
    suspend fun change(
        entry: DirectoryEntry,
        parent: RootPath,
        expectedMetadata: RootFileMetadata,
        permissions: FilePermissions,
        confirmed: Boolean,
    ): OperationResult<RootFileMetadata> {
        if (RootPathRiskPolicy.isProtected(entry.path) || RootPathRiskPolicy.isProtected(parent)) {
            return OperationResult.Failure(ErrorCode.NOT_WRITABLE, "系统保护区域禁止修改权限")
        }
        if (entry.symbolicLink || entry.type == EntryType.OTHER || entry.path.value.substringBeforeLast('/', "/") != parent.value) {
            return OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, "无法修改此项目的权限")
        }
        if (PermissionRiskPolicy.requiresConfirmation(entry.path, permissions) && !confirmed) {
            return OperationResult.Failure(ErrorCode.CANCELLED, "此权限组合需要再次确认")
        }
        return fileSystem.changeMode(entry, parent, expectedMetadata, permissions.mode)
    }
}
