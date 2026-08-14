package com.isaver.filemanager.data.root

import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryName
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibsuRootFileSystemCreateFileTest {
    @Test
    fun `create file dispatches fixed helper and verifies empty regular file`() = runTest {
        val runner = CreateFileRunner()
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler)).createFileNoReplace(
            parent = path(PARENT),
            name = EntryName.parse("new';\n.txt").getOrThrow(),
        )

        assertTrue(result.toString(), result is OperationResult.Success)
        assertEquals("$PARENT/new';\n.txt", (result as OperationResult.Success).value.path.value)
        assertTrue(runner.createCommand.orEmpty().contains("'create-file-noreplace'"))
        assertTrue(runner.createCommand.orEmpty().contains("'new'\\'';\n.txt'"))
        assertFalse(runner.createCommand.orEmpty().contains("touch"))
        assertFalse(runner.createCommand.orEmpty().contains("sh -c"))
    }

    @Test
    fun `existing target prevents helper dispatch`() = runTest {
        val runner = CreateFileRunner(targetExists = true)
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler)).createFileNoReplace(
            parent = path(PARENT),
            name = EntryName.parse("existing.txt").getOrThrow(),
        )

        assertEquals(ErrorCode.ALREADY_EXISTS, (result as OperationResult.Failure).code)
        assertEquals(null, runner.createCommand)
    }

    @Test
    fun `protected parent is rejected before root dispatch`() = runTest {
        val runner = CreateFileRunner()
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler)).createFileNoReplace(
            parent = path("/system"),
            name = EntryName.parse("blocked.txt").getOrThrow(),
        )

        assertEquals(ErrorCode.NOT_WRITABLE, (result as OperationResult.Failure).code)
        assertEquals(0, runner.commandCount)
    }

    @Test
    fun `malformed helper identity reports uncertain outcome`() = runTest {
        val runner = CreateFileRunner(helperOutput = listOf("not-an-identity"))
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler)).createFileNoReplace(
            parent = path(PARENT),
            name = EntryName.parse("uncertain.txt").getOrThrow(),
        )

        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (result as OperationResult.Failure).code)
        assertEquals("新建文件结果不确定，请刷新目录核对", result.userMessage)
    }

    private fun fileSystem(
        runner: CreateFileRunner,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) = LibsuRootFileSystem(
        commandRunner = runner,
        ioDispatcher = dispatcher,
        timeoutMillis = 5_000L,
        transferCommandRunner = runner,
    )

    private inner class CreateFileRunner(
        private val targetExists: Boolean = false,
        private val helperOutput: List<String> = listOf("1:20"),
    ) : RootCommandRunner, RootTransferCommandRunner {
        var createCommand: String? = null
        var commandCount = 0
        private var created = false

        override suspend fun run(command: String): RootCommandResult {
            commandCount++
            if (command.contains("'create-file-noreplace'")) {
                createCommand = command
                created = true
                return RootCommandResult(0, helperOutput, emptyList())
            }
            return when {
                command.contains("emit_isaver_record") -> stat(quotedAssignment(command, "target"))
                command.contains("readlink -f") -> RootCommandResult(
                    0,
                    listOf(Base64.getEncoder().encodeToString("${quotedAssignment(command, "target")}\n".toByteArray())),
                    emptyList(),
                )
                command.contains("stat -c '%d:%i'") -> identity(command)
                else -> error("Unexpected command: $command")
            }
        }

        private fun stat(target: String): RootCommandResult = when {
            target == PARENT -> successRecord("create-parent", target, EntryType.DIRECTORY, null, writable = true)
            target.startsWith("$PARENT/") && (targetExists || created) ->
                successRecord(target.substringAfterLast('/'), target, EntryType.FILE, 0L)
            target.startsWith("$PARENT/") -> RootCommandResult(44, emptyList(), emptyList())
            else -> error("Unexpected stat target: $target")
        }

        private fun identity(command: String): RootCommandResult {
            val path = decodeQuotedWord(command.substringAfter(" -- "))
            return RootCommandResult(0, listOf(if (path == PARENT) "1:10" else "1:20"), emptyList())
        }

        private fun quotedAssignment(command: String, name: String): String {
            val prefix = "$name='"
            val start = command.indexOf(prefix)
            require(start >= 0)
            val end = command.indexOf("'\n", start + prefix.length)
            require(end >= 0)
            return decodeQuotedWord(command.substring(start + name.length + 1, end + 1))
        }

        private fun decodeQuotedWord(value: String): String {
            require(value.length >= 2 && value.first() == '\'' && value.last() == '\'')
            return value.substring(1, value.lastIndex).replace("'\\''", "'")
        }

        private fun successRecord(
            name: String,
            path: String,
            type: EntryType,
            size: Long?,
            writable: Boolean = false,
        ) = RootCommandResult(
            0,
            listOf(
                listOf(
                    b64(name),
                    b64(path),
                    type.name.lowercase(),
                    size?.toString() ?: "-",
                    "1",
                    "1",
                    if (writable) "1" else "0",
                    "0",
                ).joinToString("\t"),
            ),
            emptyList(),
        )

        private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
    }

    private fun path(value: String) = RootPath.parse(value).getOrThrow()

    private companion object {
        const val PARENT = "/data/local/tmp/create-parent"
    }
}
