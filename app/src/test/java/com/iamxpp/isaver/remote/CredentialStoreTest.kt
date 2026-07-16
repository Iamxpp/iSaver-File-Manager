package com.iamxpp.isaver.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialStoreTest {
    @Test
    fun inMemoryStoreReplacesAndRemovesSecrets() = runBlocking {
        val store = InMemoryCredentialStore()
        assertNull(store.get("missing"))
        store.put("server", "secret-1")
        assertEquals("secret-1", store.get("server"))
        store.put("server", "secret-2")
        assertEquals("secret-2", store.get("server"))
        store.remove("server")
        assertNull(store.get("server"))
    }

    @Test
    fun credentialEnvelopeIsVersionedAndRejectsMalformedValues() {
        val encoded = CredentialEnvelope.encode(byteArrayOf(1, 2), byteArrayOf(3, 4))
        assertTrue(encoded.startsWith("v1:"))
        val decoded = CredentialEnvelope.decode(encoded).getOrThrow()
        assertEquals(listOf<Byte>(1, 2), decoded.iv.toList())
        assertEquals(listOf<Byte>(3, 4), decoded.ciphertext.toList())
        assertTrue(CredentialEnvelope.decode("v2:bad").isFailure)
    }
}
