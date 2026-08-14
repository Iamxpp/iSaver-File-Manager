package com.iamxpp.isaver.data.access

import com.iamxpp.isaver.data.root.DirectorySnapshot
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FileAccessModeTest {
    @Test
    fun `mode aware file system switches existing instance between delegates`() = runTest {
        val root = NamedFileSystem("root")
        val local = NamedFileSystem("local")
        val controller = FileAccessController(FileAccessMode.LOCAL_READ_ONLY)
        val fileSystem = ModeAwareRootFileSystem(controller, root, local)

        assertEquals("local", fileSystem.stat(TEST_PATH).successName())

        controller.activate(FileAccessMode.ROOT)

        assertEquals("root", fileSystem.stat(TEST_PATH).successName())
    }

    @Test
    fun `controller publishes only explicitly activated modes`() {
        val controller = FileAccessController(FileAccessMode.LOCAL_READ_ONLY)

        assertEquals(FileAccessMode.LOCAL_READ_ONLY, controller.mode.value)
        controller.activate(FileAccessMode.ROOT)
        assertEquals(FileAccessMode.ROOT, controller.mode.value)
    }

    private fun OperationResult<DirectoryEntry>.successName(): String =
        (this as OperationResult.Success).value.name

    private class NamedFileSystem(private val label: String) : RootFileSystem {
        override suspend fun readDirectory(path: RootPath) = OperationResult.Success(
            DirectorySnapshot(1, 1, true, false, emptyList()),
        )

        override suspend fun stat(path: RootPath) = OperationResult.Success(
            DirectoryEntry(path, label, EntryType.FILE, 0, 0, true, false, false),
        )

        override suspend fun canonicalize(path: RootPath) = OperationResult.Success(path)

        override suspend fun createDirectory(
            parent: RootPath,
            name: com.iamxpp.isaver.domain.FolderName,
        ): OperationResult<DirectoryEntry> = error("not used")
    }

    private companion object {
        val TEST_PATH = RootPath.parse("/test").getOrThrow()
    }
}
