package com.iamxpp.isaver.remote

import java.io.InputStream
import java.io.OutputStream

enum class RemoteProtocol(val defaultPort: Int) {
    SFTP(22),
    FTPS(21),
    FTP(21),
}

@JvmInline
value class RemotePath private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Result<RemotePath> = runCatching {
            require(raw.isNotEmpty()) { "远程路径不能为空" }
            require('\u0000' !in raw) { "远程路径包含非法字符" }
            RemotePath(raw)
        }
    }
}

data class RemoteProfile(
    val id: String,
    val protocol: RemoteProtocol,
    val host: String,
    val port: Int,
    val username: String,
    val secretRef: String,
    val remoteRoot: RemotePath,
    val hostKeyFingerprint: String? = null,
    val certificateFingerprint: String? = null,
    val allowPlaintext: Boolean = false,
)

data class RemoteEntry(
    val path: RemotePath,
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long?,
    val modifiedAtEpochMillis: Long?,
)

data class TransferProgress(
    val transferredBytes: Long,
    val totalBytes: Long?,
)

data class RemoteTransferRequest(
    val source: InputStream,
    val target: RemotePath,
    val totalBytes: Long? = null,
)

data class RemoteDownloadRequest(
    val source: RemotePath,
    val sink: OutputStream,
)
