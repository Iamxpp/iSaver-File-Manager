package com.iamxpp.isaver.fileops

import com.iamxpp.isaver.data.root.RootFileMetadata
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePermissionRepositoryTest {
    @Test fun `permission bits and presets produce exact modes`() {
        var permissions = FilePermissions.fromMode(0x1A4)
        assertEquals("0644", permissions.label)
        assertTrue(permissions.ownerRead)
        assertFalse(permissions.groupWrite)

        permissions = permissions.withBit(PermissionBit.GROUP_WRITE, true)
        assertEquals(0x1B4, permissions.mode)
        assertEquals(0x1ED, PermissionPreset.DIRECTORY_STANDARD.permissions.mode)
    }

    @Test fun `rejects special bits and invalid modes`() {
        assertTrue(runCatching { FilePermissions.fromMode(0xFFF) }.isFailure)
        assertTrue(runCatching { FilePermissions.fromMode(-1) }.isFailure)
    }

    @Test fun `risk confirmation is required for writable peers or private app data`() {
        assertFalse(PermissionRiskPolicy.requiresConfirmation(path("/storage/emulated/0/a"), FilePermissions.fromMode(0x1A4)))
        assertTrue(PermissionRiskPolicy.requiresConfirmation(path("/storage/emulated/0/a"), FilePermissions.fromMode(0x1B6)))
        assertTrue(PermissionRiskPolicy.requiresConfirmation(path("/data/user/0/pkg/a"), FilePermissions.fromMode(0x180)))
    }

    @Test fun `protected path is rejected without dispatch`() = runTest {
        val fs = FakeFileSystem()
        val result = FilePermissionRepository(fs).change(
            entry("/system/build.prop"), path("/system"), metadata(), FilePermissions.fromMode(0x1A4), confirmed = true,
        )

        assertEquals(ErrorCode.NOT_WRITABLE, (result as OperationResult.Failure).code)
        assertEquals(0, fs.changes)
    }

    @Test fun `high risk mode requires explicit confirmation`() = runTest {
        val fs = FakeFileSystem()
        val result = FilePermissionRepository(fs).change(
            entry("/data/local/tmp/a"), path("/data/local/tmp"), metadata(), FilePermissions.fromMode(0x1B6), confirmed = false,
        )

        assertEquals(ErrorCode.CANCELLED, (result as OperationResult.Failure).code)
        assertEquals(0, fs.changes)
    }

    @Test fun `dispatches exact mode and expected metadata`() = runTest {
        val fs = FakeFileSystem()
        val expected = metadata()
        val result = FilePermissionRepository(fs).change(
            entry("/data/local/tmp/a"), path("/data/local/tmp"), expected, FilePermissions.fromMode(0x1ED), confirmed = true,
        )

        assertTrue(result is OperationResult.Success)
        assertEquals(1, fs.changes)
        assertEquals(0x1ED, fs.mode)
        assertEquals(expected, fs.expected)
    }

    private class FakeFileSystem : RootFileSystem {
        var changes = 0
        var mode: Int? = null
        var expected: RootFileMetadata? = null
        override suspend fun changeMode(
            source: DirectoryEntry,
            sourceDirectory: RootPath,
            expectedMetadata: RootFileMetadata,
            mode: Int,
        ): OperationResult<RootFileMetadata> {
            changes++
            this.mode = mode
            expected = expectedMetadata
            return OperationResult.Success(expectedMetadata.copy(mode = mode))
        }
        override suspend fun stat(path: RootPath) = error("unused")
        override suspend fun canonicalize(path: RootPath) = error("unused")
        override suspend fun createDirectory(parent: RootPath, name: FolderName) = error("unused")
    }

    private fun entry(value: String) = DirectoryEntry(path(value), value.substringAfterLast('/'), EntryType.FILE, 1, 1, true, true, false)
    private fun metadata() = RootFileMetadata(0x1A4, 0, 0, 1, 2)
    private fun path(value: String) = RootPath.parse(value).getOrThrow()
}
