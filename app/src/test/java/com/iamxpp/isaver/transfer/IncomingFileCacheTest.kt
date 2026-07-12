package com.iamxpp.isaver.transfer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.iamxpp.isaver.share.IncomingShare
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        assertEquals(File(context.cacheDir, "incoming").canonicalFile, file.file.parentFile.canonicalFile)
        assertTrue(file.file.name.endsWith(".tmp")); UUID.fromString(file.file.name.removeSuffix(".tmp"))
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
        listOf(SecurityException() to IncomingFileCacheFailure.SOURCE_UNREADABLE, FileNotFoundException() to IncomingFileCacheFailure.SOURCE_UNREADABLE).forEach { (error, expected) ->
            register { throw error }; val result = cache().cache(share(null)) {}
            assertEquals(expected, (result as IncomingFileCacheResult.Failure).reason)
        }
    }

    @Test fun `copy IO and no space failures are typed and clean partial`() = runTest {
        listOf(IOException("disk full") to IncomingFileCacheFailure.NO_SPACE, IOException("broken") to IncomingFileCacheFailure.CACHE_WRITE_FAILED).forEach { (error, expected) ->
            register { object: InputStream() { var read = false; override fun read(): Int = if (!read) { read=true; 1 } else throw error } }
            val result = cache().cache(share(null)) {}; assertEquals(expected, (result as IncomingFileCacheResult.Failure).reason); assertIncomingEmpty()
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
        assertFalse(cache.cleanup(CachedIncomingFile(outside,1))); assertTrue(outside.exists())
    }

    private fun cache()=IncomingFileCache(context.contentResolver, context.cacheDir, Dispatchers.Unconfined) { opener() }
    private fun share(size:Long?)=IncomingShare(uri,"report.pdf",size,"application/pdf")
    private fun register(open:()->InputStream){ opener=open; ShadowContentResolver.registerProviderInternal("cache.test",Provider(open)) }
    private fun assertIncomingEmpty(){ assertTrue(File(context.cacheDir,"incoming").listFiles().orEmpty().isEmpty()) }
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
