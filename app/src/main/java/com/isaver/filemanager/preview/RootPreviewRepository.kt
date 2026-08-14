package com.isaver.filemanager.preview

import com.isaver.filemanager.data.root.RootFileSystem
import com.isaver.filemanager.data.root.RootFileVersion
import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

sealed interface PreviewContent {
    data class Text(val value: String) : PreviewContent
    data class Image(val bytes: ByteArray, val mimeType: String) : PreviewContent {
        override fun equals(other: Any?): Boolean = other is Image && bytes.contentEquals(other.bytes) && mimeType == other.mimeType
        override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
    }
}

class RootPreviewRepository(
    private val fileSystem: RootFileSystem,
) {
    fun supports(name: String): Boolean = kind(name) != null

    suspend fun preview(entry: DirectoryEntry): OperationResult<PreviewContent> {
        if (entry.type != EntryType.FILE || entry.symbolicLink || !entry.readable) return failure("无法预览此文件")
        val size = entry.sizeBytes ?: return failure("无法读取文件大小")
        if (size < 0L) return failure("文件大小无效")
        val kind = kind(entry.name)
        val limit = when (kind) {
            Kind.TEXT -> MAX_TEXT_BYTES
            Kind.IMAGE -> MAX_IMAGE_BYTES
            null -> return failure("此文件格式不支持内置预览")
        }
        if (size > limit) return failure("文件过大，无法内置预览")
        val bytes = readStable(entry.path, size) ?: return failure("文件在读取期间发生变化，请刷新后重试")
        return when (kind) {
            Kind.TEXT -> decodeText(bytes)?.let { OperationResult.Success(PreviewContent.Text(it)) }
                ?: failure("文本编码不是 UTF-8，无法只读预览")
            Kind.IMAGE -> OperationResult.Success(PreviewContent.Image(bytes, imageMime(entry.name, bytes)))
        }
    }

    private suspend fun readStable(path: com.isaver.filemanager.domain.RootPath, size: Long): ByteArray? {
        val output = ByteArray(size.toInt())
        var offset = 0L
        var version: RootFileVersion? = null
        while (offset < size || size == 0L && version == null) {
            val count = minOf(CHUNK_BYTES, size - offset)
            val result = fileSystem.readRange(path, offset, count)
            if (result !is OperationResult.Success) return null
            val chunk = result.value
            if (chunk.bytes.size.toLong() != count || !sameVersion(version, chunk.version)) return null
            if (count > 0L) chunk.bytes.copyInto(output, offset.toInt())
            version = chunk.version
            offset += count
        }
        return output
    }

    private fun sameVersion(left: RootFileVersion?, right: RootFileVersion): Boolean = left == null || left == right

    private fun decodeText(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }

    private fun kind(name: String): Kind? = when {
        TEXT_EXTENSIONS.any(name.lowercase()::endsWith) -> Kind.TEXT
        IMAGE_EXTENSIONS.any(name.lowercase()::endsWith) -> Kind.IMAGE
        else -> null
    }

    private fun imageMime(name: String, bytes: ByteArray): String = when {
        bytes.startsWith(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
        bytes.startsWith(byteArrayOf(-1, -40, -1)) -> "image/jpeg"
        bytes.startsWith("GIF87a".toByteArray()) || bytes.startsWith("GIF89a".toByteArray()) -> "image/gif"
        name.lowercase().endsWith(".webp") -> "image/webp"
        else -> "image/*"
    }

    private fun failure(message: String): OperationResult.Failure =
        OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, message)

    private enum class Kind { TEXT, IMAGE }

    companion object {
        const val MAX_TEXT_BYTES = 512 * 1024
        const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
        private const val CHUNK_BYTES = 4L * 1024L * 1024L
        private val TEXT_EXTENSIONS = setOf(".txt", ".md", ".markdown", ".json", ".xml", ".csv", ".log", ".ini", ".conf", ".properties", ".yaml", ".yml", ".kt", ".java", ".c", ".h", ".cpp", ".js", ".ts", ".css", ".html")
        private val IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".gif", ".webp")
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
