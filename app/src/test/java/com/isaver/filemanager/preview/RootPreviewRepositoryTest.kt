package com.isaver.filemanager.preview

import com.isaver.filemanager.data.root.RootFileSystem
import com.isaver.filemanager.data.root.RootFileVersion
import com.isaver.filemanager.data.root.RootFileChunk
import com.isaver.filemanager.data.root.RootFileMetadata
import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.FolderName
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPreviewRepositoryTest {
    @Test
    fun `reads utf8 text without reading past the preview limit`() = runTest {
        val bytes = "第一行\nsecond".toByteArray()
        val result = repository(bytes, "note.txt").preview(entry("note.txt", bytes.size.toLong()))

        assertEquals(PreviewContent.Text("第一行\nsecond"), (result as OperationResult.Success).value)
    }

    @Test
    fun `rejects invalid utf8 as a text preview`() = runTest {
        val result = repository(byteArrayOf(0xc3.toByte(), 0x28), "note.txt")
            .preview(entry("note.txt", 2L))

        assertTrue(result is OperationResult.Failure)
    }

    @Test
    fun `returns image bytes for a recognized png`() = runTest {
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2)
        val result = repository(png, "photo.png").preview(entry("photo.png", png.size.toLong()))

        assertArrayEquals(png, ((result as OperationResult.Success).value as PreviewContent.Image).bytes)
    }

    @Test
    fun `rejects previews larger than the configured limit`() = runTest {
        val result = repository(ByteArray(RootPreviewRepository.MAX_TEXT_BYTES + 1), "note.txt")
            .preview(entry("note.txt", (RootPreviewRepository.MAX_TEXT_BYTES + 1).toLong()))

        assertTrue(result is OperationResult.Failure)
    }

    @Test
    fun `rejects a file that changes between range reads`() = runTest {
        val bytes = ByteArray(4 * 1024 * 1024 + 1) { 'a'.code.toByte() }
        val fileSystem = FakeFileSystem(bytes, "photo.png", changingVersion = true)
        val result = RootPreviewRepository(fileSystem).preview(entry("photo.png", bytes.size.toLong()))

        assertTrue(result is OperationResult.Failure)
    }

    private fun repository(bytes: ByteArray, name: String) =
        RootPreviewRepository(FakeFileSystem(bytes, name))

    private fun entry(name: String, size: Long) = DirectoryEntry(
        RootPath.parse("/data/local/tmp/$name").getOrThrow(), name, EntryType.FILE, size, 1L,
        readable = true, writable = false, symbolicLink = false,
    )

    private class FakeFileSystem(
        private val bytes: ByteArray,
        private val name: String,
        private val changingVersion: Boolean = false,
    ) : RootFileSystem {
        private var reads = 0
        private fun version() = RootFileVersion(
            bytes.size.toLong(), 1L, 2L, 3L, 4L, if (changingVersion && reads > 1) 8L else 5L, 6L,
        )

        override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> =
            OperationResult.Success(DirectoryEntry(path, name, EntryType.FILE, bytes.size.toLong(), 1L, true, false, false))

        override suspend fun identity(path: RootPath) =
            OperationResult.Success(com.isaver.filemanager.domain.RootEntryIdentity(1L, 2L))

        override suspend fun canonicalize(path: RootPath) = OperationResult.Success(path)

        override suspend fun readRange(source: RootPath, offset: Long, count: Long): OperationResult<RootFileChunk> {
            reads += 1
            return OperationResult.Success(RootFileChunk(bytes.copyOfRange(offset.toInt(), (offset + count).toInt()), version()))
        }

        override suspend fun metadata(source: RootPath) =
            OperationResult.Success(RootFileMetadata(0x1a4, 0L, 0L, 1L, 2L))

        override suspend fun createDirectory(parent: RootPath, name: FolderName): OperationResult<DirectoryEntry> = error("unused")
        override suspend fun copyToOutput(source: RootPath, output: OutputStream): OperationResult<Long> = error("unused")
    }
}
