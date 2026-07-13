package com.iamxpp.isaver.domain

import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed class EntryNameValidationException(message: String) : IllegalArgumentException(message) {
    class InvalidUnicode : EntryNameValidationException("Entry name is not well-formed Unicode")
    class TooLong : EntryNameValidationException("Entry name exceeds 255 UTF-8 bytes")
}

/** One legal Linux directory-entry name, preserved byte-for-byte as UTF-8 text. */
@JvmInline
value class EntryName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Result<EntryName> = runCatching {
            require(raw.isNotEmpty() && raw.isNotBlank() && raw != "." && raw != "..")
            require('/' !in raw && '\u0000' !in raw)
            val encoded = try {
                StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(raw))
            } catch (_: CharacterCodingException) {
                throw EntryNameValidationException.InvalidUnicode()
            }
            if (encoded.remaining() > 255) throw EntryNameValidationException.TooLong()
            EntryName(raw)
        }
        fun join(parent: RootPath, name: EntryName): RootPath {
            val value = if (parent.value == "/") "/${name.value}" else "${parent.value.trimEnd('/')}/${name.value}"
            return RootPath.parse(value).getOrThrow()
        }
    }
}
