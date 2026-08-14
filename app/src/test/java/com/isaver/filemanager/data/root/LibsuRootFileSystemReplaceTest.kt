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
class LibsuRootFileSystemReplaceTest {
    @Test
    fun `replace dispatches fixed helper with exact loaded version`() = runTest {
        val runner = ReplaceRunner()
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler)).replaceFileAtomically(
            source = entry(),
            sourceDirectory = path(PARENT),
            expectedVersion = VERSION,
            content = stream(13L),
        )

        assertTrue(result.toString(), result is OperationResult.Success)
        val command = runner.replaceCommand.orEmpty()
        assertTrue(command.contains("'replace-file-stdin'"))
        assertTrue(command.contains("'11' '3' '4' '5' '6' '7' '8' '13'"))
        assertFalse(command.contains(" rm "))
    }

    @Test
    fun `source changed is an explicit reload failure`() = runTest {
        val runner = ReplaceRunner(replaceExit = 54)
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler)).replaceFileAtomically(
            entry(), path(PARENT), VERSION, stream(13L),
        )

        assertEquals(ErrorCode.SOURCE_UNREADABLE, (result as OperationResult.Failure).code)
        assertEquals("文件已被外部修改，请重新加载", result.userMessage)
    }

    @Test
    fun `protected source is rejected before root dispatch`() = runTest {
        val runner = ReplaceRunner()
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler)).replaceFileAtomically(
            entry("/system/build.prop", "/system"), path("/system"), VERSION, stream(13L),
        )

        assertEquals(ErrorCode.NOT_WRITABLE, (result as OperationResult.Failure).code)
        assertEquals(0, runner.commandCount)
    }

    @Test
    fun `malformed success output is uncertain`() = runTest {
        val runner = ReplaceRunner(replaceOutput = listOf("bad"))
        val result = fileSystem(runner, StandardTestDispatcher(testScheduler)).replaceFileAtomically(
            entry(), path(PARENT), VERSION, stream(13L),
        )

        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (result as OperationResult.Failure).code)
        assertEquals("保存结果不确定，请刷新并核对文件", result.userMessage)
    }

    private fun fileSystem(runner: ReplaceRunner, dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        LibsuRootFileSystem(
            commandRunner = runner,
            ioDispatcher = dispatcher,
            timeoutMillis = 5_000L,
            transferCommandRunner = runner,
            editStageNameFactory = { ".isaver-edit-123e4567-e89b-12d3-a456-426614174000" },
        )

    private inner class ReplaceRunner(
        private val replaceExit: Int = 0,
        private val replaceOutput: List<String> = listOf("3:40:13"),
    ) : RootCommandRunner, RootTransferCommandRunner {
        var replaceCommand: String? = null
        var commandCount = 0
        private var replaced = false

        override suspend fun run(command: String): RootCommandResult {
            commandCount++
            if (command.contains("'replace-file-stdin'")) {
                replaceCommand = command
                if (replaceExit == 0) replaced = true
                return RootCommandResult(replaceExit, replaceOutput, emptyList())
            }
            return when {
                command.contains("emit_isaver_record") -> stat(quotedAssignment(command, "target"))
                command.contains("readlink -f") -> RootCommandResult(
                    0,
                    listOf(Base64.getEncoder().encodeToString("${quotedAssignment(command, "target")}\n".toByteArray())),
                    emptyList(),
                )
                command.contains("stat -c '%d:%i'") -> {
                    val target = decodeQuotedWord(command.substringAfter(" -- "))
                    RootCommandResult(0, listOf(if (target == PARENT) "1:2" else if (replaced) "3:40" else "3:4"), emptyList())
                }
                else -> error("Unexpected command: $command")
            }
        }

        private fun stat(target: String): RootCommandResult = when (target) {
            PARENT -> successRecord("edit-parent", target, EntryType.DIRECTORY, null, writable = true)
            FILE -> successRecord("note.txt", target, EntryType.FILE, if (replaced) 13L else 11L, writable = true)
            else -> error("Unexpected stat target: $target")
        }

        private fun quotedAssignment(command: String, name: String): String {
            val prefix = "$name='"
            val start = command.indexOf(prefix)
            require(start >= 0)
            val end = command.indexOf("'\n", start + prefix.length)
            return decodeQuotedWord(command.substring(start + name.length + 1, end + 1))
        }

        private fun decodeQuotedWord(value: String): String =
            value.substring(1, value.lastIndex).replace("'\\''", "'")

        private fun successRecord(name: String, path: String, type: EntryType, size: Long?, writable: Boolean) = RootCommandResult(
            0,
            listOf(listOf(
                b64(name), b64(path), type.name.lowercase(), size?.toString() ?: "-", "1", "1",
                if (writable) "1" else "0", "0",
            ).joinToString("\t")),
            emptyList(),
        )

        private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
    }

    private fun entry(file: String = FILE, parent: String = PARENT) = DirectoryEntry(
        path(file), file.removePrefix("$parent/"), EntryType.FILE, 11L, 5L, true, true, false,
    )
    private fun stream(size: Long) = RootTransferSource(
        "content://com.isaver.filemanager.incoming-stream/incoming/${"ab".repeat(32)}", size, "ab".repeat(32),
    )
    private fun path(value: String) = RootPath.parse(value).getOrThrow()

    private companion object {
        const val PARENT = "/data/local/tmp/edit-parent"
        const val FILE = "$PARENT/note.txt"
        val VERSION = RootFileVersion(11L, 3L, 4L, 5L, 6L, 7L, 8L)
    }
}
