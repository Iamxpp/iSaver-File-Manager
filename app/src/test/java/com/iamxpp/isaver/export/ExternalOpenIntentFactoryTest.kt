package com.iamxpp.isaver.export

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalOpenIntentFactoryTest {
    @Test
    fun `default open intent uses a granted opaque content Uri`() {
        val grant = ExternalFileGrant(
            contentUri = "content://com.iamxpp.isaver.external-file/file/${"ab".repeat(32)}",
            token = "ab".repeat(32),
            displayName = "report.pdf",
            mimeType = "application/pdf",
        )

        val intent = ExternalOpenIntentFactory.create(grant)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(grant.contentUri), intent.data)
        assertEquals("application/pdf", intent.type)
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertFalse((intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
        assertEquals(Uri.parse(grant.contentUri), intent.clipData?.getItemAt(0)?.uri)
    }
}
