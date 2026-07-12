package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.FolderName
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

    @Test fun `canonicalize uses fixed readlink command and parses one absolute line`() = runTest {
        val runner = FakeRunner(RootCommandResult(0, listOf(b64("/real/path\n")), emptyList()))
        val fs = LibsuRootFileSystem(runner, StandardTestDispatcher(testScheduler), 5_000)
        val result = fs.canonicalize(path("/tmp/a b"))
        assertEquals("/real/path", (result as OperationResult.Success).value.value)
        assertTrue(runner.command!!.contains("set -o pipefail"))
        assertTrue(runner.command!!.contains("readlink -f -- \"\$target\" | base64 -w 0"))
        assertTrue(runner.command!!.contains("target='/tmp/a b'"))
    }

    @Test fun `canonicalize rejects malformed output and maps command failure`() = runTest {
        listOf("***", b64("/no-delimiter")).forEach { output ->
            val malformed = LibsuRootFileSystem(FakeRunner(RootCommandResult(0, listOf(output), emptyList())), StandardTestDispatcher(testScheduler), 5_000)
            assertEquals(ErrorCode.COMMAND_FAILED, (malformed.canonicalize(path("/a")) as OperationResult.Failure).code)
        }
        val invalidUtf8 = LibsuRootFileSystem(FakeRunner(RootCommandResult(0, listOf("/wo="), emptyList())), StandardTestDispatcher(testScheduler), 5_000)
        assertEquals(ErrorCode.COMMAND_FAILED, (invalidUtf8.canonicalize(path("/a")) as OperationResult.Failure).code)
        val multiline = LibsuRootFileSystem(FakeRunner(RootCommandResult(0, listOf(b64("/one\n"), "extra"), emptyList())), StandardTestDispatcher(testScheduler), 5_000)
        assertEquals(ErrorCode.COMMAND_FAILED, (multiline.canonicalize(path("/a")) as OperationResult.Failure).code)
        val failed = LibsuRootFileSystem(FakeRunner(RootCommandResult(44, emptyList(), emptyList())), StandardTestDispatcher(testScheduler), 5_000)
        assertEquals(ErrorCode.NOT_FOUND, (failed.canonicalize(path("/missing")) as OperationResult.Failure).code)
    }

    @Test fun `canonicalize preserves newline that belongs to the path`() = runTest {
        val fs = LibsuRootFileSystem(FakeRunner(RootCommandResult(0, listOf(b64("/real/path\n\n")), emptyList())), StandardTestDispatcher(testScheduler), 5_000)
        assertEquals("/real/path\n", (fs.canonicalize(path("/a")) as OperationResult.Success).value.value)
        val internal = LibsuRootFileSystem(FakeRunner(RootCommandResult(0, listOf(b64("/real\npath\n")), emptyList())), StandardTestDispatcher(testScheduler), 5_000)
        assertEquals("/real\npath", (internal.canonicalize(path("/a")) as OperationResult.Success).value.value)
    }
    @Test fun `create directory uses validated flow and fixed quoted mkdir`()=runTest{
        val runner=QueueRunner(ArrayDeque(listOf(
            RootCommandResult(0,listOf(b64("/parent\n")),emptyList()),
            RootCommandResult(0,listOf(record("parent","/parent","directory","-","2","1","1","0")),emptyList()),
            RootCommandResult(44,emptyList(),emptyList()),
            RootCommandResult(0,emptyList(),emptyList()),
            RootCommandResult(0,listOf(record("x';\n","/parent/x';\n","directory","-","2","1","1","0")),emptyList()),
        )))
        val fs=LibsuRootFileSystem(runner,StandardTestDispatcher(testScheduler),5_000)
        val result=fs.createDirectory(path("/parent"),FolderName.parse("x';\n").getOrThrow())
        assertTrue(result is OperationResult.Success)
        assertTrue(runner.commands.any{it.contains("child='/parent/x'\\'';\n'")&&it.contains("mkdir -- \"\$child\"")})
    }
    @Test fun `create directory rejects invalid parent states before mkdir`()=runTest{
        val cases=listOf(
            record("p","/p","file","1","2","1","1","0") to ErrorCode.NOT_DIRECTORY,
            record("p","/p","directory","-","2","1","0","0") to ErrorCode.NOT_WRITABLE,
            record("p","/p","directory","-","2","1","1","1") to ErrorCode.COMMAND_FAILED,
        )
        cases.forEach{(parentRecord,code)->val runner=QueueRunner(ArrayDeque(listOf(RootCommandResult(0,listOf(b64("/p\n")),emptyList()),RootCommandResult(0,listOf(parentRecord),emptyList()))));val result=LibsuRootFileSystem(runner,StandardTestDispatcher(testScheduler),5_000).createDirectory(path("/p"),FolderName.parse("x").getOrThrow());assertEquals(code,(result as OperationResult.Failure).code);assertFalse(runner.commands.any{it.contains("mkdir --")})}
    }
    @Test fun `create directory maps existing and mkdir race to already exists`()=runTest{
        val parent=RootCommandResult(0,listOf(record("p","/p","directory","-","2","1","1","0")),emptyList());val child=RootCommandResult(0,listOf(record("x","/p/x","directory","-","2","1","1","0")),emptyList())
        val existing=QueueRunner(ArrayDeque(listOf(RootCommandResult(0,listOf(b64("/p\n")),emptyList()),parent,child)))
        assertEquals(ErrorCode.ALREADY_EXISTS,(LibsuRootFileSystem(existing,StandardTestDispatcher(testScheduler),5_000).createDirectory(path("/p"),FolderName.parse("x").getOrThrow()) as OperationResult.Failure).code)
        val race=QueueRunner(ArrayDeque(listOf(RootCommandResult(0,listOf(b64("/p\n")),emptyList()),parent,RootCommandResult(44,emptyList(),emptyList()),RootCommandResult(47,emptyList(),emptyList()),child)))
        assertEquals(ErrorCode.ALREADY_EXISTS,(LibsuRootFileSystem(race,StandardTestDispatcher(testScheduler),5_000).createDirectory(path("/p"),FolderName.parse("x").getOrThrow()) as OperationResult.Failure).code)
    }
    @Test fun `create directory propagates cancellation and maps ordinary execution exception`()=runTest{
        val cancelled=RootCommandRunner{throw kotlinx.coroutines.CancellationException()};try{LibsuRootFileSystem(cancelled,StandardTestDispatcher(testScheduler),5_000).createDirectory(path("/p"),FolderName.parse("x").getOrThrow());throw AssertionError("expected cancellation")}catch(_:kotlinx.coroutines.CancellationException){}
        val failed=RootCommandRunner{error("boom")};assertEquals(ErrorCode.COMMAND_FAILED,(LibsuRootFileSystem(failed,StandardTestDispatcher(testScheduler),5_000).createDirectory(path("/p"),FolderName.parse("x").getOrThrow()) as OperationResult.Failure).code)
    }

    private class FakeRunner(private val result: RootCommandResult) : RootCommandRunner {
        var command: String? = null
        override suspend fun run(command: String): RootCommandResult {
            this.command = command
            return result
        }
    }
    private class QueueRunner(private val results:ArrayDeque<RootCommandResult>):RootCommandRunner{val commands=mutableListOf<String>();override suspend fun run(command:String):RootCommandResult{commands+=command;return results.removeFirst()}}

    private fun path(value: String) = RootPath.parse(value).getOrThrow()

    private fun record(name: String, path: String,type:String="file",size:String="1",mtime:String="2",readable:String="1",writable:String="0",symlink:String="0"): String = listOf(
        b64(name), b64(path), type,size,mtime,readable,writable,symlink,
    ).joinToString("\t")

    private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
}
