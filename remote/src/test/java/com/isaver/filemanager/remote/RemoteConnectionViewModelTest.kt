package com.isaver.filemanager.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConnectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun storesSecretAndReportsConnectedWithoutExposingPassword() = runTest(dispatcher) {
        val credentials = InMemoryCredentialStore()
        var connectedProfile: RemoteProfile? = null
        val connector = RemoteConnector { profile ->
            connectedProfile = profile
            Result.success(FakeRemoteSession())
        }
        val viewModel = RemoteConnectionViewModel(credentials, connector, dispatcher)

        viewModel.connect(
            RemoteConnectionDraft(
                protocol = RemoteProtocol.SFTP,
                host = "example.test",
                port = 22,
                username = "user",
                password = "secret",
                fingerprint = RemoteFingerprint.sha256("key".toByteArray()),
            ),
        )
        testScheduler.advanceUntilIdle()

        val connected = viewModel.state.value as RemoteConnectionUiState.Connected
        assertEquals("example.test", connected.host)
        assertEquals("远程.txt", connected.entries.single().name)
        val ref = connectedProfile!!.secretRef
        assertEquals("secret", credentials.get(ref))
        assertEquals(false, ref.contains("secret"))
    }
}

private class FakeRemoteSession : RemoteSession {
    override suspend fun list(path: RemotePath) = Result.success(
        listOf(
            RemoteEntry(
                path = RemotePath.parse("/远程.txt").getOrThrow(),
                name = "远程.txt",
                directory = false,
                sizeBytes = 7,
                modifiedAtEpochMillis = null,
            ),
        ),
    )
    override suspend fun createDirectory(path: RemotePath) = Result.success(Unit)
    override fun upload(request: RemoteTransferRequest): Flow<TransferProgress> = emptyFlow()
    override fun download(request: RemoteDownloadRequest): Flow<TransferProgress> = emptyFlow()
    override fun close() = Unit
}
