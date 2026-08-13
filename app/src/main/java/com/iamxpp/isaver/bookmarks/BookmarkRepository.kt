package com.iamxpp.isaver.bookmarks

import com.iamxpp.isaver.data.local.BookmarkDao
import com.iamxpp.isaver.data.local.BookmarkEntity
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootEntryIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Bookmark(
    val path: RootPath,
    val displayName: String,
    val createdAt: Long,
    val type: EntryType = EntryType.DIRECTORY,
    val identity: RootEntryIdentity? = null,
    val available: Boolean = true,
    val colorKey: String? = null,
    val groupName: String? = null,
)

enum class BookmarkColor(val key: String) {
    BLUE("BLUE"), GREEN("GREEN"), RED("RED"), YELLOW("YELLOW");

    companion object {
        fun normalize(value: String?): String? = entries.firstOrNull { it.key == value }?.key
    }
}

class BookmarkRepository internal constructor(
    private val dao: BookmarkDao,
    private val clock: () -> Long,
) {
    constructor(dao: BookmarkDao) : this(dao, System::currentTimeMillis)

    val bookmarks: Flow<List<Bookmark>> = dao.observeAll().map { rows ->
        rows.mapNotNull { row ->
            RootPath.parse(row.absolutePath).getOrNull()?.let {
                Bookmark(
                    path = it,
                    displayName = row.displayName,
                    createdAt = row.createdAt,
                    type = runCatching { EntryType.valueOf(row.entryType) }.getOrDefault(EntryType.DIRECTORY),
                    identity = if (row.device != null && row.inode != null) RootEntryIdentity(row.device, row.inode) else null,
                    available = row.available,
                    colorKey = row.colorKey,
                    groupName = row.groupName,
                )
            }
        }
    }

    suspend fun add(
        path: RootPath,
        displayName: String,
        type: EntryType = EntryType.DIRECTORY,
        identity: RootEntryIdentity? = null,
        colorKey: String? = null,
        groupName: String? = null,
    ) {
        dao.upsert(
            BookmarkEntity(
                absolutePath = path.value,
                displayName = displayName.ifBlank { path.value },
                createdAt = clock(),
                entryType = type.name,
                device = identity?.device,
                inode = identity?.inode,
                available = true,
                colorKey = BookmarkColor.normalize(colorKey),
                groupName = groupName?.trim()?.takeIf(String::isNotEmpty),
            ),
        )
    }

    suspend fun remove(bookmark: Bookmark) {
        dao.delete(bookmark.toEntity())
    }

    suspend fun setAvailability(path: RootPath, available: Boolean) = dao.setAvailability(path.value, available)

    suspend fun updateDetails(bookmark: Bookmark, displayName: String, colorKey: String?, groupName: String?) {
        dao.upsert(
            bookmark.copy(
                displayName = displayName.trim().ifEmpty { bookmark.path.value.substringAfterLast('/') },
                colorKey = BookmarkColor.normalize(colorKey),
                groupName = groupName?.trim()?.takeIf(String::isNotEmpty),
            ).toEntity(),
        )
    }

    suspend fun relocate(bookmark: Bookmark, newPath: RootPath, newDefaultName: String, identity: RootEntryIdentity?) {
        dao.relocate(
            oldPath = bookmark.path.value,
            newPath = newPath.value,
            displayName = if (bookmark.displayName == bookmark.path.value.substringAfterLast('/')) {
                newDefaultName
            } else bookmark.displayName,
            entryType = bookmark.type.name,
            device = identity?.device,
            inode = identity?.inode,
        )
    }

    private fun Bookmark.toEntity() = BookmarkEntity(
        absolutePath = path.value,
        displayName = displayName,
        createdAt = createdAt,
        entryType = type.name,
        device = identity?.device,
        inode = identity?.inode,
        available = available,
        colorKey = colorKey,
        groupName = groupName,
    )
}
