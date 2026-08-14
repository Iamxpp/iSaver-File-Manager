package com.isaver.filemanager.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSecurityPolicyTest {
    @Test
    fun remotePathRejectsNulAndPreservesAbsoluteRemotePath() {
        assertTrue(RemotePath.parse("/incoming/docs").isSuccess)
        assertTrue(RemotePath.parse("/incoming\u0000docs").isFailure)
    }

    @Test
    fun sftpRequiresPinnedHostKeyFingerprint() {
        val result = RemoteSecurityPolicy.validate(
            profile(
                protocol = RemoteProtocol.SFTP,
                hostKeyFingerprint = null,
            ),
        )
        assertEquals(RemoteSecurityError.HOST_KEY_PIN_REQUIRED, result.exceptionOrNull()?.let { it as RemoteSecurityException }?.code)
    }

    @Test
    fun ftpsRequiresCertificateFingerprint() {
        val result = RemoteSecurityPolicy.validate(
            profile(
                protocol = RemoteProtocol.FTPS,
                certificateFingerprint = null,
            ),
        )
        assertEquals(RemoteSecurityError.CERTIFICATE_PIN_REQUIRED, result.exceptionOrNull()?.let { it as RemoteSecurityException }?.code)
    }

    @Test
    fun ftpRequiresExplicitPlaintextAcknowledgement() {
        val result = RemoteSecurityPolicy.validate(
            profile(
                protocol = RemoteProtocol.FTP,
                allowPlaintext = false,
            ),
        )
        assertEquals(RemoteSecurityError.PLAINTEXT_FTP_NOT_ACKNOWLEDGED, result.exceptionOrNull()?.let { it as RemoteSecurityException }?.code)
    }

    private fun profile(
        protocol: RemoteProtocol,
        hostKeyFingerprint: String? = "SHA256:host",
        certificateFingerprint: String? = "SHA256:cert",
        allowPlaintext: Boolean = true,
    ) = RemoteProfile(
        id = "test",
        protocol = protocol,
        host = "example.test",
        port = protocol.defaultPort,
        username = "user",
        secretRef = "secret",
        remoteRoot = RemotePath.parse("/").getOrThrow(),
        hostKeyFingerprint = hostKeyFingerprint,
        certificateFingerprint = certificateFingerprint,
        allowPlaintext = allowPlaintext,
    )
}
