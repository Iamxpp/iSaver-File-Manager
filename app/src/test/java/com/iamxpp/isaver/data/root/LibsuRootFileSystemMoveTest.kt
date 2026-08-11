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
class LibsuRootFileSystemMoveTest {
    @Test
    fun `same filesystem move dispatches fixed helper and verifies moved identity`() = runTest {
        val runner = MoveRunner()
        val fileSystem = fileSystem(runner, StandardTestDispatcher(testScheduler))

        val result = fileSystem.moveFileNoReplace(
            source = source("a';\n.txt"),
            sourceDirectory = path(SOURCE_DIRECTORY),
            targetDirectory = path(TARGET_DIRECTORY),
        )

        assertTrue(result.toString(), result is OperationResult.Success)
        val output = (result as OperationResult.Success).value
        assertEquals("$TARGET_DIRECTORY/a';\n.txt", output.path.value)
        assertTrue(runner.moveCommand.orEmpty().contains("'move-noreplace'"))
        assertTrue(runner.moveCommand.orEmpty().contains("'a'\\'';\n.txt'"))
        assertFalse(runner.moveCommand.orEmpty().contains(" mv "))
        assertTrue(runner.moved)
    }

    @Test
    fun `cross filesystem move copies publishes and removes source`() = runTest {
        val runner = MoveRunner(moveResult = RootCommandResult(58, emptyList(), emptyList()))
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler)).moveFileNoReplace(
            source = source("report.txt"),
            sourceDirectory = path(SOURCE_DIRECTORY),
            targetDirectory = path(TARGET_DIRECTORY),
        )

        assertTrue(result.toString(), result is OperationResult.Success)
        assertTrue(runner.crossMoveCommand.orEmpty().contains("'move-cross-device-noreplace'"))
        assertTrue(runner.sourceRemoved)
    }

    @Test
    fun `published target with retained source reports partial move`() = runTest {
        val runner = MoveRunner(
            moveResult = RootCommandResult(58, emptyList(), emptyList()),
            crossMoveResult = RootCommandResult(59, listOf("1:30:12"), emptyList()),
        )
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler)).moveFileNoReplace(
            source = source("report.txt"),
            sourceDirectory = path(SOURCE_DIRECTORY),
            targetDirectory = path(TARGET_DIRECTORY),
        )

        assertEquals(result.toString(), ErrorCode.MOVE_PARTIAL, (result as OperationResult.Failure).code)
        assertEquals("文件已复制，但来源未删除", result.userMessage)
        assertTrue(runner.published)
        assertFalse(runner.sourceRemoved)
    }

    @Test
    fun `existing target prevents native move dispatch`() = runTest {
        val runner = MoveRunner(targetExists = true)
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler)).moveFileNoReplace(
            source = source("report.txt"),
            sourceDirectory = path(SOURCE_DIRECTORY),
            targetDirectory = path(TARGET_DIRECTORY),
        )

        assertEquals(result.toString(), ErrorCode.ALREADY_EXISTS, (result as OperationResult.Failure).code)
        assertEquals(null, runner.moveCommand)
        assertFalse(runner.moved)
    }

    private fun fileSystem(runner: MoveRunner, dispatcher: kotlinx.coroutines.CoroutineDispatcher) = LibsuRootFileSystem(
        commandRunner = runner,
        ioDispatcher = dispatcher,
        timeoutMillis = 5_000L,
        transferCommandRunner = runner,
    )

    private fun source(name: String) = DirectoryEntry(
        path = path("$SOURCE_DIRECTORY/$name"),
        name = name,
        type = EntryType.FILE,
        sizeBytes = 12L,
        modifiedAtEpochSeconds = 1L,
        readable = true,
        writable = false,
        symbolicLink = false,
    )

    private inner class MoveRunner(
        private val moveResult: RootCommandResult = RootCommandResult(0, listOf("1:30"), emptyList()),
        private val crossMoveResult: RootCommandResult = RootCommandResult(0, listOf("1:30:12"), emptyList()),
        private val targetExists: Boolean = false,
    ) : RootCommandRunner, RootTransferCommandRunner {
        var moved = false
        var moveCommand: String? = null
        var crossMoveCommand: String? = null
        var published = false
        var sourceRemoved = false

        override suspend fun run(command: String): RootCommandResult {
            if (command.contains("'move-cross-device-noreplace'")) {
                crossMoveCommand = command
                published = crossMoveResult.exitCode == 0 || crossMoveResult.exitCode == 59
                sourceRemoved = crossMoveResult.exitCode == 0
                return crossMoveResult
            }
            if (command.contains("'prepare-stage'")) {
                return RootCommandResult(0, listOf("1:70"), emptyList())
            }
            if (command.contains("'move-noreplace'")) {
                moveCommand = command
                if (moveResult.exitCode == 0) moved = true
                return moveResult
            }
            return when {
                command.contains("emit_isaver_record") -> stat(quotedAssignment(command, "target"))
                command.contains("readlink -f") -> RootCommandResult(
                    0,
                    listOf(
                        Base64.getEncoder().encodeToString(
                            "${quotedAssignment(command, "target")}\n".toByteArray(),
                        ),
                    ),
                    emptyList(),
                )
                command.contains("stat -c '%d:%i'") -> identity(command)
                else -> error("Unexpected command: $command")
            }
        }

        private fun stat(target: String): RootCommandResult = when (target) {
            SOURCE_DIRECTORY -> successRecord("source", target, EntryType.DIRECTORY, null, writable = true)
            TARGET_DIRECTORY -> successRecord("target", target, EntryType.DIRECTORY, null, writable = true)
            "$SOURCE_DIRECTORY/a';\n.txt",
            "$SOURCE_DIRECTORY/report.txt" -> if (moved || sourceRemoved) missing() else successRecord(
                target.substringAfterLast('/'), target, EntryType.FILE, 12L,
            )
            "$TARGET_DIRECTORY/a';\n.txt",
            "$TARGET_DIRECTORY/report.txt" -> if (moved || published || targetExists) successRecord(
                target.substringAfterLast('/'), target, EntryType.FILE, 12L,
            ) else missing()
            else -> error("Unexpected stat target: $target")
        }

        private fun identity(command: String): RootCommandResult {
            val separator = " -- "
            val start = command.indexOf(separator)
            require(start >= 0) { "Missing identity path: $command" }
            val quotedPath = decodeQuotedWord(command.substring(start + separator.length))
            val value = when (quotedPath) {
                SOURCE_DIRECTORY -> "1:10"
                TARGET_DIRECTORY -> "1:20"
                "$SOURCE_DIRECTORY/a';\n.txt",
                "$SOURCE_DIRECTORY/report.txt",
                "$TARGET_DIRECTORY/a';\n.txt",
                "$TARGET_DIRECTORY/report.txt" -> "1:30"
                else -> error("Unexpected identity path: $quotedPath")
            }
            return RootCommandResult(0, listOf(value), emptyList())
        }

        private fun quotedAssignment(command: String, name: String): String {
            val prefix = "$name='"
            val start = command.indexOf(prefix)
            require(start >= 0) { "Missing $name assignment: $command" }
            val end = command.indexOf("'\n", start + prefix.length)
            require(end >= 0) { "Unterminated $name assignment: $command" }
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

        private fun missing() = RootCommandResult(44, emptyList(), emptyList())
        private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
    }

    private fun path(value: String) = RootPath.parse(value).getOrThrow()

    private companion object {
        const val SOURCE_DIRECTORY = "/data/local/tmp/source"
        const val TARGET_DIRECTORY = "/data/local/tmp/target"
    }
}
