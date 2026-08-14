package com.isaver.filemanager.data.root

import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.domain.FolderName
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibsuRootFileSystemTest {
    @Test fun `range source change maps to uncertain outcome`() = runTest {
        val runner = FakeRunner(RootCommandResult(54, emptyList(), emptyList()))
        val fileSystem = LibsuRootFileSystem(runner, StandardTestDispatcher(testScheduler), 5_000)

        val result = fileSystem.readRange(path("/tmp/value"), 0, 1)

        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (result as OperationResult.Failure).code)
        assertEquals("文件读取结果需要核对", result.userMessage)
    }
    @Test fun `file identity parser accepts one nonnegative device inode pair only`(){assertEquals(RootFileIdentity(12,34),RootFileIdentity.parse(listOf("12:34")).getOrThrow());listOf(emptyList(),listOf("1:2","3:4"),listOf("-1:2"),listOf("a:2"),listOf("1:2:3")).forEach{assertTrue(RootFileIdentity.parse(it).isFailure)}}
    @Test fun `stat maps a missing marker without exiting the cached root shell`() = runTest {
        val runner = FakeRunner(RootCommandResult(0, listOf("ISAVER_STAT_V1_NOT_FOUND"), emptyList()))
        val fileSystem = LibsuRootFileSystem(runner, StandardTestDispatcher(testScheduler), 5_000)

        val result = fileSystem.stat(path("/missing"))

        assertEquals(ErrorCode.NOT_FOUND, (result as OperationResult.Failure).code)
        assertTrue(runner.command!!.contains("ISAVER_STAT_V1_NOT_FOUND"))
        assertFalse(runner.command!!.contains("exit 44"))
    }
    @Test
    fun `read directory returns parent capabilities and entries from one native invocation`() = runTest {
        val runner = FakeRunner(
            RootCommandResult(
                0,
                listOf(
                    "ISAVER_LIST_V1\t259\t1024\t1\t0",
                    record("a", "/tmp/a"),
                ),
                emptyList(),
            ),
        )
        val fileSystem = LibsuRootFileSystem(runner, StandardTestDispatcher(testScheduler), 5_000)

        val result = fileSystem.readDirectory(path("/tmp"))

        val snapshot = (result as OperationResult.Success).value
        assertEquals(259L, snapshot.parentDevice)
        assertEquals(1024L, snapshot.parentInode)
        assertTrue(snapshot.parentReadable)
        assertFalse(snapshot.parentWritable)
        assertEquals(listOf("a"), snapshot.entries.map { it.name })
        assertEquals(1, runner.commands.size)
        assertEquals(
            "'/data/local/tmp/isaver_fs_helper' 'list-dir' '/tmp'",
            runner.commands.single(),
        )
        assertFalse(runner.commands.single().contains("emit_isaver_record"))
        assertFalse(runner.commands.single().contains("stat -c"))
        assertFalse(runner.commands.single().contains("su -c"))
    }

    @Test
    fun `list compatibility default unwraps the production native snapshot`() = runTest {
        val runner = FakeRunner(
            RootCommandResult(
                0,
                listOf(
                    "ISAVER_LIST_V1\t1\t2\t1\t1",
                    record("a", "/tmp/a"),
                ),
                emptyList(),
            ),
        )
        val fileSystem = LibsuRootFileSystem(runner, StandardTestDispatcher(testScheduler), 5_000)

        val result = fileSystem.list(path("/tmp"))

        assertEquals("a", ((result as OperationResult.Success).value.single()).name)
        assertEquals(1, runner.commands.size)
        assertTrue(runner.commands.single().contains("'list-dir'"))
        assertFalse(runner.commands.single().contains("emit_isaver_record"))
    }

    @Test
    fun `read directory binds decoded children to the requested parent`() = runTest {
        val runner = FakeRunner(
            RootCommandResult(
                0,
                listOf(
                    "ISAVER_LIST_V1\t1\t2\t1\t1",
                    record("child", "/other/child"),
                ),
                emptyList(),
            ),
        )
        val fileSystem = LibsuRootFileSystem(runner, StandardTestDispatcher(testScheduler), 5_000)

        val result = fileSystem.readDirectory(path("/requested"))

        val snapshot = (result as OperationResult.Success).value
        assertTrue(snapshot.entries.isEmpty())
        assertEquals(
            NativeDirectoryListingRecordFailureReason.PATH_MISMATCH,
            snapshot.recordFailures.single().reason,
        )
    }

    @Test
    fun `read directory maps native exits without transfer uncertainty semantics`() = runTest {
        val cases = mapOf(
            43 to ErrorCode.ROOT_DENIED,
            44 to ErrorCode.NOT_FOUND,
            45 to ErrorCode.NOT_DIRECTORY,
            46 to ErrorCode.NOT_READABLE,
            51 to ErrorCode.COMMAND_FAILED,
            57 to ErrorCode.COMMAND_FAILED,
            64 to ErrorCode.COMMAND_FAILED,
            137 to ErrorCode.COMMAND_FAILED,
        )

        cases.forEach { (exitCode, expected) ->
            val fileSystem = LibsuRootFileSystem(
                FakeRunner(RootCommandResult(exitCode, emptyList(), listOf("sensitive path omitted"))),
                StandardTestDispatcher(testScheduler),
                5_000,
            )

            val result = fileSystem.readDirectory(path("/private"))

            assertEquals(expected, (result as OperationResult.Failure).code)
            assertFalse(result.technicalMessage.orEmpty().contains("/private"))
        }
    }

    @Test
    fun `read directory maps timeout and execution failure but preserves cancellation`() = runTest {
        val timedOut = LibsuRootFileSystem(
            RootCommandRunner {
                delay(100)
                RootCommandResult(0, listOf("ISAVER_LIST_V1\t1\t2\t1\t1"), emptyList())
            },
            StandardTestDispatcher(testScheduler),
            10,
        )
        val failed = LibsuRootFileSystem(
            RootCommandRunner { error("boom") },
            StandardTestDispatcher(testScheduler),
            5_000,
        )
        val cancelled = LibsuRootFileSystem(
            RootCommandRunner { throw CancellationException("stop") },
            StandardTestDispatcher(testScheduler),
            5_000,
        )

        assertEquals(
            ErrorCode.COMMAND_FAILED,
            (timedOut.readDirectory(path("/tmp")) as OperationResult.Failure).code,
        )
        assertEquals(
            ErrorCode.COMMAND_FAILED,
            (failed.readDirectory(path("/tmp")) as OperationResult.Failure).code,
        )
        try {
            cancelled.readDirectory(path("/tmp"))
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `read directory preserves a caller timeout shorter than its filesystem timeout`() = runTest {
        var returnedFailure = false
        val fileSystem = LibsuRootFileSystem(
            RootCommandRunner {
                delay(100)
                RootCommandResult(0, listOf("ISAVER_LIST_V1\t1\t2\t1\t1"), emptyList())
            },
            StandardTestDispatcher(testScheduler),
            5_000,
        )

        try {
            withTimeout(10) {
                returnedFailure = fileSystem.readDirectory(path("/tmp")) is OperationResult.Failure
            }
            throw AssertionError("expected caller timeout cancellation")
        } catch (_: CancellationException) {
        }
        assertFalse("caller timeout was converted to a filesystem failure", returnedFailure)
    }

    @Test
    fun `read directory rejects fatal malformed native output without exposing it`() = runTest {
        val runner = FakeRunner(
            RootCommandResult(
                0,
                listOf("ISAVER_LIST_V2\t/private/raw\toutput\t1\t1"),
                emptyList(),
            ),
        )
        val fileSystem = LibsuRootFileSystem(runner, StandardTestDispatcher(testScheduler), 5_000)

        val result = fileSystem.readDirectory(path("/private"))

        assertEquals(ErrorCode.COMMAND_FAILED, (result as OperationResult.Failure).code)
        assertFalse(result.technicalMessage.orEmpty().contains("/private/raw"))
    }

    @Test
    fun `legacy list fake does not fabricate parent identity for snapshots`() = runTest {
        val fileSystem = LegacyListOnlyFileSystem()

        assertTrue(fileSystem.list(path("/tmp")) is OperationResult.Success)
        val snapshot = fileSystem.readDirectory(path("/tmp"))

        assertEquals(ErrorCode.COMMAND_FAILED, (snapshot as OperationResult.Failure).code)
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
            RootCommandResult(0,listOf(record("parent","/parent","directory","-","2","1","1","0")),emptyList()),
            RootCommandResult(0,listOf(b64("/parent\n")),emptyList()),
            RootCommandResult(0,listOf(record("parent","/parent","directory","-","2","1","1","0")),emptyList()),
            RootCommandResult(0,listOf("1:2"),emptyList()),
            RootCommandResult(44,emptyList(),emptyList()),
            RootCommandResult(0,emptyList(),emptyList()),
            RootCommandResult(0,listOf("1:2"),emptyList()),
            RootCommandResult(0,listOf(b64("/parent\n")),emptyList()),
            RootCommandResult(0,listOf(record("x';\n","/parent/x';\n","directory","-","2","1","1","0")),emptyList()),
        )))
        val fs=LibsuRootFileSystem(runner,StandardTestDispatcher(testScheduler),5_000)
        val result=fs.createDirectory(path("/parent"),FolderName.parse("x';\n").getOrThrow())
        assertTrue(result is OperationResult.Success)
        assertTrue(runner.commands.any{val a=it.lastIndexOf("[ ! -L \"\$original\" ]");val identity=it.indexOf("[ \"\$current\" = '1:2' ]",a);val mapping=it.indexOf("[ \"\$mapped\" = '${b64("/parent\n")}' ]",identity);val b=it.indexOf("[ -d \"\$original\" ]",mapping);val c=it.indexOf("[ -w \"\$original\" ]",b);val d=it.indexOf("mkdir -- \"\$child\"",c);it.contains("original='/parent'")&&it.contains("child='/parent/x'\\'';\n'")&&a>=0&&a<identity&&identity<mapping&&mapping<b&&b<c&&c<d})
    }
    @Test fun `create directory rejects invalid parent states before mkdir`()=runTest{
        val cases=listOf(
            record("p","/p","file","1","2","1","1","0") to ErrorCode.NOT_DIRECTORY,
            record("p","/p","directory","-","2","1","0","0") to ErrorCode.NOT_WRITABLE,
            record("p","/p","directory","-","2","1","1","1") to ErrorCode.COMMAND_FAILED,
        )
        cases.forEach{(parentRecord,code)->val original=RootCommandResult(0,listOf(parentRecord),emptyList());val results=if(parentRecord.endsWith("\t1"))listOf(original)else listOf(original,RootCommandResult(0,listOf(b64("/p\n")),emptyList()),original);val runner=QueueRunner(ArrayDeque(results));val result=LibsuRootFileSystem(runner,StandardTestDispatcher(testScheduler),5_000).createDirectory(path("/p"),FolderName.parse("x").getOrThrow());assertEquals(code,(result as OperationResult.Failure).code);assertFalse(runner.commands.any{it.contains("mkdir --")})}
    }
    @Test fun `create directory rejects an original parent symlink before canonicalization`()=runTest{
        val runner=OriginalSymlinkRunner()
        val result=LibsuRootFileSystem(runner,StandardTestDispatcher(testScheduler),5_000)
            .createDirectory(path("/link"),FolderName.parse("x").getOrThrow())

        assertEquals(ErrorCode.COMMAND_FAILED,(result as OperationResult.Failure).code)
        assertEquals("Original parent was symlink",result.technicalMessage)
        assertFalse(runner.commands.any{it.contains("mkdir --")})
    }
    @Test fun `create directory rechecks original path mapping immediately before mkdir`()=runTest{
        val runner=OriginalPathRaceRunner()
        val result=LibsuRootFileSystem(runner,StandardTestDispatcher(testScheduler),5_000)
            .createDirectory(path("/p"),FolderName.parse("x").getOrThrow())

        assertEquals(ErrorCode.COMMAND_FAILED,(result as OperationResult.Failure).code)
        assertFalse(runner.mkdirExecuted)
        val command=runner.commands.single{it.contains("mkdir --")}
        val noLink=command.lastIndexOf("[ ! -L \"\$original\" ]")
        val identity=command.indexOf("[ \"\$current\" = '1:2' ]",noLink)
        val mapping=command.indexOf("[ \"\$mapped\" = '${b64("/p\n")}' ]",identity)
        val mkdir=command.indexOf("mkdir -- \"\$child\"",mapping)
        assertTrue(noLink>=0&&noLink<identity&&identity<mapping&&mapping<mkdir)
    }
    @Test fun `create directory reports uncertain when mkdir side effect precedes exception or timeout`()=runTest{
        listOf(PostMkdirFailure.EXCEPTION,PostMkdirFailure.TIMEOUT).forEach{failureMode->
            val runner=PostMkdirFailureRunner(failureMode)
            val result=LibsuRootFileSystem(runner,StandardTestDispatcher(testScheduler),10)
                .createDirectory(path("/p"),FolderName.parse("x").getOrThrow())

            assertEquals(ErrorCode.OUTCOME_UNCERTAIN,(result as OperationResult.Failure).code)
            assertEquals(1,runner.postSideEffectStatChecks)
        }
    }
    @Test fun `create directory verifies in non cancellable context then preserves cancellation`()=runTest{
        val runner=PostMkdirFailureRunner(PostMkdirFailure.CANCEL)
        try{
            LibsuRootFileSystem(runner,StandardTestDispatcher(testScheduler),5_000)
                .createDirectory(path("/p"),FolderName.parse("x").getOrThrow())
            throw AssertionError("expected cancellation")
        }catch(_:kotlinx.coroutines.CancellationException){}

        assertEquals(1,runner.postSideEffectStatChecks)
    }
    @Test fun `create directory distinguishes preexisting child from uncertain post mkdir appearance`()=runTest{
        val parent=RootCommandResult(0,listOf(record("p","/p","directory","-","2","1","1","0")),emptyList());val child=RootCommandResult(0,listOf(record("x","/p/x","directory","-","2","1","1","0")),emptyList())
        val existing=QueueRunner(ArrayDeque(listOf(parent,RootCommandResult(0,listOf(b64("/p\n")),emptyList()),parent,RootCommandResult(0,listOf("1:2"),emptyList()),child)))
        assertEquals(ErrorCode.ALREADY_EXISTS,(LibsuRootFileSystem(existing,StandardTestDispatcher(testScheduler),5_000).createDirectory(path("/p"),FolderName.parse("x").getOrThrow()) as OperationResult.Failure).code)
        val race=QueueRunner(ArrayDeque(listOf(parent,RootCommandResult(0,listOf(b64("/p\n")),emptyList()),parent,RootCommandResult(0,listOf("1:2"),emptyList()),RootCommandResult(44,emptyList(),emptyList()),RootCommandResult(47,emptyList(),emptyList()),child)))
        assertEquals(ErrorCode.OUTCOME_UNCERTAIN,(LibsuRootFileSystem(race,StandardTestDispatcher(testScheduler),5_000).createDirectory(path("/p"),FolderName.parse("x").getOrThrow()) as OperationResult.Failure).code)
    }
    @Test fun `create directory propagates cancellation and maps ordinary execution exception`()=runTest{
        val cancelled=RootCommandRunner{throw kotlinx.coroutines.CancellationException()};try{LibsuRootFileSystem(cancelled,StandardTestDispatcher(testScheduler),5_000).createDirectory(path("/p"),FolderName.parse("x").getOrThrow());throw AssertionError("expected cancellation")}catch(_:kotlinx.coroutines.CancellationException){}
        val failed=RootCommandRunner{error("boom")};assertEquals(ErrorCode.COMMAND_FAILED,(LibsuRootFileSystem(failed,StandardTestDispatcher(testScheduler),5_000).createDirectory(path("/p"),FolderName.parse("x").getOrThrow()) as OperationResult.Failure).code)
    }
    @Test fun `create directory rejects changed parent and non directory final child`()=runTest{
        val parent=RootCommandResult(0,listOf(record("p","/p","directory","-","2","1","1","0")),emptyList());val missing=RootCommandResult(44,emptyList(),emptyList());val made=RootCommandResult(0,emptyList(),emptyList())
        val changed=QueueRunner(ArrayDeque(listOf(parent,RootCommandResult(0,listOf(b64("/p\n")),emptyList()),parent,RootCommandResult(0,listOf("1:2"),emptyList()),missing,made,RootCommandResult(0,listOf("1:2"),emptyList()),RootCommandResult(0,listOf(b64("/other\n")),emptyList()))))
        assertEquals(ErrorCode.OUTCOME_UNCERTAIN,(LibsuRootFileSystem(changed,StandardTestDispatcher(testScheduler),5_000).createDirectory(path("/p"),FolderName.parse("x").getOrThrow()) as OperationResult.Failure).code)
        listOf(record("x","/p/x","file","1","2","1","1","0"),record("x","/p/x","directory","-","2","1","1","1")).forEach{bad->val runner=QueueRunner(ArrayDeque(listOf(parent,RootCommandResult(0,listOf(b64("/p\n")),emptyList()),parent,RootCommandResult(0,listOf("1:2"),emptyList()),missing,made,RootCommandResult(0,listOf("1:2"),emptyList()),RootCommandResult(0,listOf(b64("/p\n")),emptyList()),RootCommandResult(0,listOf(bad),emptyList()))));assertEquals(ErrorCode.OUTCOME_UNCERTAIN,(LibsuRootFileSystem(runner,StandardTestDispatcher(testScheduler),5_000).createDirectory(path("/p"),FolderName.parse("x").getOrThrow()) as OperationResult.Failure).code)}
    }

    private class FakeRunner(private val result: RootCommandResult) : RootCommandRunner {
        var command: String? = null
        val commands = mutableListOf<String>()
        override suspend fun run(command: String): RootCommandResult {
            this.command = command
            commands += command
            return result
        }
    }
    private class LegacyListOnlyFileSystem : RootFileSystem {
        override suspend fun list(path: RootPath): OperationResult<List<DirectoryEntry>> =
            OperationResult.Success(emptyList())
        override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> = error("unused")
        override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> = error("unused")
        override suspend fun createDirectory(
            parent: RootPath,
            name: FolderName,
        ): OperationResult<DirectoryEntry> = error("unused")
    }
    private class QueueRunner(private val results:ArrayDeque<RootCommandResult>):RootCommandRunner{val commands=mutableListOf<String>();override suspend fun run(command:String):RootCommandResult{commands+=command;return results.removeFirst()}}
    private inner class OriginalSymlinkRunner:RootCommandRunner{
        val commands=mutableListOf<String>();private var made=false
        override suspend fun run(command:String):RootCommandResult{commands+=command;return when{
            command.contains("target='/link'")&&command.contains("emit_isaver_record \"\$target\"")->RootCommandResult(0,listOf(record("link","/link","directory","-","2","1","1","1")),emptyList())
            command.contains("target='/link'")&&command.contains("readlink -f")->RootCommandResult(0,listOf(b64("/real\n")),emptyList())
            command.contains("target='/real/x'")->if(made)RootCommandResult(0,listOf(record("x","/real/x","directory","-","2","1","1","0")),emptyList())else RootCommandResult(44,emptyList(),emptyList())
            command.contains("target='/real'")&&command.contains("emit_isaver_record \"\$target\"")->RootCommandResult(0,listOf(record("real","/real","directory","-","2","1","1","0")),emptyList())
            command.contains("stat -c '%d:%i'")->RootCommandResult(0,listOf("1:2"),emptyList())
            command.contains("mkdir --")->{made=true;RootCommandResult(0,emptyList(),emptyList())}
            else->error("Unexpected command: $command")
        }}
    }
    private inner class OriginalPathRaceRunner:RootCommandRunner{
        val commands=mutableListOf<String>();var mkdirExecuted=false;private var made=false
        override suspend fun run(command:String):RootCommandResult{commands+=command;return when{
            command.contains("target='/p/x'")->if(made)RootCommandResult(0,listOf(record("x","/p/x","directory","-","2","1","1","0")),emptyList())else RootCommandResult(44,emptyList(),emptyList())
            command.contains("target='/p'")&&command.contains("emit_isaver_record \"\$target\"")->RootCommandResult(0,listOf(record("p","/p","directory","-","2","1","1","0")),emptyList())
            command.contains("target='/p'")&&command.contains("readlink -f")->RootCommandResult(0,listOf(b64("/p\n")),emptyList())
            command.contains("stat -c '%d:%i'")&&!command.contains("mkdir --")->RootCommandResult(0,listOf("1:2"),emptyList())
            command.contains("mkdir --")->{
                val protected=command.contains("original='/p'")&&
                    command.contains("readlink -f -- \"\$original\" | base64 -w 0")&&
                    command.contains("[ ! -L \"\$original\" ]")&&
                    command.contains("[ \"\$current\" = '1:2' ]")&&
                    command.contains("[ \"\$mapped\" = '${b64("/p\n")}' ]")
                if(protected)RootCommandResult(47,emptyList(),emptyList())else{mkdirExecuted=true;made=true;RootCommandResult(0,emptyList(),emptyList())}
            }
            else->error("Unexpected command: $command")
        }}
    }
    private enum class PostMkdirFailure{EXCEPTION,TIMEOUT,CANCEL}
    private inner class PostMkdirFailureRunner(private val failure:PostMkdirFailure):RootCommandRunner{
        var postSideEffectStatChecks=0;private var mkdirAttempted=false
        override suspend fun run(command:String):RootCommandResult=when{
            command.contains("target='/p/x'")->{
                if(mkdirAttempted){postSideEffectStatChecks++;RootCommandResult(0,listOf(record("x","/p/x","directory","-","2","1","1","0")),emptyList())}
                else RootCommandResult(44,emptyList(),emptyList())
            }
            command.contains("target='/p'")&&command.contains("emit_isaver_record \"\$target\"")->RootCommandResult(0,listOf(record("p","/p","directory","-","2","1","1","0")),emptyList())
            command.contains("target='/p'")&&command.contains("readlink -f")->RootCommandResult(0,listOf(b64("/p\n")),emptyList())
            command.contains("stat -c '%d:%i'")&&!command.contains("mkdir --")->RootCommandResult(0,listOf("1:2"),emptyList())
            command.contains("mkdir --")->{
                mkdirAttempted=true
                when(failure){
                    PostMkdirFailure.EXCEPTION->error("shell result lost")
                    PostMkdirFailure.TIMEOUT->{delay(100);RootCommandResult(0,emptyList(),emptyList())}
                    PostMkdirFailure.CANCEL->throw kotlinx.coroutines.CancellationException("cancel after mkdir")
                }
            }
            else->error("Unexpected command: $command")
        }
    }

    private fun path(value: String) = RootPath.parse(value).getOrThrow()

    private fun record(name: String, path: String,type:String="file",size:String="1",mtime:String="2",readable:String="1",writable:String="0",symlink:String="0"): String = listOf(
        b64(name), b64(path), type,size,mtime,readable,writable,symlink,
    ).joinToString("\t")

    private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
}
