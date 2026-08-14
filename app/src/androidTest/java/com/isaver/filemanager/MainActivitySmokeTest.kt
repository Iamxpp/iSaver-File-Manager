package com.isaver.filemanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    private lateinit var device: UiDevice

    @Before
    fun prepareDevice() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressHome()
    }

    @Test
    fun grantedRootStartsInViewsHome() {
        launch("-a android.intent.action.MAIN -c android.intent.category.LAUNCHER")

        assertTrue(device.wait(Until.hasObject(By.text("视图")), TIMEOUT_MILLIS))
        assertTrue(device.hasObject(By.text("最近项目")))
        assertTrue(device.hasObject(By.text("浏览")))
        assertTrue(device.hasObject(By.text("通用位置")))
        assertFalse(device.hasObject(By.text("应用位置")))
    }

    @Test
    fun contentViewKeepsThreeTabHomeAndShowsInlineSaveBar() {
        launch(
            "-a android.intent.action.VIEW " +
                "-d content://com.isaver.filemanager.debug-share/report.pdf " +
                "-t application/pdf -f 1",
        )

        assertTrue(device.wait(Until.hasObject(By.text("测试 报告")), TIMEOUT_MILLIS))
        assertTrue(device.hasObject(By.text("最近项目")))
        assertTrue(device.hasObject(By.text("浏览")))
        assertTrue(device.hasObject(By.text("通用位置")))
        assertFalse(device.hasObject(By.text("应用位置")))
        assertTrue(device.hasObject(By.desc("文件名")))
        assertTrue(device.hasObject(By.desc("扩展名")))
        assertTrue(device.hasObject(By.text("存储")))
        assertFalse(device.hasObject(By.desc("更多操作")))
    }

    private fun launch(arguments: String) {
        val result = device.executeShellCommand(
            "am start -W $arguments -n com.isaver.filemanager/.MainActivity",
        )
        assertTrue("Activity launch failed: $result", result.contains("Status: ok"))
        device.waitForIdle()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}
