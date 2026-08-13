package com.iamxpp.isaver.texteditor

import com.iamxpp.isaver.data.root.RootFileChunk
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.data.root.RootFileVersion
import com.iamxpp.isaver.data.root.RootTransferSource
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditorRepositoryTest {
    @Test
    fun `load reads stable chunks and preserves document settings`() = runTest {
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "甲\r\n乙".toByteArray()
        val fileSystem = FakeFileSystem(bytes, replacement = "new\r\nline".toByteArray())
        val result = repository(fileSystem).load(entry(bytes.size.toLong()), PARENT)

        val loaded = (result as OperationResult.Success).value
        assertEquals("甲\n乙", loaded.document.text)
        assertEquals(LineEnding.CRLF, loaded.document.lineEnding)
        assertTrue(loaded.document.hasBom)
        assertEquals(VERSION.copy(sizeBytes = bytes.size.toLong()), loaded.version)
    }

    @Test
    fun `load rejects files over editor limit without root read`() = runTest {
        val fileSystem = FakeFileSystem(ByteArray(0))
        val result = repository(fileSystem).load(entry(TextEditorRepository.MAX_BYTES + 1), PARENT)

        assertEquals(ErrorCode.COMMAND_FAILED, (result as OperationResult.Failure).code)
        assertEquals(0, fileSystem.readCount)
    }

    @Test
    fun `load rejects version changes across chunks`() = runTest {
        val bytes = ByteArray(17) { 'a'.code.toByte() }
        val fileSystem = FakeFileSystem(bytes, changing = true)
        val result = repository(fileSystem, chunkBytes = 8).load(entry(bytes.size.toLong()), PARENT)

        assertEquals(ErrorCode.SOURCE_UNREADABLE, (result as OperationResult.Failure).code)
    }

    @Test
    fun `save issues encoded bytes and always releases capability`() = runTest {
        val bytes = "old".toByteArray()
        val fileSystem = FakeFileSystem(bytes, replacement = "new\r\nline".toByteArray())
        var issued: ByteArray? = null
        var released = false
        val repository = TextEditorRepository(
            fileSystem = fileSystem,
            issueContent = { content ->
                issued = content
                OperationResult.Success(EditorContent(stream(content.size.toLong())) { released = true })
            },
        )
        val loaded = LoadedTextFile(entry(3L), PARENT, TextDocument("old", TextEncoding.UTF8, LineEnding.LF, false), VERSION.copy(sizeBytes = 3L))
        val result = repository.save(loaded, loaded.document.copy(text = "new\nline", lineEnding = LineEnding.CRLF))

        assertTrue(result is OperationResult.Success)
        assertEquals("new\r\nline", issued!!.toString(Charsets.UTF_8))
        assertTrue(released)
        assertEquals(loaded.version, fileSystem.savedVersion)
    }

    private fun repository(fileSystem: FakeFileSystem, chunkBytes: Int = 1024 * 1024) = TextEditorRepository(
        fileSystem = fileSystem,
        chunkBytes = chunkBytes,
        issueContent = { OperationResult.Failure(ErrorCode.COMMAND_FAILED, "unused") },
    )

    private class FakeFileSystem(
        bytes: ByteArray,
        private val changing: Boolean = false,
        private val replacement: ByteArray? = null,
    ) : RootFileSystem {
        private var bytes = bytes
        var readCount = 0
        var savedVersion: RootFileVersion? = null
        override suspend fun readRange(source: RootPath, offset: Long, count: Long): OperationResult<RootFileChunk> {
            readCount++
            val version = VERSION.copy(
                sizeBytes = bytes.size.toLong(),
                changedSeconds = if (changing && readCount > 1) 99L else VERSION.changedSeconds,
            )
            return OperationResult.Success(RootFileChunk(bytes.copyOfRange(offset.toInt(), (offset + count).toInt()), version))
        }
        override suspend fun replaceFileAtomically(
            source: DirectoryEntry,
            sourceDirectory: RootPath,
            expectedVersion: RootFileVersion,
            content: RootTransferSource,
        ): OperationResult<DirectoryEntry> {
            savedVersion = expectedVersion
            replacement?.let { bytes = it }
            return OperationResult.Success(source.copy(sizeBytes = content.expectedSizeBytes))
        }
        override suspend fun stat(path: RootPath) = error("unused")
        override suspend fun canonicalize(path: RootPath) = error("unused")
        override suspend fun createDirectory(parent: RootPath, name: com.iamxpp.isaver.domain.FolderName) = error("unused")
    }

    private fun entry(size: Long) = DirectoryEntry(
        path("${PARENT.value}/note.txt"), "note.txt", EntryType.FILE, size, 1L, true, true, false,
    )
    private fun stream(size: Long) = RootTransferSource(
        "content://com.iamxpp.isaver.incoming-stream/incoming/${"ab".repeat(32)}", size, "ab".repeat(32),
    )
    private fun path(value: String) = RootPath.parse(value).getOrThrow()

    private companion object {
        val PARENT = RootPath.parse("/data/local/tmp/editor").getOrThrow()
        val VERSION = RootFileVersion(0L, 1L, 2L, 3L, 4L, 5L, 6L)
    }
}
