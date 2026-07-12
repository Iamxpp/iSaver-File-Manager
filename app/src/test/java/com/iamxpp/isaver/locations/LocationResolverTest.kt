package com.iamxpp.isaver.locations

import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test

class LocationResolverTest {
    @Test fun `keeps readable directories deduplicates canonical paths and preserves priority order`() = runTest {
        val fs = FakeFs()
        fs.entries["/one"] = entry("/one", EntryType.DIRECTORY, true)
        fs.entries["/dup"] = entry("/dup", EntryType.DIRECTORY, true)
        fs.entries["/file"] = entry("/file", EntryType.FILE, true)
        fs.entries["/blocked"] = entry("/blocked", EntryType.DIRECTORY, false)
        fs.canonical["/one"] = root("/real")
        fs.canonical["/dup"] = root("/real")
        val result = LocationResolver(fs, StandardTestDispatcher(testScheduler)).resolve(template("/file", "/dup", "/one", "/blocked", "/missing"))
        assertEquals(listOf("dup"), result.children.map { it.displayName })
        assertEquals("/real", result.children.single().path.value)
        assertEquals(3, result.unavailableCount)
        assertFalse(result.empty)
    }

    @Test fun `all unavailable returns explicit empty result`() = runTest {
        val result = LocationResolver(FakeFs(), StandardTestDispatcher(testScheduler)).resolve(template("/a", "/b"))
        assertTrue(result.empty); assertEquals(2, result.unavailableCount); assertTrue(result.children.isEmpty())
    }

    @Test fun `concurrency is limited and output remains priority ordered`() = runTest {
        val fs = FakeFs(delay = true)
        val paths = (1..8).map { "/$it" }
        paths.forEach { fs.entries[it] = entry(it, EntryType.DIRECTORY, true); fs.canonical[it] = root("/real$it") }
        val resolver = LocationResolver(fs, StandardTestDispatcher(testScheduler), 4)
        val shuffled = AppPathTemplate(LocationId.of("template.concurrent"), "T", listOf("pkg"),
            paths.reversed().map { path -> PathCandidate(LocationId.of("candidate.concurrent.${path.drop(1)}"), path.drop(1), root(path), path.drop(1).toInt()) })
        val deferred = async { resolver.resolve(shuffled) }
        testScheduler.runCurrent()
        assertEquals(4, fs.maxActive)
        fs.release.complete(Unit)
        val result = deferred.await()
        assertEquals((1..8).map { it.toString() }, result.children.map { it.displayName })
    }

    @Test(expected = CancellationException::class) fun `cancellation propagates`() = runTest {
        val fs = object : RootFileSystem {
            override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> = throw CancellationException()
            override suspend fun list(path: RootPath): OperationResult<List<DirectoryEntry>> = error("unused")
            override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> = error("unused")
        }
        LocationResolver(fs, StandardTestDispatcher(testScheduler)).resolve(template("/a"))
    }

    @Test fun `stat exception only marks that candidate unavailable`() = runTest {
        val fs = FakeFs(); fs.entries["/good"] = entry("/good", EntryType.DIRECTORY, true); fs.canonical["/good"] = root("/real")
        fs.statExceptions += "/bad"
        val result = LocationResolver(fs, StandardTestDispatcher(testScheduler)).resolve(template("/bad", "/good"))
        assertEquals(listOf("good"), result.children.map { it.displayName }); assertEquals(1, result.unavailableCount)
    }

    @Test fun `canonicalize exception only marks that candidate unavailable`() = runTest {
        val fs = FakeFs(); listOf("/bad", "/good").forEach { fs.entries[it] = entry(it, EntryType.DIRECTORY, true) }
        fs.canonicalExceptions += "/bad"; fs.canonical["/good"] = root("/real")
        val result = LocationResolver(fs, StandardTestDispatcher(testScheduler)).resolve(template("/bad", "/good"))
        assertEquals(listOf("good"), result.children.map { it.displayName }); assertEquals(1, result.unavailableCount)
    }

    private class FakeFs(private val delay: Boolean = false) : RootFileSystem {
        val entries = mutableMapOf<String, DirectoryEntry>(); val canonical = mutableMapOf<String, RootPath>()
        val statExceptions = mutableSetOf<String>(); val canonicalExceptions = mutableSetOf<String>()
        val release = CompletableDeferred<Unit>(); var active=0; var maxActive=0
        override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> {
            if (path.value in statExceptions) error("stat failed")
            active++; maxActive=maxOf(maxActive,active); if(delay) release.await(); active--
            return entries[path.value]?.let { OperationResult.Success(it) } ?: OperationResult.Failure(ErrorCode.NOT_FOUND,"missing")
        }
        override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> {
            if (path.value in canonicalExceptions) error("canonical failed")
            return canonical[path.value]?.let { OperationResult.Success(it) } ?: OperationResult.Failure(ErrorCode.NOT_FOUND,"missing")
        }
        override suspend fun list(path: RootPath): OperationResult<List<DirectoryEntry>> = error("unused")
    }
    private fun template(vararg paths:String) = AppPathTemplate(LocationId.of("template.resolve"),"T",listOf("pkg"),paths.mapIndexed { i,p -> PathCandidate(LocationId.of("candidate.$i"), p.substringAfterLast('/'),root(p),i) })
    private fun entry(path:String,type:EntryType,readable:Boolean)=DirectoryEntry(root(path),path.substringAfterLast('/'),type,null,null,readable,true,false)
    private fun root(path:String)=RootPath.parse(path).getOrThrow()
}
