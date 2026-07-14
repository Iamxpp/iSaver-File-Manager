package com.iamxpp.isaver.share

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareIntentResolutionTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun sendAndContentViewResolveButUnsafeViewAndMultipleDoNot() {
        assertTrue(resolves(Intent(Intent.ACTION_SEND).apply { type = "application/pdf" }))
        assertTrue(
            resolves(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        Uri.parse("content://isaver.test/report.pdf"),
                        "application/pdf",
                    )
                },
            ),
        )
        assertFalse(
            resolves(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse("file:///sdcard/report.pdf"), "application/pdf")
                },
            ),
        )
        assertFalse(
            resolves(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse("https://example.test/report.pdf"), "application/pdf")
                },
            ),
        )
        assertFalse(resolves(Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "application/pdf" }))
    }

    private fun resolves(intent: Intent): Boolean = context.packageManager
        .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .any { it.activityInfo.packageName == context.packageName }
}
