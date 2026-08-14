package com.isaver.filemanager.share

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareIntentResolutionTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun sendAndContentViewResolveToBothShareTargetsButUnsafeViewAndMultipleDoNot() {
        assertShareTargets(Intent(Intent.ACTION_SEND).apply { type = "application/pdf" })
        assertShareTargets(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://isaver.test/report.pdf"),
                    "application/pdf",
                )
            },
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

    private fun assertShareTargets(intent: Intent) {
        val activities = matchingActivities(intent)
        assertEquals(
            setOf(
                "com.isaver.filemanager.OpenSharedFileLocationActivity",
                "com.isaver.filemanager.SaveSharedFileActivity",
            ),
            activities.map { it.activityInfo.name }.toSet(),
        )
        assertEquals(
            setOf("打开文件所在位置", "保存到 iSaver"),
            activities.map { it.loadLabel(context.packageManager).toString() }.toSet(),
        )
    }

    private fun resolves(intent: Intent): Boolean = context.packageManager
        .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .any { it.activityInfo.packageName == context.packageName }

    private fun matchingActivities(intent: Intent) = context.packageManager
        .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .filter { it.activityInfo.packageName == context.packageName }
}
