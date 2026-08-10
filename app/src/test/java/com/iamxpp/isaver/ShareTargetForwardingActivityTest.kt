package com.iamxpp.isaver

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareTargetForwardingActivityTest {
    @Test
    fun `save target forwards the original share to main activity`() {
        assertForwardedTarget(SaveSharedFileActivity::class.java, ShareTarget.SAVE)
    }

    @Test
    fun `open location target forwards the original share to main activity`() {
        assertForwardedTarget(OpenSharedFileLocationActivity::class.java, ShareTarget.OPEN_LOCATION)
    }

    private fun assertForwardedTarget(
        activityClass: Class<out ShareTargetForwardingActivity>,
        expectedTarget: ShareTarget,
    ) {
        val uri = Uri.parse(
            "content://com.tencent.mm.external.fileprovider/external/tencent/MicroMsg/report.pdf",
        )
        val incoming = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val activity = Robolectric.buildActivity(activityClass, incoming).create().get()

        val forwarded = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, forwarded.component?.className)
        assertEquals(Intent.ACTION_SEND, forwarded.action)
        assertEquals("application/pdf", forwarded.type)
        @Suppress("DEPRECATION")
        assertEquals(uri, forwarded.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(expectedTarget.id, forwarded.getStringExtra(ShareTarget.EXTRA_NAME))
        assertTrue(forwarded.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(forwarded.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(forwarded.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(activity.isFinishing)
    }
}
