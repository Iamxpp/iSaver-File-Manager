package com.iamxpp.isaver.export

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalShareIntentFactoryTest {
    @Test
    fun `single file share intent carries stream clip data mime and read grant`() {
        val grant = ExternalFileGrant(
            contentUri = "content://com.iamxpp.isaver.external-file/file/${"cd".repeat(32)}",
            token = "cd".repeat(32),
            displayName = "report.pdf",
            mimeType = "application/pdf",
        )

        val intent = ExternalShareIntentFactory.create(grant)
        val uri = Uri.parse(grant.contentUri)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/pdf", intent.type)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertNull(intent.data)
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertFalse((intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
    }
}
