package com.iamxpp.isaver

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LauncherIconInstrumentedTest {
    @Test
    fun manifestDeclaresResolvableNormalAndRoundLauncherIcons() {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        val applicationInfo = app.packageManager.getApplicationInfo(app.packageName, 0)
        val roundIcon = app.resources.getIdentifier("ic_launcher_round", "mipmap", app.packageName)

        assertNotEquals(0, applicationInfo.icon)
        assertNotEquals(0, roundIcon)
        assertEquals(
            "${app.packageName}:mipmap/ic_launcher",
            app.resources.getResourceName(applicationInfo.icon),
        )
        assertEquals(
            "${app.packageName}:mipmap/ic_launcher_round",
            app.resources.getResourceName(roundIcon),
        )
        assertNotNull(app.packageManager.getApplicationIcon(applicationInfo))
        assertNotNull(app.resources.getDrawable(roundIcon, app.theme))
    }
}
