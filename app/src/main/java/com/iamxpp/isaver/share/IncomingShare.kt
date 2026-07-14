package com.iamxpp.isaver.share

import android.net.Uri

data class IncomingShare(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    val mimeType: String?,
) {
    override fun toString(): String =
        "IncomingShare(uri=<redacted>, displayName=<redacted>, sizeBytes=$sizeBytes, mimeType=<redacted>)"
}

sealed interface ShareIntentParseResult {
    data class Success(val share: IncomingShare) : ShareIntentParseResult

    data class Failure(
        val reason: ShareIntentFailureReason,
        val userMessage: String,
    ) : ShareIntentParseResult
}

enum class ShareIntentFailureReason {
    UNSUPPORTED_INTENT,
    MISSING_STREAM,
    INVALID_SHARE,
    UNSUPPORTED_URI,
    PROVIDER_TIMEOUT,
    SOURCE_UNREADABLE,
}
