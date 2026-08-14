package com.isaver.filemanager.transfer

import android.content.ContentResolver
import com.isaver.filemanager.data.root.AppCachePath
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.isaver.filemanager.share.IncomingShare
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class CachedIncomingFile(val file: File, val sizeBytes: Long, val appCachePath:AppCachePath)

sealed interface IncomingFileCacheResult {
    data class Success(val file: CachedIncomingFile) : IncomingFileCacheResult
    data class Failure(val reason: IncomingFileCacheFailure) : IncomingFileCacheResult
}

enum class IncomingFileCacheFailure { SOURCE_UNREADABLE, CACHE_WRITE_FAILED, NO_SPACE, SIZE_MISMATCH }

class IncomingFileCache internal constructor(
    private val resolver: ContentResolver,
    cacheDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val openInput: (IncomingShare) -> InputStream? = { resolver.openInputStream(it.uri) },
    private val openOutput: (File) -> OutputStream = { it.outputStream().buffered() },
) {
    private val incomingDir = File(cacheDir, "incoming")

    suspend fun cache(share: IncomingShare, onProgress: (Long) -> Unit): IncomingFileCacheResult =
        withContext(ioDispatcher) {
            val target = File(incomingDir, "${UUID.randomUUID()}.tmp")
            try {
                if (!incomingDir.exists() && !incomingDir.mkdirs()) throw IOException("cannot create cache directory")
                val input = try { openInput(share) } catch (_: SecurityException) { return@withContext failure(IncomingFileCacheFailure.SOURCE_UNREADABLE) }
                    catch (_: IOException) { return@withContext failure(IncomingFileCacheFailure.SOURCE_UNREADABLE) }
                    ?: return@withContext failure(IncomingFileCacheFailure.SOURCE_UNREADABLE)
                var copied = 0L
                input.use { source ->
                    openOutput(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = try {
                                source.read(buffer)
                            } catch (error: IOException) {
                                throw SourceReadException(error)
                            }
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            copied += count
                            onProgress(copied)
                        }
                    }
                }
                if (share.sizeBytes != null && copied != share.sizeBytes) {
                    target.delete(); failure(IncomingFileCacheFailure.SIZE_MISMATCH)
                } else IncomingFileCacheResult.Success(CachedIncomingFile(target, copied,AppCachePath.fromIncomingCacheFile(requireNotNull(incomingDir.parentFile),target).getOrThrow()))
            } catch (cancelled: CancellationException) {
                target.delete(); throw cancelled
            } catch (_: SourceReadException) {
                target.delete(); failure(IncomingFileCacheFailure.SOURCE_UNREADABLE)
            } catch (error: IOException) {
                target.delete()
                val noSpace = generateSequence<Throwable>(error) { it.cause }
                    .any { it is ErrnoException && it.errno == OsConstants.ENOSPC }
                failure(if (noSpace) IncomingFileCacheFailure.NO_SPACE else IncomingFileCacheFailure.CACHE_WRITE_FAILED)
            } catch (_: SecurityException) {
                target.delete(); failure(IncomingFileCacheFailure.SOURCE_UNREADABLE)
            }
        }

    suspend fun cleanup(cached: CachedIncomingFile): Boolean = withContext(NonCancellable + ioDispatcher) {
        val root = incomingDir.canonicalFile
        val candidate = cached.file.canonicalFile
        if (candidate.parentFile != root) return@withContext false
        !candidate.exists() || candidate.delete()
    }

    internal fun validateNow(cached: CachedIncomingFile): Boolean {
        val candidate = cached.file
        val incoming = try {
            incomingDir.canonicalFile
        } catch (_: IOException) {
            return false
        }
        val canonical = try {
            candidate.canonicalFile
        } catch (_: IOException) {
            return false
        }
        if (canonical.parentFile != incoming || !candidate.exists()) return false
        val identity = try {
            Os.lstat(candidate.path)
        } catch (_: Exception) {
            return false
        }
        return OsConstants.S_ISREG(identity.st_mode) &&
            identity.st_dev == cached.appCachePath.device &&
            identity.st_ino == cached.appCachePath.inode &&
            identity.st_size == cached.sizeBytes
    }

    suspend fun validate(cached: CachedIncomingFile): Boolean = withContext(ioDispatcher) {
        validateNow(cached)
    }

    suspend fun cleanupOrphans(
        nowMillis: Long,
        owned: Set<AppCachePath> = emptySet(),
    ): Int = withContext(NonCancellable + ioDispatcher) {
        val cutoff = nowMillis - ORPHAN_TTL_MILLIS
        var removed = 0
        incomingDir.listFiles().orEmpty().forEach { candidate ->
            if (!INCOMING_NAME.matches(candidate.name) || candidate.lastModified() > cutoff) return@forEach
            val identity = try {
                Os.lstat(candidate.path)
            } catch (_: Exception) {
                return@forEach
            }
            if (!OsConstants.S_ISREG(identity.st_mode)) return@forEach
            val isOwned = owned.any { owner ->
                owner.value == candidate.canonicalPath &&
                    owner.device == identity.st_dev &&
                    owner.inode == identity.st_ino
            }
            if (!isOwned && candidate.delete()) removed += 1
        }
        removed
    }

    private fun failure(reason: IncomingFileCacheFailure) = IncomingFileCacheResult.Failure(reason)

    private class SourceReadException(cause: IOException) : RuntimeException(cause)

    companion object {
        const val ORPHAN_TTL_MILLIS = 24L * 60L * 60L * 1_000L
        private val INCOMING_NAME = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
                "[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\.tmp",
        )
    }
}
