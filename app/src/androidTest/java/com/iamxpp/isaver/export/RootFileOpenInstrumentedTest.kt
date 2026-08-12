package com.iamxpp.isaver.export

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.iamxpp.isaver.ISaverApplication
import com.iamxpp.isaver.data.root.LibsuRootSession
import com.iamxpp.isaver.data.root.RootCommandCodec
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootStatus
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootFileOpenInstrumentedTest {
    @Test
    fun rootFileExportsToOneShotContentUriForAndroidDefaultOpen() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        resetTarget(app)
        try {
            root(app, "printf %s '%PDF-1.7 root-open-fixture' > ${quote(SOURCE)}")
            val source = RootPath.parse(SOURCE).getOrThrow()
            val entry = (app.rootFileSystem.stat(source) as OperationResult.Success).value

            val grant = (app.rootExportRepository.export(entry) as OperationResult.Success).value
            val intent = ExternalOpenIntentFactory.create(grant)
            val chooser = ExternalOpenIntentFactory.createChooser(grant)

            assertTrue(grant.contentUri.startsWith("content://${app.packageName}.external-file/file/"))
            assertFalse(grant.contentUri.contains(SOURCE))
            assertEquals("application/pdf", grant.mimeType)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

            val uri = Uri.parse(grant.contentUri)
            val bytes = app.contentResolver.openFileDescriptor(uri, "r")!!.use {
                ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            assertArrayEquals("%PDF-1.7 root-open-fixture".toByteArray(), bytes)
            assertThrows(FileNotFoundException::class.java) {
                app.contentResolver.openFileDescriptor(uri, "r")
            }
            assertEquals("%PDF-1.7 root-open-fixture", root(app, "cat -- ${quote(SOURCE)}"))
        } finally {
            root(app, "rm -rf -- ${quote(TARGET)}")
        }
    }

    private suspend fun resetTarget(app: ISaverApplication) {
        root(app, "rm -rf -- ${quote(TARGET)}; mkdir -p -- ${quote(TARGET)}")
    }

    private suspend fun root(app: ISaverApplication, command: String): String {
        val session = app.rootSession as LibsuRootSession
        val result = session.shellCoordinator.execute(command)
        assertEquals(0, result.exitCode)
        return result.stdout.joinToString("\n")
    }

    private fun quote(value: String) = RootCommandCodec.quote(value)

    private companion object {
        const val TARGET = "/data/local/tmp/isaver-test/default-open"
        const val SOURCE = "$TARGET/report.txt"
    }
}
