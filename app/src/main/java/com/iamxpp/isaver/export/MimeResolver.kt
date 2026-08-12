package com.iamxpp.isaver.export

import android.webkit.MimeTypeMap
import java.util.Locale

class MimeResolver(
    private val platformLookup: (String) -> String? = { extension ->
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    },
) {
    fun resolve(displayName: String): String {
        val normalized = displayName.lowercase(Locale.US)
        COMPOUND_TYPES.firstNotNullOfOrNull { (suffix, mimeType) ->
            mimeType.takeIf { normalized.endsWith(suffix) }
        }?.let { return it }

        val extension = normalized.substringAfterLast('.', missingDelimiterValue = "")
        if (extension.isEmpty()) return BINARY_MIME
        return platformLookup(extension) ?: BINARY_MIME
    }

    fun resolve(displayName: String, header: ByteArray): String {
        val extensionMime = resolve(displayName)
        return when {
            header.hasPrefix(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)) -> "application/pdf"
            header.hasPrefix(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
            header.hasPrefix(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) -> "image/jpeg"
            header.hasPrefix("GIF87a".toByteArray()) || header.hasPrefix("GIF89a".toByteArray()) -> "image/gif"
            header.hasPrefix(byteArrayOf(0x1f, 0x8b.toByte())) -> "application/gzip"
            header.hasPrefix(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) ||
                header.hasPrefix(byteArrayOf(0x50, 0x4b, 0x05, 0x06)) ||
                header.hasPrefix(byteArrayOf(0x50, 0x4b, 0x07, 0x08)) ->
                extensionMime.takeIf { isZipContainer(displayName) } ?: "application/zip"
            else -> extensionMime
        }
    }

    private fun isZipContainer(displayName: String): Boolean {
        val normalized = displayName.lowercase(Locale.US)
        return ZIP_CONTAINER_SUFFIXES.any(normalized::endsWith)
    }

    private companion object {
        const val BINARY_MIME = "application/octet-stream"
        val COMPOUND_TYPES = listOf(
            ".tar.gz" to "application/gzip",
            ".tgz" to "application/gzip",
            ".apk" to "application/vnd.android.package-archive",
        )
        val ZIP_CONTAINER_SUFFIXES = setOf(
            ".apk", ".jar", ".docx", ".xlsx", ".pptx", ".odt", ".ods", ".odp", ".epub",
        )
    }
}

private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
