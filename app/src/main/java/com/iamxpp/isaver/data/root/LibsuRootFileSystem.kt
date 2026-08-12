package com.iamxpp.isaver.data.root

import android.util.Log
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootPathRiskPolicy
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
            if (lines == listOf(STAT_NOT_FOUND_MARKER)) {
                failure(ErrorCode.NOT_FOUND, "路径不存在", "Path was not found")
            } else {
                when (val parsed = DirectoryListingParser.parse(lines)) {
                    is OperationResult.Failure -> parsed
                    is OperationResult.Success -> parsed.value.singleOrNull()?.let { OperationResult.Success(it) }
                        ?: malformedOutput()
                }
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

    override suspend fun createFileNoReplace(
        parent: RootPath,
        name: EntryName,
    ): OperationResult<DirectoryEntry> {
        if (RootPathRiskPolicy.isProtected(parent)) {
            return failure(ErrorCode.NOT_WRITABLE, "系统保护区域仅允许浏览", "Protected create-file path")
        }
        val preparedResult = prepareWritableDirectory(parent)
        if (preparedResult !is OperationResult.Success) return preparedResult as OperationResult.Failure
        val prepared = preparedResult.value
        val targetPath = EntryName.join(prepared.canonical, name)
        when (val target = stat(targetPath)) {
            is OperationResult.Success -> return failure(
                ErrorCode.ALREADY_EXISTS,
                "文件已存在",
                "Create-file target already existed",
            )
            is OperationResult.Failure -> if (target.code != ErrorCode.NOT_FOUND) return target
        }
        currentCoroutineContext().ensureActive()
        val execution = runHelperBounded(
            transferHelper.createFileNoReplace(
                original = prepared.original.value,
                canonical = prepared.canonical.value,
                name = name,
                parentIdentity = prepared.identity,
            ),
            helperOperationTimeoutMillis,
        ).getOrElse {
            return uncertainCreateFile("Create-file helper exceeded its bounded deadline or lost its result")
        }
        if (execution.exitCode != 0) {
            return mapExitCode(execution.exitCode, execution.stderr, "无法新建文件", "create-file-noreplace")
        }
        val createdIdentity = RootFileIdentity.parse(execution.stdout).getOrElse {
            return uncertainCreateFile("Create-file helper returned malformed identity")
        }
        val created = stat(targetPath)
        val actualIdentity = readIdentity(targetPath)
        val parentAfter = canonicalize(parent)
        if (
            created !is OperationResult.Success ||
            created.value.type != com.iamxpp.isaver.domain.EntryType.FILE ||
            created.value.symbolicLink ||
            created.value.sizeBytes != 0L ||
            actualIdentity !is OperationResult.Success || actualIdentity.value != createdIdentity ||
            parentAfter !is OperationResult.Success || parentAfter.value != prepared.canonical
        ) {
            return uncertainCreateFile("Created file could not be fully reconciled")
        }
        return created
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
        failureMessage: String = "无法完成保存",
        operation: String = "copy-publish",
        uncertainResult: (String) -> OperationResult.Failure = ::uncertainTransfer,
        copyCommand: (PreparedTransferDirectory, TransferStage) -> String,
    ):OperationResult<DirectoryEntry>{
        if(expectedSizeBytes<0)return failure(ErrorCode.SOURCE_UNREADABLE,"无法读取来源文件","Negative source size")
        val prepared=prepareWritableDirectory(targetDirectory)
        if(prepared !is OperationResult.Success)return prepared as OperationResult.Failure
        val directory=prepared.value
        val stageName=stageNameFactory()
        if(!STAGE_NAME.matches(stageName))return failure(ErrorCode.COMMAND_FAILED,"无法准备目标目录","Invalid generated stage name")

        val preparedStage=prepareStage(directory,stageName,uncertainResult)
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
                logRootUncertain(operation, reason)
                return@withContext reconcileLostTransfer(
                    directory, stage, finalName, reason, uncertainResult,
                )
            }
            if(execution.exitCode!=0){
                if(execution.exitCode==55||execution.exitCode==137){
                    logRootUncertain(operation, "Native helper reported exit ${execution.exitCode}")
                    return@withContext reconcileLostTransfer(
                        directory,
                        stage,
                        finalName,
                        "Native helper reported an uncertain outcome",
                        uncertainResult,
                    )
                }
                return@withContext mapExitCode(
                    execution.exitCode,
                    execution.stderr,
                    failureMessage,
                    operation,
                )
            }
            val published=parsePublishedIdentity(execution.stdout)
            if(published !is OperationResult.Success)return@withContext uncertainResult("Malformed copy-publish result")
            if(published.value.sizeBytes!=expectedSizeBytes)return@withContext uncertainResult("Published size did not match source")
            val finalPath=EntryName.join(directory.canonical,finalName)
            val finalEntry=stat(finalPath)
            if(finalEntry !is OperationResult.Success)return@withContext uncertainResult("Published file could not be verified")
            if(finalEntry.value.type!=com.iamxpp.isaver.domain.EntryType.FILE||finalEntry.value.symbolicLink||finalEntry.value.sizeBytes!=expectedSizeBytes){
                return@withContext uncertainResult("Published path was not the expected regular file")
            }
            val finalIdentity=readIdentity(finalPath)
            if(finalIdentity !is OperationResult.Success||finalIdentity.value!=published.value.identity){
                return@withContext uncertainResult("Published file identity changed")
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

    override suspend fun moveFileNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
    ): OperationResult<DirectoryEntry> {
        val targetName = EntryName.parse(source.name).getOrElse { return invalidMoveSource() }
        return moveFileAsNoReplace(source, sourceDirectory, targetDirectory, targetName)
    }

    override suspend fun moveFileAsNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
        targetName: EntryName,
    ): OperationResult<DirectoryEntry> {
        val name = EntryName.parse(source.name).getOrElse { return invalidMoveSource() }
        if (
            source.type != com.iamxpp.isaver.domain.EntryType.FILE ||
            source.symbolicLink ||
            !source.readable ||
            source.path != EntryName.join(sourceDirectory, name)
        ) {
            return invalidMoveSource()
        }
        if (sourceDirectory == targetDirectory) {
            return failure(ErrorCode.ALREADY_EXISTS, "文件已在当前目录", "Move target matched source parent")
        }
        if (
            RootPathRiskPolicy.isProtected(sourceDirectory) ||
            RootPathRiskPolicy.isProtected(targetDirectory)
        ) {
            return failure(ErrorCode.NOT_WRITABLE, "系统保护区域仅允许浏览", "Protected move path")
        }

        val sourceParent = prepareWritableDirectory(sourceDirectory)
        if (sourceParent !is OperationResult.Success) return sourceParent as OperationResult.Failure
        val targetParent = prepareWritableDirectory(targetDirectory)
        if (targetParent !is OperationResult.Success) return targetParent as OperationResult.Failure
        val preparedSourceParent = sourceParent.value
        val preparedTargetParent = targetParent.value

        val expectedCanonicalSource = EntryName.join(preparedSourceParent.canonical, name)
        val currentSource = stat(source.path)
        if (
            currentSource !is OperationResult.Success ||
            currentSource.value.type != com.iamxpp.isaver.domain.EntryType.FILE ||
            currentSource.value.symbolicLink ||
            !currentSource.value.readable ||
            currentSource.value.sizeBytes != source.sizeBytes
        ) {
            return invalidMoveSource()
        }
        val canonicalSource = canonicalize(source.path)
        if (canonicalSource !is OperationResult.Success || canonicalSource.value != expectedCanonicalSource) {
            return invalidMoveSource("Source no longer mapped to its selected parent")
        }
        val sourceIdentity = readIdentity(expectedCanonicalSource)
        if (sourceIdentity !is OperationResult.Success) return sourceIdentity as OperationResult.Failure

        val targetPath = EntryName.join(preparedTargetParent.canonical, targetName)
        when (val target = stat(targetPath)) {
            is OperationResult.Success -> return failure(
                ErrorCode.ALREADY_EXISTS,
                "目标位置已存在同名文件",
                "Move target already existed",
            )
            is OperationResult.Failure -> if (target.code != ErrorCode.NOT_FOUND) return target
        }
        currentCoroutineContext().ensureActive()

        val execution = runHelperBounded(
            transferHelper.moveNoReplace(
                sourceOriginal = preparedSourceParent.original.value,
                sourceCanonical = preparedSourceParent.canonical.value,
                sourceName = name,
                sourceParentIdentity = preparedSourceParent.identity,
                sourceIdentity = sourceIdentity.value,
                targetOriginal = preparedTargetParent.original.value,
                targetCanonical = preparedTargetParent.canonical.value,
                targetParentIdentity = preparedTargetParent.identity,
                targetName = targetName,
            ),
            helperOperationTimeoutMillis,
        ).getOrElse {
            return uncertainMove("Move helper exceeded its bounded deadline or lost its result")
        }
        if (execution.exitCode == 58) {
            return moveAcrossDevices(
                source = source,
                name = name,
                sourceParent = preparedSourceParent,
                sourceIdentity = sourceIdentity.value,
                targetName = targetName,
                targetDirectory = targetDirectory,
                targetParent = preparedTargetParent,
            )
        }
        if (execution.exitCode != 0) {
            return mapExitCode(execution.exitCode, execution.stderr, "无法移动文件", "move-noreplace")
        }
        val movedIdentity = RootFileIdentity.parse(execution.stdout).getOrElse {
            return uncertainMove("Move helper returned malformed identity")
        }
        if (movedIdentity != sourceIdentity.value) {
            return uncertainMove("Moved identity differed from the selected source")
        }

        val sourceAfter = stat(expectedCanonicalSource)
        val targetAfter = stat(targetPath)
        val targetIdentityAfter = readIdentity(targetPath)
        val sourceParentAfter = canonicalize(sourceDirectory)
        val targetParentAfter = canonicalize(targetDirectory)
        if (
            sourceAfter !is OperationResult.Failure || sourceAfter.code != ErrorCode.NOT_FOUND ||
            targetAfter !is OperationResult.Success ||
            targetAfter.value.type != com.iamxpp.isaver.domain.EntryType.FILE ||
            targetAfter.value.symbolicLink ||
            targetAfter.value.sizeBytes != source.sizeBytes ||
            targetIdentityAfter !is OperationResult.Success || targetIdentityAfter.value != movedIdentity ||
            sourceParentAfter !is OperationResult.Success ||
            sourceParentAfter.value != preparedSourceParent.canonical ||
            targetParentAfter !is OperationResult.Success ||
            targetParentAfter.value != preparedTargetParent.canonical
        ) {
            return uncertainMove("Move result could not be fully reconciled")
        }
        return targetAfter
    }

    override suspend fun renameFileNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetName: EntryName,
    ): OperationResult<DirectoryEntry> {
        val sourceName = EntryName.parse(source.name).getOrElse { return invalidRenameSource() }
        if (
            source.type != com.iamxpp.isaver.domain.EntryType.FILE ||
            source.symbolicLink ||
            !source.readable ||
            source.path != EntryName.join(sourceDirectory, sourceName) ||
            sourceName == targetName
        ) return invalidRenameSource()
        if (RootPathRiskPolicy.isProtected(sourceDirectory)) {
            return failure(ErrorCode.NOT_WRITABLE, "系统保护区域仅允许浏览", "Protected rename path")
        }
        val parent = prepareWritableDirectory(sourceDirectory)
        if (parent !is OperationResult.Success) return parent as OperationResult.Failure
        val prepared = parent.value
        val expectedSource = EntryName.join(prepared.canonical, sourceName)
        val currentSource = stat(source.path)
        if (
            currentSource !is OperationResult.Success ||
            currentSource.value.type != com.iamxpp.isaver.domain.EntryType.FILE ||
            currentSource.value.symbolicLink ||
            !currentSource.value.readable ||
            currentSource.value.sizeBytes != source.sizeBytes
        ) return invalidRenameSource()
        val canonicalSource = canonicalize(source.path)
        if (canonicalSource !is OperationResult.Success || canonicalSource.value != expectedSource) {
            return invalidRenameSource("Source no longer mapped to its selected parent")
        }
        val sourceIdentity = readIdentity(expectedSource)
        if (sourceIdentity !is OperationResult.Success) return sourceIdentity as OperationResult.Failure
        val targetPath = EntryName.join(prepared.canonical, targetName)
        when (val target = stat(targetPath)) {
            is OperationResult.Success -> return failure(ErrorCode.ALREADY_EXISTS, "目标位置已存在同名文件", "Rename target already existed")
            is OperationResult.Failure -> if (target.code != ErrorCode.NOT_FOUND) return target
        }
        currentCoroutineContext().ensureActive()
        val execution = runHelperBounded(
            transferHelper.renameNoReplace(
                original = prepared.original.value,
                canonical = prepared.canonical.value,
                sourceName = sourceName,
                parentIdentity = prepared.identity,
                sourceIdentity = sourceIdentity.value,
                targetName = targetName,
            ),
            helperOperationTimeoutMillis,
        ).getOrElse { return uncertainRename("Rename helper exceeded its bounded deadline or lost its result") }
        if (execution.exitCode != 0) {
            return mapExitCode(execution.exitCode, execution.stderr, "无法重命名文件", "rename-noreplace")
        }
        val renamedIdentity = RootFileIdentity.parse(execution.stdout).getOrElse {
            return uncertainRename("Rename helper returned malformed identity")
        }
        if (renamedIdentity != sourceIdentity.value) return uncertainRename("Renamed identity differed from selected source")
        val renamed = stat(targetPath)
        val old = stat(expectedSource)
        val parentAfter = canonicalize(sourceDirectory)
        val actualIdentity = readIdentity(targetPath)
        if (
            renamed !is OperationResult.Success ||
            renamed.value.type != com.iamxpp.isaver.domain.EntryType.FILE ||
            renamed.value.symbolicLink ||
            renamed.value.sizeBytes != source.sizeBytes ||
            old !is OperationResult.Failure || old.code != ErrorCode.NOT_FOUND ||
            parentAfter !is OperationResult.Success || parentAfter.value != prepared.canonical ||
            actualIdentity !is OperationResult.Success || actualIdentity.value != sourceIdentity.value
        ) return uncertainRename("Rename result could not be fully reconciled")
        return renamed
    }

    private suspend fun moveAcrossDevices(
        source: DirectoryEntry,
        name: EntryName,
        sourceParent: PreparedTransferDirectory,
        sourceIdentity: RootFileIdentity,
        targetName: EntryName,
        targetDirectory: RootPath,
        targetParent: PreparedTransferDirectory,
    ): OperationResult<DirectoryEntry> {
        val expectedSize = source.sizeBytes ?: return invalidMoveSource("Move source size was unavailable")
        val stageName = stageNameFactory()
        if (!STAGE_NAME.matches(stageName)) {
            return failure(ErrorCode.COMMAND_FAILED, "无法准备目标目录", "Invalid generated stage name")
        }
        val preparedStage = prepareStage(targetParent, stageName, ::uncertainMove)
        if (preparedStage !is OperationResult.Success) return preparedStage as OperationResult.Failure
        val stage = preparedStage.value
        try {
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            cleanupStage(targetParent, stage)
            throw cancelled
        }

        val operation = "move-cross-device-noreplace"
        val command = transferHelper.moveCrossDeviceNoReplace(
            sourceOriginal = sourceParent.original.value,
            sourceCanonical = sourceParent.canonical.value,
            sourceName = name,
            sourceParentIdentity = sourceParent.identity,
            sourceIdentity = sourceIdentity,
            targetOriginal = targetParent.original.value,
            targetCanonical = targetParent.canonical.value,
            stage = stage,
            finalName = targetName,
            targetParentIdentity = targetParent.identity,
            expectedSizeBytes = expectedSize,
            timeoutMillis = transferDeadlineMillis(expectedSize),
        )
        val callerJob = currentCoroutineContext()[Job]
        val dispatch = awaitTransferExecution(command, callerJob, transferDeadlineMillis(expectedSize))
        if (!dispatch.dispatched) {
            cleanupStage(targetParent, stage)
            throw CancellationException("Cross-device move cancelled before dispatch")
        }
        return withContext(NonCancellable) {
            val execution = dispatch.result.getOrElse { error ->
                val reason = when {
                    dispatch.waitTimedOut -> "Cross-device move wait timed out before backend completion"
                    dispatch.callerCancelled -> "Cross-device move caller was cancelled after dispatch"
                    error is java.net.SocketTimeoutException -> "Cross-device move backend timed out after dispatch"
                    else -> "Cross-device move result was lost"
                }
                logRootUncertain(operation, reason)
                return@withContext reconcileLostTransfer(
                    targetParent, stage, targetName, reason, ::uncertainMove,
                )
            }
            if (execution.exitCode == 55 || execution.exitCode == 137) {
                logRootUncertain(operation, "Native helper reported exit ${execution.exitCode}")
                return@withContext reconcileLostTransfer(
                    targetParent,
                    stage,
                    targetName,
                    "Native helper reported an uncertain cross-device move outcome",
                    ::uncertainMove,
                )
            }
            if (execution.exitCode != 0 && execution.exitCode != 59) {
                return@withContext mapExitCode(
                    execution.exitCode,
                    execution.stderr,
                    "无法移动文件",
                    operation,
                )
            }

            val published = parsePublishedIdentity(execution.stdout)
            if (published !is OperationResult.Success || published.value.sizeBytes != expectedSize) {
                return@withContext uncertainMove("Malformed cross-device move publication identity")
            }
            val targetPath = EntryName.join(targetParent.canonical, targetName)
            val targetAfter = stat(targetPath)
            val targetIdentityAfter = readIdentity(targetPath)
            val sourceParentAfter = canonicalize(sourceParent.original)
            val targetParentAfter = canonicalize(targetDirectory)
            if (
                targetAfter !is OperationResult.Success ||
                targetAfter.value.type != com.iamxpp.isaver.domain.EntryType.FILE ||
                targetAfter.value.symbolicLink ||
                targetAfter.value.sizeBytes != expectedSize ||
                targetIdentityAfter !is OperationResult.Success ||
                targetIdentityAfter.value != published.value.identity ||
                sourceParentAfter !is OperationResult.Success ||
                sourceParentAfter.value != sourceParent.canonical ||
                targetParentAfter !is OperationResult.Success ||
                targetParentAfter.value != targetParent.canonical
            ) {
                return@withContext uncertainMove("Cross-device move target could not be fully reconciled")
            }
            if (execution.exitCode == 59) {
                return@withContext failure(
                    ErrorCode.MOVE_PARTIAL,
                    "文件已复制，但来源未删除",
                    "Cross-device target was published but the selected source was retained",
                )
            }

            val sourcePath = EntryName.join(sourceParent.canonical, name)
            when (val sourceAfter = stat(sourcePath)) {
                is OperationResult.Success -> {
                    val currentIdentity = readIdentity(sourcePath)
                    if (currentIdentity !is OperationResult.Success || currentIdentity.value == sourceIdentity) {
                        return@withContext uncertainMove("Cross-device move source still referenced the selected identity")
                    }
                }
                is OperationResult.Failure -> if (sourceAfter.code != ErrorCode.NOT_FOUND) {
                    return@withContext uncertainMove("Cross-device move source could not be reconciled")
                }
            }
            targetAfter
        }
    }

    override suspend fun copyFileNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
    ): OperationResult<DirectoryEntry> {
        val targetName = EntryName.parse(source.name).getOrElse { return invalidCopySource() }
        return copyFileAsNoReplace(source, sourceDirectory, targetDirectory, targetName)
    }

    override suspend fun copyFileAsNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
        targetName: EntryName,
    ): OperationResult<DirectoryEntry> {
        val name = EntryName.parse(source.name).getOrElse { return invalidCopySource() }
        val expectedSize = source.sizeBytes
        if (
            source.type != com.iamxpp.isaver.domain.EntryType.FILE ||
            source.symbolicLink ||
            !source.readable ||
            expectedSize == null ||
            expectedSize < 0L ||
            source.path != EntryName.join(sourceDirectory, name)
        ) {
            return invalidCopySource()
        }
        if (sourceDirectory == targetDirectory) {
            return failure(ErrorCode.ALREADY_EXISTS, "文件已在当前目录", "Copy target matched source parent")
        }
        if (RootPathRiskPolicy.isProtected(targetDirectory)) {
            return failure(ErrorCode.NOT_WRITABLE, "系统保护区域仅允许浏览", "Protected copy target")
        }

        val sourceParent = prepareReadableDirectory(sourceDirectory)
        if (sourceParent !is OperationResult.Success) return sourceParent as OperationResult.Failure
        val targetParent = prepareWritableDirectory(targetDirectory)
        if (targetParent !is OperationResult.Success) return targetParent as OperationResult.Failure
        val preparedSourceParent = sourceParent.value
        val preparedTargetParent = targetParent.value

        val expectedCanonicalSource = EntryName.join(preparedSourceParent.canonical, name)
        val currentSource = stat(source.path)
        if (
            currentSource !is OperationResult.Success ||
            currentSource.value.type != com.iamxpp.isaver.domain.EntryType.FILE ||
            currentSource.value.symbolicLink ||
            !currentSource.value.readable ||
            currentSource.value.sizeBytes != expectedSize
        ) {
            return invalidCopySource()
        }
        val canonicalSource = canonicalize(source.path)
        if (canonicalSource !is OperationResult.Success || canonicalSource.value != expectedCanonicalSource) {
            return invalidCopySource("Source no longer mapped to its selected parent")
        }
        val sourceIdentity = readIdentity(expectedCanonicalSource)
        if (sourceIdentity !is OperationResult.Success) return sourceIdentity as OperationResult.Failure

        val targetPath = EntryName.join(preparedTargetParent.canonical, targetName)
        when (val target = stat(targetPath)) {
            is OperationResult.Success -> return failure(
                ErrorCode.ALREADY_EXISTS,
                "目标位置已存在同名文件",
                "Copy target already existed",
            )
            is OperationResult.Failure -> if (target.code != ErrorCode.NOT_FOUND) return target
        }

        return transfer(
            targetDirectory = targetDirectory,
            finalName = targetName,
            expectedSizeBytes = expectedSize,
            failureMessage = "无法复制文件",
            operation = "copy-file-publish",
            uncertainResult = ::uncertainCopy,
        ) { directory, stage ->
            transferHelper.copyFilePublish(
                sourceOriginal = preparedSourceParent.original.value,
                sourceCanonical = preparedSourceParent.canonical.value,
                sourceName = name,
                sourceParentIdentity = preparedSourceParent.identity,
                sourceIdentity = sourceIdentity.value,
                targetOriginal = directory.original.value,
                targetCanonical = directory.canonical.value,
                stage = stage,
                finalName = targetName,
                targetParentIdentity = directory.identity,
                expectedSizeBytes = expectedSize,
                timeoutMillis = transferDeadlineMillis(expectedSize),
            )
        }
    }

    override suspend fun copyDirectoryAsNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
        targetName: EntryName,
    ): OperationResult<DirectoryEntry> = directoryOperation(
        source, sourceDirectory, targetDirectory, targetName, move = false,
    )

    override suspend fun moveDirectoryAsNoReplace(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
        targetName: EntryName,
    ): OperationResult<DirectoryEntry> = directoryOperation(
        source, sourceDirectory, targetDirectory, targetName, move = true,
    )

    private suspend fun directoryOperation(
        source: DirectoryEntry,
        sourceDirectory: RootPath,
        targetDirectory: RootPath,
        targetName: EntryName,
        move: Boolean,
    ): OperationResult<DirectoryEntry> {
        val sourceName = EntryName.parse(source.name).getOrElse { return invalidDirectorySource() }
        if (
            source.type != com.iamxpp.isaver.domain.EntryType.DIRECTORY || source.symbolicLink ||
            !source.readable || source.path != EntryName.join(sourceDirectory, sourceName)
        ) return invalidDirectorySource()
        if (sourceDirectory == targetDirectory) {
            return failure(ErrorCode.ALREADY_EXISTS, "项目已在当前目录", "Directory target matched source parent")
        }
        if (RootPathRiskPolicy.isProtected(targetDirectory) ||
            move && RootPathRiskPolicy.isProtected(sourceDirectory)) {
            return failure(ErrorCode.NOT_WRITABLE, "系统保护区域仅允许浏览", "Protected directory operation")
        }
        val sourceParent = if (move) prepareWritableDirectory(sourceDirectory)
            else prepareReadableDirectory(sourceDirectory)
        if (sourceParent !is OperationResult.Success) return sourceParent as OperationResult.Failure
        val targetParent = prepareWritableDirectory(targetDirectory)
        if (targetParent !is OperationResult.Success) return targetParent as OperationResult.Failure
        val expectedSource = EntryName.join(sourceParent.value.canonical, sourceName)
        val currentSource = stat(source.path)
        val canonicalSource = canonicalize(source.path)
        if (
            currentSource !is OperationResult.Success ||
            currentSource.value.type != com.iamxpp.isaver.domain.EntryType.DIRECTORY ||
            currentSource.value.symbolicLink || !currentSource.value.readable ||
            canonicalSource !is OperationResult.Success || canonicalSource.value != expectedSource ||
            canonicalDescendant(canonicalSource.value, targetParent.value.canonical)
        ) return invalidDirectorySource("Directory source or target mapping changed")
        val sourceIdentity = readIdentity(expectedSource)
        if (sourceIdentity !is OperationResult.Success) return sourceIdentity as OperationResult.Failure
        val targetPath = EntryName.join(targetParent.value.canonical, targetName)
        when (val existing = stat(targetPath)) {
            is OperationResult.Success -> return failure(
                ErrorCode.ALREADY_EXISTS, "目标位置已存在同名项目", "Directory target already existed",
            )
            is OperationResult.Failure -> if (existing.code != ErrorCode.NOT_FOUND) return existing
        }
        if (move) {
            val direct = runHelperBounded(
                transferHelper.moveDirectoryNoReplace(
                    sourceParent.value.original.value, sourceParent.value.canonical.value, sourceName,
                    sourceParent.value.identity, sourceIdentity.value, targetParent.value.original.value,
                    targetParent.value.canonical.value, targetParent.value.identity, targetName,
                ),
                helperOperationTimeoutMillis,
            ).getOrElse { return uncertainDirectoryMove("Directory move helper result was lost") }
            if (direct.exitCode == 0) {
                return reconcileDirectoryResult(
                    expectedSource, sourceIdentity.value, targetPath, targetName,
                    sourceDirectory, sourceParent.value, targetDirectory, targetParent.value, true, direct.stdout,
                )
            }
            if (direct.exitCode != 58) {
                return mapExitCode(direct.exitCode, direct.stderr, "无法移动目录", "move-directory-noreplace")
            }
        }
        val stageName = stageNameFactory()
        if (!STAGE_NAME.matches(stageName)) {
            return failure(ErrorCode.COMMAND_FAILED, "无法准备目标目录", "Invalid generated stage name")
        }
        val stageResult = prepareStage(targetParent.value, stageName) { reason ->
            if (move) uncertainDirectoryMove(reason) else uncertainDirectoryCopy(reason)
        }
        if (stageResult !is OperationResult.Success) return stageResult as OperationResult.Failure
        val stage = stageResult.value
        val command = if (move) {
            transferHelper.moveDirectoryCrossDeviceNoReplace(
                sourceParent.value.original.value, sourceParent.value.canonical.value, sourceName,
                sourceParent.value.identity, sourceIdentity.value, targetParent.value.original.value,
                targetParent.value.canonical.value, stage, targetName, targetParent.value.identity,
                DIRECTORY_OPERATION_TIMEOUT_MILLIS,
            )
        } else {
            transferHelper.copyDirectoryPublish(
                sourceParent.value.original.value, sourceParent.value.canonical.value, sourceName,
                sourceParent.value.identity, sourceIdentity.value, targetParent.value.original.value,
                targetParent.value.canonical.value, stage, targetName, targetParent.value.identity,
                DIRECTORY_OPERATION_TIMEOUT_MILLIS,
            )
        }
        val operation = if (move) "move-directory-cross-device-noreplace" else "copy-directory-publish"
        val execution = runHelperBounded(command, DIRECTORY_OPERATION_TIMEOUT_MILLIS).getOrElse {
            return if (move) uncertainDirectoryMove("Directory move result was lost")
            else uncertainDirectoryCopy("Directory copy result was lost")
        }
        if (execution.exitCode != 0) {
            return mapExitCode(
                execution.exitCode, execution.stderr,
                if (move) "无法移动目录" else "无法复制目录", operation,
            )
        }
        return reconcileDirectoryResult(
            expectedSource, sourceIdentity.value, targetPath, targetName, sourceDirectory,
            sourceParent.value, targetDirectory, targetParent.value, move, execution.stdout,
        )
    }

    private suspend fun reconcileDirectoryResult(
        expectedSource: RootPath,
        sourceIdentity: RootFileIdentity,
        targetPath: RootPath,
        targetName: EntryName,
        sourceDirectory: RootPath,
        sourceParent: PreparedTransferDirectory,
        targetDirectory: RootPath,
        targetParent: PreparedTransferDirectory,
        move: Boolean,
        stdout: List<String>,
    ): OperationResult<DirectoryEntry> {
        val publishedIdentity = RootFileIdentity.parse(stdout).getOrElse {
            return if (move) uncertainDirectoryMove("Directory helper returned malformed identity")
            else uncertainDirectoryCopy("Directory helper returned malformed identity")
        }
        val target = stat(targetPath)
        val targetIdentity = readIdentity(targetPath)
        val sourceAfter = stat(expectedSource)
        val sourceIdentityAfter = if (sourceAfter is OperationResult.Success) readIdentity(expectedSource) else null
        val sourceParentAfter = canonicalize(sourceDirectory)
        val targetParentAfter = canonicalize(targetDirectory)
        val sourceMatches = sourceAfter is OperationResult.Success &&
            sourceAfter.value.type == com.iamxpp.isaver.domain.EntryType.DIRECTORY &&
            !sourceAfter.value.symbolicLink &&
            sourceIdentityAfter is OperationResult.Success && sourceIdentityAfter.value == sourceIdentity
        val sourceExpected = if (move) {
            sourceAfter is OperationResult.Failure && sourceAfter.code == ErrorCode.NOT_FOUND
        } else {
            sourceMatches
        }
        if (
            target !is OperationResult.Success || target.value.name != targetName.value ||
            target.value.type != com.iamxpp.isaver.domain.EntryType.DIRECTORY || target.value.symbolicLink ||
            targetIdentity !is OperationResult.Success || targetIdentity.value != publishedIdentity ||
            !sourceExpected || sourceParentAfter !is OperationResult.Success ||
            sourceParentAfter.value != sourceParent.canonical ||
            targetParentAfter !is OperationResult.Success || targetParentAfter.value != targetParent.canonical
        ) {
            return if (move) uncertainDirectoryMove("Directory move could not be reconciled")
            else uncertainDirectoryCopy("Directory copy could not be reconciled")
        }
        return target
    }

    private fun canonicalDescendant(source: RootPath, target: RootPath): Boolean {
        val prefix = source.value.trimEnd('/') + "/"
        return target == source || target.value.startsWith(prefix)
    }

    private suspend fun prepareStage(
        directory:PreparedTransferDirectory,
        stageName:String,
        uncertainResult: (String) -> OperationResult.Failure,
    ):OperationResult<TransferStage>{
        val command=transferHelper.prepare(
            directory.original.value,directory.canonical.value,stageName,directory.identity,
        )
        val result=runHelperBounded(command,helperOperationTimeoutMillis).getOrElse{
            return uncertainResult("Prepare-stage exceeded its bounded deadline or lost its result")
        }
        if(result.exitCode!=0)return mapExitCode(result.exitCode,result.stderr,"无法准备目标目录","prepare-stage")
        return RootFileIdentity.parse(result.stdout).fold(
            onSuccess={OperationResult.Success(TransferStage(stageName,it))},
            onFailure={uncertainResult("Malformed prepare-stage identity")},
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
        uncertainResult: (String) -> OperationResult.Failure,
    ):OperationResult.Failure = withContext(NonCancellable){
        val finalPath=EntryName.join(directory.canonical,finalName)
        val finalState=stat(finalPath)
        if(finalState is OperationResult.Failure&&finalState.code==ErrorCode.NOT_FOUND){
            cleanupStage(directory,stage)
        }
        uncertainResult(reason)
    }

    private suspend fun prepareWritableDirectory(original:RootPath):OperationResult<PreparedTransferDirectory> =
        prepareDirectory(original, requireWritable = true)

    private suspend fun prepareReadableDirectory(original:RootPath):OperationResult<PreparedTransferDirectory> =
        prepareDirectory(original, requireWritable = false)

    private suspend fun prepareDirectory(
        original: RootPath,
        requireWritable: Boolean,
    ):OperationResult<PreparedTransferDirectory>{
        val role = if (requireWritable) "目标目录" else "来源目录"
        if(original.value.length>1&&original.value.endsWith('/'))return failure(ErrorCode.COMMAND_FAILED,"${role}路径无效","Trailing slash is not accepted for secure transfer")
        val first=stat(original);if(first !is OperationResult.Success)return first as OperationResult.Failure
        if(first.value.symbolicLink)return failure(ErrorCode.COMMAND_FAILED,"${role}不能是符号链接","Parent symlink")
        if(first.value.type!=com.iamxpp.isaver.domain.EntryType.DIRECTORY)return failure(ErrorCode.NOT_DIRECTORY,"路径不是目录","Parent was not directory")
        if(!first.value.readable)return failure(ErrorCode.NOT_READABLE,"目录不可读","Parent not readable")
        if(requireWritable&&!first.value.writable)return failure(ErrorCode.NOT_WRITABLE,"目录不可写","Parent not writable")
        val canonical=canonicalize(original);if(canonical !is OperationResult.Success)return canonical as OperationResult.Failure
        if(canonical.value.value.length>1&&canonical.value.value.endsWith('/'))return failure(ErrorCode.COMMAND_FAILED,"${role}路径无效","Canonical path had trailing slash")
        val canonicalStat=stat(canonical.value);if(canonicalStat !is OperationResult.Success)return canonicalStat as OperationResult.Failure
        if(canonicalStat.value.symbolicLink||canonicalStat.value.type!=com.iamxpp.isaver.domain.EntryType.DIRECTORY)return failure(ErrorCode.COMMAND_FAILED,"${role}无效","Canonical parent was not a plain directory")
        if(!canonicalStat.value.readable)return failure(ErrorCode.NOT_READABLE,"目录不可读","Canonical parent not readable")
        if(requireWritable&&!canonicalStat.value.writable)return failure(ErrorCode.NOT_WRITABLE,"目录不可写","Canonical parent not writable")
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
            EXIT_PARENT_INVALID -> failure(ErrorCode.COMMAND_FAILED, "目标目录已变化，请重新打开后再试", "Parent directory identity changed")
            EXIT_STAGE_INVALID -> failure(
                ErrorCode.COMMAND_FAILED,
                if (operation == "prepare-stage") "目标目录临时文件受系统限制，请换个文件夹再试" else failureMessage,
                "Stage directory did not pass safety checks",
            )
            54 -> failure(
                ErrorCode.SOURCE_UNREADABLE,
                if (operation.isDirectoryOperation()) "无法读取来源目录" else "无法读取来源文件",
                "Source identity or contents changed",
            )
            56 -> failure(
                ErrorCode.SOURCE_UNREADABLE,
                if (operation.isDirectoryOperation()) "无法读取来源目录" else "无法读取来源文件",
                "Source could not be read",
            )
            55 -> when (operation) {
                "rename-noreplace" -> uncertainRename("Native helper reported an uncertain rename outcome")
                "create-file-noreplace" -> uncertainCreateFile("Native helper reported an uncertain create-file outcome")
                "move-directory-noreplace", "move-directory-cross-device-noreplace" ->
                    uncertainDirectoryMove("Native helper reported an uncertain directory move outcome")
                "copy-directory-publish" ->
                    uncertainDirectoryCopy("Native helper reported an uncertain directory copy outcome")
                else -> failure(ErrorCode.OUTCOME_UNCERTAIN, "保存结果不确定，请刷新确认", "Native helper reported an uncertain outcome")
            }
            58 -> failure(ErrorCode.CROSS_DEVICE, "暂不支持跨存储移动", "Move crossed a file-system boundary")
            59 -> failure(
                ErrorCode.MOVE_PARTIAL,
                if (operation.isDirectoryOperation()) "目录已复制，但来源未完整删除" else "文件已复制，但来源未删除",
                "Move target was published but source removal failed",
            )
            137 -> when (operation) {
                "rename-noreplace" -> uncertainRename("Native rename helper was killed after timeout")
                "create-file-noreplace" -> uncertainCreateFile("Native create-file helper was killed after timeout")
                "move-directory-noreplace", "move-directory-cross-device-noreplace" ->
                    uncertainDirectoryMove("Native directory move helper was killed after timeout")
                "copy-directory-publish" ->
                    uncertainDirectoryCopy("Native directory copy helper was killed after timeout")
                else -> failure(ErrorCode.OUTCOME_UNCERTAIN, "保存结果不确定，请刷新确认", "Native helper was killed after timeout")
            }
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

    private fun String.isDirectoryOperation(): Boolean = contains("directory")

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
            if [ -e "${'$'}target" ] || [ -L "${'$'}target" ]; then
              emit_isaver_record "${'$'}target"
            else
              printf '%s\n' '$STAT_NOT_FOUND_MARKER'
            fi
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
        const val DIRECTORY_OPERATION_TIMEOUT_MILLIS = 300_000L
        const val MIN_STREAM_TRANSFER_TIMEOUT_MILLIS = 30_000L
        const val TRANSFER_TIMEOUT_BYTES_PER_SECOND = 2L * 1024L * 1024L
        const val EXIT_NOT_FOUND = 44
        const val STAT_NOT_FOUND_MARKER = "ISAVER_STAT_V1_NOT_FOUND"
        const val EXIT_NOT_DIRECTORY = 45
        const val EXIT_NOT_READABLE = 46
        const val EXIT_NATIVE_IO = 51
        const val EXIT_PARENT_INVALID = 52
        const val EXIT_STAGE_INVALID = 53
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
private fun uncertainMove(technical:String)=failure(ErrorCode.OUTCOME_UNCERTAIN,"移动结果不确定，请刷新来源和目标目录核对",technical)
private fun invalidMoveSource(technical:String="Move source was not a stable readable regular file")=failure(ErrorCode.SOURCE_UNREADABLE,"无法移动此文件",technical)
private fun uncertainDirectoryMove(technical:String)=failure(
    ErrorCode.OUTCOME_UNCERTAIN, "目录移动结果不确定，请刷新来源和目标目录核对", technical,
)
private fun uncertainDirectoryCopy(technical:String)=failure(
    ErrorCode.OUTCOME_UNCERTAIN, "目录复制结果不确定，请刷新目标目录核对", technical,
)
private fun invalidDirectorySource(technical:String="Directory source was not a stable readable directory")=failure(
    ErrorCode.SOURCE_UNREADABLE, "无法读取来源目录", technical,
)
private fun uncertainRename(technical:String)=failure(ErrorCode.OUTCOME_UNCERTAIN,"重命名结果不确定，请刷新目录核对",technical)
private fun uncertainCreateFile(technical:String)=failure(ErrorCode.OUTCOME_UNCERTAIN,"新建文件结果不确定，请刷新目录核对",technical)
private fun invalidRenameSource(technical:String="Rename source was not a stable readable regular file")=failure(ErrorCode.SOURCE_UNREADABLE,"无法重命名此文件",technical)
private fun uncertainCopy(technical:String)=failure(ErrorCode.OUTCOME_UNCERTAIN,"复制结果不确定，请刷新目标目录核对",technical)
private fun invalidCopySource(technical:String="Copy source was not a stable readable regular file")=failure(ErrorCode.SOURCE_UNREADABLE,"无法复制此文件",technical)

private fun failure(code: ErrorCode, userMessage: String, technicalMessage: String) =
    OperationResult.Failure(code, userMessage, technicalMessage)
