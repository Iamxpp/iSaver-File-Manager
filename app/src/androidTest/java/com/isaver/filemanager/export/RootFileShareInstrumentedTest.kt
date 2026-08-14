package com.isaver.filemanager.export

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.isaver.filemanager.ISaverApplication
import com.isaver.filemanager.data.root.LibsuRootSession
import com.isaver.filemanager.data.root.RootCommandCodec
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.domain.RootStatus
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

    @Test
    fun multipleRootFilesExportForActionSendMultipleAndEachGrantIsSingleUse() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        resetTarget(app)
        try {
            root(app, "printf %s first-share > ${quote(FIRST_SOURCE)}")
            root(app, "printf %s second-share > ${quote(SECOND_SOURCE)}")
            val entries = listOf(FIRST_SOURCE, SECOND_SOURCE).map { source ->
                val path = RootPath.parse(source).getOrThrow()
                (app.rootFileSystem.stat(path) as OperationResult.Success).value
            }
            val grants = entries.map { entry ->
                (app.rootExportRepository.share(entry) as OperationResult.Success).value
            }

            val intent = ExternalShareIntentFactory.create(grants)

            assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
            assertEquals(2, intent.clipData?.itemCount)
            grants.forEachIndexed { index, grant ->
                val uri = Uri.parse(grant.contentUri)
                assertEquals(uri, intent.clipData?.getItemAt(index)?.uri)
                val bytes = app.contentResolver.openFileDescriptor(uri, "r")!!.use {
                    ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                }
                assertArrayEquals(
                    if (index == 0) "first-share".toByteArray() else "second-share".toByteArray(),
                    bytes,
                )
                assertThrows(FileNotFoundException::class.java) {
                    app.contentResolver.openFileDescriptor(uri, "r")
                }
            }
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
        const val FIRST_SOURCE = "$TARGET/first.txt"
        const val SECOND_SOURCE = "$TARGET/second.txt"
    }
}
