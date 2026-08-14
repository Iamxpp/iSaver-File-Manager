package com.isaver.filemanager.fileops

import androidx.test.core.app.ApplicationProvider
import com.isaver.filemanager.ISaverApplication
import com.isaver.filemanager.data.root.LibsuRootSession
import com.isaver.filemanager.data.root.RootCommandCodec
import com.isaver.filemanager.domain.EntryName
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.domain.RootStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootFileCreateInstrumentedTest {
    @Test
    fun createEmptyFileDoesNotReplaceExistingTarget() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        resetTarget(app)
        try {
            val created = app.rootFileSystem.createFileNoReplace(
                path(TARGET_DIRECTORY),
                EntryName.parse(FILE_NAME).getOrThrow(),
            )
            assertTrue(created.toString(), created is OperationResult.Success)
            assertEquals("0", root(app, "stat -c %s -- ${quote(TARGET_FILE)}"))

            root(app, "printf %s preserved > ${quote(TARGET_FILE)}")
            val conflict = app.rootFileSystem.createFileNoReplace(
                path(TARGET_DIRECTORY),
                EntryName.parse(FILE_NAME).getOrThrow(),
            )
            assertEquals(ErrorCode.ALREADY_EXISTS, (conflict as OperationResult.Failure).code)
            assertEquals("preserved", root(app, "cat -- ${quote(TARGET_FILE)}"))
        } finally {
            cleanupTarget(app)
        }
    }

    private suspend fun resetTarget(app: ISaverApplication) {
        cleanupTarget(app)
        root(app, "mkdir -p -- ${quote(TARGET_DIRECTORY)}")
    }

    private suspend fun cleanupTarget(app: ISaverApplication) {
        root(app, "rm -rf -- ${quote(ROOT_TARGET)}")
    }

    private suspend fun root(app: ISaverApplication, command: String): String {
        val session = app.rootSession as LibsuRootSession
        val result = session.shellCoordinator.execute(command)
        assertEquals(0, result.exitCode)
        return result.stdout.joinToString("\n")
    }

    private fun path(value: String) = RootPath.parse(value).getOrThrow()
    private fun quote(value: String) = RootCommandCodec.quote(value)

    private companion object {
        const val ROOT_TARGET = "/data/local/tmp/isaver-test/create-file"
        const val TARGET_DIRECTORY = "$ROOT_TARGET/target"
        const val FILE_NAME = "empty report.txt"
        const val TARGET_FILE = "$TARGET_DIRECTORY/$FILE_NAME"
    }
}
