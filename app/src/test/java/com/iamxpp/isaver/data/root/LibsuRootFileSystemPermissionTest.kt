package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibsuRootFileSystemPermissionTest {
    @Test
    fun `change mode dispatches fixed helper and verifies exact metadata`() = runTest {
        val runner = PermissionRunner()
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler))
            .changeMode(entry(), path(PARENT), INITIAL, 0x180)

        assertEquals(CHANGED, (result as OperationResult.Success).value)
        assertTrue(runner.chmodCommand.orEmpty().contains("'chmod-bound'"))
        assertTrue(runner.chmodCommand.orEmpty().endsWith("'420' '384'"))
        assertFalse(runner.chmodCommand.orEmpty().contains("sh -c"))
    }

    @Test
    fun `stale metadata prevents chmod dispatch`() = runTest {
        val runner = PermissionRunner(currentBefore = INITIAL.copy(mode = 0x1C0))
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler))
            .changeMode(entry(), path(PARENT), INITIAL, 0x180)

        assertEquals(ErrorCode.SOURCE_UNREADABLE, (result as OperationResult.Failure).code)
        assertEquals("项目已变化，请重新打开属性", result.userMessage)
        assertEquals(null, runner.chmodCommand)
    }

    @Test
    fun `protected source prevents all root commands`() = runTest {
        val runner = PermissionRunner()
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler)).changeMode(
            entry("/system/build.prop", "/system"), path("/system"), INITIAL, 0x180,
        )

        assertEquals(ErrorCode.NOT_WRITABLE, (result as OperationResult.Failure).code)
        assertEquals(0, runner.commandCount)
    }

    @Test
    fun `malformed helper metadata is uncertain`() = runTest {
        val runner = PermissionRunner(chmodOutput = listOf("bad"))
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler))
            .changeMode(entry(), path(PARENT), INITIAL, 0x180)

        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (result as OperationResult.Failure).code)
        assertEquals("权限修改结果不确定，请刷新属性核对", result.userMessage)
    }

    @Test
    fun `post verification mismatch is uncertain`() = runTest {
        val runner = PermissionRunner(currentAfter = CHANGED.copy(mode = 0x1A4))
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler))
            .changeMode(entry(), path(PARENT), INITIAL, 0x180)

        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (result as OperationResult.Failure).code)
        assertEquals("权限修改结果不确定，请刷新属性核对", result.userMessage)
    }

    private fun fileSystem(
        runner: PermissionRunner,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) = LibsuRootFileSystem(
        commandRunner = runner,
        ioDispatcher = dispatcher,
        timeoutMillis = 5_000L,
        transferCommandRunner = runner,
    )

    private inner class PermissionRunner(
        private val currentBefore: RootFileMetadata = INITIAL,
        private val currentAfter: RootFileMetadata = CHANGED,
        private val chmodOutput: List<String> = metadataLine(CHANGED),
    ) : RootCommandRunner, RootTransferCommandRunner {
        var commandCount = 0
        var chmodCommand: String? = null
        private var changed = false

        override suspend fun run(command: String): RootCommandResult {
            commandCount++
            if (command.contains("'chmod-bound'")) {
                chmodCommand = command
                changed = true
                return RootCommandResult(0, chmodOutput, emptyList())
            }
            if (command.contains("'file-metadata'")) {
                return RootCommandResult(0, metadataLine(if (changed) currentAfter else currentBefore), emptyList())
            }
            return when {
                command.contains("emit_isaver_record") -> stat(quotedAssignment(command, "target"))
                command.contains("readlink -f") -> RootCommandResult(
                    0,
                    listOf(Base64.getEncoder().encodeToString("${quotedAssignment(command, "target")}\n".toByteArray())),
                    emptyList(),
                )
                command.contains("stat -c '%d:%i'") -> RootCommandResult(0, listOf("1:2"), emptyList())
                else -> error("Unexpected command: $command")
            }
        }

        private fun stat(target: String) = when (target) {
            PARENT -> record("permissions", target, EntryType.DIRECTORY, null)
            FILE -> record("note.txt", target, EntryType.FILE, 11L)
            else -> error("Unexpected stat target: $target")
        }

        private fun quotedAssignment(command: String, name: String): String {
            val prefix = "$name='"
            val start = command.indexOf(prefix)
            require(start >= 0)
            val end = command.indexOf("'\n", start + prefix.length)
            return decodeQuotedWord(command.substring(start + name.length + 1, end + 1))
        }

        private fun decodeQuotedWord(value: String) =
            value.substring(1, value.lastIndex).replace("'\\''", "'")

        private fun record(name: String, target: String, type: EntryType, size: Long?) = RootCommandResult(
            0,
            listOf(
                listOf(
                    b64(name), b64(target), type.name.lowercase(), size?.toString() ?: "-",
                    "1", "1", "1", "0",
                ).joinToString("\t"),
            ),
            emptyList(),
        )

        private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
    }

    private fun entry(file: String = FILE, parent: String = PARENT) = DirectoryEntry(
        path(file), file.removePrefix("$parent/"), EntryType.FILE, 11L, 1L, true, true, false,
    )
    private fun path(value: String) = RootPath.parse(value).getOrThrow()

    private companion object {
        const val PARENT = "/data/local/tmp/permissions"
        const val FILE = "$PARENT/note.txt"
        val INITIAL = RootFileMetadata(0x1A4, 0, 0, 3, 4)
        val CHANGED = INITIAL.copy(mode = 0x180)

        fun metadataLine(value: RootFileMetadata) = listOf(
            "ISAVER_META_V1\t${value.mode}\t${value.uid}\t${value.gid}\t${value.device}\t${value.inode}",
        )
    }
}
