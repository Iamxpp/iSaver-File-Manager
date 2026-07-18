package com.iamxpp.isaver.archive

import com.iamxpp.isaver.data.root.AppCachePath
import com.iamxpp.isaver.data.root.DirectorySnapshot
import com.iamxpp.isaver.data.root.ExtractionStage
import com.iamxpp.isaver.data.root.RootFileIdentity
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.data.root.RootTransferSource
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.transfer.CachedIncomingFile
import com.iamxpp.isaver.transfer.OutputNameDraft
import com.iamxpp.isaver.transfer.TransferState
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveExtractionRepositoryTest {
    @Test
    fun `extract writes only to one stage commits once and records definite output`() = runTest {
        fixture(listOf("docs/readme.txt" to "one", "docs/second.txt" to "two", "empty/" to null)) { cacheDir, root ->
            val harness = repository(cacheDir, root)

            val states = harness.repository.extract(path("/source.zip"), path("/target")).toList()

            val success = states.last() as ArchiveState.Success
            assertEquals("/target/source", success.output.path.value)
            assertEquals(listOf("docs", "empty"), root.createdStageDirectories)
            assertEquals(listOf("docs/readme.txt", "docs/second.txt"), root.transferredPaths)
            assertEquals(listOf("source"), root.commitNames)
            assertEquals(0, root.cleanupCount)
            assertEquals(2, harness.issued.map { it.token }.distinct().size)
            assertEquals(harness.issued, harness.revoked)
            assertEquals(listOf(success.output), harness.extractedRecords)
        }
    }

    @Test
    fun `commit collision retries the same stage with numbered folder`() = runTest {
        fixture(listOf("item.txt" to "payload")) { cacheDir, root ->
            root.collisionsBeforeSuccess = 1
            val states = repository(cacheDir, root).repository
                .extract(path("/source.tar.gz"), path("/target"))
                .toList()

            assertTrue(states.last() is ArchiveState.Success)
            assertEquals(listOf("source", "source (1)"), root.commitNames)
            assertEquals(0, root.cleanupCount)
        }
    }

    @Test
    fun `stage write failure cleans stage and never publishes visible directory`() = runTest {
        fixture(listOf("item.txt" to "payload")) { cacheDir, root ->
            root.transferFailure = OperationResult.Failure(ErrorCode.NO_SPACE, "存储空间不足")

            val states = repository(cacheDir, root).repository
                .extract(path("/source.zip"), path("/target"))
                .toList()

            assertEquals(ErrorCode.NO_SPACE, (states.last() as ArchiveState.Failure).code)
            assertEquals(1, root.cleanupCount)
            assertTrue(root.commitNames.isEmpty())
        }
    }

    @Test
    fun `cancellation waits for identity-bound cleanup`() = runTest {
        fixture(listOf("item.txt" to "payload")) { cacheDir, root ->
            root.blockTransfer = true
            val extraction = async {
                repository(cacheDir, root).repository
                    .extract(path("/source.zip"), path("/target"))
                    .collect {}
            }
            root.transferStarted.await()

            extraction.cancelAndJoin()

            assertEquals(1, root.cleanupCount)
            assertTrue(root.commitNames.isEmpty())
        }
    }

    @Test
    fun `uncertain final commit does not cleanup or guess a visible path`() = runTest {
        fixture(listOf("item.txt" to "payload")) { cacheDir, root ->
            root.uncertainCommit = true

            val states = repository(cacheDir, root).repository
                .extract(path("/source.zip"), path("/target"))
                .toList()

            assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (states.last() as ArchiveState.Failure).code)
            assertEquals(0, root.cleanupCount)
            assertEquals(listOf("source"), root.commitNames)
        }
    }

    private suspend fun fixture(
        entries: List<Pair<String, String?>>,
        block: suspend (File, FakeRootFileSystem) -> Unit,
    ) {
        val cacheDir = Files.createTempDirectory("isaver-stage-extract").toFile()
        val archive = File(cacheDir, "fixture.zip")
        ZipOutputStream(archive.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                if (content != null) output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        val root = FakeRootFileSystem(archive.readBytes())
        try {
            block(cacheDir, root)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    private fun repository(cacheDir: File, root: FakeRootFileSystem): Harness {
        val issued = mutableListOf<RootTransferSource>()
        val revoked = mutableListOf<RootTransferSource>()
        val extracted = mutableListOf<DirectoryEntry>()
        var sequence = 0
        val repository = ArchiveRepository(
            rootFileSystem = root,
            localEngine = LocalArchiveEngine(),
            cacheDir = cacheDir,
            publish = { cached, name, target ->
                flowOf(
                    TransferState.Success(
                        fileEntry("${target.value}/${name.toEntryName().getOrThrow().value}", cached.sizeBytes),
                        name.toEntryName().getOrThrow(),
                    ),
                )
            },
            issueSource = { cached ->
                sequence++
                val token = sequence.toString(16).padStart(64, '0')
                val source = RootTransferSource(
                    "content://com.iamxpp.isaver.incoming-stream/incoming/$token",
                    cached.sizeBytes,
                    token,
                )
                issued += source
                OperationResult.Success(source)
            },
            revokeSource = { revoked += it },
            recordExtracted = { extracted += it },
            cachedFactory = { file ->
                Result.success(
                    CachedIncomingFile(
                        file,
                        file.length(),
                        AppCachePath.fromIncomingCacheFile(cacheDir, file) {
                            1L to file.name.hashCode().toLong()
                        }.getOrThrow(),
                    ),
                )
            },
        )
        return Harness(repository, issued, revoked, extracted)
    }

    private data class Harness(
        val repository: ArchiveRepository,
        val issued: List<RootTransferSource>,
        val revoked: List<RootTransferSource>,
        val extractedRecords: List<DirectoryEntry>,
    )

    private class FakeRootFileSystem(private val archiveBytes: ByteArray) : RootFileSystem {
        val createdStageDirectories = mutableListOf<String>()
        val transferredPaths = mutableListOf<String>()
        val commitNames = mutableListOf<String>()
        val transferStarted = CompletableDeferred<Unit>()
        var cleanupCount = 0
        var collisionsBeforeSuccess = 0
        var transferFailure: OperationResult.Failure? = null
        var blockTransfer = false
        var uncertainCommit = false

        private val stage = ExtractionStage.create(
            path("/target"), path("/target"), RootFileIdentity(1, 2),
            ".isaver-extract-123e4567-e89b-12d3-a456-426614174000",
            RootFileIdentity(3, 4),
        ).getOrThrow()

        override suspend fun copyToOutput(source: RootPath, output: OutputStream): OperationResult<Long> {
            output.write(archiveBytes)
            return OperationResult.Success(archiveBytes.size.toLong())
        }

        override suspend fun prepareExtractionStage(parent: RootPath): OperationResult<ExtractionStage> =
            OperationResult.Success(stage)

        override suspend fun createExtractionDirectory(
            stage: ExtractionStage,
            relativePath: String,
        ): OperationResult<Unit> {
            createdStageDirectories += relativePath
            return OperationResult.Success(Unit)
        }

        override suspend fun transferIntoExtractionStage(
            stage: ExtractionStage,
            relativeParent: String,
            source: RootTransferSource,
            finalName: EntryName,
        ): OperationResult<Unit> {
            transferStarted.complete(Unit)
            if (blockTransfer) awaitCancellation()
            transferFailure?.let { return it }
            transferredPaths += listOf(relativeParent, finalName.value).filter(String::isNotEmpty).joinToString("/")
            return OperationResult.Success(Unit)
        }

        override suspend fun commitExtractionStage(
            stage: ExtractionStage,
            finalName: FolderName,
        ): OperationResult<DirectoryEntry> {
            commitNames += finalName.value
            if (uncertainCommit) {
                return OperationResult.Failure(ErrorCode.OUTCOME_UNCERTAIN, "结果不确定")
            }
            if (collisionsBeforeSuccess-- > 0) {
                return OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "已存在")
            }
            return OperationResult.Success(directoryEntry("/target/${finalName.value}"))
        }

        override suspend fun cleanupExtractionStage(stage: ExtractionStage): OperationResult<Unit> {
            cleanupCount++
            return OperationResult.Success(Unit)
        }

        override suspend fun readDirectory(path: RootPath): OperationResult<DirectorySnapshot> = error("unused")
        override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> = error("unused")
        override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> = OperationResult.Success(path)
        override suspend fun createDirectory(parent: RootPath, name: FolderName): OperationResult<DirectoryEntry> = error("unused")
    }

    private companion object {
        fun path(value: String) = RootPath.parse(value).getOrThrow()
        fun fileEntry(value: String, size: Long) = DirectoryEntry(
            path(value), value.substringAfterLast('/'), EntryType.FILE, size, 1, true, true, false,
        )
        fun directoryEntry(value: String) = DirectoryEntry(
            path(value), value.substringAfterLast('/'), EntryType.DIRECTORY, null, 1, true, true, false,
        )
    }
}
