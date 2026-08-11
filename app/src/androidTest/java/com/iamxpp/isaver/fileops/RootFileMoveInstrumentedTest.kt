package com.iamxpp.isaver.fileops

import androidx.test.core.app.ApplicationProvider
import com.iamxpp.isaver.ISaverApplication
import com.iamxpp.isaver.data.root.LibsuRootSession
import com.iamxpp.isaver.data.root.RootCommandCodec
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootFileMoveInstrumentedTest {
    @Test
    fun sameFilesystemMoveIsAtomicAndCrossFilesystemMovePublishesBeforeDeletingSource() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        resetTargets(app)
        try {
            root(app, "printf %s first > ${quote(SOURCE_FILE)}")
            val source = stat(app, SOURCE_FILE)
            assertSuccess("source parent stat", app.rootFileSystem.stat(path(SOURCE_DIRECTORY)))
            assertSuccess("target parent stat", app.rootFileSystem.stat(path(TARGET_DIRECTORY)))
            assertSuccess("source parent canonical", app.rootFileSystem.canonicalize(path(SOURCE_DIRECTORY)))
            assertSuccess("target parent canonical", app.rootFileSystem.canonicalize(path(TARGET_DIRECTORY)))
            assertSuccess("source canonical", app.rootFileSystem.canonicalize(path(SOURCE_FILE)))
            val missingTarget = app.rootFileSystem.stat(path(TARGET_FILE))
            assertEquals(missingTarget.toString(), ErrorCode.NOT_FOUND, (missingTarget as OperationResult.Failure).code)

            val moved = app.fileMoveRepository.move(
                source,
                path(SOURCE_DIRECTORY),
                path(TARGET_DIRECTORY),
            )

            assertTrue(moved.toString(), moved is OperationResult.Success)
            assertEquals("first", root(app, "cat -- ${quote(TARGET_FILE)}"))
            assertEquals("missing", root(app, "if [ -e ${quote(SOURCE_FILE)} ]; then echo present; else echo missing; fi"))

            root(app, "printf %s keep-source > ${quote(SOURCE_FILE)}")
            val conflictingSource = stat(app, SOURCE_FILE)
            val conflict = app.fileMoveRepository.move(
                conflictingSource,
                path(SOURCE_DIRECTORY),
                path(TARGET_DIRECTORY),
            )
            assertEquals(ErrorCode.ALREADY_EXISTS, (conflict as OperationResult.Failure).code)
            assertEquals("keep-source", root(app, "cat -- ${quote(SOURCE_FILE)}"))
            assertEquals("first", root(app, "cat -- ${quote(TARGET_FILE)}"))

            root(app, "printf %s cross-device > ${quote(CROSS_SOURCE_FILE)}")
            val crossSource = stat(app, CROSS_SOURCE_FILE)
            val cross = app.fileMoveRepository.move(
                crossSource,
                path(SOURCE_DIRECTORY),
                path(SHARED_TARGET_DIRECTORY),
            )
            assertTrue(cross.toString(), cross is OperationResult.Success)
            assertEquals("missing", root(app, "if [ -e ${quote(CROSS_SOURCE_FILE)} ]; then echo present; else echo missing; fi"))
            assertEquals("cross-device", root(app, "cat -- ${quote(SHARED_TARGET_FILE)}"))
        } finally {
            cleanupTargets(app)
        }
    }

    private suspend fun resetTargets(app: ISaverApplication) {
        cleanupTargets(app)
        root(
            app,
            "mkdir -p -- ${quote(SOURCE_DIRECTORY)} ${quote(TARGET_DIRECTORY)} ${quote(SHARED_TARGET_DIRECTORY)}",
        )
    }

    private suspend fun cleanupTargets(app: ISaverApplication) {
        root(app, "rm -rf -- ${quote(ROOT_TARGET)} ${quote(SHARED_TARGET_DIRECTORY)}")
    }

    private suspend fun stat(app: ISaverApplication, value: String) =
        (app.rootFileSystem.stat(path(value)) as OperationResult.Success).value

    private fun assertSuccess(label: String, result: OperationResult<*>) {
        assertTrue("$label: $result", result is OperationResult.Success)
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
        const val ROOT_TARGET = "/data/local/tmp/isaver-test/move"
        const val SOURCE_DIRECTORY = "$ROOT_TARGET/source"
        const val TARGET_DIRECTORY = "$ROOT_TARGET/target"
        const val SOURCE_FILE = "$SOURCE_DIRECTORY/report.txt"
        const val TARGET_FILE = "$TARGET_DIRECTORY/report.txt"
        const val CROSS_SOURCE_FILE = "$SOURCE_DIRECTORY/cross.txt"
        const val SHARED_TARGET_DIRECTORY = "/storage/emulated/0/isaver-test/move-target"
        const val SHARED_TARGET_FILE = "$SHARED_TARGET_DIRECTORY/cross.txt"
    }
}
