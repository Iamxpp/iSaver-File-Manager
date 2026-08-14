package com.isaver.filemanager.data.root

@ConsistentCopyVisibility
data class RootTransferSource internal constructor(
    val contentUri: String,
    val expectedSizeBytes: Long,
    internal val token: String,
)
