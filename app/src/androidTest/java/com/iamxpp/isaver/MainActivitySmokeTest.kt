package com.iamxpp.isaver

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
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
}
