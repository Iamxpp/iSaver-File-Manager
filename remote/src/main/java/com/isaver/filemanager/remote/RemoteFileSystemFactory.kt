package com.isaver.filemanager.remote

class RemoteFileSystemFactory(
    private val credentialStore: CredentialStore,
) : RemoteConnector {
    fun adapterFor(protocol: RemoteProtocol): RemoteFileSystem = when (protocol) {
        RemoteProtocol.FTP, RemoteProtocol.FTPS -> CommonsNetRemoteFileSystem(credentialStore)
        RemoteProtocol.SFTP -> JschSftpRemoteFileSystem(credentialStore)
    }

    override suspend fun connect(profile: RemoteProfile): Result<RemoteSession> =
        RemoteSecurityPolicy.validate(profile).fold(
            onSuccess = { adapterFor(profile.protocol).connect(profile) },
            onFailure = { Result.failure(it) },
        )
}
