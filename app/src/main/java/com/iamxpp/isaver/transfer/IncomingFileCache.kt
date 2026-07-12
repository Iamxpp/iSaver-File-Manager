package com.iamxpp.isaver.transfer

import android.content.ContentResolver
import com.iamxpp.isaver.share.IncomingShare
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class CachedIncomingFile(val file: File, val sizeBytes: Long)

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
) {
    private val incomingDir = File(cacheDir, "incoming")

    suspend fun cache(share: IncomingShare, onProgress: (Long) -> Unit): IncomingFileCacheResult =
        withContext(ioDispatcher) {
            val target = File(incomingDir, "${UUID.randomUUID()}.tmp")
            try {
                if (!incomingDir.exists() && !incomingDir.mkdirs()) throw IOException("cannot create cache directory")
                val input = try { openInput(share) } catch (_: SecurityException) { return@withContext failure(IncomingFileCacheFailure.SOURCE_UNREADABLE) }
                    catch (_: FileNotFoundException) { return@withContext failure(IncomingFileCacheFailure.SOURCE_UNREADABLE) }
                    ?: return@withContext failure(IncomingFileCacheFailure.SOURCE_UNREADABLE)
                var copied = 0L
                input.use { source ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = source.read(buffer)
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
                } else IncomingFileCacheResult.Success(CachedIncomingFile(target, copied))
            } catch (cancelled: CancellationException) {
                target.delete(); throw cancelled
            } catch (error: IOException) {
                target.delete()
                val noSpace = generateSequence<Throwable>(error) { it.cause }
                    .any { it.message?.let { message -> message.contains("space", true) || message.contains("full", true) } == true }
                failure(if (noSpace) IncomingFileCacheFailure.NO_SPACE else IncomingFileCacheFailure.CACHE_WRITE_FAILED)
            } catch (_: SecurityException) {
                target.delete(); failure(IncomingFileCacheFailure.SOURCE_UNREADABLE)
            }
        }

    suspend fun cleanup(cached: CachedIncomingFile): Boolean = withContext(ioDispatcher) {
        val root = incomingDir.canonicalFile
        val candidate = cached.file.canonicalFile
        if (candidate.parentFile != root) return@withContext false
        !candidate.exists() || candidate.delete()
    }

    private fun failure(reason: IncomingFileCacheFailure) = IncomingFileCacheResult.Failure(reason)
}
