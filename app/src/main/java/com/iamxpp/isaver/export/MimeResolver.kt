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

    private companion object {
        const val BINARY_MIME = "application/octet-stream"
        val COMPOUND_TYPES = listOf(
            ".tar.gz" to "application/gzip",
            ".tgz" to "application/gzip",
            ".apk" to "application/vnd.android.package-archive",
        )
    }
}
