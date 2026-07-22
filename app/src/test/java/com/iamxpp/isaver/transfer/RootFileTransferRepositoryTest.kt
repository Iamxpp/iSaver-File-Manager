package com.iamxpp.isaver.transfer

import com.iamxpp.isaver.data.root.AppCachePath
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.data.root.RootTransferSource
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RootFileTransferRepositoryTest {
    @Test
    fun `collision consumes and revokes one fresh capability per candidate`() = runTest {
        val cache = fakeCached()
        val fs = FakeFs(
            mutableListOf(
                failure(ErrorCode.ALREADY_EXISTS),
                success("archive (1).tar.gz"),
            ),
        )
        val cleanup = CleanupSpy(true)
        val capabilities = CapabilitySpy()

        val states = repository(fs, cleanup, capabilities)
            .transfer(cache, OutputNameDraft("archive", "tar.gz"), path("/target"))
            .toList()

        assertEquals(listOf("archive.tar.gz", "archive (1).tar.gz"), fs.names)
        assertEquals(capabilities.issued, fs.sources)
        assertEquals(capabilities.issued, capabilities.revoked)
        assertEquals(2, capabilities.issued.map { it.contentUri }.distinct().size)
        assertTrue(states.first() is TransferState.Resolving)
        assertEquals(
            listOf(0, 1),
            states.filterIsInstance<TransferState.Publishing>().map { it.attempt },
        )
        val terminal = states.last() as TransferState.Success
        assertEquals("archive (1).tar.gz", terminal.name.value)
        assertNull(terminal.cleanupWarning)
        assertEquals(1, cleanup.calls)
    }

    @Test
    fun `issue failure never crosses the root boundary`() = runTest {
        val fs = FakeFs(mutableListOf())
        val cleanup = CleanupSpy(true)
        val capabilities = CapabilitySpy(issueFailure = ErrorCode.SOURCE_UNREADABLE)

        val terminal = repository(fs, cleanup, capabilities)
            .transfer(fakeCached(), draft("a.txt"), path("/target"))
            .toList()
            .last()

        assertEquals(ErrorCode.SOURCE_UNREADABLE, (terminal as TransferState.Failure).code)
        assertTrue(fs.sources.isEmpty())
        assertTrue(capabilities.issued.isEmpty())
        assertTrue(capabilities.revoked.isEmpty())
        assertEquals(0, cleanup.calls)
    }

    @Test
    fun `definite failures do not retry and retain cache for explicit policy`() = runTest {
        listOf(
            ErrorCode.NO_SPACE,
            ErrorCode.ROOT_DENIED,
            ErrorCode.ROOT_UNAVAILABLE,
            ErrorCode.SOURCE_UNREADABLE,
            ErrorCode.COMMAND_FAILED,
            ErrorCode.NOT_WRITABLE,
        ).forEach { code ->
            val fs = FakeFs(mutableListOf(failure(code)))
            val cleanup = CleanupSpy(false)
            val capabilities = CapabilitySpy()

            val terminal = repository(fs, cleanup, capabilities)
                .transfer(fakeCached(), draft("secret.txt"), path("/private/target"))
                .toList()
                .last()

            assertEquals(1, fs.names.size)
            assertEquals(code, (terminal as TransferState.Failure).code)
            assertNull(terminal.cleanupWarning)
            assertEquals(0, cleanup.calls)
            assertEquals(capabilities.issued, capabilities.revoked)
            assertFalse(terminal.message.contains("/private/target"))
            assertFalse(terminal.message.contains("content://"))
        }
    }

    @Test
    fun `filesystem failure preserves its safe user message`() = runTest {
        val terminal = repository(
            FakeFs(mutableListOf(failure(ErrorCode.COMMAND_FAILED, "文件名过长，无法保存"))),
            CleanupSpy(true),
            CapabilitySpy(),
        ).transfer(fakeCached(), draft("very-long-name.txt"), path("/target")).toList().last()

        assertEquals(ErrorCode.COMMAND_FAILED, (terminal as TransferState.Failure).code)
        assertEquals("文件名过长，无法保存", terminal.message)
    }

    @Test
    fun `uncertain outcome revokes capability retains cache and is terminal`() = runTest {
        val cleanup = CleanupSpy(true)
        val capabilities = CapabilitySpy()

        val terminal = repository(
            FakeFs(mutableListOf(failure(ErrorCode.OUTCOME_UNCERTAIN))),
            cleanup,
            capabilities,
        ).transfer(fakeCached(), draft("a.txt"), path("/target")).toList().last()

        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (terminal as TransferState.Failure).code)
        assertEquals(capabilities.issued, capabilities.revoked)
        assertEquals(0, cleanup.calls)
    }

    @Test
    fun `cleanup exceptions become warnings without replacing success failure or cancellation`() = runTest {
        suspend fun throwingCleanup(
            @Suppress("UNUSED_PARAMETER") cached: CachedIncomingFile,
        ): Boolean = throw IOException("private cache path")

        val successCapabilities = CapabilitySpy()
        val successStates = RootFileTransferRepository(
            fileSystem = FakeFs(mutableListOf(success("a.txt"))),
            nameResolver = TargetNameResolver(10),
            issueSource = successCapabilities::issue,
            revokeSource = successCapabilities::revoke,
            cleanupCache = ::throwingCleanup,
        ).transfer(fakeCached(), draft("a.txt"), path("/target")).toList()
        assertNotNull((successStates.last() as TransferState.Success).cleanupWarning)
        assertEquals(
            1,
            successStates.count { it is TransferState.Success || it is TransferState.Failure },
        )
        assertEquals(successCapabilities.issued, successCapabilities.revoked)

        val failureCapabilities = CapabilitySpy()
        val failureStates = RootFileTransferRepository(
            fileSystem = FakeFs(mutableListOf(failure(ErrorCode.NO_SPACE))),
            nameResolver = TargetNameResolver(10),
            issueSource = failureCapabilities::issue,
            revokeSource = failureCapabilities::revoke,
            cleanupCache = { throw CancellationException("cleanup must not run") },
        ).transfer(fakeCached(), draft("a.txt"), path("/target")).toList()
        assertNull((failureStates.last() as TransferState.Failure).cleanupWarning)
        assertEquals(failureCapabilities.issued, failureCapabilities.revoked)
    }

    @Test
    fun `attempt exhaustion revokes each capability and emits one failure terminal`() = runTest {
        val cleanup = CleanupSpy(true)
        val fs = FakeFs(MutableList(2) { failure(ErrorCode.ALREADY_EXISTS) })
        val capabilities = CapabilitySpy()

        val states = RootFileTransferRepository(
            fileSystem = fs,
            nameResolver = TargetNameResolver(2),
            issueSource = capabilities::issue,
            revokeSource = capabilities::revoke,
            cleanupCache = cleanup::invoke,
        ).transfer(fakeCached(), draft("a.txt"), path("/target")).toList()

        assertEquals(listOf("a.txt", "a (1).txt"), fs.names)
        assertEquals(capabilities.issued, capabilities.revoked)
        assertEquals(1, states.count { it is TransferState.Success || it is TransferState.Failure })
        assertEquals(ErrorCode.COMMAND_FAILED, (states.last() as TransferState.Failure).code)
        assertEquals(0, cleanup.calls)
    }

    @Test
    fun `queued generation stops after collision before another capability is issued`() = runTest {
        val cleanup = CleanupSpy(true)
        val fs = FakeFs(
            mutableListOf(
                failure(ErrorCode.ALREADY_EXISTS),
                success("a (1).txt"),
            ),
        )
        val capabilities = CapabilitySpy()

        val states = repository(fs, cleanup, capabilities)
            .transfer(fakeCached(), draft("a.txt"), path("/target")) { false }
            .toList()

        assertEquals(listOf("a.txt"), fs.names)
        assertEquals(1, capabilities.issued.size)
        assertEquals(capabilities.issued, capabilities.revoked)
        assertEquals(ErrorCode.CANCELLED, (states.last() as TransferState.Failure).code)
        assertEquals(0, cleanup.calls)
    }

    @Test
    fun `cancellation before root dispatch revokes capability and cleans cache`() = runTest {
        val cleanup = CleanupSpy(true)
        val fs = FakeFs(mutableListOf(success("a.txt")))
        val capabilities = CapabilitySpy()
        try {
            repository(fs, cleanup, capabilities)
                .transfer(fakeCached(), draft("a.txt"), path("/target"))
                .collect {
                    if (it is TransferState.Publishing) {
                        throw CancellationException("before dispatch")
                    }
                }
            fail()
        } catch (_: CancellationException) {
            // Expected caller cancellation.
        }

        assertEquals(1, cleanup.calls)
        assertTrue(fs.names.isEmpty())
        assertEquals(capabilities.issued, capabilities.revoked)
    }

    @Test
    fun `cancellation after root dispatch revokes capability and preserves cache`() = runTest {
        val cleanup = CleanupSpy(true)
        val original = CancellationException("after dispatch")
        val fs = object : RootFileSystem by FakeFs(mutableListOf()) {
            override suspend fun transferFromStream(
                source: RootTransferSource,
                targetDirectory: RootPath,
                finalName: EntryName,
            ): OperationResult<DirectoryEntry> = throw original
        }
        val capabilities = CapabilitySpy()

        try {
            repository(fs, cleanup, capabilities)
                .transfer(fakeCached(), draft("a.txt"), path("/target"))
                .toList()
            fail()
        } catch (caught: CancellationException) {
            assertSame(original, caught)
        }

        assertEquals(capabilities.issued, capabilities.revoked)
        assertEquals(0, cleanup.calls)
    }

    @Test
    fun `root exception revokes capability without inventing a terminal state`() = runTest {
        val cleanup = CleanupSpy(true)
        val original = IOException("root transport failed")
        val fs = object : RootFileSystem by FakeFs(mutableListOf()) {
            override suspend fun transferFromStream(
                source: RootTransferSource,
                targetDirectory: RootPath,
                finalName: EntryName,
            ): OperationResult<DirectoryEntry> = throw original
        }
        val capabilities = CapabilitySpy()

        try {
            repository(fs, cleanup, capabilities)
                .transfer(fakeCached(), draft("a.txt"), path("/target"))
                .toList()
            fail()
        } catch (caught: IOException) {
            assertSame(original, caught)
        }

        assertEquals(capabilities.issued, capabilities.revoked)
        assertEquals(0, cleanup.calls)
    }

    @Test
    fun `concurrent name race resolves without overwrite`() = runTest {
        val occupied = mutableSetOf<String>()
        val mutex = Mutex()
        val firstArrivals = CompletableDeferred<Unit>()
        var arrivals = 0
        fun racingFs() = object : RootFileSystem by FakeFs(mutableListOf()) {
            override suspend fun transferFromStream(
                source: RootTransferSource,
                targetDirectory: RootPath,
                finalName: EntryName,
            ): OperationResult<DirectoryEntry> {
                if (finalName.value == "same.txt") {
                    mutex.withLock {
                        arrivals++
                        if (arrivals == 2) firstArrivals.complete(Unit)
                    }
                    firstArrivals.await()
                }
                return mutex.withLock {
                    if (!occupied.add(finalName.value)) {
                        failure(ErrorCode.ALREADY_EXISTS)
                    } else {
                        success(finalName.value)
                    }
                }
            }
        }
        val capabilities = CapabilitySpy()
        val firstFlow = async {
            repository(racingFs(), CleanupSpy(true), capabilities)
                .transfer(fakeCached(), draft("same.txt"), path("/target"))
                .toList()
        }
        val secondFlow = async {
            repository(racingFs(), CleanupSpy(true), capabilities)
                .transfer(fakeCached(), draft("same.txt"), path("/target"))
                .toList()
        }

        val flows = listOf(firstFlow.await(), secondFlow.await())
        assertEquals(
            setOf("same.txt", "same (1).txt"),
            flows.map { (it.last() as TransferState.Success).name.value }.toSet(),
        )
        assertTrue(
            flows.all { states ->
                states.count { it is TransferState.Success || it is TransferState.Failure } == 1
            },
        )
        assertEquals(setOf("same.txt", "same (1).txt"), occupied)
        assertEquals(capabilities.issued.toSet(), capabilities.revoked.toSet())
    }

    private fun repository(
        fs: RootFileSystem,
        cleanup: CleanupSpy,
        capabilities: CapabilitySpy = CapabilitySpy(),
    ) = RootFileTransferRepository(
        fileSystem = fs,
        nameResolver = TargetNameResolver(10),
        issueSource = capabilities::issue,
        revokeSource = capabilities::revoke,
        cleanupCache = cleanup::invoke,
    )

    private fun draft(displayName: String) = OutputNameDraft.fromDisplayName(displayName)

    private fun fakeCached(): CachedIncomingFile {
        val root = kotlin.io.path.createTempDirectory("isaver-repository").toFile()
        val file = File(root, "incoming/123e4567-e89b-12d3-a456-426614174000.tmp")
        file.parentFile!!.mkdirs()
        file.writeText("x")
        return CachedIncomingFile(
            file = file,
            sizeBytes = 1,
            appCachePath = AppCachePath.fromIncomingCacheFile(root, file) { 1L to 2L }
                .getOrThrow(),
        )
    }

    private fun path(value: String) = RootPath.parse(value).getOrThrow()

    private fun success(name: String) = OperationResult.Success(
        DirectoryEntry(
            path = path("/target/$name"),
            name = name,
            type = EntryType.FILE,
            sizeBytes = 1,
            modifiedAtEpochSeconds = 1,
            readable = true,
            writable = true,
            symbolicLink = false,
        ),
    )

    private fun failure(code: ErrorCode, userMessage: String = "安全消息") = OperationResult.Failure(
        code = code,
        userMessage = userMessage,
        technicalMessage = "technical redacted",
    )

    private class CapabilitySpy(
        private val issueFailure: ErrorCode? = null,
    ) {
        val issued = mutableListOf<RootTransferSource>()
        val revoked = mutableListOf<RootTransferSource>()

        @Synchronized
        fun issue(cached: CachedIncomingFile): OperationResult<RootTransferSource> {
            issueFailure?.let {
                return OperationResult.Failure(
                    code = it,
                    userMessage = "无法读取分享文件",
                    technicalMessage = "capability unavailable",
                )
            }
            val token = (issued.size + 1).toString(16).padStart(64, '0')
            val source = RootTransferSource(
                contentUri = "content://com.iamxpp.isaver.incoming-stream/incoming/$token",
                expectedSizeBytes = cached.sizeBytes,
                token = token,
            )
            issued += source
            return OperationResult.Success(source)
        }

        @Synchronized
        fun revoke(source: RootTransferSource) {
            revoked += source
        }
    }

    private class CleanupSpy(private val result: Boolean) {
        var calls = 0

        suspend fun invoke(@Suppress("UNUSED_PARAMETER") cached: CachedIncomingFile): Boolean {
            calls++
            return result
        }
    }

    private class FakeFs(
        private val results: MutableList<OperationResult<DirectoryEntry>>,
        private val cancel: Boolean = false,
    ) : RootFileSystem {
        val names = mutableListOf<String>()
        val sources = mutableListOf<RootTransferSource>()

        override suspend fun transferFromStream(
            source: RootTransferSource,
            targetDirectory: RootPath,
            finalName: EntryName,
        ): OperationResult<DirectoryEntry> {
            sources += source
            names += finalName.value
            if (cancel) throw CancellationException()
            return results.removeFirst()
        }

        override suspend fun list(path: RootPath): OperationResult<List<DirectoryEntry>> =
            OperationResult.Failure(ErrorCode.COMMAND_FAILED, "失败")

        override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> =
            OperationResult.Failure(ErrorCode.COMMAND_FAILED, "失败")

        override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> =
            OperationResult.Failure(ErrorCode.COMMAND_FAILED, "失败")

        override suspend fun createDirectory(
            parent: RootPath,
            name: FolderName,
        ): OperationResult<DirectoryEntry> =
            OperationResult.Failure(ErrorCode.COMMAND_FAILED, "失败")
    }
}
