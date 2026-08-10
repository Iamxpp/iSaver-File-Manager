package com.iamxpp.isaver

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareTargetTest {
    @Test
    fun `share target defaults to save for legacy intents`() {
        assertEquals(ShareTarget.SAVE, ShareTarget.fromIntent(Intent(Intent.ACTION_SEND)))
        assertEquals(ShareTarget.SAVE, ShareTarget.fromIntent(Intent(Intent.ACTION_VIEW)))
    }

    @Test
    fun `forwarded share target selects requested action`() {
        val intent = Intent(Intent.ACTION_SEND).putExtra(
            ShareTarget.EXTRA_NAME,
            ShareTarget.OPEN_LOCATION.id,
        )

        assertEquals(ShareTarget.OPEN_LOCATION, ShareTarget.fromIntent(intent))
    }

    @Test
    fun `non share intents do not select a target`() {
        assertNull(ShareTarget.fromIntent(Intent(Intent.ACTION_MAIN)))
        assertNull(ShareTarget.fromIntent(null))
    }
}
