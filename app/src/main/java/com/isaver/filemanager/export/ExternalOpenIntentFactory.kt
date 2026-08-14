package com.isaver.filemanager.export

import android.content.ClipData
import android.content.Intent
import android.net.Uri

object ExternalOpenIntentFactory {
    fun create(grant: ExternalFileGrant): Intent {
        val uri = Uri.parse(grant.contentUri)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, grant.mimeType)
            clipData = ClipData.newRawUri(grant.displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun createChooser(grant: ExternalFileGrant): Intent =
        Intent.createChooser(create(grant), "打开方式")
}
