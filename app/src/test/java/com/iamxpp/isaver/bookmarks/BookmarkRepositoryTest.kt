package com.iamxpp.isaver.bookmarks

import com.iamxpp.isaver.data.local.BookmarkDao
import com.iamxpp.isaver.data.local.BookmarkEntity
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

        private fun emit() {
            flow.value = rows.values.sortedWith(
                compareByDescending<BookmarkEntity> { it.createdAt }.thenBy { it.absolutePath },
            )
        }
    }

    private fun root(value: String): RootPath = RootPath.parse(value).getOrThrow()
}
