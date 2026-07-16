package com.iamxpp.isaver.archive

enum class ArchiveFormat {
    ZIP,
    TAR,
    TAR_GZ,
    SEVEN_Z,
    RAR,
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
