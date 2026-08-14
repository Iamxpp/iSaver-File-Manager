package com.isaver.filemanager.data.root

import com.isaver.filemanager.domain.DirectoryEntry

data class DirectorySnapshot(
    val parentDevice: Long,
    val parentInode: Long,
    val parentReadable: Boolean,
    val parentWritable: Boolean,
    val entries: List<DirectoryEntry>,
    val recordFailures: List<NativeDirectoryListingRecordFailure> = emptyList(),
)

data class NativeDirectoryListingRecordFailure(
    val recordIndex: Int,
    val reason: NativeDirectoryListingRecordFailureReason,
)

enum class NativeDirectoryListingRecordFailureReason {
    FIELD_TOO_LARGE,
    INVALID_BASE64,
    INVALID_BOOLEAN,
    INVALID_FIELD_COUNT,
    INVALID_NAME,
    INVALID_NUMBER,
    INVALID_PATH,
    INVALID_UTF8,
    PATH_MISMATCH,
    UNKNOWN_TYPE,
}
