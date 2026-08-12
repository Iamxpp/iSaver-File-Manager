package com.iamxpp.isaver.archive

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.export.ExternalFileGrant
import com.iamxpp.isaver.export.RootExportRepository

class DirectoryShareRepository(
    private val createArchive: suspend (List<DirectoryEntry>) -> OperationResult<java.io.File>,
    private val discardArchive: (java.io.File) -> Unit,
    private val shareLocalFile: suspend (java.io.File, String, String) -> OperationResult<ExternalFileGrant>,
) {
    constructor(
        archiveRepository: ArchiveRepository,
        exportRepository: RootExportRepository,
    ) : this(
        createArchive = archiveRepository::createZipCache,
        discardArchive = archiveRepository::discardArchiveCache,
        shareLocalFile = exportRepository::shareLocalFile,
    )

    suspend fun share(entries: List<DirectoryEntry>): OperationResult<ExternalFileGrant> {
        if (entries.isEmpty() || entries.any {
                it.type == EntryType.OTHER || !it.readable || it.symbolicLink
            }
        ) {
            return OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, "无法准备目录分享")
        }
        val archive = when (val result = createArchive(entries)) {
            is OperationResult.Failure -> return result
            is OperationResult.Success -> result.value
        }
        return try {
            shareLocalFile(archive, "iSaver-share.zip", "application/zip")
        } finally {
            discardArchive(archive)
        }
    }
}
