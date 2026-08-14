package com.isaver.filemanager.fileops

import androidx.test.core.app.ApplicationProvider
import com.isaver.filemanager.ISaverApplication
import com.isaver.filemanager.data.root.LibsuRootSession
import com.isaver.filemanager.data.root.RootCommandCodec
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.domain.RootStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootFileCopyInstrumentedTest {
    @Test
    fun copyUsesNoReplaceStageAndSupportsCrossFilesystemTargets() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        resetTargets(app)
        try {
            root(app, "printf %s first > ${quote(SOURCE_FILE)}")
            root(app, "touch -d @1700000001 -- ${quote(SOURCE_FILE)}")
            val source = stat(app, SOURCE_FILE)

            val copied = app.fileCopyRepository.copy(
                source,
                path(SOURCE_DIRECTORY),
                path(TARGET_DIRECTORY),
            )

            assertTrue(copied.toString(), copied is OperationResult.Success)
            assertEquals("first", root(app, "cat -- ${quote(SOURCE_FILE)}"))
            assertEquals("first", root(app, "cat -- ${quote(TARGET_FILE)}"))
            assertEquals("1700000001", root(app, "stat -c %Y -- ${quote(TARGET_FILE)}"))

            root(app, "printf %s changed-source > ${quote(SOURCE_FILE)}")
            val conflictingSource = stat(app, SOURCE_FILE)
            val conflict = app.fileCopyRepository.copy(
                conflictingSource,
                path(SOURCE_DIRECTORY),
                path(TARGET_DIRECTORY),
            )
            assertEquals(ErrorCode.ALREADY_EXISTS, (conflict as OperationResult.Failure).code)
            assertEquals("changed-source", root(app, "cat -- ${quote(SOURCE_FILE)}"))
            assertEquals("first", root(app, "cat -- ${quote(TARGET_FILE)}"))

            val keptBoth = app.fileCopyRepository.copy(
                conflictingSource,
                path(SOURCE_DIRECTORY),
                path(TARGET_DIRECTORY),
                ConflictAction.KEEP_BOTH,
            )
            assertTrue(keptBoth.toString(), keptBoth is OperationResult.Success)
            assertEquals("report (1).txt", (keptBoth as OperationResult.Success).value.name)
            assertEquals("changed-source", root(app, "cat -- ${quote(SOURCE_FILE)}"))
            assertEquals("changed-source", root(app, "cat -- ${quote(TARGET_KEEP_BOTH_FILE)}"))
            assertEquals("first", root(app, "cat -- ${quote(TARGET_FILE)}"))

            root(app, "printf %s cross-device > ${quote(CROSS_SOURCE_FILE)}")
            root(app, "touch -d @1700000002 -- ${quote(CROSS_SOURCE_FILE)}")
            val crossSource = stat(app, CROSS_SOURCE_FILE)
            val cross = app.fileCopyRepository.copy(
                crossSource,
                path(SOURCE_DIRECTORY),
                path(SHARED_TARGET_DIRECTORY),
            )
            assertTrue(cross.toString(), cross is OperationResult.Success)
            assertEquals("cross-device", root(app, "cat -- ${quote(CROSS_SOURCE_FILE)}"))
            assertEquals("cross-device", root(app, "cat -- ${quote(SHARED_TARGET_FILE)}"))
            assertEquals("1700000002", root(app, "stat -c %Y -- ${quote(SHARED_TARGET_FILE)}"))
            assertEquals(
                "clean",
                root(
                    app,
                    "if find ${quote(TARGET_DIRECTORY)} ${quote(SHARED_TARGET_DIRECTORY)} " +
                        "-maxdepth 1 -name '.isaver-stage-*' | grep -q .; then echo staged; else echo clean; fi",
                ),
            )
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

    private suspend fun root(app: ISaverApplication, command: String): String {
        val session = app.rootSession as LibsuRootSession
        val result = session.shellCoordinator.execute(command)
        assertEquals(0, result.exitCode)
        return result.stdout.joinToString("\n")
    }

    private fun path(value: String) = RootPath.parse(value).getOrThrow()
    private fun quote(value: String) = RootCommandCodec.quote(value)

    private companion object {
        const val ROOT_TARGET = "/data/local/tmp/isaver-test/copy"
        const val SOURCE_DIRECTORY = "$ROOT_TARGET/source"
        const val TARGET_DIRECTORY = "$ROOT_TARGET/target"
        const val SOURCE_FILE = "$SOURCE_DIRECTORY/report.txt"
        const val TARGET_FILE = "$TARGET_DIRECTORY/report.txt"
        const val TARGET_KEEP_BOTH_FILE = "$TARGET_DIRECTORY/report (1).txt"
        const val CROSS_SOURCE_FILE = "$SOURCE_DIRECTORY/cross.txt"
        const val SHARED_TARGET_DIRECTORY = "/storage/emulated/0/isaver-test/copy-target"
        const val SHARED_TARGET_FILE = "$SHARED_TARGET_DIRECTORY/cross.txt"
    }
}
