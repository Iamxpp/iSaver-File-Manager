package com.iamxpp.isaver.export

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootFileShareInstrumentedTest {
    @Test
    fun rootFileExportsForActionSendWithBoundedReplayProtectedGrant() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        resetTarget(app)
        try {
            root(app, "printf %s root-share-fixture > ${quote(SOURCE)}")
            val source = RootPath.parse(SOURCE).getOrThrow()
            val entry = (app.rootFileSystem.stat(source) as OperationResult.Success).value
            val issuedAt = SystemClock.elapsedRealtime()

            val grant = (app.rootExportRepository.share(entry) as OperationResult.Success).value
            val intent = ExternalShareIntentFactory.create(grant)
            val uri = Uri.parse(grant.contentUri)

            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("text/plain", intent.type)
            @Suppress("DEPRECATION")
            val streamUri = intent.extras?.getParcelable(Intent.EXTRA_STREAM) as? Uri
            assertEquals(uri, streamUri)
            assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
            assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
            assertFalse((intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
            assertTrue(
                app.externalFileRegistry.peek(
                    grant.token,
                    issuedAt + ExternalFileRegistry.OPEN_TTL_MILLIS + 1L,
                ) != null,
            )

            val bytes = app.contentResolver.openFileDescriptor(uri, "r")!!.use {
                ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            assertArrayEquals("root-share-fixture".toByteArray(), bytes)
            assertThrows(FileNotFoundException::class.java) {
                app.contentResolver.openFileDescriptor(uri, "r")
            }

            val expiringGrant =
                (app.rootExportRepository.share(entry) as OperationResult.Success).value
            assertNull(
                app.externalFileRegistry.peek(
                    expiringGrant.token,
                    SystemClock.elapsedRealtime() + ExternalFileRegistry.SHARE_TTL_MILLIS,
                ),
            )
            assertEquals("root-share-fixture", root(app, "cat -- ${quote(SOURCE)}"))
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
        const val TARGET = "/data/local/tmp/isaver-test/share"
        const val SOURCE = "$TARGET/report.txt"
    }
}
