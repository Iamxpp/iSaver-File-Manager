package com.iamxpp.isaver.remote

import java.security.MessageDigest
import java.util.Base64

object RemoteFingerprint {
    fun sha256(material: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(material)
        return "SHA256:${Base64.getUrlEncoder().withoutPadding().encodeToString(digest)}"
    }

    fun matches(expected: String, material: ByteArray): Boolean {
        val normalized = expected.trim().replace(Regex("^sha256:", RegexOption.IGNORE_CASE), "")
        val actual = sha256(material).removePrefix("SHA256:")
        return MessageDigest.isEqual(normalized.toByteArray(), actual.toByteArray())
    }
}
