package com.iamxpp.isaver.archive

import com.iamxpp.isaver.data.root.AppCachePath
import com.iamxpp.isaver.data.root.ExtractionStage
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.data.root.RootTransferSource
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.transfer.CachedIncomingFile
import com.iamxpp.isaver.transfer.OutputNameDraft
import com.iamxpp.isaver.transfer.TransferState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ArchiveRepository(
    private val rootFileSystem: RootFileSystem,
    private val localEngine: LocalArchiveEngine,
    private val cacheDir: File,
    private val publish: (CachedIncomingFile, OutputNameDraft, RootPath) -> Flow<TransferState>,
    private val issueSource: (CachedIncomingFile) -> OperationResult<RootTransferSource> = {
        OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, "无法准备解压文件流")
    },
    private val revokeSource: (RootTransferSource) -> Unit = {},
    private val recordCompressed: suspend (DirectoryEntry) -> Unit = {},
    private val recordExtracted: suspend (DirectoryEntry) -> Unit = {},
    private val cachedFactory: (File) -> Result<CachedIncomingFile> = { file ->
        runCatching {
            CachedIncomingFile(
                file = file,
                sizeBytes = file.length(),
                appCachePath = AppCachePath.fromIncomingCacheFile(cacheDir, file).getOrThrow(),
            )
        }
    },
) {
    private val incomingDir = File(cacheDir, "incoming")
    private val extractionDir = File(cacheDir, "archive-extract")

    suspend fun createZipCache(sources: List<DirectoryEntry>): OperationResult<File> {
        if (sources.isEmpty()) return OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, "压缩源为空")
        val localSources = mutableListOf<LocalArchiveSource>()
        val sourceCaches = mutableListOf<File>()
        val archiveFile = newIncomingFile()
        return try {
            for (entry in sources) {
                when (val collected = collectSource(entry, entry.name, localSources, sourceCaches)) {
                    is OperationResult.Failure -> {
                        archiveFile.delete()
                        return collected
                    }
                    is OperationResult.Success -> Unit
                }
            }
            localEngine.createZip(localSources, archiveFile).fold(
                onSuccess = { OperationResult.Success(archiveFile) },
                onFailure = {
                    archiveFile.delete()
                    OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法创建分享压缩包")
                },
            )
        } catch (cancelled: CancellationException) {
            archiveFile.delete()
            throw cancelled
        } finally {
            sourceCaches.forEach(File::delete)
        }
    }

    fun discardArchiveCache(file: File) {
        val directory = runCatching { incomingDir.canonicalFile }.getOrNull() ?: return
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (candidate.parentFile == directory && ARCHIVE_CACHE_NAME.matches(candidate.name)) candidate.delete()
    }

    fun createZip(
        sources: List<DirectoryEntry>,
        targetDirectory: RootPath,
        outputName: OutputNameDraft,
    ): Flow<ArchiveState> = channelFlow {
        send(ArchiveState.Preparing)
        if (outputName.extension.lowercase() != "zip") {
            send(failure(ErrorCode.COMMAND_FAILED, "压缩文件必须使用 zip 扩展名"))
            return@channelFlow
        }
        val localSources = mutableListOf<LocalArchiveSource>()
        val sourceCaches = mutableListOf<File>()
        try {
            sources.forEach { entry ->
                when (val collected = collectSource(entry, entry.name, localSources, sourceCaches)) {
                    is OperationResult.Failure -> {
                        send(failure(collected.code, collected.userMessage))
                        return@channelFlow
                    }
                    is OperationResult.Success -> Unit
                }
            }
            val archiveFile = newIncomingFile()
            val summary = localEngine.createZip(localSources, archiveFile) { progress ->
                send(ArchiveState.Running(progress))
            }.getOrElse {
                archiveFile.delete()
                send(failure(ErrorCode.COMMAND_FAILED, "无法创建 ZIP 压缩包"))
                return@channelFlow
            }
            val cached = cachedFactory(archiveFile).getOrElse {
                archiveFile.delete()
                send(failure(ErrorCode.COMMAND_FAILED, "无法准备压缩包缓存"))
                return@channelFlow
            }
            var terminal: TransferState? = null
            publish(cached, outputName, targetDirectory).collect { state ->
                terminal = state
                when (state) {
                    is TransferState.Publishing -> send(ArchiveState.Publishing(state.candidate.value))
                    else -> Unit
                }
            }
            when (val result = terminal) {
                is TransferState.Success -> {
                    recordWithoutBlocking { recordCompressed(result.entry) }
                    send(
                        ArchiveState.Success(
                            result.entry,
                            summary.format,
                            summary.entryCount,
                            summary.expandedBytes,
                        ),
                    )
                }
                is TransferState.Failure -> {
                    if (result.code != ErrorCode.OUTCOME_UNCERTAIN) archiveFile.delete()
                    send(failure(result.code, result.message))
                }
                else -> {
                    archiveFile.delete()
                    send(failure(ErrorCode.COMMAND_FAILED, "压缩包发布失败"))
                }
            }
        } finally {
            sourceCaches.forEach(File::delete)
        }
    }

    suspend fun inspect(source: RootPath): OperationResult<ArchiveListing> {
        val cached = when (val result = cacheRootFile(source)) {
            is OperationResult.Failure -> return result
            is OperationResult.Success -> result.value
        }
        return try {
            localEngine.inspect(cached.file).fold(
                onSuccess = { OperationResult.Success(it) },
                onFailure = {
                    OperationResult.Failure(
                        ErrorCode.COMMAND_FAILED,
                        "无法读取压缩包",
                        "Archive inspection failed",
                    )
                },
            )
        } finally {
            cached.file.delete()
        }
    }

    fun extract(source: RootPath, targetDirectory: RootPath): Flow<ArchiveState> = channelFlow {
        send(ArchiveState.Preparing)
        val sourceCache = when (val result = cacheRootFile(source)) {
            is OperationResult.Failure -> {
                send(failure(result.code, result.userMessage))
                return@channelFlow
            }
            is OperationResult.Success -> result.value
        }
        val localDestination = File(extractionDir, UUID.randomUUID().toString())
        var stage: ExtractionStage? = null
        var cleanupStage = false
        var preserveUncertainStage = false
        try {
            val summary = localEngine.extract(sourceCache.file, localDestination) { progress ->
                send(ArchiveState.Running(progress))
            }.getOrElse {
                send(failure(ErrorCode.COMMAND_FAILED, "无法解压文件"))
                return@channelFlow
            }
            val activeStage = when (val prepared = rootFileSystem.prepareExtractionStage(targetDirectory)) {
                is OperationResult.Success -> prepared.value
                is OperationResult.Failure -> {
                    send(failure(prepared.code, prepared.userMessage))
                    return@channelFlow
                }
            }
            stage = activeStage
            cleanupStage = true
            val directories = localDestination.walkTopDown()
                .drop(1)
                .filter(File::isDirectory)
                .sortedWith(
                    compareBy<File>(
                        { directory ->
                            directory.relativeTo(localDestination).invariantSeparatorsPath
                                .count { character -> character == '/' }
                        },
                        { directory -> directory.relativeTo(localDestination).invariantSeparatorsPath },
                    ),
                )
                .toList()
            for (directory in directories) {
                val relative = directory.relativeTo(localDestination).invariantSeparatorsPath
                when (val created = rootFileSystem.createExtractionDirectory(activeStage, relative)) {
                    is OperationResult.Success -> Unit
                    is OperationResult.Failure -> {
                        send(failure(created.code, created.userMessage))
                        return@channelFlow
                    }
                }
            }
            val files = localDestination.walkTopDown()
                .filter(File::isFile)
                .sortedBy { file -> file.relativeTo(localDestination).invariantSeparatorsPath }
                .toList()
            for ((index, file) in files.withIndex()) {
                val relative = file.relativeTo(localDestination).invariantSeparatorsPath
                val relativeParent = relative.substringBeforeLast('/', "")
                val incoming = newIncomingFile()
                file.copyTo(incoming, overwrite = false)
                val cached = cachedFactory(incoming).getOrElse {
                    incoming.delete()
                    send(failure(ErrorCode.COMMAND_FAILED, "无法准备解压文件缓存"))
                    return@channelFlow
                }
                val transferSource = when (val issued = issueSource(cached)) {
                    is OperationResult.Success -> issued.value
                    is OperationResult.Failure -> {
                        incoming.delete()
                        send(failure(issued.code, issued.userMessage))
                        return@channelFlow
                    }
                }
                try {
                    send(ArchiveState.Running(ArchiveProgress.Publishing(index.toLong(), files.size.toLong())))
                    when (val transferred = rootFileSystem.transferIntoExtractionStage(
                        activeStage,
                        relativeParent,
                        transferSource,
                        com.iamxpp.isaver.domain.EntryName.parse(file.name).getOrElse {
                            send(failure(ErrorCode.COMMAND_FAILED, "压缩包文件名无效"))
                            return@channelFlow
                        },
                    )) {
                        is OperationResult.Success -> Unit
                        is OperationResult.Failure -> {
                            send(failure(transferred.code, transferred.userMessage))
                            return@channelFlow
                        }
                    }
                } finally {
                    revokeSource(transferSource)
                    incoming.delete()
                }
                send(
                    ArchiveState.Running(
                        ArchiveProgress.Publishing(index.toLong() + 1L, files.size.toLong()),
                    ),
                )
            }
            send(ArchiveState.Finalizing)
            val baseName = archiveDisplayName(source.value.substringAfterLast('/'))
            var output: DirectoryEntry? = null
            for (attempt in 0 until MAX_EXTRACTION_NAME_ATTEMPTS) {
                val candidate = FolderName.parse(
                    if (attempt == 0) baseName else "$baseName ($attempt)",
                ).getOrElse {
                    send(failure(ErrorCode.COMMAND_FAILED, "解压目录名称无效"))
                    return@channelFlow
                }
                when (val committed = rootFileSystem.commitExtractionStage(activeStage, candidate)) {
                    is OperationResult.Success -> {
                        output = committed.value
                        cleanupStage = false
                        break
                    }
                    is OperationResult.Failure -> when (committed.code) {
                        ErrorCode.ALREADY_EXISTS -> Unit
                        ErrorCode.OUTCOME_UNCERTAIN -> {
                            preserveUncertainStage = true
                            cleanupStage = false
                            send(failure(committed.code, committed.userMessage))
                            return@channelFlow
                        }
                        else -> {
                            send(failure(committed.code, committed.userMessage))
                            return@channelFlow
                        }
                    }
                }
            }
            if (output == null) {
                send(failure(ErrorCode.ALREADY_EXISTS, "同名解压目录过多"))
                return@channelFlow
            }
            recordWithoutBlocking { recordExtracted(output) }
            send(
                ArchiveState.Success(
                    output,
                    summary.format,
                    summary.entryCount,
                    summary.expandedBytes,
                ),
            )
        } catch (cancelled: CancellationException) {
            if (cleanupStage && !preserveUncertainStage) {
                withContext(NonCancellable) {
                    stage?.let { rootFileSystem.cleanupExtractionStage(it) }
                }
                cleanupStage = false
            }
            throw cancelled
        } finally {
            if (cleanupStage && !preserveUncertainStage) {
                withContext(NonCancellable) {
                    stage?.let { rootFileSystem.cleanupExtractionStage(it) }
                }
            }
            sourceCache.file.delete()
            localDestination.deleteRecursively()
        }
    }

    private suspend fun recordWithoutBlocking(record: suspend () -> Unit) {
        try {
            record()
        } catch (_: Exception) {
            Unit
        }
    }

    private suspend fun collectSource(
        entry: DirectoryEntry,
        relativePath: String,
        output: MutableList<LocalArchiveSource>,
        sourceCaches: MutableList<File>,
    ): OperationResult<Unit> {
        if (entry.symbolicLink || !entry.readable) {
            return OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, "压缩源不可读取")
        }
        return when (entry.type) {
            EntryType.FILE -> when (val cached = cacheRootFile(entry.path)) {
                is OperationResult.Failure -> cached
                is OperationResult.Success -> {
                    sourceCaches += cached.value.file
                    output += LocalArchiveSource(relativePath, cached.value.file)
                    OperationResult.Success(Unit)
                }
            }
            EntryType.DIRECTORY -> when (val listed = rootFileSystem.readDirectory(entry.path)) {
                is OperationResult.Failure -> listed
                is OperationResult.Success -> {
                    for (child in listed.value.entries) {
                        when (val result = collectSource(child, "$relativePath/${child.name}", output, sourceCaches)) {
                            is OperationResult.Failure -> return result
                            is OperationResult.Success -> Unit
                        }
                    }
                    OperationResult.Success(Unit)
                }
            }
            EntryType.OTHER -> OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, "不支持的压缩源类型")
        }
    }

    private suspend fun cacheRootFile(source: RootPath): OperationResult<CachedIncomingFile> {
        val target = newIncomingFile()
        return try {
            val result = target.outputStream().buffered().use { output ->
                rootFileSystem.copyToOutput(source, output)
            }
            when (result) {
                is OperationResult.Failure -> {
                    target.delete()
                    result
                }
                is OperationResult.Success -> cachedFactory(target).fold(
                    onSuccess = { OperationResult.Success(it) },
                    onFailure = {
                        target.delete()
                        OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法准备 Root 文件缓存")
                    },
                )
            }
        } catch (_: Exception) {
            target.delete()
            OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法准备 Root 文件缓存")
        }
    }

    private fun newIncomingFile(): File {
        check(incomingDir.exists() || incomingDir.mkdirs())
        return File(incomingDir, "${UUID.randomUUID()}.tmp")
    }

    private fun failure(code: ErrorCode, message: String) = ArchiveState.Failure(code, message)

    private companion object {
        const val MAX_EXTRACTION_NAME_ATTEMPTS = 100
        val ARCHIVE_CACHE_NAME = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\.tmp")
    }
}
