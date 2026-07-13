package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RootTransferStagingTest {
    @Test
    fun `transfer uses one private stage and one copy publish helper command`() = runTest {
        val runner = StagingRunner()
        val fileSystem = fileSystem(runner)

        val result = fileSystem.transferFromAppCache(
            source = source(),
            targetDirectory = path("/target"),
            finalName = name("报告 final.txt"),
            expectedSizeBytes = 4,
        )

        assertTrue(result.toString(), result is OperationResult.Success<*>)
        assertEquals(listOf("prepare-stage", "copy-publish"), runner.helperCommands())
        val copy = runner.commands.single { it.contains("'copy-publish'") }
        assertTrue(copy.contains("'/target' '/target'"))
        assertTrue(copy.contains("'.isaver-stage-123e4567-e89b-12d3-a456-426614174000'"))
        assertTrue(copy.contains("'报告 final.txt'"))
        assertFalse(runner.commands.any { it.contains("copy-to-temp") || it.contains("publish-noreplace") || it.contains("remove-temp") })
    }

    @Test
    fun `transfer maps final race and no space without claiming success`() = runTest {
        listOf(49 to ErrorCode.ALREADY_EXISTS, 50 to ErrorCode.NO_SPACE).forEach { (exit, expected) ->
            val runner = StagingRunner(copyExit = exit)

            val result = fileSystem(runner).transferFromAppCache(source(), path("/target"), name("final.txt"), 4)

            assertEquals(expected, (result as OperationResult.Failure).code)
            assertFalse(runner.finalExists)
        }
    }

    @Test
    fun `transfer maps changed source and invalid stage as typed failures`() = runTest {
        listOf(54 to ErrorCode.SOURCE_UNREADABLE,56 to ErrorCode.SOURCE_UNREADABLE, 53 to ErrorCode.COMMAND_FAILED).forEach { (exit, expected) ->
            val result = fileSystem(StagingRunner(copyExit = exit))
                .transferFromAppCache(source(), path("/target"), name("final.txt"), 4)

            assertEquals(expected, (result as OperationResult.Failure).code)
        }
    }

    @Test
    fun `toybox timeout exit is uncertain and reconciles stage only after final check`() = runTest {
        val runner=StagingRunner(copyExit=137)

        val result=fileSystem(runner).transferFromAppCache(source(),path("/target"),name("final.txt"),4)

        assertEquals(ErrorCode.OUTCOME_UNCERTAIN,(result as OperationResult.Failure).code)
        assertEquals(1,runner.commands.count{it.contains("'remove-stage'")})
    }

    @Test
    fun `native uncertain outcome performs stage-only cleanup and stays uncertain`() = runTest {
        val runner = StagingRunner(copyExit = 55)

        val result = fileSystem(runner).transferFromAppCache(source(), path("/target"), name("final.txt"), 4)

        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (result as OperationResult.Failure).code)
        assertEquals(1, runner.commands.count { it.contains("'remove-stage'") })
        assertFalse(runner.commands.single { it.contains("'remove-stage'") }.contains("final.txt"))
    }

    @Test
    fun `native uncertain outcome preserves stage when final exists`() = runTest {
        val runner = StagingRunner(copyExit = 55, finalOnFailure = true)

        val result = fileSystem(runner).transferFromAppCache(source(), path("/target"), name("final.txt"), 4)

        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (result as OperationResult.Failure).code)
        assertTrue(runner.finalExists)
        assertEquals(0, runner.commands.count { it.contains("'remove-stage'") })
    }

    @Test
    fun `lost copy result cleans only the recorded stage and reports uncertain`() = runTest {
        val runner = StagingRunner(copyFailure = CopyFailure.EXCEPTION)

        val result = fileSystem(runner).transferFromAppCache(source(), path("/target"), name("final.txt"), 4)

        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (result as OperationResult.Failure).code)
        val cleanup = runner.commands.single { it.contains("'remove-stage'") }
        assertTrue(cleanup.contains("'11' '22' '33' '44'"))
        assertFalse(cleanup.contains("final.txt"))
    }

    @Test
    fun `copy timeout cleans the recorded stage and reports uncertain`() = runTest {
        val runner = StagingRunner(copyFailure = CopyFailure.TIMEOUT)

        val result = fileSystem(runner, timeoutMillis = 10)
            .transferFromAppCache(source(), path("/target"), name("final.txt"), 4)

        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (result as OperationResult.Failure).code)
        assertEquals(1, runner.commands.count { it.contains("'remove-stage'") })
    }

    @Test
    fun `copy cancellation after dispatch returns uncertain and cleans only when final is absent`() = runTest {
        val runner = StagingRunner(copyFailure = CopyFailure.CANCEL)

        val result = fileSystem(runner).transferFromAppCache(source(), path("/target"), name("final.txt"), 4)

        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (result as OperationResult.Failure).code)
        assertEquals(1, runner.commands.count { it.contains("'remove-stage'") })
    }

    @Test
    fun `lost timeout or cancellation never cleans stage when final may have been published`() = runTest {
        listOf(CopyFailure.TIMEOUT, CopyFailure.CANCEL, CopyFailure.EXCEPTION).forEach { failure ->
            val runner = StagingRunner(copyFailure = failure, finalOnFailure = true)

            val result = fileSystem(runner, timeoutMillis = 10)
                .transferFromAppCache(source(), path("/target"), name("final.txt"), 4)

            assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (result as OperationResult.Failure).code)
            assertEquals(0, runner.commands.count { it.contains("'remove-stage'") })
            assertTrue(runner.finalExists)
        }
    }

    @Test
    fun `wait timeout marks the transfer but does not cancel backend before reconciliation`() = runTest {
        val gate=CompletableDeferred<Unit>()
        val runner=StagingRunner(copyFailure=CopyFailure.EXCEPTION,copyGate=gate)
        val transfer=async{
            fileSystem(runner,timeoutMillis=10,dispatcher=StandardTestDispatcher(testScheduler))
                .transferFromAppCache(source(),path("/target"),name("final.txt"),4)
        }
        runner.copyStarted.await()

        testScheduler.advanceTimeBy(11)
        testScheduler.runCurrent()

        assertFalse(transfer.isCompleted)
        assertFalse(runner.copyCancelled)
        gate.complete(Unit)
        val result=transfer.await()
        assertEquals(ErrorCode.OUTCOME_UNCERTAIN,(result as OperationResult.Failure).code)
        assertTrue(result.technicalMessage.orEmpty().contains("timed out"))
        assertEquals(1,runner.commands.count{it.contains("'remove-stage'")})
    }

    @Test
    fun `caller cancellation waits for backend completion before final reconciliation`() = runTest {
        val gate=CompletableDeferred<Unit>()
        val runner=StagingRunner(
            copyFailure=CopyFailure.EXCEPTION,
            finalOnFailure=true,
            copyGate=gate,
        )
        val transfer=async{
            fileSystem(runner,dispatcher=StandardTestDispatcher(testScheduler))
                .transferFromAppCache(source(),path("/target"),name("final.txt"),4)
        }
        runner.copyStarted.await()

        transfer.cancel()
        testScheduler.runCurrent()

        assertFalse(runner.copyCancelled)
        assertFalse(transfer.isCompleted)
        gate.complete(Unit)
        transfer.join()
        assertTrue(runner.finalExists)
        assertEquals(0,runner.commands.count{it.contains("'remove-stage'")})
    }

    @Test
    fun `hung transfer backend does not occupy the global root coordinator after timeout`() = runTest {
        val gate=CompletableDeferred<Unit>()
        val started=CompletableDeferred<Unit>()
        val runner=StagingRunner()
        val isolated=RootTransferCommandRunner{command->
            if(command.contains("'copy-publish'")){started.complete(Unit);gate.await()}
            runner.run(command)
        }
        val fs=fileSystem(
            runner,
            timeoutMillis=10,
            dispatcher=StandardTestDispatcher(testScheduler),
            transferRunner=isolated,
        )
        val transfer=async{fs.transferFromAppCache(source(),path("/target"),name("final.txt"),4)}
        started.await()
        testScheduler.advanceTimeBy(11)

        val stat=async{fs.stat(path("/target"))}
        testScheduler.runCurrent()

        assertTrue(stat.await() is OperationResult.Success)
        assertFalse(transfer.isCompleted)
        gate.complete(Unit)
        assertTrue(transfer.await() is OperationResult.Success)
    }

    @Test
    fun `hung copy backend reaches hard deadline without waiting forever`() = runTest {
        val runner=StagingRunner(copyGate=CompletableDeferred())
        val transfer=async{
            fileSystem(
                runner,timeoutMillis=10,dispatcher=StandardTestDispatcher(testScheduler),
                transferTimeoutGraceMillis=5,
            ).transferFromAppCache(source(),path("/target"),name("final.txt"),4)
        }
        runner.copyStarted.await()

        testScheduler.advanceTimeBy(16)
        testScheduler.runCurrent()

        val result=transfer.await()
        assertEquals(ErrorCode.OUTCOME_UNCERTAIN,(result as OperationResult.Failure).code)
        assertTrue(runner.copyCancelled)
    }

    @Test
    fun `root loss in prepare or copy maps root denied without replay`() = runTest {
        val prepareRunner=StagingRunner(prepareExit=43)
        val prepareResult=fileSystem(prepareRunner).transferFromAppCache(source(),path("/target"),name("final.txt"),4)
        assertEquals(ErrorCode.ROOT_DENIED,(prepareResult as OperationResult.Failure).code)
        assertEquals(0,prepareRunner.commands.count{it.contains("'copy-publish'")})

        val copyRunner=StagingRunner(copyExit=43)
        val copyResult=fileSystem(copyRunner).transferFromAppCache(source(),path("/target"),name("final.txt"),4)
        assertEquals(ErrorCode.ROOT_DENIED,(copyResult as OperationResult.Failure).code)
        assertEquals(1,copyRunner.commands.count{it.contains("'copy-publish'")})
    }

    @Test
    fun `hung prepare and cleanup helpers stop at their bounded deadline`() = runTest {
        val prepareBase=StagingRunner()
        val prepareRunner=RootTransferCommandRunner{command->
            if(command.contains("'prepare-stage'"))kotlinx.coroutines.awaitCancellation()
            prepareBase.run(command)
        }
        val preparing=async{
            fileSystem(
                prepareBase,dispatcher=StandardTestDispatcher(testScheduler),
                transferRunner=prepareRunner,helperOperationTimeoutMillis=5,
            ).transferFromAppCache(source(),path("/target"),name("final.txt"),4)
        }
        testScheduler.advanceTimeBy(6);testScheduler.runCurrent()
        assertEquals(ErrorCode.OUTCOME_UNCERTAIN,(preparing.await() as OperationResult.Failure).code)

        val cleanupBase=StagingRunner()
        val cleanupRunner=RootTransferCommandRunner{command->when{
            command.contains("'copy-publish'")->error("lost")
            command.contains("'remove-stage'")->kotlinx.coroutines.awaitCancellation()
            else->cleanupBase.run(command)
        }}
        val cleaning=async{
            fileSystem(
                cleanupBase,dispatcher=StandardTestDispatcher(testScheduler),
                transferRunner=cleanupRunner,helperOperationTimeoutMillis=5,
            ).transferFromAppCache(source(),path("/target"),name("final.txt"),4)
        }
        testScheduler.advanceTimeBy(6);testScheduler.runCurrent()
        assertEquals(ErrorCode.OUTCOME_UNCERTAIN,(cleaning.await() as OperationResult.Failure).code)
    }

    @Test
    fun `original symlink is rejected before a stage is prepared`() = runTest {
        val runner = StagingRunner(originalSymlink = true)

        val result = fileSystem(runner).transferFromAppCache(source(), path("/target"), name("final.txt"), 4)

        assertEquals(ErrorCode.COMMAND_FAILED, (result as OperationResult.Failure).code)
        assertTrue(runner.helperCommands().isEmpty())
    }

    private fun fileSystem(
        runner: StagingRunner,
        timeoutMillis: Long = 5_000,
        dispatcher:CoroutineDispatcher=Dispatchers.Unconfined,
        transferRunner:RootTransferCommandRunner?=null,
        transferTimeoutGraceMillis:Long=1_000,
        helperOperationTimeoutMillis:Long=3_000,
    ) =
        LibsuRootFileSystem(
            commandRunner = runner,
            ioDispatcher = dispatcher,
            timeoutMillis = timeoutMillis,
            stageNameFactory = { ".isaver-stage-123e4567-e89b-12d3-a456-426614174000" },
            transferCommandRunner=transferRunner?:RootTransferCommandRunner{runner.run(it)},
            transferTimeoutGraceMillis=transferTimeoutGraceMillis,
            helperOperationTimeoutMillis=helperOperationTimeoutMillis,
        )

    private fun source(): AppCachePath {
        val cache = java.nio.file.Files.createTempDirectory("isaver-transfer-source").toFile()
        val file = File(cache, "incoming/123e4567-e89b-12d3-a456-426614174000.tmp")
        requireNotNull(file.parentFile).mkdirs()
        file.writeText("test")
        return AppCachePath.fromIncomingCacheFile(cache, file) { 55L to 66L }.getOrThrow()
    }

    private inner class StagingRunner(
        private val copyExit: Int = 0,
        private val copyFailure: CopyFailure? = null,
        private val originalSymlink: Boolean = false,
        private val finalOnFailure:Boolean = false,
        private val copyGate:CompletableDeferred<Unit>?=null,
        private val prepareExit:Int=0,
    ) : RootCommandRunner {
        val commands = mutableListOf<String>()
        var finalExists = false
        val copyStarted=CompletableDeferred<Unit>()
        var copyCancelled=false

        override suspend fun run(command: String): RootCommandResult {
            commands += command
            return when {
                command.contains("target='/target/final.txt'") || command.contains("target='/target/报告 final.txt'") ->
                    if (finalExists) RootCommandResult(0, listOf(record("final.txt", "/target/final.txt", "file", "4")), emptyList())
                    else RootCommandResult(44, emptyList(), emptyList())
                command.contains("target='/target'") && command.contains("readlink -f") -> RootCommandResult(0, listOf(b64("/target\n")), emptyList())
                command.contains("target='/target'") && command.contains("emit_isaver_record") -> RootCommandResult(
                    0,
                    listOf(record("target", "/target", "directory", "-", symlink = if (originalSymlink) "1" else "0")),
                    emptyList(),
                )
                command.contains("stat -c '%d:%i'") && (command.contains("/target/final.txt") || command.contains("/target/报告 final.txt")) ->
                    RootCommandResult(0, listOf("77:88"), emptyList())
                command.contains("stat -c '%d:%i'") -> RootCommandResult(0, listOf("11:22"), emptyList())
                command.contains("'prepare-stage'") -> RootCommandResult(prepareExit, if(prepareExit==0)listOf("33:44")else emptyList(), emptyList())
                command.contains("'copy-publish'") -> {
                    copyStarted.complete(Unit)
                    try{copyGate?.await()}catch(cancelled:CancellationException){copyCancelled=true;throw cancelled}
                    when (copyFailure) {
                        CopyFailure.EXCEPTION -> { finalExists=finalOnFailure;error("result lost") }
                        CopyFailure.TIMEOUT -> { finalExists=finalOnFailure;throw java.net.SocketTimeoutException("timed out") }
                        CopyFailure.CANCEL -> { finalExists=finalOnFailure;throw CancellationException("cancelled") }
                        null -> {
                            if (copyExit == 0 || (copyExit == 55 && finalOnFailure)) finalExists = true
                            RootCommandResult(copyExit, if (copyExit == 0) listOf("77:88:4") else emptyList(), emptyList())
                        }
                    }
                }
                command.contains("'remove-stage'") -> RootCommandResult(0, emptyList(), emptyList())
                else -> error("Unexpected command: $command")
            }
        }

        fun helperCommands(): List<String> = commands.mapNotNull { command ->
            listOf("prepare-stage", "copy-publish", "remove-stage").singleOrNull { command.contains("'$it'") }
        }
    }

    private enum class CopyFailure { EXCEPTION, TIMEOUT, CANCEL }

    private fun path(value: String) = RootPath.parse(value).getOrThrow()
    private fun name(value: String) = FolderName.parse(value).getOrThrow()
    private fun record(name: String, path: String, type: String, size: String, symlink: String = "0") =
        listOf(b64(name), b64(path), type, size, "2", "1", "1", symlink).joinToString("\t")
    private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
}
