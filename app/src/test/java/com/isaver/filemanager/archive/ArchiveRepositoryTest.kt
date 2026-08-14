package com.isaver.filemanager.archive

import com.isaver.filemanager.data.root.DirectorySnapshot
import com.isaver.filemanager.data.root.AppCachePath
import com.isaver.filemanager.data.root.RootFileSystem
import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryName
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.FolderName
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.transfer.CachedIncomingFile
import com.isaver.filemanager.transfer.OutputNameDraft
import com.isaver.filemanager.transfer.TransferState
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveRepositoryTest {
    @Test
    fun `creates zip from root file and directory then publishes it`() = runTest {
        val cacheDir = Files.createTempDirectory("isaver-archive-repository").toFile()
        val root = FakeRootFileSystem().apply {
            addFile("/single.txt", "one")
            addDirectory("/folder", listOf(fileEntry("/folder/nested.txt", 3)))
            addFile("/folder/nested.txt", "two")
        }
        val published = mutableListOf<CachedIncomingFile>()
        val repository = repository(cacheDir, root) { cached, _, _ ->
            published += cached
            flowOf(successTransfer("archive.zip", cached.sizeBytes))
        }
        try {
            val states = repository.createZip(
                sources = listOf(fileEntry("/single.txt", 3), directoryEntry("/folder")),
                targetDirectory = path("/target"),
                outputName = OutputNameDraft("archive", "zip"),
            ).toList()

            assertTrue(states.first() is ArchiveState.Preparing)
            assertTrue(states.last() is ArchiveState.Success)
            val listing = LocalArchiveEngine().inspect(published.single().file).getOrThrow()
            assertEquals(listOf("single.txt", "folder", "folder/nested.txt"), listing.entries.map { it.path })
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `creates and publishes each supported archive format`() = runTest {
        val cacheDir = Files.createTempDirectory("isaver-archive-formats").toFile()
        val root = FakeRootFileSystem().apply { addFile("/single.txt", "one") }
        val published = mutableListOf<Pair<OutputNameDraft, CachedIncomingFile>>()
        val repository = repository(cacheDir, root) { cached, name, _ ->
            published += name to cached
            flowOf(successTransfer(name.toEntryName().getOrThrow().value, cached.sizeBytes))
        }
        try {
            listOf(
                ArchiveFormat.ZIP to OutputNameDraft("archive", "zip"),
                ArchiveFormat.TAR to OutputNameDraft("archive", "tar"),
                ArchiveFormat.TAR_GZ to OutputNameDraft("archive", "tar.gz"),
                ArchiveFormat.SEVEN_Z to OutputNameDraft("archive", "7z"),
            ).forEach { (format, outputName) ->
                val states = repository.createArchive(
                    sources = listOf(fileEntry("/single.txt", 3)),
                    targetDirectory = path("/target"),
                    outputName = outputName,
                    format = format,
                ).toList()
                assertEquals(format, (states.last() as ArchiveState.Success).format)
            }
            assertEquals(
                listOf(ArchiveFormat.ZIP, ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.SEVEN_Z),
                published.map { (_, cached) -> LocalArchiveEngine().inspect(cached.file).getOrThrow().format },
            )
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `rejects unsupported creation format and mismatched extension`() = runTest {
        val cacheDir = Files.createTempDirectory("isaver-archive-format-rejection").toFile()
        val root = FakeRootFileSystem().apply { addFile("/single.txt", "one") }
        var publishCount = 0
        val repository = repository(cacheDir, root) { cached, name, target ->
            publishCount += 1
            flowOf(successTransfer(name.toEntryName().getOrThrow().value, cached.sizeBytes, target))
        }
        try {
            val mismatched = repository.createArchive(
                sources = listOf(fileEntry("/single.txt", 3)),
                targetDirectory = path("/target"),
                outputName = OutputNameDraft("archive", "zip"),
                format = ArchiveFormat.TAR,
            ).toList()
            val unsupported = repository.createArchive(
                sources = listOf(fileEntry("/single.txt", 3)),
                targetDirectory = path("/target"),
                outputName = OutputNameDraft("archive", "rar"),
                format = ArchiveFormat.RAR,
            ).toList()

            assertTrue(mismatched.last() is ArchiveState.Failure)
            assertTrue(unsupported.last() is ArchiveState.Failure)
            assertEquals(0, publishCount)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `inspects archive copied from root and cleans private cache`() = runTest {
        val cacheDir = Files.createTempDirectory("isaver-archive-inspect").toFile()
        val archive = File(cacheDir, "fixture.zip")
        ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("docs/readme.txt"))
            output.write("payload".toByteArray())
            output.closeEntry()
        }
        val root = FakeRootFileSystem().apply { addFile("/source.zip", archive.readBytes()) }
        try {
            val result = repository(cacheDir, root).inspect(path("/source.zip"))
            assertEquals(ArchiveFormat.ZIP, (result as OperationResult.Success<ArchiveListing>).value.format)
            assertEquals(listOf("docs/readme.txt"), result.value.entries.map { it.path })
            assertTrue(File(cacheDir, "incoming").listFiles().isNullOrEmpty())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    private fun repository(
        cacheDir: File,
        root: RootFileSystem,
        publish: (CachedIncomingFile, OutputNameDraft, RootPath) -> Flow<TransferState> = { cached, name, target ->
            flowOf(successTransfer(name.toEntryName().getOrThrow().value, cached.sizeBytes, target))
        },
    ) = ArchiveRepository(
        rootFileSystem = root,
        localEngine = LocalArchiveEngine(),
        cacheDir = cacheDir,
        publish = publish,
        cachedFactory = { file ->
            Result.success(
                CachedIncomingFile(
                    file,
                    file.length(),
                    AppCachePath.fromIncomingCacheFile(cacheDir, file) { 1L to file.name.hashCode().toLong() }
                        .getOrThrow(),
                ),
            )
        },
    )

    private class FakeRootFileSystem : RootFileSystem {
        private val bytes = mutableMapOf<String, ByteArray>()
        private val directories = mutableMapOf<String, List<DirectoryEntry>>()
        val createdDirectories = mutableListOf<String>()

        fun addFile(path: String, content: String) = addFile(path, content.toByteArray())

        fun addFile(path: String, content: ByteArray) {
            bytes[path] = content
        }

        fun addDirectory(path: String, children: List<DirectoryEntry>) {
            directories[path] = children
        }

        override suspend fun copyToOutput(source: RootPath, output: OutputStream): OperationResult<Long> {
            val content = bytes[source.value]
                ?: return OperationResult.Failure(ErrorCode.NOT_FOUND, "路径不存在")
            output.write(content)
            return OperationResult.Success(content.size.toLong())
        }

        override suspend fun readDirectory(path: RootPath): OperationResult<DirectorySnapshot> {
            val entries = directories[path.value]
                ?: return OperationResult.Failure(ErrorCode.NOT_FOUND, "路径不存在")
            return OperationResult.Success(DirectorySnapshot(1, 1, true, true, entries))
        }

        override suspend fun createDirectory(
            parent: RootPath,
            name: FolderName,
        ): OperationResult<DirectoryEntry> {
            val child = "${parent.value.trimEnd('/')}/${name.value}"
            createdDirectories += child
            return OperationResult.Success(directoryEntry(child))
        }

        override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> = when {
            bytes.containsKey(path.value) -> OperationResult.Success(fileEntry(path.value, bytes.getValue(path.value).size.toLong()))
            directories.containsKey(path.value) || path.value == "/target" || path.value in createdDirectories ->
                OperationResult.Success(directoryEntry(path.value))
            else -> OperationResult.Failure(ErrorCode.NOT_FOUND, "路径不存在")
        }

        override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> = OperationResult.Success(path)
    }

    private companion object {
        fun path(value: String) = RootPath.parse(value).getOrThrow()

        fun fileEntry(value: String, size: Long) = DirectoryEntry(
            path(value), value.substringAfterLast('/'), EntryType.FILE, size, 1, true, true, false,
        )

        fun directoryEntry(value: String) = DirectoryEntry(
            path(value), value.substringAfterLast('/'), EntryType.DIRECTORY, null, 1, true, true, false,
        )

        fun successTransfer(
            name: String,
            size: Long,
            target: RootPath = path("/target"),
        ) = TransferState.Success(
            DirectoryEntry(
                path("${target.value.trimEnd('/')}/$name"), name, EntryType.FILE, size, 1, true, true, false,
            ),
            EntryName.parse(name).getOrThrow(),
        )
    }
}
