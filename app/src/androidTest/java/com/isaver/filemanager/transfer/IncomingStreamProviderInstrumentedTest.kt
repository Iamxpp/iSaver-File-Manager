package com.isaver.filemanager.transfer

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.isaver.filemanager.ISaverApplication
import com.isaver.filemanager.data.root.AppCachePath
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class IncomingStreamProviderInstrumentedTest {
    @Test
    fun shellReadsExactlyOnceWhileAppUidIsRejected() {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        val cached = fixture(app, "private-payload".toByteArray())
        try {
            val source = app.incomingStreamRegistry.issue(cached).getOrThrow()

            assertArrayEquals(
                "private-payload".toByteArray(),
                readAsShell(source.contentUri),
            )
            assertFalse(readAsShell(source.contentUri).contentEquals("private-payload".toByteArray()))

            val rejected = app.incomingStreamRegistry.issue(cached).getOrThrow()
            assertThrows(FileNotFoundException::class.java) {
                app.contentResolver.openFileDescriptor(Uri.parse(rejected.contentUri), "r")
            }
            app.incomingStreamRegistry.revoke(rejected)
        } finally {
            cached.file.delete()
        }
    }

    @Test
    fun manifestExportsOnlyTheFixedIncomingStreamAuthority() {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        val authority = "${app.packageName}.incoming-stream"

        val provider = app.packageManager.resolveContentProvider(authority, 0)

        assertNotNull(provider)
        assertEquals("com.isaver.filemanager.transfer.IncomingStreamProvider", provider!!.name)
        assertEquals(authority, provider.authority)
        assertEquals(true, provider.exported)
        assertEquals(false, provider.grantUriPermissions)
    }

    private fun readAsShell(uri: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("content read --uri $uri")
            .use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }

    private fun fixture(
        app: ISaverApplication,
        bytes: ByteArray,
    ): CachedIncomingFile {
        val file = File(app.cacheDir, "incoming/${UUID.randomUUID()}.tmp")
        check(file.parentFile!!.exists() || file.parentFile!!.mkdirs())
        file.writeBytes(bytes)
        return CachedIncomingFile(
            file = file,
            sizeBytes = bytes.size.toLong(),
            appCachePath = AppCachePath.fromIncomingCacheFile(app.cacheDir, file).getOrThrow(),
        )
    }
}
