package com.isaver.filemanager.texteditor

import com.isaver.filemanager.data.root.RootFileVersion
import com.isaver.filemanager.domain.RootPath
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TextDraftStoreTest {
    @Test
    fun `draft restores only for the same file version and uses hashed filename`() = runTest {
        val root = Files.createTempDirectory("draft-test").toFile()
        try {
            val store = TextDraftStore(root)
            val path = RootPath.parse("/data/user/0/private/note.txt").getOrThrow()
            val document = TextDocument("changed", TextEncoding.UTF8, LineEnding.CRLF, true)
            store.write(path, VERSION, document)

            assertEquals(document, store.read(path, VERSION))
            assertNull(store.read(path, VERSION.copy(changedSeconds = 99L)))
            val draft = root.resolve("text-drafts").listFiles()!!.single()
            assertFalse(draft.name.contains("note"))
            assertFalse(draft.readText().contains(path.value))
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        val VERSION = RootFileVersion(7L, 1L, 2L, 3L, 4L, 5L, 6L)
    }
}
