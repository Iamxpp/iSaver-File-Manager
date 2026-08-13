package com.iamxpp.isaver.remote

import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFileSystemFactoryTest {
    @Test
    fun selectsDedicatedAdapterForEachProtocol() {
        val factory = RemoteFileSystemFactory(InMemoryCredentialStore())
        assertTrue(factory.adapterFor(RemoteProtocol.FTP) is CommonsNetRemoteFileSystem)
        assertTrue(factory.adapterFor(RemoteProtocol.FTPS) is CommonsNetRemoteFileSystem)
        assertTrue(factory.adapterFor(RemoteProtocol.SFTP) is JschSftpRemoteFileSystem)
    }

    @Test
    fun rejectsProfileBeforeOpeningNetworkSession() {
        val factory = RemoteFileSystemFactory(InMemoryCredentialStore())
        val result = kotlinx.coroutines.runBlocking {
            factory.connect(
                RemoteProfile(
                    id = "ftp",
                    protocol = RemoteProtocol.FTP,
                    host = "host",
                    port = 21,
                    username = "user",
                    secretRef = "secret",
                    remoteRoot = RemotePath.parse("/").getOrThrow(),
                    allowPlaintext = false,
                ),
            )
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is RemoteSecurityException)
    }
}
