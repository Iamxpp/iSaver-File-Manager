package com.iamxpp.isaver.remote

import com.iamxpp.isaver.domain.EntryName
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

sealed interface RemoteTransferEvent {
    data object Preparing : RemoteTransferEvent
    data class Progress(val value: TransferProgress) : RemoteTransferEvent
    data class Completed(val file: File) : RemoteTransferEvent
    data class Failed(val message: String) : RemoteTransferEvent
}

class RemoteTransferRepository(
    private val cacheDirectory: File,
) {
    fun downloadToCache(
        session: RemoteSession,
        source: RemotePath,
        outputName: String,
    ): Flow<RemoteTransferEvent> = flow {
        emit(RemoteTransferEvent.Preparing)
        val entryName = EntryName.parse(outputName).getOrElse {
            emit(RemoteTransferEvent.Failed("远程文件名无效"))
            return@flow
        }
        check(cacheDirectory.exists() || cacheDirectory.mkdirs())
        val temporary = File(cacheDirectory, ".isaver-remote-${UUID.randomUUID()}.tmp")
        val finalFile = nextAvailable(File(cacheDirectory, entryName.value))
        try {
            temporary.outputStream().buffered().use { sink ->
                session.download(RemoteDownloadRequest(source, sink)).collect { progress ->
                    emit(RemoteTransferEvent.Progress(progress))
                }
            }
            check(temporary.renameTo(finalFile)) { "远程下载临时文件发布失败" }
            emit(RemoteTransferEvent.Completed(finalFile))
        } catch (error: Exception) {
            temporary.delete()
            emit(RemoteTransferEvent.Failed(error.message ?: "远程下载失败"))
        }
    }

    private fun nextAvailable(base: File): File {
        if (!base.exists()) return base
        val stem = base.nameWithoutExtension
        val extension = base.extension.takeIf(String::isNotEmpty)?.let { ".${it}" }.orEmpty()
        var index = 1
        while (true) {
            val candidate = File(base.parentFile, "$stem ($index)$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }
}
