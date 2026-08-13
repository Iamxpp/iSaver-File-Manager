package com.iamxpp.isaver.texteditor

import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.data.root.RootFileVersion
import com.iamxpp.isaver.data.root.RootTransferSource
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import java.io.ByteArrayOutputStream

data class LoadedTextFile(
    val entry: DirectoryEntry,
    val parent: RootPath,
    val document: TextDocument,
    val version: RootFileVersion,
)

class EditorContent(
    val source: RootTransferSource,
    private val releaseAction: () -> Unit,
) {
    fun release() = releaseAction()
}

class TextEditorRepository(
    private val fileSystem: RootFileSystem,
    private val issueContent: suspend (ByteArray) -> OperationResult<EditorContent>,
    private val chunkBytes: Int = 1024 * 1024,
) {
    suspend fun load(entry: DirectoryEntry, parent: RootPath): OperationResult<LoadedTextFile> {
        if (entry.type != EntryType.FILE || entry.symbolicLink || !entry.readable) return failure("无法读取此文本文件")
        val size = entry.sizeBytes ?: return failure("无法确认文件大小")
        if (size < 0 || size > MAX_BYTES || size > Int.MAX_VALUE) {
            return OperationResult.Failure(ErrorCode.COMMAND_FAILED, "文本编辑器最多打开 2 MiB 文件")
        }
        val output = ByteArrayOutputStream(size.toInt())
        var offset = 0L
        var version: RootFileVersion? = null
        do {
            val count = minOf(chunkBytes.toLong(), size - offset)
            val chunk = fileSystem.readRange(entry.path, offset, count)
            if (chunk !is OperationResult.Success) return chunk as OperationResult.Failure
            if (version != null && version != chunk.value.version) return failure("文件读取期间发生变化，请重试")
            version = chunk.value.version
            output.write(chunk.value.bytes)
            offset += count
        } while (offset < size)
        val stableVersion = version ?: return failure("无法确认空文件版本")
        if (stableVersion.sizeBytes != size || output.size().toLong() != size) return failure("文件读取结果不完整")
        val document = TextDocumentCodec.decode(output.toByteArray())
            ?: return OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, "无法识别文本编码")
        return OperationResult.Success(LoadedTextFile(entry, parent, document, stableVersion))
    }

    suspend fun save(loaded: LoadedTextFile, document: TextDocument): OperationResult<LoadedTextFile> {
        val bytes = TextDocumentCodec.encodeOrNull(document)
            ?: return OperationResult.Failure(ErrorCode.COMMAND_FAILED, "当前编码无法保存这些字符")
        if (bytes.size.toLong() > MAX_BYTES) {
            return OperationResult.Failure(ErrorCode.COMMAND_FAILED, "编辑后的文本超过 2 MiB 限制")
        }
        val issued = issueContent(bytes)
        if (issued !is OperationResult.Success) return issued as OperationResult.Failure
        return try {
            when (val saved = fileSystem.replaceFileAtomically(
                loaded.entry, loaded.parent, loaded.version, issued.value.source,
            )) {
                is OperationResult.Failure -> saved
                is OperationResult.Success -> load(saved.value, loaded.parent)
            }
        } finally {
            issued.value.release()
        }
    }

    private fun failure(message: String) = OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, message)

    companion object { const val MAX_BYTES = 2L * 1024L * 1024L }
}
