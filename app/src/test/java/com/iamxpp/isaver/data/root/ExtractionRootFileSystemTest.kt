package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionRootFileSystemTest {
    @Test
    fun `typed extraction operations preserve one identity-bound stage`() = runTest {
        val runner = ExtractionRunner()
        val fileSystem = fileSystem(runner)

        val stage = (fileSystem.prepareExtractionStage(path("/target")) as OperationResult.Success).value
        assertTrue(fileSystem.createExtractionDirectory(stage, "docs/子目录") is OperationResult.Success)
        assertTrue(
            fileSystem.transferIntoExtractionStage(
                stage,
                "docs/子目录",
                source(),
                EntryName.parse("报告.txt").getOrThrow(),
            ) is OperationResult.Success,
        )
        val committed = fileSystem.commitExtractionStage(
            stage,
            FolderName.parse("backup").getOrThrow(),
        )

        assertTrue(committed.toString(), committed is OperationResult.Success)
        assertEquals("/target/backup", (committed as OperationResult.Success).value.path.value)
        assertEquals(
            listOf("prepare-extract-stage", "mkdir-extract", "copy-extract-stdin", "commit-extract-stage"),
            runner.helperCommands(),
        )
        assertFalse(runner.commands.any { it.contains("remove-stage") || it.contains(" rm ") })
    }

    @Test
    fun `invalid relative path is rejected before helper dispatch`() = runTest {
        val runner = ExtractionRunner()
        val fileSystem = fileSystem(runner)
        val stage = (fileSystem.prepareExtractionStage(path("/target")) as OperationResult.Success).value
        val before = runner.helperCommands().size

        val mkdir = fileSystem.createExtractionDirectory(stage, "../escape")
        val copy = fileSystem.transferIntoExtractionStage(
            stage, "a/../b", source(), EntryName.parse("file.txt").getOrThrow(),
        )

        assertEquals(ErrorCode.COMMAND_FAILED, (mkdir as OperationResult.Failure).code)
        assertEquals(ErrorCode.COMMAND_FAILED, (copy as OperationResult.Failure).code)
        assertEquals(before, runner.helperCommands().size)
    }

    @Test
    fun `commit collision is definite and uncertain commit never guesses cleanup`() = runTest {
        val collisionRunner = ExtractionRunner(commitExit = 49)
        val collisionFs = fileSystem(collisionRunner)
        val collisionStage = (collisionFs.prepareExtractionStage(path("/target")) as OperationResult.Success).value
        val collision = collisionFs.commitExtractionStage(collisionStage, FolderName.parse("backup").getOrThrow())
        assertEquals(ErrorCode.ALREADY_EXISTS, (collision as OperationResult.Failure).code)

        val uncertainRunner = ExtractionRunner(commitThrows = true)
        val uncertainFs = fileSystem(uncertainRunner)
        val uncertainStage = (uncertainFs.prepareExtractionStage(path("/target")) as OperationResult.Success).value
        val uncertain = uncertainFs.commitExtractionStage(uncertainStage, FolderName.parse("backup").getOrThrow())
        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (uncertain as OperationResult.Failure).code)
        assertFalse(uncertainRunner.commands.any { it.contains("remove-extract-stage") })
    }

    @Test
    fun `cleanup uses only the identity-bound extraction stage`() = runTest {
        val runner = ExtractionRunner()
        val fileSystem = fileSystem(runner)
        val stage = (fileSystem.prepareExtractionStage(path("/target")) as OperationResult.Success).value

        assertTrue(fileSystem.cleanupExtractionStage(stage) is OperationResult.Success)

        val cleanup = runner.commands.single { it.contains("'remove-extract-stage'") }
        assertTrue(cleanup.contains("'.isaver-extract-123e4567-e89b-12d3-a456-426614174000' '11' '22' '33' '44'"))
        assertFalse(cleanup.contains("backup"))
    }

    private fun fileSystem(runner: ExtractionRunner) = LibsuRootFileSystem(
        commandRunner = runner,
        ioDispatcher = Dispatchers.Unconfined,
        timeoutMillis = 5_000,
        extractionStageNameFactory = {
            ".isaver-extract-123e4567-e89b-12d3-a456-426614174000"
        },
        transferCommandRunner = RootTransferCommandRunner { runner.run(it) },
    )

    private fun source() = RootTransferSource(
        contentUri = "content://com.iamxpp.isaver.incoming-stream/incoming/${"ab".repeat(32)}",
        expectedSizeBytes = 4L,
        token = "ab".repeat(32),
    )

    private fun path(value: String) = RootPath.parse(value).getOrThrow()

    private class ExtractionRunner(
        private val commitExit: Int = 0,
        private val commitThrows: Boolean = false,
    ) : RootCommandRunner {
        val commands = mutableListOf<String>()
        private var committed = false

        override suspend fun run(command: String): RootCommandResult {
            commands += command
            return when {
                command.contains("'prepare-extract-stage'") -> RootCommandResult(0, listOf("33:44"), emptyList())
                command.contains("'mkdir-extract'") -> RootCommandResult(0, emptyList(), emptyList())
                command.contains("'copy-extract-stdin'") -> RootCommandResult(0, listOf("55:66:4"), emptyList())
                command.contains("'commit-extract-stage'") -> {
                    if (commitThrows) error("commit result lost")
                    committed = commitExit == 0
                    RootCommandResult(commitExit, if (committed) listOf("33:44") else emptyList(), emptyList())
                }
                command.contains("'remove-extract-stage'") -> RootCommandResult(0, emptyList(), emptyList())
                command.contains("target='/target/backup'") -> if (committed) {
                    RootCommandResult(0, listOf(record("backup", "/target/backup", "directory", "-")), emptyList())
                } else {
                    RootCommandResult(44, emptyList(), emptyList())
                }
                command.contains("target='/target'") && command.contains("readlink -f") ->
                    RootCommandResult(0, listOf(Base64.getEncoder().encodeToString("/target\n".toByteArray())), emptyList())
                command.contains("target='/target'") && command.contains("emit_isaver_record") ->
                    RootCommandResult(0, listOf(record("target", "/target", "directory", "-")), emptyList())
                command.contains("stat -c '%d:%i'") && command.contains("/target/backup") ->
                    RootCommandResult(0, listOf("33:44"), emptyList())
                command.contains("stat -c '%d:%i'") -> RootCommandResult(0, listOf("11:22"), emptyList())
                else -> error("unexpected command: $command")
            }
        }

        fun helperCommands(): List<String> = commands.mapNotNull { command ->
            listOf(
                "prepare-extract-stage", "mkdir-extract", "copy-extract-stdin",
                "commit-extract-stage", "remove-extract-stage",
            ).firstOrNull { command.contains("'$it'") }
        }

        private fun record(name: String, path: String, type: String, size: String): String = listOf(
            Base64.getEncoder().encodeToString(name.toByteArray()),
            Base64.getEncoder().encodeToString(path.toByteArray()),
            type,
            size,
            "1",
            "1",
            "1",
            "0",
        ).joinToString("\t")
    }
}
