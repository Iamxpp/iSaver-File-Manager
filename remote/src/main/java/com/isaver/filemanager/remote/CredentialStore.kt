package com.isaver.filemanager.remote

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

interface CredentialStore {
    suspend fun put(reference: String, secret: String)
    suspend fun get(reference: String): String?
    suspend fun remove(reference: String)
}

class InMemoryCredentialStore : CredentialStore {
    private val values = ConcurrentHashMap<String, String>()

    override suspend fun put(reference: String, secret: String) {
        require(reference.isNotBlank())
        values[reference] = secret
    }

    override suspend fun get(reference: String): String? = values[reference]

    override suspend fun remove(reference: String) {
        values.remove(reference)
    }
}

data class CredentialEnvelope(val iv: ByteArray, val ciphertext: ByteArray) {
    companion object {
        private const val VERSION = "v1"
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        fun encode(iv: ByteArray, ciphertext: ByteArray): String =
            "$VERSION:${encoder.encodeToString(iv)}:${encoder.encodeToString(ciphertext)}"

        fun decode(value: String): Result<CredentialEnvelope> = runCatching {
            val parts = value.split(':')
            require(parts.size == 3 && parts[0] == VERSION) { "unsupported credential envelope" }
            CredentialEnvelope(decoder.decode(parts[1]), decoder.decode(parts[2]))
        }
    }
}
