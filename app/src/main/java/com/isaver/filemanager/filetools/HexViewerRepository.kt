package com.isaver.filemanager.filetools

import com.isaver.filemanager.data.root.RootFileChunk
import com.isaver.filemanager.data.root.RootFileSystem
import com.isaver.filemanager.data.root.RootFileVersion
import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult

data class HexRow(
    val offset: Long,
    val offsetLabel: String,
    val hex: String,
    val ascii: String,
    val byteCount: Int,
)

data class HexPage(
    val offset: Long,
    val totalSizeBytes: Long,
    val rows: List<HexRow>,
    val version: RootFileVersion,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

object HexFormatter {
    fun rows(startOffset: Long, bytes: ByteArray): List<HexRow> =
        bytes.asList().chunked(BYTES_PER_ROW).mapIndexed { index, values ->
            val offset = startOffset + index * BYTES_PER_ROW
            HexRow(
                offset = offset,
                offsetLabel = offset.toHexOffset(),
                hex = values.joinToString(" ") { "%02X".format(it.toInt() and 0xff) },
                ascii = values.joinToString("") { byte ->
                    val value = byte.toInt() and 0xff
                    if (value in 0x20..0x7e) value.toChar().toString() else "."
                },
                byteCount = values.size,
            )
        }

    private fun Long.toHexOffset(): String =
        if (this <= 0xffff_ffffL) "%08X".format(this) else "%016X".format(this)

    const val BYTES_PER_ROW = 16
}

class HexViewerRepository internal constructor(
    private val readRange: suspend (DirectoryEntry, Long, Long) -> OperationResult<RootFileChunk>,
    pageSizeBytes: Int,
) {
    val pageSizeBytes = checkedPageSize(pageSizeBytes)

    constructor(fileSystem: RootFileSystem, pageSizeBytes: Int = DEFAULT_PAGE_BYTES) : this(
        readRange = { entry: DirectoryEntry, offset: Long, count: Long ->
            fileSystem.readRange(entry.path, offset, count)
        },
        pageSizeBytes = pageSizeBytes,
    )

    suspend fun loadPage(
        entry: DirectoryEntry,
        requestedOffset: Long,
        expectedVersion: RootFileVersion? = null,
    ): OperationResult<HexPage> {
        if (!entry.isReadableFile()) return unreadable()
        if (requestedOffset < 0) return invalidOffset()
        val size = entry.sizeBytes ?: return unreadable()
        val lastPageOffset = if (size == 0L) 0L else ((size - 1) / pageSizeBytes) * pageSizeBytes
        val offset = minOf(requestedOffset / pageSizeBytes * pageSizeBytes, lastPageOffset)
        val count = minOf(pageSizeBytes.toLong(), size - offset)
        return when (val read = readRange(entry, offset, count)) {
            is OperationResult.Failure -> read
            is OperationResult.Success -> {
                val chunk = read.value
                if (chunk.bytes.size.toLong() != count || chunk.version.sizeBytes != size ||
                    expectedVersion != null && chunk.version != expectedVersion
                ) changed()
                else OperationResult.Success(
                    HexPage(
                        offset = offset,
                        totalSizeBytes = size,
                        rows = HexFormatter.rows(offset, chunk.bytes),
                        version = chunk.version,
                        hasPrevious = offset > 0,
                        hasNext = offset + count < size,
                    ),
                )
            }
        }
    }

    private fun DirectoryEntry.isReadableFile() =
        type == EntryType.FILE && readable && !symbolicLink && sizeBytes != null

    private fun unreadable() = OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, "无法以 Hex 查看此项目")
    private fun invalidOffset() = OperationResult.Failure(ErrorCode.COMMAND_FAILED, "偏移量无效")
    private fun changed() = OperationResult.Failure(ErrorCode.OUTCOME_UNCERTAIN, "文件已发生变化，请重新加载")

    companion object {
        const val DEFAULT_PAGE_BYTES = 4 * 1024
        private fun checkedPageSize(value: Int): Int = value.also {
            require(it > 0 && it <= com.isaver.filemanager.data.root.RootFileRangeProtocol.MAX_RANGE_BYTES)
            require(it % HexFormatter.BYTES_PER_ROW == 0)
        }
    }
}
