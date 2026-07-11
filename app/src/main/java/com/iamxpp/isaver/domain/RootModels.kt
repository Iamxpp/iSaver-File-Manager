package com.iamxpp.isaver.domain

sealed interface RootStatus {
    data object Available : RootStatus

    data class Unavailable(val reason: String) : RootStatus
}

data class DirectoryEntry(
    val path: RootPath,
    val name: String,
    val type: EntryType,
    val sizeBytes: Long?,
    val modifiedAtEpochSeconds: Long?,
    val readable: Boolean,
    val writable: Boolean,
    val symbolicLink: Boolean,
)

enum class EntryType { DIRECTORY, FILE, OTHER }

sealed interface OperationResult<out T> {
    data class Success<T>(val value: T) : OperationResult<T>

    data class Failure(
        val code: ErrorCode,
        val userMessage: String,
        val technicalMessage: String? = null,
    ) : OperationResult<Nothing>
}

enum class ErrorCode {
    ROOT_DENIED,
    ROOT_UNAVAILABLE,
    NOT_FOUND,
    NOT_DIRECTORY,
    NOT_READABLE,
    NOT_WRITABLE,
    ALREADY_EXISTS,
    NO_SPACE,
    SOURCE_UNREADABLE,
    COMMAND_FAILED,
    CANCELLED,
}
