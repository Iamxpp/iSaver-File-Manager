package com.iamxpp.isaver.data.access

import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReadOnlyFileSystemTest {
    @Test
    fun `lists readable entries but always reports them as non writable`() = runTest {
        val directory = Files.createTempDirectory("isaver-local-read")
        val child = Files.write(directory.resolve("note.txt"), "hello".toByteArray())
        val fileSystem = LocalReadOnlyFileSystem()

        val result = fileSystem.readDirectory(directory.rootPath()) as OperationResult.Success<*>
        result.value as com.iamxpp.isaver.data.root.DirectorySnapshot
        val entry = result.value.entries.single()

        val childPath = child.toAbsolutePath().toString().replace('\\', '/')
        assertEquals(if (childPath.startsWith('/')) childPath else "/$childPath", entry.path.value)
        assertEquals(EntryType.FILE, entry.type)
        assertEquals(5L, entry.sizeBytes)
        assertTrue(entry.readable)
        assertFalse(entry.writable)
        assertFalse(result.value.parentWritable)
    }

    @Test
    fun `range and output reads use the local app process`() = runTest {
        val file = Files.write(Files.createTempFile("isaver-local-read", ".txt"), "abcdef".toByteArray())
        val fileSystem = LocalReadOnlyFileSystem()
        val output = ByteArrayOutputStream()

        val copied = fileSystem.copyToOutput(file.rootPath(), output) as OperationResult.Success<*>
        val range = fileSystem.readRange(file.rootPath(), 2, 3) as OperationResult.Success<*>
        range.value as com.iamxpp.isaver.data.root.RootFileChunk

        assertEquals(6L, copied.value)
        assertArrayEquals("abcdef".toByteArray(), output.toByteArray())
        assertArrayEquals("cde".toByteArray(), range.value.bytes)
    }

    @Test
    fun `all exposed mutations fail with non root read only reason`() = runTest {
        val directory = Files.createTempDirectory("isaver-local-write")
        val fileSystem = LocalReadOnlyFileSystem()

        val createDirectory = fileSystem.createDirectory(
            directory.rootPath(),
            FolderName.parse("new").getOrThrow(),
        )
        val createFile = fileSystem.createFileNoReplace(
            directory.rootPath(),
            EntryName.parse("new.txt").getOrThrow(),
        )

        listOf(createDirectory, createFile).forEach { result ->
            result as OperationResult.Failure
            assertEquals(ErrorCode.NOT_WRITABLE, result.code)
            assertEquals("非 Root 模式仅支持只读浏览", result.userMessage)
        }
        assertFalse(Files.exists(directory.resolve("new")))
        assertFalse(Files.exists(directory.resolve("new.txt")))
    }

    private fun java.nio.file.Path.rootPath(): RootPath {
        val value = toAbsolutePath().normalize().toString().replace('\\', '/')
        return RootPath.parse(if (value.startsWith('/')) value else "/$value").getOrThrow()
    }
}
