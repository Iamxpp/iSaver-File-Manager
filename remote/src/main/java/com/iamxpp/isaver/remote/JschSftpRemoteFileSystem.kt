package com.iamxpp.isaver.remote

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.util.Vector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class JschSftpRemoteFileSystem(
    private val credentialStore: CredentialStore,
) : RemoteFileSystem {
    override suspend fun connect(profile: RemoteProfile): Result<RemoteSession> = runCatching {
        RemoteSecurityPolicy.validate(profile).getOrThrow()
        val secret = credentialStore.get(profile.secretRef) ?: error("远程凭据不可用")
        val jsch = JSch()
        val session = jsch.getSession(profile.username, profile.host, profile.port).apply {
            setPassword(secret)
            hostKeyRepository = PinnedHostKeyRepository(profile.hostKeyFingerprint!!)
            setConfig("StrictHostKeyChecking", "yes")
            connect(CONNECT_TIMEOUT_MILLIS.toInt())
        }
        val channel = (session.openChannel("sftp") as ChannelSftp).apply { connect(CONNECT_TIMEOUT_MILLIS.toInt()) }
        JschSftpRemoteSession(session, channel)
    }

    private companion object { const val CONNECT_TIMEOUT_MILLIS = 10_000L }
}

private class JschSftpRemoteSession(
    private val session: Session,
    private val channel: ChannelSftp,
) : RemoteSession {
    override suspend fun list(path: RemotePath): Result<List<RemoteEntry>> = runCatching {
        withContext(Dispatchers.IO) {
            @Suppress("UNCHECKED_CAST")
            (channel.ls(path.value) as Vector<ChannelSftp.LsEntry>).map { entry ->
                val attrs = entry.attrs
                RemoteEntry(
                    path = RemotePath.parse(join(path.value, entry.filename)).getOrThrow(),
                    name = entry.filename,
                    directory = attrs.isDir,
                    sizeBytes = attrs.takeIf { !it.isDir }?.size,
                    modifiedAtEpochMillis = attrs.mTime.toLong() * 1_000L,
                )
            }
        }
    }

    override suspend fun createDirectory(path: RemotePath): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { channel.mkdir(path.value) }
    }

    override fun upload(request: RemoteTransferRequest): Flow<TransferProgress> = flow {
        emit(TransferProgress(0L, request.totalBytes))
        withContext(Dispatchers.IO) {
            request.source.use { input ->
                channel.put(input, request.target.value)
            }
        }
        emit(TransferProgress(request.totalBytes ?: 1L, request.totalBytes))
    }

    override fun download(request: RemoteDownloadRequest): Flow<TransferProgress> = flow {
        emit(TransferProgress(0L, null))
        withContext(Dispatchers.IO) {
            request.sink.use { output ->
                channel.get(request.source.value, output)
            }
        }
        emit(TransferProgress(1L, null))
    }

    override fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }

    private fun join(parent: String, name: String): String =
        if (parent == "/") "/$name" else "${parent.trimEnd('/')}/$name"
}

private class PinnedHostKeyRepository(private val expected: String) : HostKeyRepository {
    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        return if (RemoteFingerprint.matches(expected, key)) {
            HostKeyRepository.OK
        } else {
            HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: HostKey?, userinfo: UserInfo?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun getKnownHostsRepositoryID(): String = "iSaver-pinned"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}
