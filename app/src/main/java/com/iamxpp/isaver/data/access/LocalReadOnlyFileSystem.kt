package com.iamxpp.isaver.data.access

import com.iamxpp.isaver.data.root.DirectorySnapshot
import com.iamxpp.isaver.data.root.ExtractionStage
import com.iamxpp.isaver.data.root.RootFileChunk
import com.iamxpp.isaver.data.root.RootFileMetadata
import com.iamxpp.isaver.data.root.RootFileRangeProtocol
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.data.root.RootFileVersion
import com.iamxpp.isaver.data.root.RootTransferSource
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootEntryIdentity
import com.iamxpp.isaver.domain.RootPath
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class LocalReadOnlyFileSystem(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RootFileSystem {
    override suspend fun readDirectory(path: RootPath): OperationResult<DirectorySnapshot> = io {
        val localPath = path.localPath()
        val parentAttributes = attributes(localPath)
        if (!parentAttributes.isDirectory || Files.isSymbolicLink(localPath)) {
            return@io failure(ErrorCode.NOT_DIRECTORY, "路径不是目录")
        }
        if (!Files.isReadable(localPath)) return@io failure(ErrorCode.NOT_READABLE, "目录不可读")
        val parentIdentity = identityOf(localPath, parentAttributes)
        val entries = Files.newDirectoryStream(localPath).use { stream ->
            buildList {
                stream.forEach { child ->
                    coroutineContext.ensureActive()
                    add(entry(child))
                }
            }
        }
        OperationResult.Success(
            DirectorySnapshot(
                parentDevice = parentIdentity.device,
                parentInode = parentIdentity.inode,
                parentReadable = true,
                parentWritable = false,
                entries = entries,
            ),
        )
    }

    override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> = io {
        entry(path.localPath())
            .let { OperationResult.Success(it) }
    }

    override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> = io {
        OperationResult.Success(pathOf(path.localPath().toRealPath()))
    }

    override suspend fun identity(path: RootPath): OperationResult<RootEntryIdentity> = io {
        val localPath = path.localPath()
        OperationResult.Success(identityOf(localPath, attributes(localPath)))
    }

    override suspend fun copyToOutput(source: RootPath, output: OutputStream): OperationResult<Long> = io {
        val path = readableFile(source)
        var copied = 0L
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                copied += count
            }
        }
        OperationResult.Success(copied)
    }

    override suspend fun readRange(source: RootPath, offset: Long, count: Long): OperationResult<RootFileChunk> = io {
        if (offset < 0 || count < 0 || count > RootFileRangeProtocol.MAX_RANGE_BYTES || count > Int.MAX_VALUE) {
            return@io failure(ErrorCode.SOURCE_UNREADABLE, "无法读取来源文件")
        }
        val path = readableFile(source)
        val before = version(path)
        if (offset > before.sizeBytes || count > before.sizeBytes - offset) {
            return@io failure(ErrorCode.SOURCE_UNREADABLE, "读取范围超出文件大小")
        }
        val bytes = ByteArray(count.toInt())
        RandomAccessFile(path.toFile(), "r").use { file ->
            file.seek(offset)
            file.readFully(bytes)
        }
        val after = version(path)
        if (after != before) return@io failure(ErrorCode.OUTCOME_UNCERTAIN, "文件已发生变化")
        OperationResult.Success(RootFileChunk(bytes, before))
    }

    override suspend fun metadata(source: RootPath): OperationResult<RootFileMetadata> = io {
        val path = source.localPath()
        val attrs = attributes(path)
        val identity = identityOf(path, attrs)
        val unix = runCatching {
            Files.readAttributes(path, "unix:mode,uid,gid", NOFOLLOW_LINKS)
        }.getOrNull()
        OperationResult.Success(
            RootFileMetadata(
                mode = ((unix?.get("mode") as? Number)?.toInt() ?: if (attrs.isDirectory) 0x16D else 0x124) and 0xFFF,
                uid = (unix?.get("uid") as? Number)?.toLong()?.coerceAtLeast(0) ?: 0,
                gid = (unix?.get("gid") as? Number)?.toLong()?.coerceAtLeast(0) ?: 0,
                device = identity.device,
                inode = identity.inode,
            ),
        )
    }

    override suspend fun createDirectory(parent: RootPath, name: FolderName): OperationResult<DirectoryEntry> = readOnly()
    override suspend fun createFileNoReplace(parent: RootPath, name: EntryName): OperationResult<DirectoryEntry> = readOnly()
    override suspend fun changeMode(source: DirectoryEntry, sourceDirectory: RootPath, expectedMetadata: RootFileMetadata, mode: Int): OperationResult<RootFileMetadata> = readOnly()
    override suspend fun transferFromStream(source: RootTransferSource, targetDirectory: RootPath, finalName: EntryName): OperationResult<DirectoryEntry> = readOnly()
    override suspend fun replaceFileAtomically(source: DirectoryEntry, sourceDirectory: RootPath, expectedVersion: RootFileVersion, content: RootTransferSource): OperationResult<DirectoryEntry> = readOnly()
    override suspend fun moveFileNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath): OperationResult<DirectoryEntry> = readOnly()
    override suspend fun moveFileAsNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath, targetName: EntryName): OperationResult<DirectoryEntry> = readOnly()
    override suspend fun moveDirectoryAsNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath, targetName: EntryName): OperationResult<DirectoryEntry> = readOnly()
    override suspend fun moveEntryAsNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath, targetName: EntryName): OperationResult<DirectoryEntry> = readOnly()
    override suspend fun renameFileNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetName: EntryName): OperationResult<DirectoryEntry> = readOnly()
    override suspend fun renameEntryNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetName: EntryName): OperationResult<DirectoryEntry> = readOnly()
    override suspend fun copyFileNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath): OperationResult<DirectoryEntry> = readOnly()
    override suspend fun copyFileAsNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath, targetName: EntryName): OperationResult<DirectoryEntry> = readOnly()
    override suspend fun copyDirectoryAsNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath, targetName: EntryName): OperationResult<DirectoryEntry> = readOnly()
    override suspend fun copyEntryAsNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath, targetName: EntryName): OperationResult<DirectoryEntry> = readOnly()
    override suspend fun prepareExtractionStage(parent: RootPath): OperationResult<ExtractionStage> = readOnly()
    override suspend fun createExtractionDirectory(stage: ExtractionStage, relativePath: String): OperationResult<Unit> = readOnly()
    override suspend fun transferIntoExtractionStage(stage: ExtractionStage, relativeParent: String, source: RootTransferSource, finalName: EntryName): OperationResult<Unit> = readOnly()
    override suspend fun commitExtractionStage(stage: ExtractionStage, finalName: FolderName): OperationResult<DirectoryEntry> = readOnly()
    override suspend fun cleanupExtractionStage(stage: ExtractionStage): OperationResult<Unit> = readOnly()
    override suspend fun deleteEntryPermanently(source: DirectoryEntry, sourceDirectory: RootPath): OperationResult<Unit> = readOnly()

    private suspend fun <T> io(block: suspend () -> OperationResult<T>): OperationResult<T> = withContext(ioDispatcher) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: NoSuchFileException) {
            failure(ErrorCode.NOT_FOUND, "路径不存在")
        } catch (_: NotDirectoryException) {
            failure(ErrorCode.NOT_DIRECTORY, "路径不是目录")
        } catch (_: AccessDeniedException) {
            failure(ErrorCode.NOT_READABLE, "没有权限读取此路径")
        } catch (_: SecurityException) {
            failure(ErrorCode.NOT_READABLE, "没有权限读取此路径")
        } catch (_: Exception) {
            failure(ErrorCode.COMMAND_FAILED, "无法读取此路径")
        }
    }

    private fun entry(path: Path): DirectoryEntry {
        val attrs = attributes(path)
        val symbolicLink = Files.isSymbolicLink(path)
        val type = when {
            symbolicLink -> EntryType.OTHER
            attrs.isDirectory -> EntryType.DIRECTORY
            attrs.isRegularFile -> EntryType.FILE
            else -> EntryType.OTHER
        }
        return DirectoryEntry(
            path = pathOf(path),
            name = path.fileName?.toString() ?: "/",
            type = type,
            sizeBytes = attrs.size().takeIf { type == EntryType.FILE },
            modifiedAtEpochSeconds = attrs.lastModifiedTime().to(TimeUnit.SECONDS),
            readable = Files.isReadable(path),
            writable = false,
            symbolicLink = symbolicLink,
        )
    }

    private fun readableFile(source: RootPath): Path {
        val path = source.localPath()
        val attrs = attributes(path)
        if (!attrs.isRegularFile || Files.isSymbolicLink(path) || !Files.isReadable(path)) {
            throw AccessDeniedException(path.toString())
        }
        return path
    }

    private fun attributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    private fun identityOf(path: Path, attrs: BasicFileAttributes): RootEntryIdentity {
        val fileKey = attrs.fileKey()?.toString() ?: path.toAbsolutePath().normalize().toString()
        val inode = stablePositiveHash(fileKey)
        val device = stablePositiveHash(runCatching { Files.getFileStore(path).name() }.getOrDefault("local"))
        return RootEntryIdentity(device, inode)
    }

    private fun version(path: Path): RootFileVersion {
        val attrs = attributes(path)
        val identity = identityOf(path, attrs)
        val modifiedMillis = attrs.lastModifiedTime().toMillis()
        val modifiedSeconds = Math.floorDiv(modifiedMillis, 1_000L)
        val modifiedNanoseconds = Math.floorMod(modifiedMillis, 1_000L) * 1_000_000L
        return RootFileVersion(
            sizeBytes = attrs.size(),
            device = identity.device,
            inode = identity.inode,
            modifiedSeconds = modifiedSeconds,
            modifiedNanoseconds = modifiedNanoseconds,
            changedSeconds = modifiedSeconds,
            changedNanoseconds = modifiedNanoseconds,
        )
    }

    private fun RootPath.localPath(): Path {
        val raw = if (WINDOWS_DRIVE_PATH.matches(value)) value.drop(1) else value
        return Paths.get(raw)
    }

    private fun pathOf(path: Path): RootPath {
        val normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/')
        val absolute = if (normalized.startsWith('/')) normalized else "/$normalized"
        return RootPath.parse(absolute).getOrThrow()
    }

    private fun stablePositiveHash(value: String): Long =
        value.fold(1L) { hash, character -> (hash * 31 + character.code) and Long.MAX_VALUE }

    private fun <T> readOnly(): OperationResult<T> = failure(
        ErrorCode.NOT_WRITABLE,
        "非 Root 模式仅支持只读浏览",
    )

    private fun failure(code: ErrorCode, message: String): OperationResult.Failure =
        OperationResult.Failure(code, message, "Local read-only file access failed")

    private companion object {
        val WINDOWS_DRIVE_PATH = Regex("^/[A-Za-z]:/.*")
    }
}
