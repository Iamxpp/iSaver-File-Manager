package com.iamxpp.isaver.export

import android.content.ClipData
import android.content.Intent
import android.net.Uri

object ExternalShareIntentFactory {
    fun create(grant: ExternalFileGrant): Intent {
        val uri = Uri.parse(grant.contentUri)
        return Intent(Intent.ACTION_SEND).apply {
            type = grant.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(grant.displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
