package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import java.util.Base64
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibsuRootFileSystemTest {
    @Test
    fun `list parses structured command output`() = runTest {
        val runner = FakeRunner(RootCommandResult(0, listOf(record("a", "/tmp/a")), emptyList()))
        val fileSystem = LibsuRootFileSystem(runner, StandardTestDispatcher(testScheduler), 5_000)

        val result = fileSystem.list(path("/tmp"))

        assertEquals("a", ((result as OperationResult.Success).value.single()).name)
        assertTrue(runner.command!!.contains("dir='/tmp'"))
        assertFalse(runner.command!!.contains("su -c"))
    }

    @Test
    fun `stat rejects multiple structured records`() = runTest {
        val runner = FakeRunner(RootCommandResult(0, listOf(record("a", "/a"), record("b", "/b")), emptyList()))
        val fileSystem = LibsuRootFileSystem(runner, StandardTestDispatcher(testScheduler), 5_000)

        val result = fileSystem.stat(path("/a"))

        assertEquals(ErrorCode.COMMAND_FAILED, (result as OperationResult.Failure).code)
    }

    @Test
    fun `maps fixed command exit codes to structured failures`() = runTest {
        val cases = mapOf(44 to ErrorCode.NOT_FOUND, 45 to ErrorCode.NOT_DIRECTORY, 46 to ErrorCode.NOT_READABLE)
        cases.forEach { (exitCode, expected) ->
            val fileSystem = LibsuRootFileSystem(
                FakeRunner(RootCommandResult(exitCode, emptyList(), listOf("sensitive path omitted"))),
                StandardTestDispatcher(testScheduler),
                5_000,
            )
            val result = fileSystem.list(path("/private"))
            assertEquals(expected, (result as OperationResult.Failure).code)
            assertFalse(result.technicalMessage.orEmpty().contains("/private"))
        }
    }

    @Test
    fun `quotes hostile path as one shell value`() = runTest {
        val hostile = "/tmp/a' ; \$(id) `whoami`\n-thing"
        val runner = FakeRunner(RootCommandResult(44, emptyList(), emptyList()))
        val fileSystem = LibsuRootFileSystem(runner, StandardTestDispatcher(testScheduler), 5_000)

        fileSystem.stat(path(hostile))

        assertTrue(runner.command!!.contains("target='/tmp/a'\\'' ; \$(id) `whoami`\n-thing'"))
    }

    @Test
    fun `stat strips trailing slashes for basename but keeps root name`() = runTest {
        val runner = FakeRunner(RootCommandResult(44, emptyList(), emptyList()))
        val fileSystem = LibsuRootFileSystem(runner, StandardTestDispatcher(testScheduler), 5_000)

        fileSystem.stat(path("/tmp/"))

        assertTrue(runner.command!!.contains("while [ \"\$trimmed\" != / ]"))
        assertTrue(runner.command!!.contains("name=\${trimmed##*/}"))
        assertTrue(runner.command!!.contains("name=/"))
    }

    @Test
    fun `unexpected execution exception maps to command failed`() = runTest {
        val runner = RootCommandRunner { error("boom") }
        val fileSystem = LibsuRootFileSystem(runner, StandardTestDispatcher(testScheduler), 5_000)

        val result = fileSystem.stat(path("/tmp"))

        assertEquals(ErrorCode.COMMAND_FAILED, (result as OperationResult.Failure).code)
    }

    @Test
    fun `record emitter encodes basename without command generated newline`() = runTest {
        val runner = FakeRunner(RootCommandResult(44, emptyList(), emptyList()))
        val fileSystem = LibsuRootFileSystem(runner, StandardTestDispatcher(testScheduler), 5_000)

        fileSystem.stat(path("/tmp/name"))

        assertTrue(runner.command!!.contains("name=\${trimmed##*/}"))
        assertFalse(runner.command!!.contains("basename --"))
    }

    private class FakeRunner(private val result: RootCommandResult) : RootCommandRunner {
        var command: String? = null
        override suspend fun run(command: String): RootCommandResult {
            this.command = command
            return result
        }
    }

    private fun path(value: String) = RootPath.parse(value).getOrThrow()

    private fun record(name: String, path: String): String = listOf(
        b64(name), b64(path), "file", "1", "2", "1", "0", "0",
    ).joinToString("\t")

    private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
}
