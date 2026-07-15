package com.iamxpp.isaver.transfer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import com.iamxpp.isaver.data.root.AppCachePath
import com.iamxpp.isaver.share.IncomingShare
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class IncomingFileCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val uri = Uri.parse("content://cache.test/file")
    private var opener: () -> InputStream = { ByteArrayInputStream(byteArrayOf()) }

    @Test fun `copies exact bytes to private incoming UUID tmp and reports monotonic progress`() = runTest {
        val bytes = ByteArray(20_000) { it.toByte() }; register { ByteArrayInputStream(bytes) }
        val progress = mutableListOf<Long>(); val cache = cache()
        val result = cache.cache(share(bytes.size.toLong())) { progress += it }
        val file = (result as IncomingFileCacheResult.Success).file
        assertArrayEquals(bytes, file.file.readBytes())
        assertEquals(File(context.cacheDir, "incoming").canonicalFile, file.file.parentFile!!.canonicalFile)
        assertTrue(file.file.name.endsWith(".tmp")); UUID.fromString(file.file.name.removeSuffix(".tmp"))
        assertEquals(file.file.canonicalPath, file.appCachePath.value)
        assertFalse(file.file.name.contains("report")); assertEquals(bytes.size.toLong(), file.sizeBytes)
        assertEquals(bytes.size.toLong(), progress.last()); assertTrue(progress.zipWithNext().all { it.first <= it.second })
    }

    @Test fun `unknown and zero sizes succeed`() = runTest {
        listOf(null to byteArrayOf(1,2), 0L to byteArrayOf()).forEach { (size, bytes) ->
            register { ByteArrayInputStream(bytes) }
            assertTrue(cache().cache(share(size)) {} is IncomingFileCacheResult.Success)
        }
    }

    @Test fun `size mismatch is typed and removes partial file`() = runTest {
        register { ByteArrayInputStream(byteArrayOf(1,2)) }; val result = cache().cache(share(3)) {}
        assertEquals(IncomingFileCacheFailure.SIZE_MISMATCH, (result as IncomingFileCacheResult.Failure).reason)
        assertIncomingEmpty()
    }

    @Test fun `open security and io failures are typed`() = runTest {
        listOf(SecurityException(), FileNotFoundException(), IOException("disk full")).forEach { error ->
            register { throw error }; val result = cache().cache(share(null)) {}
            assertEquals(IncomingFileCacheFailure.SOURCE_UNREADABLE, (result as IncomingFileCacheResult.Failure).reason)
        }
    }

    @Test fun `source read IO is always source unreadable even when message says disk full`() = runTest {
        listOf(IOException("disk full"), IOException("broken")).forEach { error ->
            register { object: InputStream() { var read = false; override fun read(): Int = if (!read) { read=true; 1 } else throw error } }
            val result = cache().cache(share(null)) {}
            assertEquals(IncomingFileCacheFailure.SOURCE_UNREADABLE, (result as IncomingFileCacheResult.Failure).reason)
            assertIncomingEmpty()
        }
    }

    @Test fun `destination IO uses structural ENOSPC mapping and cleans partial`() = runTest {
        val cases = listOf(
            IOException("broken") to IncomingFileCacheFailure.CACHE_WRITE_FAILED,
            IOException("disk full") to IncomingFileCacheFailure.CACHE_WRITE_FAILED,
            IOException("write failed", ErrnoException("write", OsConstants.ENOSPC)) to IncomingFileCacheFailure.NO_SPACE,
        )
        cases.forEach { (error, expected) ->
            register { ByteArrayInputStream(byteArrayOf(1, 2)) }
            val result = cache(openOutput = { ThrowingOutputStream(error) }).cache(share(null)) {}
            assertEquals(expected, (result as IncomingFileCacheResult.Failure).reason)
            assertIncomingEmpty()
        }
    }

    @Test fun `cancellation closes input removes partial and is rethrown`() = runTest {
        var closed=false; register { object: ByteArrayInputStream(byteArrayOf(1)) { override fun close(){closed=true;super.close()} } }
        try { cache().cache(share(null)) { throw CancellationException() }; fail("expected cancellation") } catch (_: CancellationException) {}
        assertTrue(closed); assertIncomingEmpty()
    }

    @Test fun `cleanup is idempotent and rejects paths outside incoming directory`() = runTest {
        register { ByteArrayInputStream(byteArrayOf(1)) }; val cache=cache(); val cached=(cache.cache(share(1)){} as IncomingFileCacheResult.Success).file
        assertTrue(cache.cleanup(cached)); assertTrue(cache.cleanup(cached))
        val outside=File(context.cacheDir,"outside.tmp").apply{writeText("x")}
        assertFalse(cache.cleanup(CachedIncomingFile(outside,1,cached.appCachePath))); assertTrue(outside.exists())
    }

    @Test fun `cleanup deletes cached file from already cancelled coroutine`() = runTest {
        register { ByteArrayInputStream(byteArrayOf(1)) }
        val cache = cache()
        val cached = (cache.cache(share(1)) {} as IncomingFileCacheResult.Success).file
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                cache.cleanup(cached)
            }
        }
        job.cancel()
        job.join()
        assertFalse(cached.file.exists())
    }

    @Test fun `validation rejects changed size identity and missing cache files`() = runTest {
        register { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        val cache = cache()
        val cached = (cache.cache(share(3)) {} as IncomingFileCacheResult.Success).file

        assertTrue(cache.validate(cached))
        assertTrue(cache.validateNow(cached))

        cached.file.appendBytes(byteArrayOf(4))
        assertFalse(cache.validate(cached))
        assertFalse(cache.validateNow(cached))

        val replacement = (cache.cache(share(3)) {} as IncomingFileCacheResult.Success).file
        val staleIdentity = AppCachePath.fromIncomingCacheFile(context.cacheDir, replacement.file) {
            replacement.appCachePath.device + 1L to replacement.appCachePath.inode
        }.getOrThrow()
        val replaced = replacement.copy(appCachePath = staleIdentity)
        assertFalse(cache.validate(replaced))
        assertFalse(cache.validateNow(replaced))

        cached.file.delete()
        assertFalse(cache.validate(cached))
        assertFalse(cache.validateNow(cached))
    }

    @Test fun `orphan cleanup removes only expired unowned UUID cache files`() = runTest {
        val nowMillis = 2_000_000_000_000L
        register { ByteArrayInputStream(byteArrayOf(1)) }
        val cache = cache()
        val owned = (cache.cache(share(1)) {} as IncomingFileCacheResult.Success).file
        val incoming = owned.file.parentFile!!
        val orphan = File(incoming, "223e4567-e89b-12d3-a456-426614174000.tmp").apply { writeText("old") }
        val fresh = File(incoming, "323e4567-e89b-12d3-a456-426614174000.tmp").apply { writeText("fresh") }
        val unrelated = File(incoming, "do-not-delete.txt").apply { writeText("old") }
        val expired = nowMillis - IncomingFileCache.ORPHAN_TTL_MILLIS - 1L
        listOf(owned.file, orphan, unrelated).forEach { assertTrue(it.setLastModified(expired)) }
        assertTrue(fresh.setLastModified(nowMillis - 1L))

        val removed = cache.cleanupOrphans(
            nowMillis = nowMillis,
            owned = setOf(owned.appCachePath),
        )

        assertEquals(1, removed)
        assertTrue(owned.file.exists())
        assertFalse(orphan.exists())
        assertTrue(fresh.exists())
        assertTrue(unrelated.exists())
    }

    private fun cache(openOutput: (File) -> OutputStream = { it.outputStream().buffered() }) =
        IncomingFileCache(context.contentResolver, context.cacheDir, Dispatchers.Unconfined, { opener() }, openOutput)
    private fun share(size:Long?)=IncomingShare(uri,"report.pdf",size,"application/pdf")
    private fun register(open:()->InputStream){ opener=open; ShadowContentResolver.registerProviderInternal("cache.test",Provider(open)) }
    private fun assertIncomingEmpty(){ assertTrue(File(context.cacheDir,"incoming").listFiles().orEmpty().isEmpty()) }
    private class ThrowingOutputStream(private val error: IOException) : OutputStream() {
        override fun write(value: Int) = throw error
        override fun write(buffer: ByteArray, offset: Int, length: Int) = throw error
    }
    private class Provider(private val open:()->InputStream):ContentProvider(){
        override fun onCreate()=true
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor?=null
        override fun openAssetFile(uri:Uri,mode:String)=android.content.res.AssetFileDescriptor(ParcelFileDescriptor.createPipe().also { pipes ->
            Thread { ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { out -> open().use { it.copyTo(out) } } }.start()
        }[0],0,-1)
        override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor?=null
        override fun getType(uri:Uri):String?=null
        override fun insert(uri:Uri,v:ContentValues?):Uri?=null
        override fun delete(uri:Uri,s:String?,a:Array<out String>?)=0
        override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
    }
}
