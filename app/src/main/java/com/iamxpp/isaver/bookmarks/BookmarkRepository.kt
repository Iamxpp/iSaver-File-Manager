package com.iamxpp.isaver.bookmarks

import com.iamxpp.isaver.data.local.BookmarkDao
import com.iamxpp.isaver.data.local.BookmarkEntity
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Bookmark(val path: RootPath, val displayName: String, val createdAt: Long)

class BookmarkRepository internal constructor(
    private val dao: BookmarkDao,
    private val clock: () -> Long,
) {
    constructor(dao: BookmarkDao) : this(dao, System::currentTimeMillis)

    val bookmarks: Flow<List<Bookmark>> = dao.observeAll().map { rows ->
        rows.mapNotNull { row ->
            RootPath.parse(row.absolutePath).getOrNull()?.let { Bookmark(it, row.displayName, row.createdAt) }
        }
    }

    suspend fun add(path: RootPath, displayName: String) {
        dao.upsert(BookmarkEntity(path.value, displayName.ifBlank { path.value }, clock()))
    }

    suspend fun remove(bookmark: Bookmark) {
        dao.delete(BookmarkEntity(bookmark.path.value, bookmark.displayName, bookmark.createdAt))
    }
}
