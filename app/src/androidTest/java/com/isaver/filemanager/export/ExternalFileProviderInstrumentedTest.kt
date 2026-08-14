package com.isaver.filemanager.export

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.isaver.filemanager.ISaverApplication
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalFileProviderInstrumentedTest {
    @Test
    fun metadataIsAvailableBeforeOneReadAndReplayIsRejected() {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        val cached = fixture(app, "private-export".toByteArray())
        val grant = app.externalFileRegistry.issue(cached).getOrThrow()
        val uri = Uri.parse(grant.contentUri)

        app.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("report.pdf", cursor.getString(0))
            assertEquals(cached.sizeBytes, cursor.getLong(1))
        }
        assertEquals("application/pdf", app.contentResolver.getType(uri))

        val bytes = app.contentResolver.openFileDescriptor(uri, "r")!!.use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        assertArrayEquals("private-export".toByteArray(), bytes)
        assertFalse(cached.file.exists())
        assertThrows(FileNotFoundException::class.java) {
            app.contentResolver.openFileDescriptor(uri, "r")
        }
    }

    @Test
    fun manifestKeepsProviderPrivateAndAllowsReadGrants() {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        val authority = "${app.packageName}.external-file"

        val provider = app.packageManager.resolveContentProvider(authority, 0)

        assertNotNull(provider)
        assertEquals("com.isaver.filemanager.export.ExternalFileProvider", provider!!.name)
        assertEquals(authority, provider.authority)
        assertEquals(false, provider.exported)
        assertEquals(true, provider.grantUriPermissions)
    }

    private fun fixture(app: ISaverApplication, bytes: ByteArray): CachedExportFile {
        val file = File(app.cacheDir, "export/${UUID.randomUUID()}.export")
        check(file.parentFile!!.exists() || file.parentFile!!.mkdirs())
        file.writeBytes(bytes)
        val status = Os.lstat(file.path)
        check(OsConstants.S_ISREG(status.st_mode))
        return CachedExportFile(
            file = file,
            sizeBytes = bytes.size.toLong(),
            device = status.st_dev,
            inode = status.st_ino,
            displayName = "report.pdf",
            mimeType = "application/pdf",
        )
    }
}
