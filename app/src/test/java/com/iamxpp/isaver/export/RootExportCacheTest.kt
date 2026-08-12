package com.iamxpp.isaver.export

import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootExportCacheTest {
    private val roots = mutableListOf<File>()
    private val uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")

    @Test
    fun `copies a readable root file to an atomically published private cache`() = runTest {
        val bytes = "root-data".toByteArray()
        val root = tempRoot()
        val cache = cache(
            root = root,
            fileSystem = FakeFileSystem { output ->
                output.write(bytes)
                OperationResult.Success(bytes.size.toLong())
            },
        )

        val result = cache.cache(entry(size = bytes.size.toLong()), "application/pdf")

        assertTrue(result is OperationResult.Success)
        val cached = (result as OperationResult.Success<CachedExportFile>).value
        assertEquals("$uuid.export", cached.file.name)
        assertEquals("report.pdf", cached.displayName)
        assertEquals("application/pdf", cached.mimeType)
        assertEquals(bytes.size.toLong(), cached.sizeBytes)
        assertArrayEquals(bytes, cached.file.readBytes())
        assertTrue(cache.validateNow(cached))
        assertFalse(File(root, "export/$uuid.tmp").exists())
    }

    @Test
    fun `reads only a bounded prefix from a validated export`() = runTest {
        val bytes = ByteArray(128) { it.toByte() }
        val root = tempRoot()
        val cache = cache(
            root = root,
            fileSystem = FakeFileSystem { output ->
                output.write(bytes)
                OperationResult.Success(bytes.size.toLong())
            },
        )
        val cached = (cache.cache(entry(size = bytes.size.toLong()), "application/octet-stream") as
            OperationResult.Success<CachedExportFile>).value

        assertArrayEquals(bytes.copyOf(64), cache.readPrefix(cached))
        assertEquals(null, cache.readPrefix(cached.copy(inode = -1L)))
    }

    @Test
    fun `rejects unsafe entries and removes partial or mismatched stages`() = runTest {
        val root = tempRoot()
        var copyCalls = 0
        val cache = cache(
            root = root,
            fileSystem = FakeFileSystem { output ->
                copyCalls += 1
                output.write(byteArrayOf(1, 2, 3))
                OperationResult.Success(4L)
            },
        )

        val symlink = cache.cache(entry(symbolicLink = true), "application/pdf")
        val mismatched = cache.cache(entry(), "application/pdf")

        assertTrue(symlink is OperationResult.Failure)
        assertEquals(ErrorCode.SOURCE_UNREADABLE, (symlink as OperationResult.Failure).code)
        assertTrue(mismatched is OperationResult.Failure)
        assertEquals(1, copyCalls)
        assertTrue(File(root, "export").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cancellation removes both stage and final cache files`() = runTest {
        val root = tempRoot()
        val cache = cache(
            root = root,
            fileSystem = FakeFileSystem { output ->
                output.write(byteArrayOf(1, 2))
                throw CancellationException("cancel")
            },
        )

        try {
            cache.cache(entry(), "application/pdf")
            error("expected cancellation")
        } catch (_: CancellationException) {
            Unit
        }

        assertTrue(File(root, "export").listFiles().orEmpty().isEmpty())
    }

    @After
    fun cleanup() {
        roots.forEach(File::deleteRecursively)
    }

    private fun cache(root: File, fileSystem: RootFileSystem) = RootExportCache(
        rootFileSystem = fileSystem,
        cacheDir = root,
        ioDispatcher = Dispatchers.Unconfined,
        uuidFactory = { uuid },
        identityOf = { file ->
            ExportFileIdentity(
                device = 7L,
                inode = if (file.exists()) 9L else -1L,
                sizeBytes = file.length(),
                regularFile = file.isFile,
            )
        },
        atomicMove = { source, target ->
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        },
    )

    private fun tempRoot(): File = Files.createTempDirectory("isaver-root-export").toFile().also(roots::add)

    private fun entry(
        size: Long = 4L,
        symbolicLink: Boolean = false,
    ) = DirectoryEntry(
        path = RootPath.parse("/data/local/tmp/report.pdf").getOrThrow(),
        name = "report.pdf",
        type = EntryType.FILE,
        sizeBytes = size,
        modifiedAtEpochSeconds = 1L,
        readable = true,
        writable = false,
        symbolicLink = symbolicLink,
    )

    private class FakeFileSystem(
        private val copy: suspend (OutputStream) -> OperationResult<Long>,
    ) : RootFileSystem {
        override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> = error("unused")
        override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> = error("unused")
        override suspend fun createDirectory(
            parent: RootPath,
            name: FolderName,
        ): OperationResult<DirectoryEntry> = error("unused")
        override suspend fun copyToOutput(source: RootPath, output: OutputStream): OperationResult<Long> = copy(output)
    }
}
