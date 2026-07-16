package com.iamxpp.isaver.archive

import com.iamxpp.isaver.data.root.AppCachePath
import com.iamxpp.isaver.data.root.RootFileSystem
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

class ArchiveRepository(
    private val rootFileSystem: RootFileSystem,
    private val localEngine: LocalArchiveEngine,
    private val cacheDir: File,
    private val publish: (CachedIncomingFile, OutputNameDraft, RootPath) -> Flow<TransferState>,
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
                is TransferState.Success -> send(
                    ArchiveState.Success(summary.format, summary.entryCount, summary.expandedBytes),
                )
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
        try {
            val summary = localEngine.extract(sourceCache.file, localDestination) { progress ->
                send(ArchiveState.Running(progress))
            }.getOrElse {
                send(failure(ErrorCode.COMMAND_FAILED, "无法解压文件"))
                return@channelFlow
            }
            val files = localDestination.walkTopDown().filter(File::isFile).toList()
            for ((index, file) in files.withIndex()) {
                val relative = file.relativeTo(localDestination).invariantSeparatorsPath
                val parentComponents = relative.substringBeforeLast('/', "")
                    .split('/')
                    .filter(String::isNotEmpty)
                val parent = when (val ensured = ensureDirectories(targetDirectory, parentComponents)) {
                    is OperationResult.Failure -> {
                        send(failure(ensured.code, ensured.userMessage))
                        return@channelFlow
                    }
                    is OperationResult.Success -> ensured.value
                }
                val incoming = newIncomingFile()
                file.copyTo(incoming, overwrite = false)
                val cached = cachedFactory(incoming).getOrElse {
                    incoming.delete()
                    send(failure(ErrorCode.COMMAND_FAILED, "无法准备解压文件缓存"))
                    return@channelFlow
                }
                send(ArchiveState.Publishing(relative))
                val terminal = publish(
                    cached,
                    OutputNameDraft.fromDisplayName(file.name),
                    parent,
                ).lastTerminal()
                when (terminal) {
                    is TransferState.Success -> incoming.delete()
                    is TransferState.Failure -> {
                        if (terminal.code != ErrorCode.OUTCOME_UNCERTAIN) incoming.delete()
                        send(failure(terminal.code, terminal.message))
                        return@channelFlow
                    }
                    else -> {
                        incoming.delete()
                        send(failure(ErrorCode.COMMAND_FAILED, "解压文件发布失败"))
                        return@channelFlow
                    }
                }
                send(ArchiveState.Running(ArchiveProgress.Publishing(index.toLong() + 1L, files.size.toLong())))
            }
            send(ArchiveState.Success(summary.format, summary.entryCount, summary.expandedBytes))
        } finally {
            sourceCache.file.delete()
            localDestination.deleteRecursively()
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

    private suspend fun ensureDirectories(
        target: RootPath,
        components: List<String>,
    ): OperationResult<RootPath> {
        var current = target
        for (component in components) {
            val name = FolderName.parse(component).getOrElse {
                return OperationResult.Failure(ErrorCode.COMMAND_FAILED, "压缩包目录名称无效")
            }
            val child = RootPath.parse("${current.value.trimEnd('/')}/${name.value}").getOrThrow()
            when (val existing = rootFileSystem.stat(child)) {
                is OperationResult.Success -> {
                    if (existing.value.type != EntryType.DIRECTORY || existing.value.symbolicLink) {
                        return OperationResult.Failure(ErrorCode.NOT_DIRECTORY, "解压目标路径不是文件夹")
                    }
                }
                is OperationResult.Failure -> {
                    if (existing.code != ErrorCode.NOT_FOUND) return existing
                    when (val created = rootFileSystem.createDirectory(current, name)) {
                        is OperationResult.Failure -> return created
                        is OperationResult.Success -> Unit
                    }
                }
            }
            current = child
        }
        return OperationResult.Success(current)
    }

    private fun newIncomingFile(): File {
        check(incomingDir.exists() || incomingDir.mkdirs())
        return File(incomingDir, "${UUID.randomUUID()}.tmp")
    }

    private suspend fun Flow<TransferState>.lastTerminal(): TransferState? {
        var terminal: TransferState? = null
        collect { terminal = it }
        return terminal
    }

    private fun failure(code: ErrorCode, message: String) = ArchiveState.Failure(code, message)
}
