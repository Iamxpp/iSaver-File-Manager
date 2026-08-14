package com.isaver.filemanager.share

import android.content.Intent
import android.net.Uri
import android.os.Build

internal object ShareIntentStream {
    fun extract(intent: Intent): ShareIntentStreamExtra = when (intent.action) {
        Intent.ACTION_SEND -> extractStreamUri(intent)
        Intent.ACTION_VIEW -> intent.data?.let(ShareIntentStreamExtra::Valid) ?: ShareIntentStreamExtra.Missing
        else -> ShareIntentStreamExtra.Missing
    }

    private fun extractStreamUri(intent: Intent): ShareIntentStreamExtra {
        val extra = extractExtraUri(intent)
        val clip = extractClipUri(intent)
        if (extra == ShareIntentStreamExtra.Invalid || clip == ShareIntentStreamExtra.Invalid) {
            return ShareIntentStreamExtra.Invalid
        }
        if (extra is ShareIntentStreamExtra.Valid && clip is ShareIntentStreamExtra.Valid) {
            return if (extra.uri == clip.uri) extra else ShareIntentStreamExtra.Invalid
        }
        return when {
            extra is ShareIntentStreamExtra.Valid -> extra
            clip is ShareIntentStreamExtra.Valid -> clip
            else -> ShareIntentStreamExtra.Missing
        }
    }

    private fun extractExtraUri(intent: Intent): ShareIntentStreamExtra {
        return try {
            if (!intent.hasExtra(Intent.EXTRA_STREAM)) return ShareIntentStreamExtra.Missing
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                (intent.extras?.get(Intent.EXTRA_STREAM) as? Uri)
            }
            uri?.let(ShareIntentStreamExtra::Valid) ?: ShareIntentStreamExtra.Invalid
        } catch (_: RuntimeException) {
            ShareIntentStreamExtra.Invalid
        }
    }

    private fun extractClipUri(intent: Intent): ShareIntentStreamExtra {
        return try {
            val clipData = intent.clipData ?: return ShareIntentStreamExtra.Missing
            if (clipData.itemCount != 1) return ShareIntentStreamExtra.Invalid
            clipData.getItemAt(0).uri?.let(ShareIntentStreamExtra::Valid) ?: ShareIntentStreamExtra.Invalid
        } catch (_: RuntimeException) {
            ShareIntentStreamExtra.Invalid
        }
    }
}

internal sealed interface ShareIntentStreamExtra {
    data object Missing : ShareIntentStreamExtra
    data object Invalid : ShareIntentStreamExtra
    data class Valid(val uri: Uri) : ShareIntentStreamExtra
}
