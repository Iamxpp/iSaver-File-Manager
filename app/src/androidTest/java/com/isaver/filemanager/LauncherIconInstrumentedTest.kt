package com.isaver.filemanager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test
    fun folderForegroundKeepsComfortableDesktopMargin() {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        app.resources.getDrawable(R.drawable.ic_launcher_foreground, app.theme).apply {
            setBounds(0, 0, ICON_SIZE, ICON_SIZE)
            draw(Canvas(bitmap))
        }

        val coloredPixels = buildList {
            for (vertical in 0 until ICON_SIZE) {
                for (horizontal in 0 until ICON_SIZE) {
                    if (Color.alpha(bitmap.getPixel(horizontal, vertical)) > MIN_VISIBLE_ALPHA) {
                        add(horizontal to vertical)
                    }
                }
            }
        }
        assertTrue("Folder foreground had no visible pixels", coloredPixels.isNotEmpty())
        val width = coloredPixels.maxOf { it.first } - coloredPixels.minOf { it.first } + 1
        val height = coloredPixels.maxOf { it.second } - coloredPixels.minOf { it.second } + 1

        assertTrue("Folder foreground was ${width}px wide", width <= MAX_FOLDER_WIDTH)
        assertTrue("Folder foreground was ${height}px high", height <= MAX_FOLDER_HEIGHT)
    }

    private companion object {
        const val ICON_SIZE = 108
        const val MIN_VISIBLE_ALPHA = 8
        const val MAX_FOLDER_WIDTH = 56
        const val MAX_FOLDER_HEIGHT = 43
    }
}
