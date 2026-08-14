package com.isaver.filemanager.data.root

import com.isaver.filemanager.domain.DirectoryEntry
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
class LibsuRootFileSystemCopyTest {
    @Test
    fun `copy dispatches fixed staged helper and keeps source identity`() = runTest {
        val runner = CopyRunner()
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler)).copyFileNoReplace(
            source = source("a';\n.txt"),
            sourceDirectory = path(SOURCE_DIRECTORY),
            targetDirectory = path(TARGET_DIRECTORY),
        )

        assertTrue(result.toString(), result is OperationResult.Success)
        val output = (result as OperationResult.Success).value
        assertEquals("$TARGET_DIRECTORY/a';\n.txt", output.path.value)
        assertTrue(runner.copyCommand.orEmpty().contains("'copy-file-publish'"))
        assertTrue(runner.copyCommand.orEmpty().contains("'a'\\'';\n.txt'"))
        assertFalse(runner.copyCommand.orEmpty().contains(" cp "))
        assertTrue(runner.copied)
        assertFalse(runner.sourceMissing)
    }

    @Test
    fun `existing target prevents stage preparation and copy dispatch`() = runTest {
        val runner = CopyRunner(targetExists = true)
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler)).copyFileNoReplace(
            source = source("report.txt"),
            sourceDirectory = path(SOURCE_DIRECTORY),
            targetDirectory = path(TARGET_DIRECTORY),
        )

        assertEquals(ErrorCode.ALREADY_EXISTS, (result as OperationResult.Failure).code)
        assertEquals(null, runner.copyCommand)
        assertEquals(0, runner.prepareCalls)
    }

    private fun fileSystem(runner: CopyRunner, dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        LibsuRootFileSystem(
            commandRunner = runner,
            ioDispatcher = dispatcher,
            timeoutMillis = 5_000L,
            stageNameFactory = { STAGE_NAME },
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

    private inner class CopyRunner(
        private val targetExists: Boolean = false,
    ) : RootCommandRunner, RootTransferCommandRunner {
        var copied = false
        var sourceMissing = false
        var copyCommand: String? = null
        var prepareCalls = 0

        override suspend fun run(command: String): RootCommandResult {
            when {
                command.contains("'prepare-stage'") -> {
                    prepareCalls += 1
                    return RootCommandResult(0, listOf("1:50"), emptyList())
                }
                command.contains("'copy-file-publish'") -> {
                    copyCommand = command
                    copied = true
                    return RootCommandResult(0, listOf("1:40:12"), emptyList())
                }
                command.contains("emit_isaver_record") -> return stat(quotedAssignment(command, "target"))
                command.contains("readlink -f") -> return RootCommandResult(
                    0,
                    listOf(Base64.getEncoder().encodeToString("${quotedAssignment(command, "target")}\n".toByteArray())),
                    emptyList(),
                )
                command.contains("stat -c '%d:%i'") -> return identity(command)
                else -> error("Unexpected command: $command")
            }
        }

        private fun stat(target: String): RootCommandResult = when (target) {
            SOURCE_DIRECTORY -> successRecord("source", target, EntryType.DIRECTORY, null, writable = false)
            TARGET_DIRECTORY -> successRecord("target", target, EntryType.DIRECTORY, null, writable = true)
            "$SOURCE_DIRECTORY/a';\n.txt",
            "$SOURCE_DIRECTORY/report.txt" -> if (sourceMissing) missing() else successRecord(
                target.substringAfterLast('/'), target, EntryType.FILE, 12L,
            )
            "$TARGET_DIRECTORY/a';\n.txt",
            "$TARGET_DIRECTORY/report.txt" -> if (copied || targetExists) successRecord(
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
                "$SOURCE_DIRECTORY/report.txt" -> "1:30"
                "$TARGET_DIRECTORY/a';\n.txt",
                "$TARGET_DIRECTORY/report.txt" -> "1:40"
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
                    b64(name), b64(path), type.name.lowercase(), size?.toString() ?: "-",
                    "1", "1", if (writable) "1" else "0", "0",
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
        const val STAGE_NAME = ".isaver-stage-123e4567-e89b-12d3-a456-426614174000"
    }
}
