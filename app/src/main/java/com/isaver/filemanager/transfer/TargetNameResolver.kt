package com.isaver.filemanager.transfer

import com.isaver.filemanager.domain.EntryName
import com.isaver.filemanager.domain.EntryNameValidationException

sealed class TargetNameException(message: String) : IllegalArgumentException(message) {
    class InvalidName : TargetNameException("Invalid target name")
    class InvalidAttempt : TargetNameException("Attempt must not be negative")
    class AttemptsExhausted : TargetNameException("Target name attempts exhausted")
    class NameTooLong : TargetNameException("Generated target name exceeds 255 UTF-8 bytes")
}

class TargetNameResolver(val maxAttempts: Int = 100) {
    init { require(maxAttempts > 0) }

    fun resolve(draft: OutputNameDraft, attempt: Int): Result<EntryName> {
        if (attempt < 0) return Result.failure(TargetNameException.InvalidAttempt())
        if (attempt >= maxAttempts) return Result.failure(TargetNameException.AttemptsExhausted())
        val candidate = if (attempt == 0) {
            draft
        } else {
            draft.copy(stem = "${draft.stem} ($attempt)")
        }
        return candidate.toEntryName().fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(mapValidation(it)) },
        )
    }

    private fun mapValidation(error: Throwable?): TargetNameException =
        if (error is EntryNameValidationException.TooLong) TargetNameException.NameTooLong()
        else TargetNameException.InvalidName()
}
