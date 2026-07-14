package com.iamxpp.isaver

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntentDispatchPolicyTest {
    @Test
    fun `cold intents dispatch once while recreation and history relaunch do not`() {
        assertTrue(ShareIntentDispatchPolicy.shouldHandleInitial(hasSavedState = false, flags = 0))
        assertFalse(ShareIntentDispatchPolicy.shouldHandleInitial(hasSavedState = true, flags = 0))
        assertFalse(
            ShareIntentDispatchPolicy.shouldHandleInitial(
                hasSavedState = false,
                flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
            ),
        )
        assertFalse(
            ShareIntentDispatchPolicy.shouldHandleNewIntent(
                flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
            ),
        )
        assertTrue(ShareIntentDispatchPolicy.shouldHandleNewIntent(flags = 0))
    }
}
