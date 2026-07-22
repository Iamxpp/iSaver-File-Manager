package com.iamxpp.isaver.data.root

import android.util.Log
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.EntryName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

internal data class RootCommandResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
)

internal fun interface RootCommandRunner {
    suspend fun run(command: String): RootCommandResult
}

class LibsuRootFileSystem internal constructor(
    internal val shellCoordinator: RootShellCoordinator,
    private val ioDispatcher: CoroutineDispatcher,
    private val timeoutMillis: Long,
    helperExecutable:String="/data/local/tmp/isaver_fs_helper",
    private val stageNameFactory:()->String={ ".isaver-stage-${UUID.randomUUID()}" },
    private val extractionStageNameFactory:()->String={ ".isaver-extract-${UUID.randomUUID()}" },
    private val transferCommandRunner:RootTransferCommandRunner=IsolatedLibsuRootTransferCommandRunner,
    private val transferTimeoutGraceMillis:Long=2_000,
    private val helperOperationTimeoutMillis:Long=3_000,
) : RootFileSystem {
    private val transferHelper=RootTransferHelper(helperExecutable)
    constructor(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(ApplicationRootShellCoordinator, ioDispatcher, timeoutMillis)

    constructor(helperExecutable:String,timeoutMillis:Long=DEFAULT_TIMEOUT_MILLIS,ioDispatcher:CoroutineDispatcher=Dispatchers.IO):this(ApplicationRootShellCoordinator,ioDispatcher,timeoutMillis,helperExecutable)

    internal constructor(
        commandRunner: RootCommandRunner,
        ioDispatcher: CoroutineDispatcher,
        timeoutMillis: Long,
        stageNameFactory:()->String={ ".isaver-stage-${UUID.randomUUID()}" },
        extractionStageNameFactory:()->String={ ".isaver-extract-${UUID.randomUUID()}" },
        transferCommandRunner:RootTransferCommandRunner=RootTransferCommandRunner{commandRunner.run(it)},
        transferTimeoutGraceMillis:Long=2_000,
        helperOperationTimeoutMillis:Long=3_000,
    ) : this(CommandRunnerCoordinator(commandRunner), ioDispatcher, timeoutMillis, stageNameFactory=stageNameFactory,extractionStageNameFactory=extractionStageNameFactory,transferCommandRunner=transferCommandRunner,transferTimeoutGraceMillis=transferTimeoutGraceMillis,helperOperationTimeoutMillis=helperOperationTimeoutMillis)

    override suspend fun readDirectory(path: RootPath): OperationResult<DirectorySnapshot> =
        executeDirectoryListing(transferHelper.listDirectory(path.value)).flatMap { lines ->
            when (val parsed = NativeDirectoryListingParser.parse(lines, expectedParent = path)) {
                is NativeDirectoryListingParseResult.Failure -> malformedNativeDirectoryOutput(parsed.reason)
                is NativeDirectoryListingParseResult.Success -> OperationResult.Success(parsed.snapshot)
            }
        }

    override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> =
        execute(buildStatCommand(path)).flatMap { lines ->
            when (val parsed = DirectoryListingParser.parse(lines)) {
                is OperationResult.Failure -> parsed
                is OperationResult.Success -> parsed.value.singleOrNull()?.let { OperationResult.Success(it) }
                    ?: malformedOutput()
            }
        }

    override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> =
        execute(buildCanonicalizeCommand(path), "无法解析真实路径").flatMap(::parseCanonicalOutput)

    override suspend fun createDirectory(parent:RootPath,name:FolderName):OperationResult<DirectoryEntry>{
        val originalParent=stat(parent);if(originalParent !is OperationResult.Success)return originalParent as OperationResult.Failure
        if(originalParent.value.symbolicLink)return failure(ErrorCode.COMMAND_FAILED,"无法在符号链接目录中创建文件夹","Original parent was symlink")
        val canonical=canonicalize(parent);if(canonical !is OperationResult.Success)return canonical as OperationResult.Failure
        val parentStat=stat(canonical.value);if(parentStat !is OperationResult.Success)return parentStat as OperationResult.Failure
        val p=parentStat.value
        if(p.type!=com.iamxpp.isaver.domain.EntryType.DIRECTORY)return failure(ErrorCode.NOT_DIRECTORY,"路径不是目录","Parent was not directory")
        if(p.symbolicLink)return failure(ErrorCode.COMMAND_FAILED,"无法在符号链接目录中创建文件夹","Canonical parent was symlink")
        if(!p.readable)return failure(ErrorCode.NOT_READABLE,"目录不可读","Parent not readable")
        if(!p.writable)return failure(ErrorCode.NOT_WRITABLE,"目录不可写","Parent not writable")
        val preIdentity=readIdentity(canonical.value);if(preIdentity !is OperationResult.Success)return preIdentity as OperationResult.Failure
        val child=FolderName.join(canonical.value,name)
        when(val e=stat(child)){is OperationResult.Success->return failure(ErrorCode.ALREADY_EXISTS,"文件夹已存在","Child exists");is OperationResult.Failure->if(e.code!=ErrorCode.NOT_FOUND)return e}
        val made=try{execute(buildMkdirCommand(parent,canonical.value,child,preIdentity.value),"无法创建文件夹")}catch(cancelled:CancellationException){
            try{withContext(NonCancellable){stat(child)}}catch(_:Exception){}
            throw cancelled
        }
        if(made is OperationResult.Failure){if(stat(child) is OperationResult.Success)return uncertain("Mkdir outcome unknown after child appeared");return made}
        val postIdentity=readIdentity(canonical.value)
        if(postIdentity !is OperationResult.Success||postIdentity.value!=preIdentity.value)return uncertain("Parent identity changed")
        val postParent=canonicalize(parent)
        if(postParent !is OperationResult.Success||postParent.value!=canonical.value)return uncertain("Canonical parent changed")
        val finalChild=stat(child)
        if(finalChild !is OperationResult.Success)return uncertain("Created child could not be verified")
        if(finalChild.value.type!=com.iamxpp.isaver.domain.EntryType.DIRECTORY||finalChild.value.symbolicLink)return uncertain("Created child was not a plain directory")
        return finalChild
    }

    override suspend fun transferFromStream(
        source: RootTransferSource,
        targetDirectory: RootPath,
        finalName: EntryName,
    ): OperationResult<DirectoryEntry> = transfer(
        targetDirectory = targetDirectory,
        finalName = finalName,
        expectedSizeBytes = source.expectedSizeBytes,
    ) { directory, stage ->
        transferHelper.copyPublish(
            directory.original.value,directory.canonical.value,stage,finalName.value,
            directory.identity,source,transferDeadlineMillis(source.expectedSizeBytes),
        )
    }

    override suspend fun copyToOutput(
        source: RootPath,
        output: OutputStream,
    ): OperationResult<Long> {
        val sourceEntry = stat(source)
        if (sourceEntry !is OperationResult.Success) return sourceEntry as OperationResult.Failure
        if (sourceEntry.value.type != com.iamxpp.isaver.domain.EntryType.FILE ||
            sourceEntry.value.symbolicLink ||
            !sourceEntry.value.readable
        ) {
            return failure(ErrorCode.SOURCE_UNREADABLE, "无法读取来源文件", "Root archive source was not a readable regular file")
        }
        val expectedSize = sourceEntry.value.sizeBytes
            ?: return failure(ErrorCode.SOURCE_UNREADABLE, "无法读取来源文件", "Root archive source size was unavailable")
        if (expectedSize > MAX_ROOT_CACHE_BYTES) {
            return failure(ErrorCode.COMMAND_FAILED, "文件过大，无法缓存", "Root archive source exceeded private cache limit")
        }
        val result = runHelperBounded(
            transferHelper.readFile(source.value),
            maxOf(helperOperationTimeoutMillis, timeoutMillis),
        ).getOrElse {
            return failure(ErrorCode.COMMAND_FAILED, "无法读取来源文件", "Root archive read exceeded deadline")
        }
        if (result.exitCode != 0) {
            return mapExitCode(result.exitCode, result.stderr, "无法读取来源文件", "read-file")
        }
        return RootFileReadProtocol.decode(result.stdout, output, expectedSize)
    }

    override suspend fun prepareExtractionStage(parent: RootPath): OperationResult<ExtractionStage> {
        val prepared = prepareWritableDirectory(parent)
        if (prepared !is OperationResult.Success) return prepared as OperationResult.Failure
        val directory = prepared.value
        val stageName = extractionStageNameFactory()
        if (!EXTRACTION_STAGE_NAME.matches(stageName)) {
            return failure(ErrorCode.COMMAND_FAILED, "无法准备解压目录", "Invalid generated extraction stage name")
        }
        val result = runHelperBounded(
            transferHelper.prepareExtraction(
                directory.original.value,
                directory.canonical.value,
                stageName,
                directory.identity,
            ),
            helperOperationTimeoutMillis,
        ).getOrElse {
            return uncertainExtraction("Prepare extraction stage result was lost")
        }
        if (result.exitCode != 0) {
            return mapExitCode(result.exitCode, result.stderr, "无法准备解压目录", "prepare-extract-stage")
        }
        val identity = RootFileIdentity.parse(result.stdout).getOrElse {
            return uncertainExtraction("Malformed extraction stage identity")
        }
        return ExtractionStage.create(
            directory.original,
            directory.canonical,
            directory.identity,
            stageName,
            identity,
        ).fold(
            onSuccess = { OperationResult.Success(it) },
            onFailure = { uncertainExtraction("Rejected extraction stage identity") },
        )
    }

    override suspend fun createExtractionDirectory(
        stage: ExtractionStage,
        relativePath: String,
    ): OperationResult<Unit> {
        val safePath = ExtractionRelativePath.directory(relativePath).getOrElse {
            return failure(ErrorCode.COMMAND_FAILED, "解压目录名称无效", "Unsafe extraction directory path")
        }
        val result = runHelperBounded(
            transferHelper.createExtractionDirectory(stage, safePath.value),
            helperOperationTimeoutMillis,
        ).getOrElse {
            return uncertainExtraction("Create extraction directory result was lost")
        }
        if (result.exitCode != 0) {
            return mapExitCode(result.exitCode, result.stderr, "无法创建解压目录", "mkdir-extract")
        }
        return OperationResult.Success(Unit)
    }

    override suspend fun transferIntoExtractionStage(
        stage: ExtractionStage,
        relativeParent: String,
        source: RootTransferSource,
        finalName: EntryName,
    ): OperationResult<Unit> {
        val safeParent = ExtractionRelativePath.parent(relativeParent).getOrElse {
            return failure(ErrorCode.COMMAND_FAILED, "解压目录名称无效", "Unsafe extraction parent path")
        }
        if (source.expectedSizeBytes < 0L) {
            return failure(ErrorCode.SOURCE_UNREADABLE, "无法读取来源文件", "Negative extraction source size")
        }
        val transferDeadline = transferDeadlineMillis(source.expectedSizeBytes)
        val command = transferHelper.copyIntoExtraction(
            stage,
            safeParent.value,
            source,
            finalName,
            transferDeadline,
        )
        val callerJob = currentCoroutineContext()[Job]
        val dispatch = awaitTransferExecution(command, callerJob, transferDeadline)
        if (!dispatch.dispatched) throw CancellationException("Extraction transfer cancelled before dispatch")
        return withContext(NonCancellable) {
            val execution = dispatch.result.getOrElse {
                return@withContext uncertainExtraction("Extraction stage transfer result was lost")
            }
            if (execution.exitCode != 0) {
                return@withContext mapExitCode(
                    execution.exitCode,
                    execution.stderr,
                    "无法写入解压文件",
                    "copy-extract",
                )
            }
            val identity = parsePublishedIdentity(execution.stdout)
            if (identity !is OperationResult.Success || identity.value.sizeBytes != source.expectedSizeBytes) {
                return@withContext uncertainExtraction("Malformed extraction file identity")
            }
            OperationResult.Success(Unit)
        }
    }

    override suspend fun commitExtractionStage(
        stage: ExtractionStage,
        finalName: FolderName,
    ): OperationResult<DirectoryEntry> {
        val command = transferHelper.commitExtraction(stage, finalName)
        val callerJob = currentCoroutineContext()[Job]
        val dispatch = awaitTransferExecution(command, callerJob)
        if (!dispatch.dispatched) throw CancellationException("Extraction commit cancelled before dispatch")
        return withContext(NonCancellable) {
            val execution = dispatch.result.getOrElse {
                return@withContext uncertainExtraction("Extraction commit result was lost")
            }
            if (execution.exitCode != 0) {
                return@withContext mapExitCode(
                    execution.exitCode,
                    execution.stderr,
                    "无法完成解压",
                    "commit-extract",
                )
            }
            val committedIdentity = RootFileIdentity.parse(execution.stdout).getOrElse {
                return@withContext uncertainExtraction("Malformed committed directory identity")
            }
            val finalPath = FolderName.join(stage.canonicalParent, finalName)
            val entry = stat(finalPath)
            if (entry !is OperationResult.Success ||
                entry.value.type != com.iamxpp.isaver.domain.EntryType.DIRECTORY ||
                entry.value.symbolicLink
            ) {
                return@withContext uncertainExtraction("Committed extraction directory could not be verified")
            }
            val identity = readIdentity(finalPath)
            if (identity !is OperationResult.Success || identity.value != committedIdentity ||
                identity.value != stage.stageIdentity
            ) {
                return@withContext uncertainExtraction("Committed extraction directory identity changed")
            }
            entry
        }
    }

    override suspend fun cleanupExtractionStage(stage: ExtractionStage): OperationResult<Unit> {
        val result = runHelperBounded(
            transferHelper.removeExtraction(stage),
            helperOperationTimeoutMillis,
        ).getOrElse {
            return uncertainExtraction("Extraction stage cleanup result was lost")
        }
        if (result.exitCode != 0) {
            return mapExitCode(result.exitCode, result.stderr, "无法清理解压临时目录", "remove-extract-stage")
        }
        return OperationResult.Success(Unit)
    }

    private suspend fun transfer(
        targetDirectory: RootPath,
        finalName: EntryName,
        expectedSizeBytes: Long,
        copyCommand: (PreparedTransferDirectory, TransferStage) -> String,
    ):OperationResult<DirectoryEntry>{
        if(expectedSizeBytes<0)return failure(ErrorCode.SOURCE_UNREADABLE,"无法读取来源文件","Negative source size")
        val prepared=prepareWritableDirectory(targetDirectory)
        if(prepared !is OperationResult.Success)return prepared as OperationResult.Failure
        val directory=prepared.value
        val stageName=stageNameFactory()
        if(!STAGE_NAME.matches(stageName))return failure(ErrorCode.COMMAND_FAILED,"无法准备目标目录","Invalid generated stage name")

        val preparedStage=prepareStage(directory,stageName)
        if(preparedStage !is OperationResult.Success)return preparedStage as OperationResult.Failure
        val stage=preparedStage.value
        try{
            currentCoroutineContext().ensureActive()
        }catch(cancelled:CancellationException){
            cleanupStage(directory,stage)
            throw cancelled
        }

        val transferDeadline = transferDeadlineMillis(expectedSizeBytes)
        val command=copyCommand(directory,stage)
        val callerJob=currentCoroutineContext()[Job]
        val dispatch=awaitTransferExecution(command,callerJob,transferDeadline)
        if(!dispatch.dispatched){
            cleanupStage(directory,stage)
            throw CancellationException("Transfer cancelled before dispatch")
        }
        return withContext(NonCancellable){
            val execution=dispatch.result.getOrElse{error->
                val reason=when{
                    dispatch.waitTimedOut->"Copy-publish wait timed out before backend completion"
                    dispatch.callerCancelled->"Copy-publish caller was cancelled after dispatch"
                    error is java.net.SocketTimeoutException->"Copy-publish backend timed out after dispatch"
                    else->"Copy-publish result was lost"
                }
                logRootUncertain("copy-publish", reason)
                return@withContext reconcileLostTransfer(directory,stage,finalName,reason)
            }
            if(execution.exitCode!=0){
                if(execution.exitCode==55||execution.exitCode==137){
                    logRootUncertain("copy-publish", "Native helper reported exit ${execution.exitCode}")
                    return@withContext reconcileLostTransfer(directory,stage,finalName,"Native helper reported an uncertain outcome")
                }
                return@withContext mapExitCode(
                    execution.exitCode,
                    execution.stderr,
                    "无法完成保存",
                    "copy-publish",
                )
            }
            val published=parsePublishedIdentity(execution.stdout)
            if(published !is OperationResult.Success)return@withContext uncertainTransfer("Malformed copy-publish result")
            if(published.value.sizeBytes!=expectedSizeBytes)return@withContext uncertainTransfer("Published size did not match source")
            val finalPath=EntryName.join(directory.canonical,finalName)
            val finalEntry=stat(finalPath)
            if(finalEntry !is OperationResult.Success)return@withContext uncertainTransfer("Published file could not be verified")
            if(finalEntry.value.type!=com.iamxpp.isaver.domain.EntryType.FILE||finalEntry.value.symbolicLink||finalEntry.value.sizeBytes!=expectedSizeBytes){
                return@withContext uncertainTransfer("Published path was not the expected regular file")
            }
            val finalIdentity=readIdentity(finalPath)
            if(finalIdentity !is OperationResult.Success||finalIdentity.value!=published.value.identity){
                return@withContext uncertainTransfer("Published file identity changed")
            }
            finalEntry
        }
    }

    private suspend fun awaitTransferExecution(
        command:String,
        callerJob:Job?,
        waitTimeoutMillis:Long = timeoutMillis,
    ):TransferDispatch =
        withContext(NonCancellable){
            if(callerJob?.isActive==false)return@withContext TransferDispatch.notDispatched()
            val scope=CoroutineScope(SupervisorJob()+ioDispatcher)
            val backend=scope.async(start=CoroutineStart.LAZY){runCatching{transferCommandRunner.run(command)}}
            if(callerJob?.isActive==false){
                scope.cancel()
                return@withContext TransferDispatch.notDispatched()
            }
            backend.start()
            val softResult=withTimeoutOrNull(waitTimeoutMillis){backend.await()}
            if(softResult!=null){
                scope.cancel()
                return@withContext TransferDispatch(softResult,false,callerJob?.isCancelled==true,true)
            }
            val graceResult=withTimeoutOrNull(transferTimeoutGraceMillis){backend.await()}
            if(graceResult!=null){
                scope.cancel()
                return@withContext TransferDispatch(graceResult,true,callerJob?.isCancelled==true,true)
            }
            scope.cancel()
            TransferDispatch(
                Result.failure(RootTransferDeadlineException("Copy-publish exceeded hard deadline")),
                true,callerJob?.isCancelled==true,true,
            )
        }

    private fun transferDeadlineMillis(expectedSizeBytes: Long): Long {
        if (timeoutMillis < DEFAULT_TIMEOUT_MILLIS) return timeoutMillis
        val size = expectedSizeBytes.coerceAtLeast(0L)
        val throughputMillis = ((size + TRANSFER_TIMEOUT_BYTES_PER_SECOND - 1) /
            TRANSFER_TIMEOUT_BYTES_PER_SECOND) * 1_000L
        return maxOf(timeoutMillis, MIN_STREAM_TRANSFER_TIMEOUT_MILLIS, throughputMillis)
    }

    private suspend fun prepareStage(directory:PreparedTransferDirectory,stageName:String):OperationResult<TransferStage>{
        val command=transferHelper.prepare(
            directory.original.value,directory.canonical.value,stageName,directory.identity,
        )
        val result=runHelperBounded(command,helperOperationTimeoutMillis).getOrElse{
            return uncertainTransfer("Prepare-stage exceeded its bounded deadline or lost its result")
        }
        if(result.exitCode!=0)return mapExitCode(result.exitCode,result.stderr,"无法准备目标目录","prepare-stage")
        return RootFileIdentity.parse(result.stdout).fold(
            onSuccess={OperationResult.Success(TransferStage(stageName,it))},
            onFailure={uncertainTransfer("Malformed prepare-stage identity")},
        )
    }

    private suspend fun cleanupStage(directory:PreparedTransferDirectory,stage:TransferStage){
        runHelperBounded(
            transferHelper.removeStage(directory.original.value,directory.canonical.value,stage,directory.identity),
            helperOperationTimeoutMillis,
        )
    }

    private suspend fun runHelperBounded(command:String,deadlineMillis:Long):Result<RootCommandResult> =
        withContext(NonCancellable){
            val scope=CoroutineScope(SupervisorJob()+ioDispatcher)
            val backend=scope.async{runCatching{transferCommandRunner.run(command)}}
            val result=withTimeoutOrNull(deadlineMillis){backend.await()}
            scope.cancel()
            result?:Result.failure(RootTransferDeadlineException("Helper operation exceeded deadline"))
        }

    private suspend fun reconcileLostTransfer(
        directory:PreparedTransferDirectory,
        stage:TransferStage,
        finalName:EntryName,
        reason:String,
    ):OperationResult.Failure = withContext(NonCancellable){
        val finalPath=EntryName.join(directory.canonical,finalName)
        val finalState=stat(finalPath)
        if(finalState is OperationResult.Failure&&finalState.code==ErrorCode.NOT_FOUND){
            cleanupStage(directory,stage)
        }
        uncertainTransfer(reason)
    }

    private suspend fun prepareWritableDirectory(original:RootPath):OperationResult<PreparedTransferDirectory>{
        if(original.value.length>1&&original.value.endsWith('/'))return failure(ErrorCode.COMMAND_FAILED,"目标目录路径无效","Trailing slash is not accepted for secure transfer")
        val first=stat(original);if(first !is OperationResult.Success)return first as OperationResult.Failure
        if(first.value.symbolicLink)return failure(ErrorCode.COMMAND_FAILED,"目标目录不能是符号链接","Parent symlink")
        if(first.value.type!=com.iamxpp.isaver.domain.EntryType.DIRECTORY)return failure(ErrorCode.NOT_DIRECTORY,"路径不是目录","Parent was not directory")
        if(!first.value.readable)return failure(ErrorCode.NOT_READABLE,"目录不可读","Parent not readable")
        if(!first.value.writable)return failure(ErrorCode.NOT_WRITABLE,"目录不可写","Parent not writable")
        val canonical=canonicalize(original);if(canonical !is OperationResult.Success)return canonical as OperationResult.Failure
        if(canonical.value.value.length>1&&canonical.value.value.endsWith('/'))return failure(ErrorCode.COMMAND_FAILED,"目标目录路径无效","Canonical path had trailing slash")
        val canonicalStat=stat(canonical.value);if(canonicalStat !is OperationResult.Success)return canonicalStat as OperationResult.Failure
        if(canonicalStat.value.symbolicLink||canonicalStat.value.type!=com.iamxpp.isaver.domain.EntryType.DIRECTORY)return failure(ErrorCode.COMMAND_FAILED,"目标目录无效","Canonical parent was not a plain directory")
        val originalIdentity=readIdentity(original);if(originalIdentity !is OperationResult.Success)return originalIdentity as OperationResult.Failure
        val canonicalIdentity=readIdentity(canonical.value);if(canonicalIdentity !is OperationResult.Success)return canonicalIdentity as OperationResult.Failure
        if(originalIdentity.value!=canonicalIdentity.value)return failure(ErrorCode.COMMAND_FAILED,"目标目录已变化","Original and canonical parent identities differed")
        return OperationResult.Success(PreparedTransferDirectory(original,canonical.value,canonicalIdentity.value))
    }

    private suspend fun execute(command: String, failureMessage: String = "无法读取目录信息"): OperationResult<List<String>> {
        val result = try {
            withTimeout(timeoutMillis) {
                withContext(ioDispatcher) { shellCoordinator.execute(command) }
            }
        } catch (_: TimeoutCancellationException) {
            return failure(ErrorCode.COMMAND_FAILED, "Root 操作超时", "Root command timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(ErrorCode.COMMAND_FAILED, failureMessage, "Root command execution failed")
        }
        if (result.exitCode != 0) return mapExitCode(result.exitCode, result.stderr, failureMessage)
        return OperationResult.Success(result.stdout)
    }

    private suspend fun executeDirectoryListing(command: String): OperationResult<List<String>> {
        val result = try {
            withTimeout(timeoutMillis) {
                withContext(ioDispatcher) { shellCoordinator.execute(command) }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            return failure(ErrorCode.COMMAND_FAILED, "Root 操作超时", "Native directory helper timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(ErrorCode.COMMAND_FAILED, "无法读取目录信息", "Native directory helper execution failed")
        }
        if (result.exitCode != 0) {
            return mapDirectoryExitCode(result.exitCode, result.stderr)
        }
        return OperationResult.Success(result.stdout)
    }

    private fun mapDirectoryExitCode(exitCode: Int, stderr: List<String>): OperationResult.Failure {
        val hadStderr = stderr.isNotEmpty()
        val failure = when (exitCode) {
            43 -> failure(ErrorCode.ROOT_DENIED, "请授予 Root 权限后运行 iSaver", "Root access was lost")
            EXIT_NOT_FOUND -> failure(ErrorCode.NOT_FOUND, "路径不存在", "Path was not found")
            EXIT_NOT_DIRECTORY -> failure(ErrorCode.NOT_DIRECTORY, "路径不是目录", "Path was not a directory")
            EXIT_NOT_READABLE -> failure(ErrorCode.NOT_READABLE, "目录不可读", "Path was not readable")
            EXIT_NATIVE_IO -> failure(ErrorCode.COMMAND_FAILED, "无法读取目录信息", "Native directory helper I/O failed")
            EXIT_OUTPUT_LIMIT -> failure(ErrorCode.COMMAND_FAILED, "目录内容过多，无法读取", "Native directory listing exceeded protocol limits")
            EXIT_USAGE -> failure(ErrorCode.COMMAND_FAILED, "无法读取目录信息", "Native directory helper rejected its fixed invocation")
            else -> failure(
                ErrorCode.COMMAND_FAILED,
                "无法读取目录信息",
                if (hadStderr) "Native directory helper failed with diagnostic output" else "Native directory helper failed",
            )
        }
        logRootFailure("list-dir", exitCode, stderr, failure.code)
        return failure
    }

    private fun mapExitCode(
        exitCode: Int,
        stderr: List<String>,
        failureMessage: String,
        operation: String = "root-command",
    ): OperationResult.Failure {
        val hadStderr = stderr.isNotEmpty()
        val failure = when (exitCode) {
            43 -> failure(ErrorCode.ROOT_DENIED, "请授予 Root 权限后运行 iSaver", "Root access was lost")
            EXIT_NOT_FOUND -> failure(ErrorCode.NOT_FOUND, "路径不存在", "Path was not found")
            EXIT_NOT_DIRECTORY -> failure(ErrorCode.NOT_DIRECTORY, "路径不是目录", "Path was not a directory")
            EXIT_NOT_READABLE -> failure(ErrorCode.NOT_READABLE, "目录不可读", "Path was not readable")
            48 -> failure(ErrorCode.NOT_WRITABLE, "目录不可写", "Path was not writable")
            49 -> failure(ErrorCode.ALREADY_EXISTS, "文件已存在", "Final reservation already exists")
            50 -> failure(ErrorCode.NO_SPACE, "存储空间不足", "No space left on device")
            54 -> failure(ErrorCode.SOURCE_UNREADABLE, "无法读取来源文件", "Source identity or contents changed")
            56 -> failure(ErrorCode.SOURCE_UNREADABLE, "无法读取来源文件", "Source could not be read")
            55 -> failure(ErrorCode.OUTCOME_UNCERTAIN, "保存结果不确定，请刷新确认", "Native helper reported an uncertain outcome")
            137 -> failure(ErrorCode.OUTCOME_UNCERTAIN, "保存结果不确定，请刷新确认", "Native helper was killed after timeout")
            else -> if (operation in STREAM_COPY_OPERATIONS && stderr.looksLikeContentReadFailure()) {
                failure(
                    ErrorCode.SOURCE_UNREADABLE,
                    "无法读取来源文件",
                    "Content stream provider could not be read",
                )
            } else {
                failure(
                    ErrorCode.COMMAND_FAILED,
                    failureMessage,
                    if (hadStderr) "Root command failed with diagnostic output" else "Root command failed",
                )
            }
        }
        logRootFailure(operation, exitCode, stderr, failure.code)
        return failure
    }

    private fun logRootFailure(
        operation: String,
        exitCode: Int,
        stderr: List<String>,
        code: ErrorCode,
    ) {
        val sample = stderr.asSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.let(::redactDiagnostic)
            .orEmpty()
        runCatching {
            Log.w(
                LOG_TAG,
                "operation=$operation exitCode=$exitCode code=$code hasStderr=${stderr.isNotEmpty()} stderrSample=$sample",
            )
        }
    }

    private fun logRootUncertain(operation: String, reason: String) {
        runCatching {
            Log.w(
                LOG_TAG,
                "operation=$operation code=${ErrorCode.OUTCOME_UNCERTAIN} reason=${redactDiagnostic(reason)}",
            )
        }
    }

    private fun redactDiagnostic(value: String): String =
        value
            .replace(INCOMING_URI_PATTERN, "content://com.iamxpp.isaver.incoming-stream/incoming/<redacted>")
            .replace(ANY_CONTENT_URI_PATTERN, "content://<redacted>")
            .replace(ANDROID_PATH_PATTERN, "/<path>")
            .replace(LONG_HEX_PATTERN, "<hex>")
            .take(240)

    private fun List<String>.looksLikeContentReadFailure(): Boolean =
        any { line ->
            CONTENT_READ_FAILURE_PATTERNS.any { pattern ->
                line.contains(pattern, ignoreCase = true)
            }
        }

    private fun buildStatCommand(path: RootPath): String {
        val quoted = RootCommandCodec.quote(path.value)
        return """
            target=$quoted
            [ -e "${'$'}target" ] || [ -L "${'$'}target" ] || exit $EXIT_NOT_FOUND
            emit_isaver_record "${'$'}target"
        """.trimIndent().withRecordEmitter()
    }

    /** Uses Android's `/system/bin/sh` mksh behavior available on the min API 29 target. */
    private fun buildCanonicalizeCommand(path: RootPath): String = """
        target=${RootCommandCodec.quote(path.value)}
        [ -e "${'$'}target" ] || [ -L "${'$'}target" ] || exit $EXIT_NOT_FOUND
        set -o pipefail
        readlink -f -- "${'$'}target" | base64 -w 0 || exit 47
    """.trimIndent()
    /** Shell checks reduce but cannot eliminate the tiny check/mkdir TOCTOU window; fully atomic defense requires native mkdirat. */
    private fun buildMkdirCommand(original:RootPath,parent:RootPath,child:RootPath,identity:RootFileIdentity)="""
        set -o pipefail
        original=${RootCommandCodec.quote(original.value)}
        parent=${RootCommandCodec.quote(parent.value)}
        child=${RootCommandCodec.quote(child.value)}
        [ ! -L "${'$'}original" ] && current=${'$'}(stat -c '%d:%i' -- "${'$'}original") && mapped=${'$'}(readlink -f -- "${'$'}original" | base64 -w 0) && [ ! -L "${'$'}original" ] && [ "${'$'}current" = '${identity.device}:${identity.inode}' ] && [ "${'$'}mapped" = ${RootCommandCodec.quote(canonicalLineBase64(parent))} ] && [ -d "${'$'}original" ] && [ -w "${'$'}original" ] && mkdir -- "${'$'}child"
    """.trimIndent()

    private fun canonicalLineBase64(path:RootPath)=Base64.getEncoder().encodeToString("${path.value}\n".toByteArray(StandardCharsets.UTF_8))

    private suspend fun readIdentity(path:RootPath):OperationResult<RootFileIdentity> = execute("stat -c '%d:%i' -- ${RootCommandCodec.quote(path.value)}","无法验证目录身份").flatMap{lines->RootFileIdentity.parse(lines).fold({OperationResult.Success(it)},{failure(ErrorCode.COMMAND_FAILED,"无法验证目录身份","Malformed file identity")})}

    private fun parseCanonicalOutput(lines: List<String>): OperationResult<RootPath> = try {
        require(lines.size == 1)
        val bytes = Base64.getDecoder().decode(lines.single())
        val decoded = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        require(decoded.endsWith('\n'))
        RootPath.parse(decoded.dropLast(1)).fold(
            onSuccess = { OperationResult.Success(it) },
            onFailure = { malformedCanonicalOutput() },
        )
    } catch (_: IllegalArgumentException) {
        malformedCanonicalOutput()
    } catch (_: CharacterCodingException) {
        malformedCanonicalOutput()
    }

    /** Emits one structured record for the fixed stat target; directory listing uses the native helper. */
    private fun String.withRecordEmitter(): String = """
        emit_isaver_record() {
          item="${'$'}1"
          if [ -d "${'$'}item" ]; then kind=directory; elif [ -f "${'$'}item" ]; then kind=file; else kind=other; fi
          if [ "${'$'}kind" = file ]; then size=${'$'}(stat -c %s -- "${'$'}item") || exit 47; else size=-; fi
          mtime=${'$'}(stat -c %Y -- "${'$'}item") || mtime=-
          [ -r "${'$'}item" ] && readable=1 || readable=0
          [ -w "${'$'}item" ] && writable=1 || writable=0
          [ -L "${'$'}item" ] && symlink=1 || symlink=0
          trimmed="${'$'}item"
          while [ "${'$'}trimmed" != / ] && [ "${'$'}{trimmed%/}" != "${'$'}trimmed" ]; do trimmed=${'$'}{trimmed%/}; done
          if [ "${'$'}trimmed" = / ]; then name=/; else name=${'$'}{trimmed##*/}; fi
          name64=${'$'}(printf '%s' "${'$'}name" | base64 -w 0) || exit 47
          path64=${'$'}(printf '%s' "${'$'}item" | base64 -w 0) || exit 47
          printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}name64" "${'$'}path64" "${'$'}kind" "${'$'}size" "${'$'}mtime" "${'$'}readable" "${'$'}writable" "${'$'}symlink"
        }
        $this
    """.trimIndent()

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        const val MIN_STREAM_TRANSFER_TIMEOUT_MILLIS = 30_000L
        const val TRANSFER_TIMEOUT_BYTES_PER_SECOND = 2L * 1024L * 1024L
        const val EXIT_NOT_FOUND = 44
        const val EXIT_NOT_DIRECTORY = 45
        const val EXIT_NOT_READABLE = 46
        const val EXIT_NATIVE_IO = 51
        const val EXIT_OUTPUT_LIMIT = 57
        const val EXIT_USAGE = 64
        const val MAX_ROOT_CACHE_BYTES = 256L * 1024L * 1024L
        const val LOG_TAG = "iSaverTransfer"
        val STREAM_COPY_OPERATIONS = setOf("copy-publish", "copy-extract")
        val CONTENT_READ_FAILURE_PATTERNS = listOf(
            "Error while accessing provider",
            "FileNotFoundException",
            "SecurityException",
            "Stream unavailable",
        )
        val INCOMING_URI_PATTERN = Regex("""content://com\.iamxpp\.isaver\.incoming-stream/incoming/[^\s"'`|;]+""")
        val ANY_CONTENT_URI_PATTERN = Regex("""content://[^\s"'`|;]+""")
        val ANDROID_PATH_PATTERN = Regex("""/(?:storage|sdcard|data|mnt|system|apex|vendor|product|dev|proc)(?:/[^\s"'`|;]*)+""")
        val LONG_HEX_PATTERN = Regex("""[0-9a-fA-F]{32,}""")
        val STAGE_NAME=Regex("\\.isaver-stage-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
    }
}

private data class PreparedTransferDirectory(
    val original:RootPath,
    val canonical:RootPath,
    val identity:RootFileIdentity,
)

private data class PublishedFileIdentity(
    val identity:RootFileIdentity,
    val sizeBytes:Long,
)

private data class TransferDispatch(
    val result:Result<RootCommandResult>,
    val waitTimedOut:Boolean,
    val callerCancelled:Boolean,
    val dispatched:Boolean,
){companion object{fun notDispatched()=TransferDispatch(Result.failure(CancellationException("Not dispatched")),false,true,false)}}

private class RootTransferDeadlineException(message:String):Exception(message)

private fun parsePublishedIdentity(lines:List<String>):OperationResult<PublishedFileIdentity> = runCatching{
    require(lines.size==1)
    val parts=lines.single().split(':')
    require(parts.size==3)
    val device=parts[0].toLong()
    val inode=parts[1].toLong()
    val size=parts[2].toLong()
    require(device>=0&&inode>=0&&size>=0)
    PublishedFileIdentity(RootFileIdentity(device,inode),size)
}.fold(
    onSuccess={OperationResult.Success(it)},
    onFailure={failure(ErrorCode.OUTCOME_UNCERTAIN,"保存结果不确定，请刷新确认","Malformed published identity")},
)

private class CommandRunnerCoordinator(
    private val commandRunner: RootCommandRunner,
) : RootShellCoordinator {
    override suspend fun execute(command: String): RootCommandResult = commandRunner.run(command)

    override suspend fun invalidate() = Unit
}

private inline fun <T, R> OperationResult<T>.flatMap(transform: (T) -> OperationResult<R>): OperationResult<R> =
    when (this) {
        is OperationResult.Failure -> this
        is OperationResult.Success -> transform(value)
    }

private fun malformedOutput() = failure(
    ErrorCode.COMMAND_FAILED,
    "无法读取目录信息",
    "Unexpected structured record count",
)

private fun malformedNativeDirectoryOutput(
    reason: NativeDirectoryListingProtocolFailure,
) = failure(
    ErrorCode.COMMAND_FAILED,
    "无法读取目录信息",
    "Malformed native directory listing protocol: ${reason.name}",
)

private fun malformedCanonicalOutput() = failure(
    ErrorCode.COMMAND_FAILED,
    "无法解析真实路径",
    "Malformed canonical path output",
)
private fun uncertain(technical:String)=failure(ErrorCode.OUTCOME_UNCERTAIN,"文件夹可能已创建，请刷新确认",technical)
private fun uncertainTransfer(technical:String)=failure(ErrorCode.OUTCOME_UNCERTAIN,"保存结果不确定，请刷新确认",technical)
private fun uncertainExtraction(technical:String)=failure(ErrorCode.OUTCOME_UNCERTAIN,"解压结果不确定，请刷新目标目录核对",technical)

private fun failure(code: ErrorCode, userMessage: String, technicalMessage: String) =
    OperationResult.Failure(code, userMessage, technicalMessage)
