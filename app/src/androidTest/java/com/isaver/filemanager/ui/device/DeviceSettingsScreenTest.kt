package com.isaver.filemanager.ui.device

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.isaver.filemanager.ui.RootGateUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DeviceSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun readOnlyPageShowsStorageAndInvokesRootAndBackActions() {
        var rootRequested = false
        var backRequested = false
        compose.setContent {
            DeviceSettingsScreen(
                state = DeviceSettingsUiState(
                    loadingStorage = false,
                    storageUsage = DeviceStorageUsage(1_000_000_000, 250_000_000),
                ),
                rootState = RootGateUiState.ReadOnly(),
                onRootModeChange = { rootRequested = it },
                onBack = { backRequested = true },
                onRetryStorage = {},
            )
        }

        compose.onNodeWithText("设备").assertIsDisplayed()
        compose.onNodeWithText("访问模式").assertIsDisplayed()
        compose.onNodeWithText("非 Root 只读，仅显示当前有权读取的内容").assertIsDisplayed()
        compose.onNodeWithText("内部存储").assertIsDisplayed()
        compose.onNodeWithText("可用 250 MB").assertIsDisplayed()
        compose.onNodeWithContentDescription("Root 模式").performClick()
        compose.onNodeWithContentDescription("返回").performClick()

        compose.runOnIdle {
            assertTrue(rootRequested)
            assertTrue(backRequested)
        }
    }

    @Test
    fun enablingRootDisablesSwitchUntilCheckCompletes() {
        var changed = false
        compose.setContent {
            DeviceSettingsScreen(
                state = DeviceSettingsUiState(loadingStorage = false, storageError = "无法读取存储信息"),
                rootState = RootGateUiState.EnablingRoot,
                onRootModeChange = { changed = true },
                onBack = {},
                onRetryStorage = {},
            )
        }

        compose.onNodeWithText("正在检查 Root 权限").assertIsDisplayed()
        compose.onNodeWithContentDescription("Root 模式").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertFalse(changed) }
    }
}
