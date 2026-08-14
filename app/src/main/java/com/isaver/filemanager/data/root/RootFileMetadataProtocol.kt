package com.isaver.filemanager.data.root

import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult

data class RootFileMetadata(
    val mode: Int,
    val uid: Long,
    val gid: Long,
    val device: Long,
    val inode: Long,
)

internal object RootFileMetadataProtocol {
    fun parse(lines: List<String>): OperationResult<RootFileMetadata> = try {
        require(lines.size == 1)
        val fields = lines.single().split('\t')
        require(fields.size == 6 && fields[0] == PROTOCOL)
        val metadata = RootFileMetadata(
            fields[1].toInt(), fields[2].toLong(), fields[3].toLong(),
            fields[4].toLong(), fields[5].toLong(),
        )
        require(metadata.mode in 0..0xFFF)
        require(metadata.uid >= 0 && metadata.gid >= 0 && metadata.device >= 0 && metadata.inode >= 0)
        OperationResult.Success(metadata)
    } catch (_: IllegalArgumentException) {
        OperationResult.Failure(
            ErrorCode.SOURCE_UNREADABLE, "无法读取文件属性", "Malformed root file metadata protocol",
        )
    }

    private const val PROTOCOL = "ISAVER_META_V1"
}
