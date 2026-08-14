package com.isaver.filemanager.archive

import com.isaver.filemanager.domain.DirectoryEntry
import java.io.File

enum class ArchiveFormat {
    ZIP,
    TAR,
    TAR_GZ,
    SEVEN_Z,
    RAR,

    ;

    val defaultExtension: String
        get() = when (this) {
            ZIP -> "zip"
            TAR -> "tar"
            TAR_GZ -> "tar.gz"
            SEVEN_Z -> "7z"
            RAR -> "rar"
        }

    val creationSupported: Boolean
        get() = this != RAR

    val creationLabel: String
        get() = when (this) {
            ZIP -> "ZIP"
            TAR -> "TAR"
            TAR_GZ -> "TAR.GZ"
            SEVEN_Z -> "7Z"
            RAR -> "RAR"
        }

}

data class ArchiveLimits(
    val maxEntries: Long = 100_000L,
    val maxEntryBytes: Long = 256L * 1024L * 1024L,
    val maxExpandedBytes: Long = 2L * 1024L * 1024L * 1024L,
    val maxCompressionRatio: Double = 100.0,
) {
    fun checkEntry(
        entryCount: Long,
        entryBytes: Long,
        expandedBytes: Long,
        compressedBytes: Long? = null,
    ): Result<Unit> = runCatching {
        require(entryCount in 0..maxEntries) { "archive entry count limit exceeded" }
        require(entryBytes in 0..maxEntryBytes) { "archive entry size limit exceeded" }
        require(expandedBytes in 0..maxExpandedBytes) { "archive expanded size limit exceeded" }
        if (compressedBytes != null && compressedBytes > 0L) {
            require(expandedBytes.toDouble() / compressedBytes <= maxCompressionRatio) {
                "archive compression ratio limit exceeded"
            }
        }
    }
}

data class ArchiveEntry(
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long?,
    val compressedSizeBytes: Long? = null,
)

data class LocalArchiveSource(
    val relativePath: String,
    val file: File? = null,
    val directory: Boolean = false,
    val symbolicLink: Boolean = false,
)

data class ArchiveListing(
    val format: ArchiveFormat,
    val entries: List<ArchiveEntry>,
)

data class ArchiveOperationSummary(
    val format: ArchiveFormat,
    val entryCount: Long,
    val expandedBytes: Long,
)

sealed interface ArchiveProgress {
    data object Preparing : ArchiveProgress
    data class Entry(val path: String, val completedBytes: Long, val totalBytes: Long?) : ArchiveProgress
    data class Publishing(val completedEntries: Long, val totalEntries: Long?) : ArchiveProgress
}

sealed interface ArchiveState {
    data object Preparing : ArchiveState
    data class Running(val progress: ArchiveProgress) : ArchiveState
    data class Publishing(val path: String) : ArchiveState
    data object Cleaning : ArchiveState
    data object Finalizing : ArchiveState
    data class Success(
        val output: DirectoryEntry,
        val format: ArchiveFormat,
        val entryCount: Long,
        val expandedBytes: Long,
    ) : ArchiveState
    data class Failure(val code: com.isaver.filemanager.domain.ErrorCode, val message: String) : ArchiveState
}
