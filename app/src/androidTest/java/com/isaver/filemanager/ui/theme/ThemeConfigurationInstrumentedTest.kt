package com.isaver.filemanager.ui.theme

import android.view.ContextThemeWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.isaver.filemanager.R
import org.junit.Assert.assertFalse
import org.junit.Test

class ThemeConfigurationInstrumentedTest {
    @Test
    fun isaverThemeDisablesPlatformForceDark() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val themedContext = ContextThemeWrapper(targetContext, R.style.Theme_ISaver)
        val attributes = themedContext.obtainStyledAttributes(
            intArrayOf(android.R.attr.forceDarkAllowed),
        )
        try {
            assertFalse(attributes.getBoolean(0, true))
        } finally {
            attributes.recycle()
        }
    }
}
