package com.iamxpp.isaver.transfer

import com.iamxpp.isaver.data.root.AppCachePath
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RootFileTransferRepositoryTest {
    @Test fun `retries only already exists and emits one success terminal`() = runTest {
        val cache = fakeCached()
        val fs = FakeFs(mutableListOf(failure(ErrorCode.ALREADY_EXISTS), success("archive (1).tar.gz")))
        val cleanup = CleanupSpy(true)
        val states = repository(fs, cleanup)
            .transfer(cache, OutputNameDraft("archive", "tar.gz"), path("/target"))
            .toList()
        assertEquals(listOf("archive.tar.gz", "archive (1).tar.gz"), fs.names)
        assertTrue(states[0] is TransferState.Resolving)
        assertEquals(listOf(0, 1), states.filterIsInstance<TransferState.Publishing>().map { it.attempt })
        val terminal = states.last() as TransferState.Success
        assertEquals("archive (1).tar.gz", terminal.name.value); assertNull(terminal.cleanupWarning)
        assertEquals(1, cleanup.calls)
    }

    @Test fun `definite failures do not retry and retain cache for explicit policy`() = runTest {
        listOf(ErrorCode.NO_SPACE, ErrorCode.ROOT_DENIED, ErrorCode.ROOT_UNAVAILABLE, ErrorCode.SOURCE_UNREADABLE,
            ErrorCode.COMMAND_FAILED, ErrorCode.NOT_WRITABLE).forEach { code ->
            val fs = FakeFs(mutableListOf(failure(code))); val cleanup = CleanupSpy(false)
            val terminal = repository(fs, cleanup)
                .transfer(fakeCached(), draft("secret.txt"), path("/private/target"))
                .toList()
                .last()
            assertEquals(1, fs.names.size); assertEquals(code, (terminal as TransferState.Failure).code)
            assertNull(terminal.cleanupWarning); assertEquals(0, cleanup.calls)
            assertFalse(terminal.message.contains("/private/target")); assertFalse(terminal.message.contains("content://"))
        }
    }

    @Test fun `uncertain outcome retains cache and is terminal`() = runTest {
        val cleanup = CleanupSpy(true)
        val terminal = repository(FakeFs(mutableListOf(failure(ErrorCode.OUTCOME_UNCERTAIN))), cleanup)
            .transfer(fakeCached(), draft("a.txt"), path("/target")).toList().last()
        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (terminal as TransferState.Failure).code)
        assertEquals(0, cleanup.calls)
    }

    @Test fun `cleanup exceptions become warnings without replacing success failure or caller cancellation`() = runTest {
        suspend fun throwingCleanup(@Suppress("UNUSED_PARAMETER") cached: CachedIncomingFile): Boolean = throw java.io.IOException("private cache path")
        val successStates = RootFileTransferRepository(FakeFs(mutableListOf(success("a.txt"))), TargetNameResolver(10), ::throwingCleanup)
            .transfer(fakeCached(), draft("a.txt"), path("/target")).toList()
        assertNotNull((successStates.last() as TransferState.Success).cleanupWarning)
        assertEquals(1, successStates.count { it is TransferState.Success || it is TransferState.Failure })

        val failureStates = RootFileTransferRepository(FakeFs(mutableListOf(failure(ErrorCode.NO_SPACE))), TargetNameResolver(10),
            { throw CancellationException("cleanup must not run") })
            .transfer(fakeCached(), draft("a.txt"), path("/target")).toList()
        assertNull((failureStates.last() as TransferState.Failure).cleanupWarning)

    }

    @Test fun `attempt exhaustion cleans cache and emits exactly one failure terminal`() = runTest {
        val cleanup = CleanupSpy(true); val fs = FakeFs(MutableList(2) { failure(ErrorCode.ALREADY_EXISTS) })
        val states = RootFileTransferRepository(fs, TargetNameResolver(2), cleanup::invoke)
            .transfer(fakeCached(), draft("a.txt"), path("/target")).toList()
        assertEquals(listOf("a.txt", "a (1).txt"), fs.names)
        assertEquals(1, states.count { it is TransferState.Success || it is TransferState.Failure })
        assertEquals(ErrorCode.COMMAND_FAILED, (states.last() as TransferState.Failure).code)
        assertEquals(0, cleanup.calls)
    }

    @Test fun `queued generation stops after already exists before another publish window`() = runTest {
        val cleanup = CleanupSpy(true)
        val fs = FakeFs(mutableListOf(failure(ErrorCode.ALREADY_EXISTS), success("a (1).txt")))

        val states = repository(fs, cleanup)
            .transfer(fakeCached(), draft("a.txt"), path("/target")) { false }
            .toList()

        assertEquals(listOf("a.txt"), fs.names)
        assertEquals(ErrorCode.CANCELLED, (states.last() as TransferState.Failure).code)
        assertEquals(0, cleanup.calls)
    }

    @Test fun `cancellation before root dispatch is rethrown and cleans cache`() = runTest {
        val cleanup = CleanupSpy(true); val fs = FakeFs(mutableListOf(success("a.txt")))
        try {
            repository(fs, cleanup).transfer(fakeCached(), draft("a.txt"), path("/target")).collect {
                if (it is TransferState.Publishing) throw CancellationException("before dispatch")
            }
            fail()
        }
        catch (_: CancellationException) { }
        assertEquals(1, cleanup.calls); assertTrue(fs.names.isEmpty())
    }

    @Test fun `cancellation after root dispatch preserves cache and rethrows original`() = runTest {
        val cleanup = CleanupSpy(true); val original = CancellationException("after dispatch")
        val fs = object : RootFileSystem by FakeFs(mutableListOf()) {
            override suspend fun transferFromAppCache(source:AppCachePath,targetDirectory:RootPath,finalName:EntryName,expectedSizeBytes:Long):OperationResult<DirectoryEntry> = throw original
        }
        try { repository(fs, cleanup).transfer(fakeCached(), draft("a.txt"), path("/target")).toList(); fail() }
        catch (caught: CancellationException) { assertSame(original, caught) }
        assertEquals(0, cleanup.calls)
    }

    @Test fun `concurrent name race resolves without overwrite`() = runTest {
        val occupied = mutableSetOf<String>(); val mutex = Mutex(); val firstArrivals = CompletableDeferred<Unit>(); var arrivals = 0
        fun racingFs() = object : RootFileSystem by FakeFs(mutableListOf()) {
            override suspend fun transferFromAppCache(source: AppCachePath, targetDirectory: RootPath, finalName: EntryName, expectedSizeBytes: Long): OperationResult<DirectoryEntry> {
                if (finalName.value == "same.txt") {
                    mutex.withLock { arrivals++; if (arrivals == 2) firstArrivals.complete(Unit) }
                    firstArrivals.await()
                }
                return mutex.withLock { if (!occupied.add(finalName.value)) failure(ErrorCode.ALREADY_EXISTS) else success(finalName.value) }
            }
        }
        val firstFlow = async {
            repository(racingFs(), CleanupSpy(true)).transfer(fakeCached(), draft("same.txt"), path("/target")).toList()
        }
        val secondFlow = async {
            repository(racingFs(), CleanupSpy(true)).transfer(fakeCached(), draft("same.txt"), path("/target")).toList()
        }
        val flows = listOf(firstFlow.await(), secondFlow.await())
        assertEquals(setOf("same.txt", "same (1).txt"), flows.map { (it.last() as TransferState.Success).name.value }.toSet())
        assertTrue(flows.all { states -> states.count { it is TransferState.Success || it is TransferState.Failure } == 1 })
        assertEquals(setOf("same.txt", "same (1).txt"), occupied)
    }

    private fun repository(fs: RootFileSystem, cleanup: CleanupSpy) = RootFileTransferRepository(fs, TargetNameResolver(10), cleanup::invoke)
    private fun draft(displayName: String) = OutputNameDraft.fromDisplayName(displayName)
    private fun fakeCached(): CachedIncomingFile { val root = kotlin.io.path.createTempDirectory("isaver-repository").toFile(); val file=File(root,"incoming/123e4567-e89b-12d3-a456-426614174000.tmp");file.parentFile!!.mkdirs();file.writeText("x");return CachedIncomingFile(file,1,AppCachePath.fromIncomingCacheFile(root,file){1L to 2L}.getOrThrow()) }
    private fun path(v:String)=RootPath.parse(v).getOrThrow()
    private fun success(name:String)=OperationResult.Success(DirectoryEntry(path("/target/$name"),name,EntryType.FILE,1,1,true,true,false))
    private fun failure(code:ErrorCode)=OperationResult.Failure(code,"安全消息","technical redacted")
    private class CleanupSpy(private val result:Boolean){var calls=0;suspend fun invoke(c:CachedIncomingFile):Boolean{calls++;return result}}
    private class FakeFs(private val results:MutableList<OperationResult<DirectoryEntry>>,private val cancel:Boolean=false):RootFileSystem{
        val names=mutableListOf<String>()
        override suspend fun transferFromAppCache(source:AppCachePath,targetDirectory:RootPath,finalName:EntryName,expectedSizeBytes:Long):OperationResult<DirectoryEntry>{names+=finalName.value;if(cancel)throw CancellationException();return results.removeFirst()}
        override suspend fun list(path:RootPath):OperationResult<List<DirectoryEntry>> = OperationResult.Failure(ErrorCode.COMMAND_FAILED,"失败")
        override suspend fun stat(path:RootPath):OperationResult<DirectoryEntry> = OperationResult.Failure(ErrorCode.COMMAND_FAILED,"失败")
        override suspend fun canonicalize(path:RootPath):OperationResult<RootPath> = OperationResult.Failure(ErrorCode.COMMAND_FAILED,"失败")
        override suspend fun createDirectory(parent:RootPath,name:FolderName):OperationResult<DirectoryEntry> = OperationResult.Failure(ErrorCode.COMMAND_FAILED,"失败")
    }
}
