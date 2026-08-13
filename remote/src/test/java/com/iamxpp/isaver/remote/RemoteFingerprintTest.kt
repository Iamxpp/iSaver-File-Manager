package com.iamxpp.isaver.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFingerprintTest {
    @Test
    fun sha256FingerprintUsesOpenSshStyleBase64WithoutPadding() {
        assertEquals(
            "SHA256:ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0",
            RemoteFingerprint.sha256("abc".toByteArray()),
        )
    }

    @Test
    fun matchAcceptsCaseAndPrefixButRejectsDifferentMaterial() {
        val actual = RemoteFingerprint.sha256("server-key".toByteArray())
        assertTrue(RemoteFingerprint.matches(actual.replace("SHA256:", "sha256:"), "server-key".toByteArray()))
        assertTrue(RemoteFingerprint.matches(actual.removePrefix("SHA256:"), "server-key".toByteArray()))
        assertFalse(RemoteFingerprint.matches(actual, "other-key".toByteArray()))
    }
}
