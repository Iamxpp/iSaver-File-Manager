package com.isaver.filemanager.remote

import java.time.Duration
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient

class CommonsNetRemoteFileSystem(
    private val credentialStore: CredentialStore,
) : RemoteFileSystem {
    override suspend fun connect(profile: RemoteProfile): Result<RemoteSession> = runCatching {
        RemoteSecurityPolicy.validate(profile).getOrThrow()
        val secret = credentialStore.get(profile.secretRef)
            ?: error("远程凭据不可用")
        val client = if (profile.protocol == RemoteProtocol.FTPS) {
            FTPSClient(false).apply {
                setEndpointCheckingEnabled(true)
                setTrustManager(PinnedX509TrustManager(profile.certificateFingerprint!!))
            }
        } else {
            FTPClient()
        }
        client.connectTimeout = CONNECT_TIMEOUT_MILLIS.toInt()
        client.defaultTimeout = CONNECT_TIMEOUT_MILLIS.toInt()
        client.dataTimeout = Duration.ofMillis(CONNECT_TIMEOUT_MILLIS)
        client.connect(profile.host, profile.port)
        check(client.login(profile.username, secret)) { "远程登录失败" }
        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
        if (client is FTPSClient) {
            client.execPBSZ(0)
            client.execPROT("P")
        }
        CommonsNetRemoteSession(client)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
    }
}

private class PinnedX509TrustManager(
    private val expectedFingerprint: String,
    private val delegate: X509TrustManager = defaultTrustManager(),
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        delegate.checkServerTrusted(chain, authType)
        val leaf = chain?.firstOrNull() ?: throw CertificateException("FTPS 服务器未提供证书")
        if (!RemoteFingerprint.matches(expectedFingerprint, leaf.encoded)) {
            throw CertificateException("FTPS 服务器证书指纹不匹配")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    private companion object {
        fun defaultTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(null as KeyStore?)
            }
            return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        }
    }
}

private class CommonsNetRemoteSession(
    private val client: FTPClient,
) : RemoteSession {
    override suspend fun list(path: RemotePath): Result<List<RemoteEntry>> = runCatching {
        withContext(Dispatchers.IO) {
            client.listFiles(path.value).map { file ->
                RemoteEntry(
                    path = RemotePath.parse(join(path.value, file.name)).getOrThrow(),
                    name = file.name,
                    directory = file.isDirectory,
                    sizeBytes = file.takeIf { it.isFile }?.size,
                    modifiedAtEpochMillis = file.timestamp?.timeInMillis,
                )
            }
        }
    }

    override suspend fun createDirectory(path: RemotePath): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { check(client.makeDirectory(path.value)) { "远程新建文件夹失败" } }
    }

    override fun upload(request: RemoteTransferRequest): Flow<TransferProgress> = flow {
        emit(TransferProgress(0L, request.totalBytes))
        withContext(Dispatchers.IO) {
            request.source.use { input ->
                check(client.storeFile(request.target.value, input)) { "远程上传失败" }
            }
        }
        emit(TransferProgress(request.totalBytes ?: 1L, request.totalBytes))
    }

    override fun download(request: RemoteDownloadRequest): Flow<TransferProgress> = flow {
        emit(TransferProgress(0L, null))
        withContext(Dispatchers.IO) {
            request.sink.use { output ->
                check(client.retrieveFile(request.source.value, output)) { "远程下载失败" }
            }
        }
        emit(TransferProgress(1L, null))
    }

    override fun close() {
        runCatching { if (client.isConnected) client.logout() }
        runCatching { if (client.isConnected) client.disconnect() }
    }

    private companion object {
        fun join(parent: String, name: String): String =
            if (parent == "/") "/$name" else "${parent.trimEnd('/')}/$name"
    }
}
