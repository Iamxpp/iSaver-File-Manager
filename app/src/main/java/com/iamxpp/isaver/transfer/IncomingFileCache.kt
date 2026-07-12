package com.iamxpp.isaver.transfer

import android.content.ContentResolver
import com.iamxpp.isaver.data.root.AppCachePath
import android.system.ErrnoException
import android.system.OsConstants
import com.iamxpp.isaver.share.IncomingShare
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

    private fun failure(reason: IncomingFileCacheFailure) = IncomingFileCacheResult.Failure(reason)

    private class SourceReadException(cause: IOException) : RuntimeException(cause)
}
