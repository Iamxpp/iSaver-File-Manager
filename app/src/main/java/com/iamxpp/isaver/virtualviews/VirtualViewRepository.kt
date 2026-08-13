package com.iamxpp.isaver.virtualviews

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.iamxpp.isaver.data.local.ISaverDatabase
import com.iamxpp.isaver.data.local.VirtualViewNodeDao
import com.iamxpp.isaver.data.local.VirtualViewNodeEntity
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootEntryIdentity
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class VirtualViewRepository internal constructor(
    private val database: ISaverDatabase,
    private val idFactory: () -> String,
    private val clock: () -> Long,
) {
    constructor(database: ISaverDatabase) : this(database, { java.util.UUID.randomUUID().toString() }, System::currentTimeMillis)

    private val dao: VirtualViewNodeDao = database.virtualViewNodeDao()

    fun observeChildren(parentFolderId: String?): Flow<List<VirtualViewNode>> =
        dao.observeChildren(parentFolderId).map { rows -> rows.mapNotNull(::toModel) }

    fun observeAll(): Flow<List<VirtualViewNode>> = dao.observeAll().map { rows -> rows.mapNotNull(::toModel) }

    suspend fun findNode(id: String): VirtualViewNode? = dao.findById(id)?.let(::toModel)

    suspend fun createFolder(parentFolderId: String?, name: String): VirtualViewResult = database.withTransaction {
        val displayName = normalizeName(name) ?: return@withTransaction VirtualViewResult.InvalidName
        if (parentFolderId != null && !isFolder(parentFolderId)) {
            return@withTransaction VirtualViewResult.InvalidParent
        }
        val now = clock()
        val id = idFactory()
        dao.insert(
            VirtualViewNodeEntity(
                id = id,
                parentId = parentFolderId,
                nodeType = VirtualViewNodeType.VIRTUAL_FOLDER.name,
                displayName = displayName,
                targetPath = null,
                entryType = null,
                device = null,
                inode = null,
                available = true,
                sortOrder = dao.nextSortOrder(parentFolderId),
                createdAt = now,
                updatedAt = now,
            ),
        )
        VirtualViewResult.Success(id)
    }

    suspend fun addReference(
        targetFolderId: String,
        path: RootPath,
        type: EntryType,
        identity: RootEntryIdentity?,
        displayName: String,
    ): VirtualViewResult = database.withTransaction {
        val name = normalizeName(displayName) ?: return@withTransaction VirtualViewResult.InvalidName
        if (type != EntryType.FILE && type != EntryType.DIRECTORY) {
            return@withTransaction VirtualViewResult.InvalidNode
        }
        if (!isFolder(targetFolderId)) return@withTransaction VirtualViewResult.InvalidParent
        if (dao.findReference(targetFolderId, path.value, type.name) != null) {
            return@withTransaction VirtualViewResult.DuplicateReference
        }
        val now = clock()
        val id = idFactory()
        try {
            dao.insert(
                VirtualViewNodeEntity(
                    id = id,
                    parentId = targetFolderId,
                    nodeType = VirtualViewNodeType.REAL_REFERENCE.name,
                    displayName = name,
                    targetPath = path.value,
                    entryType = type.name,
                    device = identity?.device,
                    inode = identity?.inode,
                    available = true,
                    sortOrder = dao.nextSortOrder(targetFolderId),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            VirtualViewResult.Success(id)
        } catch (_: SQLiteConstraintException) {
            VirtualViewResult.DuplicateReference
        }
    }

    suspend fun renameNode(nodeId: String, displayName: String): VirtualViewResult {
        val name = normalizeName(displayName) ?: return VirtualViewResult.InvalidName
        return if (dao.rename(nodeId, name, clock()) == 1) {
            VirtualViewResult.Success(nodeId)
        } else {
            VirtualViewResult.NotFound
        }
    }

    suspend fun moveNode(nodeId: String, targetFolderId: String?): VirtualViewResult = database.withTransaction {
        val node = dao.findById(nodeId) ?: return@withTransaction VirtualViewResult.NotFound
        if (targetFolderId != null && !isFolder(targetFolderId)) {
            return@withTransaction VirtualViewResult.InvalidParent
        }
        if (node.nodeType == VirtualViewNodeType.REAL_REFERENCE.name && targetFolderId == null) {
            return@withTransaction VirtualViewResult.InvalidParent
        }
        if (node.nodeType == VirtualViewNodeType.VIRTUAL_FOLDER.name && wouldCreateCycle(nodeId, targetFolderId)) {
            return@withTransaction VirtualViewResult.Cycle
        }
        if (node.nodeType == VirtualViewNodeType.REAL_REFERENCE.name && targetFolderId != null &&
            dao.findReference(targetFolderId, node.targetPath.orEmpty(), node.entryType.orEmpty()) != null
        ) {
            return@withTransaction VirtualViewResult.DuplicateReference
        }
        try {
            check(dao.move(nodeId, targetFolderId, dao.nextSortOrder(targetFolderId), clock()) == 1)
            VirtualViewResult.Success(nodeId)
        } catch (_: SQLiteConstraintException) {
            VirtualViewResult.DuplicateReference
        }
    }

    suspend fun deleteFolder(nodeId: String, confirmed: Boolean): VirtualViewResult = database.withTransaction {
        val folder = dao.findById(nodeId) ?: return@withTransaction VirtualViewResult.NotFound
        if (folder.nodeType != VirtualViewNodeType.VIRTUAL_FOLDER.name) {
            return@withTransaction VirtualViewResult.InvalidNode
        }
        val all = dao.findAll()
        val childrenByParent = all.groupBy { it.parentId }
        if (!confirmed && childrenByParent[nodeId].orEmpty().isNotEmpty()) {
            return@withTransaction VirtualViewResult.ConfirmationRequired
        }
        val pending = ArrayDeque<String>().apply { add(nodeId) }
        val visited = linkedSetOf<String>()
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) return@withTransaction VirtualViewResult.Cycle
            childrenByParent[current].orEmpty().forEach { pending.addLast(it.id) }
        }
        dao.deleteByIds(visited.toList())
        VirtualViewResult.Success(nodeId)
    }

    suspend fun removeReference(nodeId: String): VirtualViewResult = database.withTransaction {
        val node = dao.findById(nodeId) ?: return@withTransaction VirtualViewResult.NotFound
        if (node.nodeType != VirtualViewNodeType.REAL_REFERENCE.name) {
            return@withTransaction VirtualViewResult.InvalidNode
        }
        dao.deleteByIds(listOf(nodeId))
        VirtualViewResult.Success(nodeId)
    }

    suspend fun setReferenceAvailability(nodeId: String, available: Boolean): VirtualViewResult =
        if (dao.setAvailability(nodeId, available, clock()) == 1) {
            VirtualViewResult.Success(nodeId)
        } else {
            VirtualViewResult.NotFound
        }

    suspend fun cleanupEmptyLegacyMigrationFolder() {
        dao.deleteEmptyFolderById(MIGRATED_UNGROUPED_ID)
    }

    private suspend fun isFolder(id: String): Boolean =
        dao.findById(id)?.nodeType == VirtualViewNodeType.VIRTUAL_FOLDER.name

    private suspend fun wouldCreateCycle(nodeId: String, targetFolderId: String?): Boolean {
        var current = targetFolderId
        val visited = hashSetOf<String>()
        while (current != null) {
            if (current == nodeId || !visited.add(current)) return true
            current = dao.findById(current)?.parentId
        }
        return false
    }

    private fun normalizeName(value: String): String? = value.trim().takeIf {
        it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX_NAME_CODE_POINTS
    }

    private fun toModel(row: VirtualViewNodeEntity): VirtualViewNode? {
        return when (row.nodeType) {
            VirtualViewNodeType.VIRTUAL_FOLDER.name -> {
                if (row.targetPath != null || row.entryType != null || row.device != null || row.inode != null) return null
                VirtualViewNode.VirtualFolder(
                    row.id, row.parentId, row.displayName, row.sortOrder, row.createdAt, row.updatedAt,
                )
            }
            VirtualViewNodeType.REAL_REFERENCE.name -> {
                val parentId = row.parentId ?: return null
                val path = row.targetPath?.let { RootPath.parse(it).getOrNull() } ?: return null
                val type = row.entryType?.let { runCatching { EntryType.valueOf(it) }.getOrNull() } ?: return null
                if (type != EntryType.FILE && type != EntryType.DIRECTORY) return null
                val identity = if (row.device != null && row.inode != null) {
                    RootEntryIdentity(row.device, row.inode)
                } else {
                    null
                }
                VirtualViewNode.RealReference(
                    row.id, parentId, row.displayName, path, type, identity, row.available,
                    row.sortOrder, row.createdAt, row.updatedAt,
                )
            }
            else -> null
        }
    }

    private companion object {
        const val MAX_NAME_CODE_POINTS = 120
        const val MIGRATED_UNGROUPED_ID = "migration.virtual.ungrouped"
    }
}
