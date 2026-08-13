package com.iamxpp.isaver.bookmarks

import com.iamxpp.isaver.data.local.BookmarkDao
import com.iamxpp.isaver.data.local.BookmarkEntity
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootEntryIdentity
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkRepositoryTest {
    @Test fun `adds replaces and removes bookmarks in newest first order`() = runTest {
        val dao = FakeBookmarkDao()
        var now = 10L
        val repository = BookmarkRepository(dao) { now }
        val documents = root("/storage/emulated/0/Documents")
        val downloads = root("/storage/emulated/0/Download")

        repository.add(documents, "文档")
        now = 20L
        repository.add(downloads, "下载")

        assertEquals(listOf(downloads, documents), repository.bookmarks.first().map { it.path })

        now = 30L
        repository.add(documents, "工作文档")
        val updated = repository.bookmarks.first()
        assertEquals(listOf(documents, downloads), updated.map { it.path })
        assertEquals("工作文档", updated.first().displayName)

        repository.remove(updated.first())
        assertEquals(listOf(downloads), repository.bookmarks.first().map { it.path })
    }

    @Test fun `stores file type identity and availability`() = runTest {
        val dao = FakeBookmarkDao()
        val repository = BookmarkRepository(dao) { 40L }
        val file = root("/data/local/tmp/report.txt")

        repository.add(
            path = file,
            displayName = "报告",
            type = EntryType.FILE,
            identity = RootEntryIdentity(8L, 99L),
        )
        repository.setAvailability(file, false)

        val bookmark = repository.bookmarks.first().single()
        assertEquals(EntryType.FILE, bookmark.type)
        assertEquals(RootEntryIdentity(8L, 99L), bookmark.identity)
        assertEquals(false, bookmark.available)
    }

    @Test fun `updates details and relocates a bookmark without losing custom name`() = runTest {
        val repository = BookmarkRepository(FakeBookmarkDao()) { 50L }
        val original = root("/data/local/tmp/report.txt")
        val target = root("/storage/emulated/0/Documents/report.txt")
        repository.add(original, "report.txt", EntryType.FILE, RootEntryIdentity(1L, 2L))
        var bookmark = repository.bookmarks.first().single()
        repository.updateDetails(bookmark, "季度报告", "GREEN", "工作")
        bookmark = repository.bookmarks.first().single()
        repository.relocate(bookmark, target, "report.txt", RootEntryIdentity(3L, 4L))

        val moved = repository.bookmarks.first().single()
        assertEquals(target, moved.path)
        assertEquals("季度报告", moved.displayName)
        assertEquals("GREEN", moved.colorKey)
        assertEquals("工作", moved.groupName)
        assertEquals(RootEntryIdentity(3L, 4L), moved.identity)
    }

    private class FakeBookmarkDao : BookmarkDao {
        private val rows = linkedMapOf<String, BookmarkEntity>()
        private val flow = MutableStateFlow<List<BookmarkEntity>>(emptyList())

        override fun observeAll(): Flow<List<BookmarkEntity>> = flow

        override suspend fun upsert(entity: BookmarkEntity) {
            rows[entity.absolutePath] = entity
            emit()
        }

        override suspend fun delete(entity: BookmarkEntity) {
            rows.remove(entity.absolutePath)
            emit()
        }

        override suspend fun setAvailability(absolutePath: String, available: Boolean) {
            rows[absolutePath]?.let { rows[absolutePath] = it.copy(available = available) }
            emit()
        }

        override suspend fun relocate(
            oldPath: String,
            newPath: String,
            displayName: String,
            entryType: String,
            device: Long?,
            inode: Long?,
        ) {
            rows.remove(oldPath)?.let {
                rows[newPath] = it.copy(
                    absolutePath = newPath,
                    displayName = displayName,
                    entryType = entryType,
                    device = device,
                    inode = inode,
                    available = true,
                )
            }
            emit()
        }

        private fun emit() {
            flow.value = rows.values.sortedWith(
                compareByDescending<BookmarkEntity> { it.createdAt }.thenBy { it.absolutePath },
            )
        }
    }

    private fun root(value: String): RootPath = RootPath.parse(value).getOrThrow()
}
