package com.iamxpp.isaver.remote

import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** One valid UTF-8 basename used by remote protocols. */
@JvmInline
value class RemoteEntryName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Result<RemoteEntryName> = runCatching {
            require(raw.isNotEmpty() && raw.isNotBlank() && raw != "." && raw != "..")
            require('/' !in raw && '\u0000' !in raw)
            val encoded = try {
                StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(raw))
            } catch (error: CharacterCodingException) {
                throw IllegalArgumentException("Remote entry name is not well-formed Unicode", error)
            }
            require(encoded.remaining() <= 255) { "Remote entry name exceeds 255 UTF-8 bytes" }
            RemoteEntryName(raw)
        }
    }
}
