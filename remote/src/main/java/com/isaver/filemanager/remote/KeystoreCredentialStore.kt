package com.isaver.filemanager.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class KeystoreCredentialStore(
    context: Context,
    private val alias: String = "iSaver.remote.credentials.v1",
) : CredentialStore {
    private val preferences = context.getSharedPreferences("remote_credentials", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    override suspend fun put(reference: String, secret: String) {
        require(reference.isNotBlank())
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString(reference, CredentialEnvelope.encode(iv, encrypted)).commit())
    }

    override suspend fun get(reference: String): String? {
        val encoded = preferences.getString(reference, null) ?: return null
        val envelope = CredentialEnvelope.decode(encoded).getOrElse { return null }
        return runCatching {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, envelope.iv))
            }.doFinal(envelope.ciphertext).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun remove(reference: String) {
        preferences.edit().remove(reference).apply()
    }

    private fun key() = (KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        .getKey(alias, null) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
        init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
    }.generateKey())

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
