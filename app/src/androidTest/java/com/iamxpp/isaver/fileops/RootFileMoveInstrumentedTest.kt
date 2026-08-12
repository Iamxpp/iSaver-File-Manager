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

            val keptBoth = app.fileMoveRepository.move(
                conflictingSource,
                path(SOURCE_DIRECTORY),
                path(TARGET_DIRECTORY),
                ConflictAction.KEEP_BOTH,
            )
            assertTrue(keptBoth.toString(), keptBoth is OperationResult.Success)
            assertEquals("report (1).txt", (keptBoth as OperationResult.Success).value.name)
            assertEquals("keep-source", root(app, "cat -- ${quote(TARGET_KEEP_BOTH_FILE)}"))
            assertEquals("first", root(app, "cat -- ${quote(TARGET_FILE)}"))
            assertEquals("missing", root(app, "if [ -e ${quote(SOURCE_FILE)} ]; then echo present; else echo missing; fi"))

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

    @Test
    fun renameFileIsAtomicNoReplaceAndPreservesSourceIdentity() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        resetRenameTargets(app)
        try {
            root(app, "printf %s rename-me > ${quote(RENAME_SOURCE_FILE)}")
            val source = stat(app, RENAME_SOURCE_FILE)
            val renamed = app.fileRenameRepository.rename(
                source,
                path(RENAME_DIRECTORY),
                "renamed.txt",
            )
            assertTrue(renamed.toString(), renamed is OperationResult.Success)
            assertEquals("rename-me", root(app, "cat -- ${quote(RENAME_TARGET_FILE)}"))
            assertEquals("missing", root(app, "if [ -e ${quote(RENAME_SOURCE_FILE)} ]; then echo present; else echo missing; fi"))

            root(app, "printf %s keep > ${quote(RENAME_SOURCE_FILE)}")
            val conflictSource = stat(app, RENAME_SOURCE_FILE)
            val conflict = app.fileRenameRepository.rename(
                conflictSource,
                path(RENAME_DIRECTORY),
                "renamed.txt",
            )
            assertEquals(ErrorCode.ALREADY_EXISTS, (conflict as OperationResult.Failure).code)
            assertEquals("keep", root(app, "cat -- ${quote(RENAME_SOURCE_FILE)}"))
            assertEquals("rename-me", root(app, "cat -- ${quote(RENAME_TARGET_FILE)}"))
        } finally {
            cleanupRenameTargets(app)
        }
    }

    @Test
    fun renameDirectoryIsAtomicNoReplaceAndPreservesTree() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        resetRenameTargets(app)
        try {
            root(app, "mkdir -p -- ${quote(RENAME_SOURCE_DIRECTORY + "/child")}")
            root(app, "printf %s nested > ${quote(RENAME_SOURCE_DIRECTORY + "/child/value.txt")}")
            val sourceIdentity = root(app, "stat -c %d:%i -- ${quote(RENAME_SOURCE_DIRECTORY)}")

            val renamed = app.fileRenameRepository.rename(
                stat(app, RENAME_SOURCE_DIRECTORY), path(RENAME_DIRECTORY), "renamed-folder",
            )

            assertTrue(renamed.toString(), renamed is OperationResult.Success)
            assertEquals(sourceIdentity, root(app, "stat -c %d:%i -- ${quote(RENAME_TARGET_DIRECTORY)}"))
            assertEquals("nested", root(app, "cat -- ${quote(RENAME_TARGET_DIRECTORY + "/child/value.txt")}"))
            assertEquals("missing", root(app, "test ! -e ${quote(RENAME_SOURCE_DIRECTORY)} && echo missing"))
        } finally {
            cleanupRenameTargets(app)
        }
    }

    @Test
    fun batchRenameSwapsNamesThroughBoundTemporaryEntries() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        resetRenameTargets(app)
        try {
            root(app, "printf %s first > ${quote(RENAME_DIRECTORY + "/a.txt")}")
            root(app, "printf %s second > ${quote(RENAME_DIRECTORY + "/b.txt")}")
            val first = stat(app, RENAME_DIRECTORY + "/a.txt")
            val second = stat(app, RENAME_DIRECTORY + "/b.txt")
            val plan = BatchRenamePlan(
                listOf(
                    BatchRenameItem(first, com.iamxpp.isaver.domain.EntryName.parse("b.txt").getOrThrow()),
                    BatchRenameItem(second, com.iamxpp.isaver.domain.EntryName.parse("a.txt").getOrThrow()),
                ),
            )
            val executor = BatchRenameExecutor(app.fileRenameRepository::rename)

            val result = executor.execute(plan, path(RENAME_DIRECTORY))

            assertTrue(result.toString(), result is OperationResult.Success)
            assertEquals("second", root(app, "cat -- ${quote(RENAME_DIRECTORY + "/a.txt")}"))
            assertEquals("first", root(app, "cat -- ${quote(RENAME_DIRECTORY + "/b.txt")}"))
            assertEquals(
                "clean",
                root(app, "if find ${quote(RENAME_DIRECTORY)} -maxdepth 1 -name '.isaver-rename-*' | grep -q .; then echo staged; else echo clean; fi"),
            )
        } finally {
            cleanupRenameTargets(app)
        }
    }

    @Test
    fun sharedTrashRestoresAndBoundDeleteRemovesNestedDirectory() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        root(app, "rm -rf -- ${quote(TRASH_TEST_ROOT)} ${quote("/storage/emulated/0/.iSaver/Trash")}")
        root(app, "mkdir -p -- ${quote(TRASH_TEST_SOURCE)} ${quote(TRASH_DELETE_DIRECTORY + "/child")}")
        try {
            root(app, "printf %s recycle > ${quote(TRASH_SOURCE_FILE)}")
            root(app, "printf %s delete > ${quote(TRASH_DELETE_DIRECTORY + "/child/value.txt")}")
            val recycled = app.trashRepository.recycle(
                stat(app, TRASH_SOURCE_FILE), path(TRASH_TEST_SOURCE),
            )
            assertTrue(recycled.toString(), recycled is OperationResult.Success)
            val item = (recycled as OperationResult.Success).value
            assertEquals("missing", root(app, "test ! -e ${quote(TRASH_SOURCE_FILE)} && echo missing"))
            assertEquals("recycle", root(app, "cat -- ${quote(item.trashedPath.value)}"))

            val restored = app.trashRepository.restore(item)
            assertTrue(restored.toString(), restored is OperationResult.Success)
            assertEquals("recycle", root(app, "cat -- ${quote(TRASH_SOURCE_FILE)}"))

            val deleted = app.trashRepository.deletePermanently(
                stat(app, TRASH_DELETE_DIRECTORY), path(TRASH_TEST_ROOT),
            )
            assertTrue(deleted.toString(), deleted is OperationResult.Success)
            assertEquals("missing", root(app, "test ! -e ${quote(TRASH_DELETE_DIRECTORY)} && echo missing"))

            val protected = app.trashRepository.deletePermanently(
                stat(app, "/system/build.prop"), path("/system"),
            )
            assertEquals(ErrorCode.NOT_WRITABLE, (protected as OperationResult.Failure).code)
        } finally {
            root(app, "rm -rf -- ${quote(TRASH_TEST_ROOT)} ${quote("/storage/emulated/0/.iSaver/Trash")}")
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

    private suspend fun resetRenameTargets(app: ISaverApplication) {
        cleanupRenameTargets(app)
        root(app, "mkdir -p -- ${quote(RENAME_DIRECTORY)}")
    }

    private suspend fun cleanupRenameTargets(app: ISaverApplication) {
        root(app, "rm -rf -- ${quote(RENAME_ROOT)}")
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
        const val TARGET_KEEP_BOTH_FILE = "$TARGET_DIRECTORY/report (1).txt"
        const val CROSS_SOURCE_FILE = "$SOURCE_DIRECTORY/cross.txt"
        const val SHARED_TARGET_DIRECTORY = "/storage/emulated/0/isaver-test/move-target"
        const val SHARED_TARGET_FILE = "$SHARED_TARGET_DIRECTORY/cross.txt"
        const val RENAME_ROOT = "/data/local/tmp/isaver-test/rename"
        const val RENAME_DIRECTORY = "$RENAME_ROOT/source"
        const val RENAME_SOURCE_FILE = "$RENAME_DIRECTORY/report.txt"
        const val RENAME_TARGET_FILE = "$RENAME_DIRECTORY/renamed.txt"
        const val RENAME_SOURCE_DIRECTORY = "$RENAME_DIRECTORY/folder"
        const val RENAME_TARGET_DIRECTORY = "$RENAME_DIRECTORY/renamed-folder"
        const val TRASH_TEST_ROOT = "/storage/emulated/0/isaver-test/trash"
        const val TRASH_TEST_SOURCE = "$TRASH_TEST_ROOT/source"
        const val TRASH_SOURCE_FILE = "$TRASH_TEST_SOURCE/report.txt"
        const val TRASH_DELETE_DIRECTORY = "$TRASH_TEST_ROOT/delete-folder"
    }
}
