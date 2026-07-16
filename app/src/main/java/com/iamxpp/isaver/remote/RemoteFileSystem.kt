package com.iamxpp.isaver.remote

import java.io.Closeable
import kotlinx.coroutines.flow.Flow

interface RemoteFileSystem {
    suspend fun connect(profile: RemoteProfile): Result<RemoteSession>
}

interface RemoteSession : Closeable {
    suspend fun list(path: RemotePath): Result<List<RemoteEntry>>
    suspend fun createDirectory(path: RemotePath): Result<Unit>
    fun upload(request: RemoteTransferRequest): Flow<TransferProgress>
    fun download(request: RemoteDownloadRequest): Flow<TransferProgress>
}
