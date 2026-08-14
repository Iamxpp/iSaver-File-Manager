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

class RootFilePermissionInstrumentedTest {
    @Test
    fun exactModesAreBoundAndDirectoryChangeIsNotRecursive() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        root(app, "rm -rf -- ${quote(ROOT)}; mkdir -p -- ${quote(DIRECTORY)}; printf 'test' > ${quote(FILE)}; printf 'child' > ${quote(CHILD)}; chmod 644 ${quote(FILE)} ${quote(CHILD)}; chmod 755 ${quote(DIRECTORY)}")
        try {
            val repository = FilePermissionRepository(app.rootFileSystem)
            val parent = path(ROOT)
            val file = success(app.rootFileSystem.stat(path(FILE)))
            val fileMetadata = success(app.rootFileSystem.metadata(file.path))
            val fileResult = repository.change(
                file, parent, fileMetadata, FilePermissions.fromMode(0x180), confirmed = true,
            )
            assertTrue(fileResult.toString(), fileResult is OperationResult.Success)
            assertEquals("600", mode(app, FILE))

            val directory = success(app.rootFileSystem.stat(path(DIRECTORY)))
            val directoryMetadata = success(app.rootFileSystem.metadata(directory.path))
            val directoryResult = repository.change(
                directory, parent, directoryMetadata, FilePermissions.fromMode(0x1C0), confirmed = true,
            )
            assertTrue(directoryResult.toString(), directoryResult is OperationResult.Success)
            assertEquals("700", mode(app, DIRECTORY))
            assertEquals("644", mode(app, CHILD))

            val stale = repository.change(
                file, parent, fileMetadata, FilePermissions.fromMode(0x1A4), confirmed = true,
            )
            assertEquals(ErrorCode.SOURCE_UNREADABLE, (stale as OperationResult.Failure).code)

            val protectedEntry = success(app.rootFileSystem.stat(path("/system")))
            val protectedMetadata = success(app.rootFileSystem.metadata(protectedEntry.path))
            val protected = repository.change(
                protectedEntry, path("/"), protectedMetadata, FilePermissions.fromMode(0x1ED), confirmed = true,
            )
            assertEquals(ErrorCode.NOT_WRITABLE, (protected as OperationResult.Failure).code)
        } finally {
            root(app, "rm -rf -- ${quote(ROOT)}")
        }
    }

    private suspend fun mode(app: ISaverApplication, value: String) =
        root(app, "stat -c '%a' -- ${quote(value)}").trim()

    private suspend fun root(app: ISaverApplication, command: String): String {
        val result = (app.rootSession as LibsuRootSession).shellCoordinator.execute(command)
        assertEquals(result.stderr.joinToString("\n"), 0, result.exitCode)
        return result.stdout.joinToString("\n")
    }

    private fun <T> success(result: OperationResult<T>): T = (result as OperationResult.Success).value
    private fun path(value: String) = RootPath.parse(value).getOrThrow()
    private fun quote(value: String) = RootCommandCodec.quote(value)

    private companion object {
        const val ROOT = "/data/local/tmp/isaver-test/permissions"
        const val FILE = "$ROOT/note.txt"
        const val DIRECTORY = "$ROOT/folder"
        const val CHILD = "$DIRECTORY/child.txt"
    }
}
