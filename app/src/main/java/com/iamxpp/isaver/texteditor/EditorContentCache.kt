package com.iamxpp.isaver.texteditor

import com.iamxpp.isaver.data.root.AppCachePath
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.transfer.CachedIncomingFile
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EditorContentCache(
    cacheDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val incomingDir = File(cacheDir, "incoming")

    suspend fun write(bytes: ByteArray): OperationResult<CachedIncomingFile> = withContext(ioDispatcher) {
        val target = File(incomingDir, "${UUID.randomUUID()}.tmp")
        try {
            if (!incomingDir.exists() && !incomingDir.mkdirs()) error("Cannot create incoming cache")
            FileOutputStream(target).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            val cached = CachedIncomingFile(
                target,
                bytes.size.toLong(),
                AppCachePath.fromIncomingCacheFile(requireNotNull(incomingDir.parentFile), target).getOrThrow(),
            )
            OperationResult.Success(cached)
        } catch (_: Exception) {
            target.delete()
            OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法准备编辑内容")
        }
    }

    fun discard(cached: CachedIncomingFile) {
        runCatching {
            val root = incomingDir.canonicalFile
            val candidate = cached.file.canonicalFile
            if (candidate.parentFile == root) candidate.delete()
        }
    }
}
