package com.iamxpp.isaver.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap

class ShareIntentParser(context: Context) {
    private val contentResolver = context.contentResolver

    fun parse(intent: Intent): ShareIntentParseResult {
        if (intent.action != Intent.ACTION_SEND) {
            return failure(
                ShareIntentFailureReason.UNSUPPORTED_INTENT,
                "仅支持分享单个文件",
            )
        }

        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: return failure(ShareIntentFailureReason.MISSING_STREAM, "未接收到文件")

        if (uri.scheme != "content") {
            return failure(
                ShareIntentFailureReason.UNSUPPORTED_URI,
                "仅支持安全的内容 Uri",
            )
        }
        if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            return failure(
                ShareIntentFailureReason.SOURCE_UNREADABLE,
                "无法读取来源文件",
            )
        }

        return try {
            val mimeType = contentResolver.getType(uri) ?: intent.type
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            val metadata = contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
                val size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getLong)
                    ?.takeIf { it >= 0L }
                name to size
            }

            ShareIntentParseResult.Success(
                IncomingShare(
                    uri = uri,
                    displayName = metadata?.first?.takeIf(String::isNotBlank)
                        ?: unnamedFile(mimeType),
                    sizeBytes = metadata?.second,
                    mimeType = mimeType,
                ),
            )
        } catch (_: SecurityException) {
            failure(ShareIntentFailureReason.SOURCE_UNREADABLE, "无法读取来源文件")
        }
    }

    private fun unnamedFile(mimeType: String?): String {
        val mappedExtension = mimeType
            ?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType)
            ?.takeIf(String::isNotBlank)
        val safeSubtype = mimeType
            ?.substringAfter('/', missingDelimiterValue = "")
            ?.takeIf { it.matches(SAFE_MIME_SUBTYPE) }
        val extension = mappedExtension ?: safeSubtype
        return if (extension == null) "未命名文件" else "未命名文件.$extension"
    }

    private fun failure(
        reason: ShareIntentFailureReason,
        userMessage: String,
    ) = ShareIntentParseResult.Failure(
        reason = reason,
        userMessage = userMessage,
    )

    private companion object {
        val SAFE_MIME_SUBTYPE = Regex("[A-Za-z0-9]{1,10}")
    }
}
