package com.iamxpp.isaver.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RootGateScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun checkingShowsProgressAndRootCheckMessage() {
        composeRule.setContent {
            RootGateScreen(
                uiState = RootGateUiState.Checking,
                onRetry = {},
                onExit = {},
            )
        }

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f)))
            .assertIsDisplayed()
        composeRule.onNodeWithText("正在检查 Root 权限").assertIsDisplayed()
    }

    @Test
    fun deniedShowsReasonAndInvokesBothActions() {
        var retryClicked = false
        var exitClicked = false

        composeRule.setContent {
            RootGateScreen(
                uiState = RootGateUiState.Denied("Root 权限不可用，请授权后重试"),
                onRetry = { retryClicked = true },
                onExit = { exitClicked = true },
            )
        }

        composeRule.onNodeWithText("请以 Root 权限运行 iSaver").assertIsDisplayed()
        composeRule.onNodeWithText("Root 权限不可用，请授权后重试").assertIsDisplayed()
        composeRule.onNodeWithText("重新检测").performClick()
        composeRule.onNodeWithText("退出应用").performClick()

        assertTrue(retryClicked)
        assertTrue(exitClicked)
    }

    @Test
    fun grantedShowsContentWithoutTheBlockingPage() {
        composeRule.setContent {
            RootGateScreen(
                uiState = RootGateUiState.Granted,
                onRetry = {},
                onExit = {},
            )
        }

        composeRule.onNodeWithText("文件位置").assertIsDisplayed()
        composeRule.onNodeWithText("请以 Root 权限运行 iSaver").assertDoesNotExist()
        composeRule.onNodeWithText("重新检测").assertDoesNotExist()
        composeRule.onNodeWithText("退出应用").assertDoesNotExist()
    }
}
