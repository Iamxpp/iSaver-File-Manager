package com.iamxpp.isaver.bookmarks

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.iamxpp.isaver.ISaverApplication
import com.iamxpp.isaver.data.local.BookmarkDao
import com.iamxpp.isaver.data.local.BookmarkEntity
import com.iamxpp.isaver.data.root.LibsuRootSession
import com.iamxpp.isaver.data.root.RootCommandCodec
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootBookmarkInstrumentedTest {
    @Test
    fun rootFileBookmarkBindsIdentityAndDetectsReplacement() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        root(app, "rm -rf -- ${quote(ROOT)}; mkdir -p -- ${quote(ROOT)}; printf %s first > ${quote(FILE)}")
        try {
            val entry = app.rootFileSystem.stat(path(FILE)) as OperationResult.Success
            val firstIdentity = app.rootFileSystem.identity(path(FILE)) as OperationResult.Success
            val repository = BookmarkRepository(MemoryBookmarkDao()) { 10L }

            repository.add(entry.value.path, entry.value.name, EntryType.FILE, firstIdentity.value)
            val bookmark = repository.bookmarks.first().single()
            assertEquals(EntryType.FILE, bookmark.type)
            assertEquals(firstIdentity.value, bookmark.identity)

            root(app, "printf %s second > ${quote(REPLACEMENT)}; mv -f -- ${quote(REPLACEMENT)} ${quote(FILE)}")
            val secondIdentity = app.rootFileSystem.identity(path(FILE)) as OperationResult.Success
            assertNotEquals(firstIdentity.value, secondIdentity.value)
            repository.setAvailability(bookmark.path, false)
            assertFalse(repository.bookmarks.first().single().available)
        } finally {
            root(app, "rm -rf -- ${quote(ROOT)}")
        }
    }

    private class MemoryBookmarkDao : BookmarkDao {
        private val rows = linkedMapOf<String, BookmarkEntity>()
        private val flow = MutableStateFlow<List<BookmarkEntity>>(emptyList())
        override fun observeAll(): Flow<List<BookmarkEntity>> = flow
        override suspend fun upsert(entity: BookmarkEntity) { rows[entity.absolutePath] = entity; emit() }
        override suspend fun delete(entity: BookmarkEntity) { rows.remove(entity.absolutePath); emit() }
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
        private fun emit() { flow.value = rows.values.toList() }
    }

    private fun path(value: String): RootPath = RootPath.parse(value).getOrThrow()

    private suspend fun root(app: ISaverApplication, command: String): String {
        val result = (app.rootSession as LibsuRootSession).shellCoordinator.execute(command)
        assertEquals(result.stderr.joinToString("\n"), 0, result.exitCode)
        return result.stdout.joinToString("\n")
    }

    private fun quote(value: String): String = RootCommandCodec.quote(value)

    private companion object {
        const val ROOT = "/data/local/tmp/isaver-test/bookmark"
        const val FILE = "$ROOT/report.txt"
        const val REPLACEMENT = "$ROOT/replacement.txt"
    }
}
