package com.iamxpp.isaver

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class MainActivitySmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun grantedRootStartsInViewsHome() {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodes(hasText("视图")).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNode(
            hasText("视图") and
                SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
        ).assertIsSelected()
        compose.onNodeWithText("最近项目").assertIsDisplayed()
        compose.onNodeWithText("浏览").assertIsDisplayed()
        compose.onNodeWithText("应用位置").assertIsDisplayed()
    }

    @Test
    fun contentViewKeepsThreeTabHomeAndShowsInlineSaveBar() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClass(compose.activity, MainActivity::class.java)
            setDataAndType(
                Uri.parse("content://com.iamxpp.isaver.debug-share/report.pdf"),
                "application/pdf",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            instrumentation.callActivityOnNewIntent(compose.activity, intent)
        }

        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodes(hasText("测试 报告")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("最近项目").assertIsDisplayed()
        compose.onNodeWithText("浏览").assertIsDisplayed()
        compose.onNodeWithText("应用位置").assertIsDisplayed()
        compose.onNodeWithTag("inline-save-bar").assertIsDisplayed()
        compose.onNodeWithTag("files-top-bar-overflow").assertDoesNotExist()
    }
}
