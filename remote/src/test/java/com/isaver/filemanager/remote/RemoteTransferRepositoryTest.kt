package com.isaver.filemanager.remote

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTransferRepositoryTest {
    @Test
    fun downloadPublishesOnlyFinalFileAfterStreamCompletes() = runBlocking {
        val directory = Files.createTempDirectory("isaver-remote").toFile()
        try {
            val repository = RemoteTransferRepository(directory)
            val events = repository.downloadToCache(
                session = FakeSession(),
                source = RemotePath.parse("/remote/report.pdf").getOrThrow(),
                outputName = "report.pdf",
            ).toList()
            val completed = events.last() as RemoteTransferEvent.Completed
            assertEquals("payload", completed.file.readText())
            assertTrue(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
        } finally {
            directory.deleteRecursively()
        }
    }
}

private class FakeSession : RemoteSession {
    override suspend fun list(path: RemotePath) = Result.success(emptyList<RemoteEntry>())
    override suspend fun createDirectory(path: RemotePath) = Result.success(Unit)
    override fun upload(request: RemoteTransferRequest): Flow<TransferProgress> = flow { emit(TransferProgress(0, request.totalBytes)) }
    override fun download(request: RemoteDownloadRequest): Flow<TransferProgress> = flow {
        request.sink.write("payload".toByteArray())
        emit(TransferProgress(7, 7))
    }
    override fun close() = Unit
}
