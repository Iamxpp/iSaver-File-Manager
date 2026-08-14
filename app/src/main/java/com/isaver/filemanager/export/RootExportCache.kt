package com.isaver.filemanager.export

import android.system.Os
import android.system.OsConstants
import com.isaver.filemanager.data.root.RootFileSystem
import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class ExportFileIdentity(
    val device: Long,
    val inode: Long,
    val sizeBytes: Long,
    val regularFile: Boolean,
)

class RootExportCache internal constructor(
    private val rootFileSystem: RootFileSystem,
    cacheDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val uuidFactory: () -> UUID = UUID::randomUUID,
    private val identityOf: (File) -> ExportFileIdentity = ::readIdentity,
    private val atomicMove: (File, File) -> Unit = ::moveAtomically,
) {
    private val exportDir = File(cacheDir, EXPORT_DIRECTORY)

    suspend fun cache(entry: DirectoryEntry, mimeType: String): OperationResult<CachedExportFile> =
        withContext(ioDispatcher) {
            if (entry.type != EntryType.FILE || entry.symbolicLink || !entry.readable) {
                return@withContext sourceUnreadable()
            }
            if (!ensureExportDirectory()) return@withContext cacheFailure()

            val uuid = uuidFactory().toString()
            val stage = File(exportDir, "$uuid.tmp")
            val target = File(exportDir, "$uuid.export")
            try {
                if (!stage.createNewFile()) return@withContext cacheFailure()
                val copied = FileOutputStream(stage).use { output ->
                    val result = rootFileSystem.copyToOutput(entry.path, output)
                    output.fd.sync()
                    result
                }
                when (copied) {
                    is OperationResult.Failure -> {
                        discardStage(stage)
                        copied
                    }
                    is OperationResult.Success -> {
                        if (copied.value < 0L || stage.length() != copied.value) {
                            discardStage(stage)
                            cacheFailure()
                        } else {
                            atomicMove(stage, target)
                            val identity = identityOf(target)
                            if (!identity.regularFile || identity.sizeBytes != copied.value) {
                                discardStage(target)
                                cacheFailure()
                            } else {
                                OperationResult.Success(
                                    CachedExportFile(
                                        file = target,
                                        sizeBytes = copied.value,
                                        device = identity.device,
                                        inode = identity.inode,
                                        displayName = entry.name,
                                        mimeType = mimeType,
                                    ),
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                discardStage(stage)
                discardStage(target)
                throw cancelled
            } catch (_: IOException) {
                discardStage(stage)
                discardStage(target)
                cacheFailure()
            } catch (_: SecurityException) {
                discardStage(stage)
                discardStage(target)
                cacheFailure()
            } catch (_: Exception) {
                discardStage(stage)
                discardStage(target)
                cacheFailure()
            }
        }

    suspend fun cacheLocalFile(
        source: File,
        displayName: String,
        mimeType: String,
    ): OperationResult<CachedExportFile> = withContext(ioDispatcher) {
        if (Files.isSymbolicLink(source.toPath())) return@withContext sourceUnreadable()
        val sourceFile = try { source.canonicalFile } catch (_: IOException) { return@withContext cacheFailure() }
        if (!sourceFile.isFile || !ensureExportDirectory()) return@withContext cacheFailure()
        val uuid = uuidFactory().toString()
        val stage = File(exportDir, "$uuid.tmp")
        val target = File(exportDir, "$uuid.export")
        try {
            if (!stage.createNewFile()) return@withContext cacheFailure()
            sourceFile.inputStream().use { input ->
                stage.outputStream().use { output -> input.copyTo(output) }
            }
            val sourceSize = sourceFile.length()
            val identity = identityOf(stage)
            if (!identity.regularFile || identity.sizeBytes != sourceSize) {
                discardStage(stage)
                return@withContext cacheFailure()
            }
            atomicMove(stage, target)
            val targetIdentity = identityOf(target)
            if (!targetIdentity.regularFile || targetIdentity.sizeBytes != sourceSize) {
                discardStage(target)
                return@withContext cacheFailure()
            }
            OperationResult.Success(
                CachedExportFile(
                    file = target,
                    sizeBytes = targetIdentity.sizeBytes,
                    device = targetIdentity.device,
                    inode = targetIdentity.inode,
                    displayName = displayName,
                    mimeType = mimeType,
                ),
            )
        } catch (cancelled: CancellationException) {
            discardStage(stage)
            discardStage(target)
            throw cancelled
        } catch (_: Exception) {
            discardStage(stage)
            discardStage(target)
            cacheFailure()
        }
    }

    fun validateNow(cached: CachedExportFile): Boolean {
        val candidate = try {
            cached.file.canonicalFile
        } catch (_: IOException) {
            return false
        }
        val directory = try {
            exportDir.canonicalFile
        } catch (_: IOException) {
            return false
        }
        if (candidate.parentFile != directory || !EXPORT_NAME.matches(candidate.name)) return false
        return try {
            val identity = identityOf(candidate)
            identity.regularFile &&
                identity.device == cached.device &&
                identity.inode == cached.inode &&
                identity.sizeBytes == cached.sizeBytes
        } catch (_: Exception) {
            false
        }
    }

    suspend fun readPrefix(
        cached: CachedExportFile,
        maxBytes: Int = MAX_MIME_HEADER_BYTES,
    ): ByteArray? = withContext(ioDispatcher) {
        if (maxBytes !in 1..MAX_MIME_HEADER_BYTES || !validateNow(cached)) return@withContext null
        try {
            cached.file.inputStream().use { input ->
                val buffer = ByteArray(maxBytes)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    if (read == 0) continue
                    count += read
                }
                buffer.copyOf(count)
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun discardNow(cached: CachedExportFile) {
        if (!belongsToExportDirectory(cached.file)) return
        cached.file.delete()
    }

    suspend fun cleanupOrphans(nowMillis: Long): Int = withContext(NonCancellable + ioDispatcher) {
        val cutoff = nowMillis - ORPHAN_TTL_MILLIS
        exportDir.listFiles().orEmpty().count { candidate ->
            if (!CACHE_NAME.matches(candidate.name) || candidate.lastModified() > cutoff) return@count false
            val identity = try {
                identityOf(candidate)
            } catch (_: Exception) {
                return@count false
            }
            identity.regularFile && candidate.delete()
        }
    }

    private fun ensureExportDirectory(): Boolean =
        (exportDir.exists() || exportDir.mkdirs()) && exportDir.isDirectory

    private fun belongsToExportDirectory(file: File): Boolean = try {
        val directory = exportDir.canonicalFile
        val candidate = file.canonicalFile
        candidate.parentFile == directory && CACHE_NAME.matches(candidate.name)
    } catch (_: IOException) {
        false
    }

    private fun discardStage(file: File) {
        if (belongsToExportDirectory(file)) file.delete()
    }

    private fun sourceUnreadable(): OperationResult.Failure = OperationResult.Failure(
        ErrorCode.SOURCE_UNREADABLE,
        "无法读取来源文件",
    )

    private fun cacheFailure(): OperationResult.Failure = OperationResult.Failure(
        ErrorCode.COMMAND_FAILED,
        "无法准备文件缓存",
    )

    private companion object {
        const val EXPORT_DIRECTORY = "export"
        const val ORPHAN_TTL_MILLIS = 24L * 60L * 60L * 1_000L
        const val MAX_MIME_HEADER_BYTES = 64
        const val UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
                "[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
        val EXPORT_NAME = Regex("$UUID_PATTERN\\.export")
        val CACHE_NAME = Regex("$UUID_PATTERN\\.(tmp|export)")

        fun readIdentity(file: File): ExportFileIdentity {
            val status = Os.lstat(file.path)
            return ExportFileIdentity(
                device = status.st_dev,
                inode = status.st_ino,
                sizeBytes = status.st_size,
                regularFile = OsConstants.S_ISREG(status.st_mode),
            )
        }

        fun moveAtomically(source: File, target: File) {
            try {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                throw IOException("Atomic cache move is unavailable")
            }
        }
    }
}
