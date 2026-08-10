package com.iamxpp.isaver.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareSourceLocationResolverTest {
    @Test
    fun `send resolves wechat external provider parent directory`() {
        val intent = sendIntent(
            "content://com.tencent.mm.external.fileprovider/external/tencent/MicroMsg/Download/report.pdf",
        )

        assertEquals(
            "/storage/emulated/0/tencent/MicroMsg/Download",
            ShareSourceLocationResolver.resolve(intent)?.directory?.value,
        )
    }

    @Test
    fun `view resolves wechat external files provider parent directory`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(
                "content://com.tencent.mm.fileprovider/external_files/MicroMsg/Download/report.pdf",
            )
        }

        assertEquals(
            "/storage/emulated/0/Android/data/com.tencent.mm/files/MicroMsg/Download",
            ShareSourceLocationResolver.resolve(intent)?.directory?.value,
        )
    }

    @Test
    fun `embedded wechat external path matching ignores case`() {
        val uri = Uri.parse(
            "content://com.tencent.mm.sdk.fileprovider/provider/ANDROID/DATA/com.tencent.mm/MicroMsg/report.pdf",
        )

        assertEquals(
            "/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg/report.pdf",
            ShareSourceLocationResolver.resolveWeChatFilePath(uri)?.value,
        )
    }

    @Test
    fun `send accepts a matching single clip data Uri`() {
        val uri = Uri.parse(
            "content://com.tencent.mm.external.fileprovider/external/tencent/MicroMsg/Download/report.pdf",
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("report", uri)
        }

        assertEquals(
            "/storage/emulated/0/tencent/MicroMsg/Download",
            ShareSourceLocationResolver.resolve(intent)?.directory?.value,
        )
    }

    @Test
    fun `resolver rejects non wechat providers and unsafe paths`() {
        assertNull(
            ShareSourceLocationResolver.resolve(
                sendIntent("content://example.fileprovider/external/tencent/MicroMsg/report.pdf"),
            ),
        )
        assertNull(
            ShareSourceLocationResolver.resolve(
                sendIntent("content://com.tencent.mm.external.fileprovider/external/%2E%2E/report.pdf"),
            ),
        )
    }

    @Test
    fun `resolver rejects conflicting shared Uris`() {
        val first = Uri.parse(
            "content://com.tencent.mm.external.fileprovider/external/tencent/MicroMsg/first.pdf",
        )
        val second = Uri.parse(
            "content://com.tencent.mm.external.fileprovider/external/tencent/MicroMsg/second.pdf",
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, first)
            clipData = ClipData.newRawUri("second", second)
        }

        assertNull(ShareSourceLocationResolver.resolve(intent))
    }

    private fun sendIntent(uri: String) = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
    }
}
