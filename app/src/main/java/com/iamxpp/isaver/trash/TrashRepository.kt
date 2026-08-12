package com.iamxpp.isaver.trash

import com.iamxpp.isaver.data.local.TrashItemDao
import com.iamxpp.isaver.data.local.TrashItemEntity
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class TrashItemState { PENDING, ACTIVE, NEEDS_REVIEW }

data class TrashItem(
    val id: String,
    val originalPath: RootPath,
    val originalParent: RootPath,
    val originalName: String,
    val trashedPath: RootPath,
    val trashedName: String,
    val entryType: EntryType,
    val sizeBytes: Long?,
    val device: Long?,
    val inode: Long?,
    val state: TrashItemState,
    val deletedAt: Long,
)

class TrashRepository internal constructor(
    private val fileSystem: RootFileSystem,
    private val dao: TrashItemDao,
    private val clock: () -> Long,
    private val idFactory: () -> String,
) {
    constructor(fileSystem: RootFileSystem, dao: TrashItemDao) :
        this(fileSystem, dao, System::currentTimeMillis, { UUID.randomUUID().toString() })

    val items: Flow<List<TrashItem>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun recycle(source: DirectoryEntry, sourceDirectory: RootPath): OperationResult<TrashItem> {
        if (!isSharedStorage(source.path) || source.symbolicLink || source.type == EntryType.OTHER ||
            source.path.value.startsWith("${TRASH_ROOT.value}/")
        ) return OperationResult.Failure(ErrorCode.NOT_WRITABLE, "此位置不支持回收站，请使用永久删除")
        val originalName = EntryName.parse(source.name).getOrElse { return invalidSource() }
        if (source.path != EntryName.join(sourceDirectory, originalName)) return invalidSource()
        val trashDirectory = ensureTrashDirectory()
        if (trashDirectory !is OperationResult.Success) return trashDirectory as OperationResult.Failure
        val id = idFactory()
        val trashedName = EntryName.parse(id).getOrElse {
            return OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法生成回收标识")
        }
        val trashedPath = EntryName.join(TRASH_FILES, trashedName)
        val pending = TrashItemEntity(
            id, source.path.value, sourceDirectory.value, source.name, trashedPath.value, trashedName.value,
            source.type.name, source.sizeBytes, null, null, TrashItemState.PENDING.name, clock(),
        )
        dao.upsert(pending)
        return when (val moved = fileSystem.moveEntryAsNoReplace(
            source, sourceDirectory, TRASH_FILES, trashedName,
        )) {
            is OperationResult.Failure -> {
                dao.delete(pending)
                moved
            }
            is OperationResult.Success -> {
                when (val identity = fileSystem.identity(moved.value.path)) {
                    is OperationResult.Failure -> {
                        dao.upsert(pending.copy(state = TrashItemState.NEEDS_REVIEW.name))
                        OperationResult.Failure(ErrorCode.OUTCOME_UNCERTAIN, "项目已移动，但回收记录需要核对")
                    }
                    is OperationResult.Success -> {
                        val active = pending.copy(
                            device = identity.value.device,
                            inode = identity.value.inode,
                            state = TrashItemState.ACTIVE.name,
                        )
                        dao.upsert(active)
                        OperationResult.Success(active.toModel())
                    }
                }
            }
        }
    }

    suspend fun restore(item: TrashItem): OperationResult<DirectoryEntry> {
        val current = verifiedTrashEntry(item) ?: return invalidTrashItem()
        val targetName = EntryName.parse(item.originalName).getOrElse { return invalidTrashItem() }
        return when (val restored = fileSystem.moveEntryAsNoReplace(
            current, TRASH_FILES, item.originalParent, targetName,
        )) {
            is OperationResult.Failure -> restored
            is OperationResult.Success -> {
                dao.find(item.id)?.let { dao.delete(it) }
                restored
            }
        }
    }

    suspend fun deletePermanently(item: TrashItem): OperationResult<Unit> {
        val current = verifiedTrashEntry(item) ?: return invalidTrashItem()
        return when (val result = fileSystem.deleteEntryPermanently(current, TRASH_FILES)) {
            is OperationResult.Failure -> result
            is OperationResult.Success -> {
                dao.find(item.id)?.let { dao.delete(it) }
                result
            }
        }
    }

    suspend fun deletePermanently(source: DirectoryEntry, parent: RootPath): OperationResult<Unit> =
        fileSystem.deleteEntryPermanently(source, parent)

    suspend fun reconcilePending() {
        dao.markPendingForReview()
    }

    private suspend fun verifiedTrashEntry(item: TrashItem): DirectoryEntry? {
        if (item.state != TrashItemState.ACTIVE || item.device == null || item.inode == null) return null
        val stat = fileSystem.stat(item.trashedPath)
        val identity = fileSystem.identity(item.trashedPath)
        return if (stat is OperationResult.Success && identity is OperationResult.Success &&
            stat.value.path == item.trashedPath && !stat.value.symbolicLink &&
            stat.value.type == item.entryType && identity.value.device == item.device &&
            identity.value.inode == item.inode
        ) stat.value else null
    }

    private suspend fun ensureTrashDirectory(): OperationResult<DirectoryEntry> {
        var current = SHARED_ROOT
        var latest: DirectoryEntry? = null
        for (name in listOf(".iSaver", "Trash", "files")) {
            val folder = FolderName.parse(name).getOrElse {
                return OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法创建回收站")
            }
            val path = FolderName.join(current, folder)
            latest = when (val stat = fileSystem.stat(path)) {
                is OperationResult.Success -> if (stat.value.type == EntryType.DIRECTORY &&
                    !stat.value.symbolicLink && stat.value.writable
                ) stat.value else return OperationResult.Failure(ErrorCode.NOT_WRITABLE, "回收站目录不可用")
                is OperationResult.Failure -> if (stat.code == ErrorCode.NOT_FOUND) {
                    when (val created = fileSystem.createDirectory(current, folder)) {
                        is OperationResult.Success -> created.value
                        is OperationResult.Failure -> return created
                    }
                } else return stat
            }
            current = path
        }
        return OperationResult.Success(requireNotNull(latest))
    }

    private fun TrashItemEntity.toModel() = TrashItem(
        id, RootPath.parse(originalPath).getOrThrow(), RootPath.parse(originalParent).getOrThrow(),
        originalName, RootPath.parse(trashedPath).getOrThrow(), trashedName, EntryType.valueOf(entryType),
        sizeBytes, device, inode, TrashItemState.valueOf(state), deletedAt,
    )

    private fun invalidSource() = OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, "无法回收此项目")
    private fun invalidTrashItem() = OperationResult.Failure(ErrorCode.OUTCOME_UNCERTAIN, "回收项目已变化，请刷新核对")
    private fun isSharedStorage(path: RootPath) = path == SHARED_ROOT || path.value.startsWith("${SHARED_ROOT.value}/")

    companion object {
        val SHARED_ROOT: RootPath = RootPath.parse("/storage/emulated/0").getOrThrow()
        val TRASH_ROOT: RootPath = RootPath.parse("/storage/emulated/0/.iSaver/Trash").getOrThrow()
        val TRASH_FILES: RootPath = RootPath.parse("/storage/emulated/0/.iSaver/Trash/files").getOrThrow()
    }
}
