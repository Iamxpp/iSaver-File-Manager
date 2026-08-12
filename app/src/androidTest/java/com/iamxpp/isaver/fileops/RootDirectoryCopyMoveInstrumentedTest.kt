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

class RootDirectoryCopyMoveInstrumentedTest {
    @Test
    fun copiesNestedDirectoriesWithoutOverwriteAndRejectsUnsafeTrees() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        resetTargets(app)
        try {
            root(app, "mkdir -p -- ${quote(SOURCE_TREE + "/empty")} ${quote(SOURCE_TREE + "/" + CHINESE_DIRECTORY)}")
            root(app, "printf %s nested > ${quote(SOURCE_TREE + "/" + CHINESE_DIRECTORY + "/" + SPACED_FILE)}")
            root(app, "touch -- ${quote(SOURCE_TREE + "/zero.bin")}")
            root(app, "touch -d @1700000011 -- ${quote(SOURCE_TREE + "/" + CHINESE_DIRECTORY + "/" + SPACED_FILE)}")
            root(app, "touch -d @1700000012 -- ${quote(SOURCE_TREE + "/" + CHINESE_DIRECTORY)}")
            root(app, "touch -d @1700000013 -- ${quote(SOURCE_TREE)}")
            val source = stat(app, SOURCE_TREE)

            val copied = app.fileCopyRepository.copy(source, path(SOURCE_PARENT), path(TARGET_PARENT))
            assertTrue(copied.toString(), copied is OperationResult.Success)
            assertEquals("nested", root(app, "cat -- ${quote(COPIED_FILE)}"))
            assertEquals("directory", root(app, "test -d ${quote(COPIED_EMPTY)} && echo directory"))
            assertEquals("0", root(app, "stat -c %s -- ${quote(COPIED_ZERO)}"))
            assertEquals("1700000011", root(app, "stat -c %Y -- ${quote(COPIED_FILE)}"))
            assertEquals("1700000012", root(app, "stat -c %Y -- ${quote(COPIED_DIRECTORY)}"))
            assertEquals("1700000013", root(app, "stat -c %Y -- ${quote(COPIED_TREE)}"))

            val conflict = app.fileCopyRepository.copy(source, path(SOURCE_PARENT), path(TARGET_PARENT))
            assertEquals(ErrorCode.ALREADY_EXISTS, (conflict as OperationResult.Failure).code)
            val keptBoth = app.fileCopyRepository.copy(
                source, path(SOURCE_PARENT), path(TARGET_PARENT), ConflictAction.KEEP_BOTH,
            )
            assertTrue(keptBoth.toString(), keptBoth is OperationResult.Success)
            assertEquals("tree (1)", (keptBoth as OperationResult.Success).value.name)

            val self = app.fileCopyRepository.copy(source, path(SOURCE_PARENT), path(SOURCE_TREE))
            assertEquals(ErrorCode.COMMAND_FAILED, (self as OperationResult.Failure).code)
            val descendant = app.fileCopyRepository.copy(source, path(SOURCE_PARENT), path("$SOURCE_TREE/empty"))
            assertEquals(ErrorCode.COMMAND_FAILED, (descendant as OperationResult.Failure).code)

            root(app, "ln -s -- ${quote(SOURCE_TREE + "/zero.bin")} ${quote(SOURCE_TREE + "/unsafe-link")}")
            val rejected = app.fileCopyRepository.copy(
                stat(app, SOURCE_TREE), path(SOURCE_PARENT), path(SYMLINK_TARGET_PARENT),
            )
            assertEquals(ErrorCode.SOURCE_UNREADABLE, (rejected as OperationResult.Failure).code)
            assertEquals(
                "clean",
                root(app, "if find ${quote(SYMLINK_TARGET_PARENT)} -maxdepth 1 -name '.isaver-stage-*' | grep -q .; then echo staged; else echo clean; fi"),
            )
            assertEquals("missing", root(app, "test ! -e ${quote(SYMLINK_TARGET_TREE)} && echo missing"))
        } finally {
            cleanupTargets(app)
        }
    }

    @Test
    fun movesDirectoriesOnSameAndCrossFilesystems() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        resetTargets(app)
        try {
            root(app, "mkdir -p -- ${quote(SAME_MOVE_SOURCE + "/child")}")
            root(app, "printf %s same > ${quote(SAME_MOVE_SOURCE + "/child/value.txt")}")
            val sameIdentity = root(app, "stat -c %d:%i -- ${quote(SAME_MOVE_SOURCE)}")
            val same = app.fileMoveRepository.move(
                stat(app, SAME_MOVE_SOURCE), path(SOURCE_PARENT), path(TARGET_PARENT),
            )
            assertTrue(same.toString(), same is OperationResult.Success)
            assertEquals(sameIdentity, root(app, "stat -c %d:%i -- ${quote(SAME_MOVE_TARGET)}"))
            assertEquals("missing", root(app, "test ! -e ${quote(SAME_MOVE_SOURCE)} && echo missing"))

            root(app, "mkdir -p -- ${quote(CROSS_MOVE_SOURCE + "/child")}")
            root(app, "printf %s cross > ${quote(CROSS_MOVE_SOURCE + "/child/value.txt")}")
            val cross = app.fileMoveRepository.move(
                stat(app, CROSS_MOVE_SOURCE), path(SOURCE_PARENT), path(SHARED_TARGET_PARENT),
            )
            assertTrue(cross.toString(), cross is OperationResult.Success)
            assertEquals("cross", root(app, "cat -- ${quote(CROSS_MOVE_TARGET + "/child/value.txt")}"))
            assertEquals("missing", root(app, "test ! -e ${quote(CROSS_MOVE_SOURCE)} && echo missing"))
        } finally {
            cleanupTargets(app)
        }
    }

    @Test
    fun mergesNestedDirectoriesAndKeepsConflictingFiles() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        resetTargets(app)
        try {
            root(app, "mkdir -p -- ${quote(COPY_MERGE_SOURCE + "/docs")} ${quote(COPY_MERGE_TARGET + "/docs")}")
            root(app, "printf %s source-copy > ${quote(COPY_MERGE_SOURCE + "/docs/readme.txt")}")
            root(app, "printf %s unique-copy > ${quote(COPY_MERGE_SOURCE + "/docs/unique.txt")}")
            root(app, "printf %s target-copy > ${quote(COPY_MERGE_TARGET + "/docs/readme.txt")}")

            val copied = app.fileCopyRepository.copy(
                stat(app, COPY_MERGE_SOURCE), path(SOURCE_PARENT), path(TARGET_PARENT), ConflictAction.MERGE,
            )
            assertTrue(copied.toString(), copied is OperationResult.Success)
            assertEquals("target-copy", root(app, "cat -- ${quote(COPY_MERGE_TARGET + "/docs/readme.txt")}"))
            assertEquals("source-copy", root(app, "cat -- ${quote(COPY_MERGE_TARGET + "/docs/readme (1).txt")}"))
            assertEquals("unique-copy", root(app, "cat -- ${quote(COPY_MERGE_TARGET + "/docs/unique.txt")}"))
            assertEquals("source-copy", root(app, "cat -- ${quote(COPY_MERGE_SOURCE + "/docs/readme.txt")}"))

            root(app, "mkdir -p -- ${quote(MOVE_MERGE_SOURCE + "/docs")} ${quote(MOVE_MERGE_TARGET + "/docs")}")
            root(app, "printf %s source-move > ${quote(MOVE_MERGE_SOURCE + "/docs/readme.txt")}")
            root(app, "printf %s unique-move > ${quote(MOVE_MERGE_SOURCE + "/docs/unique.txt")}")
            root(app, "printf %s target-move > ${quote(MOVE_MERGE_TARGET + "/docs/readme.txt")}")

            val moved = app.fileMoveRepository.move(
                stat(app, MOVE_MERGE_SOURCE), path(SOURCE_PARENT), path(TARGET_PARENT), ConflictAction.MERGE,
            )
            assertTrue(moved.toString(), moved is OperationResult.Success)
            assertEquals("target-move", root(app, "cat -- ${quote(MOVE_MERGE_TARGET + "/docs/readme.txt")}"))
            assertEquals("source-move", root(app, "cat -- ${quote(MOVE_MERGE_TARGET + "/docs/readme (1).txt")}"))
            assertEquals("unique-move", root(app, "cat -- ${quote(MOVE_MERGE_TARGET + "/docs/unique.txt")}"))
            assertEquals("missing", root(app, "test ! -e ${quote(MOVE_MERGE_SOURCE)} && echo missing"))
        } finally {
            cleanupTargets(app)
        }
    }

    private suspend fun resetTargets(app: ISaverApplication) {
        cleanupTargets(app)
        root(
            app,
            "mkdir -p -- ${quote(SOURCE_PARENT)} ${quote(TARGET_PARENT)} ${quote(SYMLINK_TARGET_PARENT)} ${quote(SHARED_TARGET_PARENT)}",
        )
    }

    private suspend fun cleanupTargets(app: ISaverApplication) {
        root(app, "rm -rf -- ${quote(ROOT_TARGET)} ${quote(SHARED_TARGET_PARENT)}")
    }

    private suspend fun stat(app: ISaverApplication, value: String) =
        (app.rootFileSystem.stat(path(value)) as OperationResult.Success).value

    private suspend fun root(app: ISaverApplication, command: String): String {
        val result = (app.rootSession as LibsuRootSession).shellCoordinator.execute(command)
        assertEquals(result.stderr.joinToString("\n"), 0, result.exitCode)
        return result.stdout.joinToString("\n")
    }

    private fun path(value: String) = RootPath.parse(value).getOrThrow()
    private fun quote(value: String) = RootCommandCodec.quote(value)

    private companion object {
        const val ROOT_TARGET = "/data/local/tmp/isaver-test/directory-fileops"
        const val SOURCE_PARENT = "$ROOT_TARGET/source"
        const val TARGET_PARENT = "$ROOT_TARGET/target"
        const val SYMLINK_TARGET_PARENT = "$ROOT_TARGET/symlink-target"
        const val SHARED_TARGET_PARENT = "/storage/emulated/0/isaver-test/directory-fileops"
        const val SOURCE_TREE = "$SOURCE_PARENT/tree"
        const val CHINESE_DIRECTORY = "中文 目录"
        const val SPACED_FILE = "name with space.txt"
        const val COPIED_FILE = "$TARGET_PARENT/tree/$CHINESE_DIRECTORY/$SPACED_FILE"
        const val COPIED_DIRECTORY = "$TARGET_PARENT/tree/$CHINESE_DIRECTORY"
        const val COPIED_TREE = "$TARGET_PARENT/tree"
        const val COPIED_EMPTY = "$TARGET_PARENT/tree/empty"
        const val COPIED_ZERO = "$TARGET_PARENT/tree/zero.bin"
        const val SYMLINK_TARGET_TREE = "$SYMLINK_TARGET_PARENT/tree"
        const val SAME_MOVE_SOURCE = "$SOURCE_PARENT/same-move"
        const val SAME_MOVE_TARGET = "$TARGET_PARENT/same-move"
        const val CROSS_MOVE_SOURCE = "$SOURCE_PARENT/cross-move"
        const val CROSS_MOVE_TARGET = "$SHARED_TARGET_PARENT/cross-move"
        const val COPY_MERGE_SOURCE = "$SOURCE_PARENT/copy-merge"
        const val COPY_MERGE_TARGET = "$TARGET_PARENT/copy-merge"
        const val MOVE_MERGE_SOURCE = "$SOURCE_PARENT/move-merge"
        const val MOVE_MERGE_TARGET = "$TARGET_PARENT/move-merge"
    }
}
