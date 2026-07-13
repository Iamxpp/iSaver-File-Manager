package com.iamxpp.isaver.transfer

import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.EntryNameValidationException

sealed class TargetNameException(message: String) : IllegalArgumentException(message) {
    class InvalidName : TargetNameException("Invalid target name")
    class InvalidAttempt : TargetNameException("Attempt must not be negative")
    class AttemptsExhausted : TargetNameException("Target name attempts exhausted")
    class NameTooLong : TargetNameException("Generated target name exceeds 255 UTF-8 bytes")
}

class TargetNameResolver(val maxAttempts: Int = 100) {
    init { require(maxAttempts > 0) }

    fun resolve(originalName: String, attempt: Int): Result<EntryName> {
        if (attempt < 0) return Result.failure(TargetNameException.InvalidAttempt())
        if (attempt >= maxAttempts) return Result.failure(TargetNameException.AttemptsExhausted())
        val original = EntryName.parse(originalName)
        if (original.isFailure) return Result.failure(mapValidation(original.exceptionOrNull()))
        if (attempt == 0) return original
        val split = extensionStart(originalName)
        val candidate = if (split == null) "$originalName ($attempt)"
        else originalName.substring(0, split) + " ($attempt)" + originalName.substring(split)
        return EntryName.parse(candidate).fold({ Result.success(it) }, { Result.failure(mapValidation(it)) })
    }

    private fun extensionStart(name: String): Int? {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) null else dot
    }

    private fun mapValidation(error: Throwable?): TargetNameException =
        if (error is EntryNameValidationException.TooLong) TargetNameException.NameTooLong()
        else TargetNameException.InvalidName()
}
