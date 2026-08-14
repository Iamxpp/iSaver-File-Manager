package com.isaver.filemanager.export

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

    fun create(grants: List<ExternalFileGrant>): Intent {
        require(grants.size > 1)
        val uris = ArrayList(grants.map { Uri.parse(it.contentUri) })
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = grants.map { it.mimeType }.distinct().singleOrNull() ?: "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri(grants.first().displayName, uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
